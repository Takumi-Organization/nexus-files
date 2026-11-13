package dev.nexus.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class ZipItem(val name: String, val size: Long, val compressed: Long, val isDir: Boolean, val modified: Long)

object Zips {
    const val MAX_ENTRIES = 200_000
    private const val BUF = 128 * 1024

    /** Lista el contenido sin extraer nada. */
    fun list(zip: File): List<ZipItem> = ZipFile(zip).use { z ->
        z.entries().asSequence().take(MAX_ENTRIES).map { ZipItem(it.name, it.size, it.compressedSize, it.isDirectory, it.time) }.toList()
    }

    /** Resuelve la entrada dentro de [dest] o devuelve null si intenta salir (zip-slip, rutas absolutas, nombres con NUL). */
    fun safeTarget(dest: File, entryName: String): File? {
        if (entryName.isEmpty() || entryName.contains('\u0000') || entryName.startsWith("/") || entryName.contains('\\')) return null
        val parts = entryName.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.any { it == ".." || it == "." }) return null
        val t = File(dest, parts.joinToString("/"))
        return if (LocalFs.isInside(dest, t.parentFile ?: dest) && t.canonicalFile.toPath().startsWith(dest.canonicalFile.toPath())) t else null
    }

    /**
     * Extrae [only] (nombres exactos de entrada; carpetas incluyen su contenido) o todo si es null.
     * Escribe a temporal + rename, valida cada ruta, no sobrescribe salvo [conflict] = REPLACE, cancelable.
     */
    suspend fun extract(zip: File, destDir: File, only: Set<String>? = null, conflict: Conflict = Conflict.KEEP_BOTH,
                        onProgress: (Progress) -> Unit = {}): Report {
        if (!destDir.isDirectory && !destDir.mkdirs()) return Report(failed = listOf(Failure(destDir.path, "No se puede crear el destino")))
        var done = 0; val skipped = ArrayList<String>(); val failed = ArrayList<Failure>()
        try {
            ZipFile(zip).use { z ->
                val all = z.entries().asSequence().take(MAX_ENTRIES).toList()
                val chosen = all.filter { e -> only == null || only.any { o -> e.name == o || (o.endsWith("/") && e.name.startsWith(o)) || e.name.startsWith("$o/") } }
                val total = chosen.sumOf { maxOf(it.size, 0L) }; var bytes = 0L
                for ((i, e) in chosen.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val target = safeTarget(destDir, e.name)
                    if (target == null) { failed += Failure(e.name, "Ruta insegura, entrada omitida"); continue }
                    try {
                        if (e.isDirectory) { target.mkdirs(); continue }
                        target.parentFile?.mkdirs()
                        val final = when {
                            !Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS) -> target
                            conflict == Conflict.SKIP -> { skipped += e.name; continue }
                            conflict == Conflict.REPLACE && target.isFile -> target
                            else -> generateSequence(1) { it + 1 }.map { File(target.parentFile, Names.withSuffix(target.name, it)) }.first { !it.exists() }
                        }
                        val part = File(final.parentFile, final.name + FileOps.PART)
                        try {
                            z.getInputStream(e).use { input -> part.outputStream().use { out ->
                                val buf = ByteArray(BUF)
                                while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); bytes += n }
                                out.fd.sync()
                            } }
                            if (e.time > 0) part.setLastModified(e.time)
                            Files.move(part.toPath(), final.toPath(), StandardCopyOption.REPLACE_EXISTING)
                            done++
                        } catch (t: Throwable) { part.delete(); throw t }
                    } catch (c: CancellationException) { throw c }
                    catch (x: Exception) { failed += Failure(e.name, x.message ?: "error") }
                    onProgress(Progress(bytes, total, i + 1, chosen.size, e.name))
                }
            }
        } catch (c: CancellationException) { return Report(done, skipped, failed, cancelled = true) }
        catch (x: java.util.zip.ZipException) { return Report(done, skipped, failed + Failure(zip.path, "ZIP dañado: ${x.message}")) }
        return Report(done, skipped, failed)
    }

    /** Crea un ZIP con [sources] (rutas relativas a su carpeta padre). Escribe a temporal y renombra; nunca sobrescribe. */
    suspend fun create(sources: List<File>, dest: File, onProgress: (Progress) -> Unit = {}): Report {
        val srcCanon = sources.map { it.canonicalFile }
        val final = if (dest.exists()) generateSequence(1) { it + 1 }.map { File(dest.parentFile, Names.withSuffix(dest.name, it)) }.first { !it.exists() } else dest
        if (srcCanon.any { s -> s.isDirectory && final.canonicalFile.toPath().startsWith(s.toPath()) }) {
            // el ZIP caería dentro de una carpeta que se está comprimiendo: se admite porque el temporal se excluye por ruta
        }
        val part = File(final.parentFile, final.name + FileOps.PART)
        val ops = FileOps(); val total = sources.sumOf { ops.sizeOf(it) }; var bytes = 0L; var done = 0
        val skipped = ArrayList<String>(); val failed = ArrayList<Failure>()
        try {
            ZipOutputStream(part.outputStream().buffered()).use { zo ->
                suspend fun add(f: File, rel: String) {
                    currentCoroutineContext().ensureActive()
                    if (f.canonicalFile == part.canonicalFile || LocalFs.isSymlink(f)) { skipped += f.path; return }
                    if (f.isDirectory) {
                        zo.putNextEntry(ZipEntry("$rel/").apply { time = f.lastModified() }); zo.closeEntry()
                        (f.listFiles() ?: run { failed += Failure(f.path, "Sin permiso de lectura"); return }).forEach { add(it, "$rel/${it.name}") }
                    } else try {
                        zo.putNextEntry(ZipEntry(rel).apply { time = f.lastModified() })
                        f.inputStream().use { i -> val buf = ByteArray(BUF); while (true) { currentCoroutineContext().ensureActive(); val n = i.read(buf); if (n < 0) break; zo.write(buf, 0, n); bytes += n } }
                        zo.closeEntry(); done++
                        onProgress(Progress(bytes, total, done, 0, f.name))
                    } catch (c: CancellationException) { throw c } catch (x: IOException) { failed += Failure(f.path, x.message ?: "error") }
                }
                for (s in sources) add(s, s.name)
            }
            if (failed.isNotEmpty() && done == 0) { part.delete(); return Report(0, skipped, failed) }
            Files.move(part.toPath(), final.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (c: CancellationException) { part.delete(); return Report(done, skipped, failed, cancelled = true) }
        catch (t: Throwable) { part.delete(); return Report(done, skipped, failed + Failure(final.path, t.message ?: "error")) }
        return Report(done, skipped, failed)
    }
}
