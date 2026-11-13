package dev.nexus.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

enum class Conflict { SKIP, KEEP_BOTH, REPLACE }

data class Progress(val doneBytes: Long, val totalBytes: Long, val doneItems: Int, val totalItems: Int, val current: String) {
    val fraction: Float get() = if (totalBytes > 0) (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
        else if (totalItems > 0) doneItems.toFloat() / totalItems else 0f
}

data class Failure(val path: String, val reason: String)

data class Report(
    val done: Int = 0,
    val skipped: List<String> = emptyList(),
    val failed: List<Failure> = emptyList(),
    val cancelled: Boolean = false,
) { val ok: Boolean get() = failed.isEmpty() && !cancelled }

/**
 * Copiar / mover / borrar con garantías:
 *  - la copia escribe a `<nombre>.nexus-part` y renombra al terminar: un corte nunca deja un archivo a medias con el nombre final;
 *  - el origen de un mover solo se borra tras copiar TODO el elemento sin fallos;
 *  - no se sobrescribe nada salvo política REPLACE explícita; no se copia una carpeta dentro de sí misma;
 *  - los enlaces simbólicos no se siguen (se omiten al copiar; se borra el enlace, no su destino);
 *  - cancelar (cancelación de la corrutina) limpia los temporales.
 */
class FileOps(private val bufferSize: Int = 256 * 1024) {
    companion object { const val PART = ".nexus-part" }

    private class Ctx(val total: Long, val items: Int, val conflict: Conflict, val onProgress: (Progress) -> Unit) {
        var bytes = 0L; var count = 0
        var done = 0; val skipped = mutableListOf<String>(); val failed = mutableListOf<Failure>()
        fun report(cur: String) = onProgress(Progress(bytes, total, count, items, cur))
    }

    suspend fun copy(sources: List<File>, destDir: File, conflict: Conflict = Conflict.KEEP_BOTH, onProgress: (Progress) -> Unit = {}): Report =
        transfer(sources, destDir, conflict, move = false, onProgress)

    suspend fun move(sources: List<File>, destDir: File, conflict: Conflict = Conflict.KEEP_BOTH, onProgress: (Progress) -> Unit = {}): Report =
        transfer(sources, destDir, conflict, move = true, onProgress)

    private suspend fun transfer(sources: List<File>, destDir: File, conflict: Conflict, move: Boolean, onProgress: (Progress) -> Unit): Report {
        if (!destDir.isDirectory) return Report(failed = listOf(Failure(destDir.path, "El destino no es una carpeta")))
        val ctx = Ctx(sources.sumOf { sizeOf(it) }, sources.size, conflict, onProgress)
        try {
            for (src in sources) {
                currentCoroutineContext().ensureActive()
                try { transferOne(src, destDir, move, ctx) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { ctx.failed += Failure(src.path, e.message ?: e.javaClass.simpleName) }
                ctx.count++; ctx.report(src.name)
            }
        } catch (e: CancellationException) {
            return Report(ctx.done, ctx.skipped, ctx.failed, cancelled = true)
        }
        return Report(ctx.done, ctx.skipped, ctx.failed)
    }

    private suspend fun transferOne(src: File, destDir: File, move: Boolean, ctx: Ctx) {
        if (!Files.exists(src.toPath(), LinkOption.NOFOLLOW_LINKS)) throw IOException("El origen ya no existe")
        if (LocalFs.isSymlink(src)) { ctx.skipped += src.path; return }
        if (src.isDirectory && LocalFs.isInside(src, destDir)) throw IOException("No se puede copiar una carpeta dentro de sí misma")
        val sameDir = src.parentFile?.canonicalFile == destDir.canonicalFile
        if (move && sameDir) { ctx.skipped += src.path; return }
        val target = resolveTarget(destDir, src.name, ctx.conflict, sameDir) ?: run { ctx.skipped += src.path; return }
        if (move && tryRename(src, target, ctx.conflict == Conflict.REPLACE)) { ctx.bytes += sizeOf(target); ctx.done++; return }
        val clean = copyTree(src, target, ctx)
        if (move) {
            if (clean) { deleteTree(src); ctx.done++ }
            else ctx.failed += Failure(src.path, "Copia incompleta: el origen se conserva")
        } else if (clean) ctx.done++
    }

    /** null = saltar. Con REPLACE sobre una carpeta existente se combina en el sitio (por eso aquí solo se devuelve la ruta). */
    private fun resolveTarget(destDir: File, name: String, conflict: Conflict, sameDir: Boolean): File? {
        val t = File(destDir, name)
        if (!Files.exists(t.toPath(), LinkOption.NOFOLLOW_LINKS)) return t
        return when {
            conflict == Conflict.SKIP -> null
            conflict == Conflict.KEEP_BOTH || sameDir -> generateSequence(1) { it + 1 }
                .map { File(destDir, Names.withSuffix(name, it)) }
                .first { !Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
            else -> t
        }
    }

    private fun tryRename(src: File, target: File, replace: Boolean): Boolean {
        if (target.exists() && (!replace || target.isDirectory || src.isDirectory)) return false
        return try {
            if (replace) Files.move(src.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            else Files.move(src.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (e: AtomicMoveNotSupportedException) { false } catch (e: IOException) { false }
    }

    /** @return true si se copió todo el árbol sin omitir ni fallar nada. */
    private suspend fun copyTree(src: File, target: File, ctx: Ctx): Boolean {
        currentCoroutineContext().ensureActive()
        if (LocalFs.isSymlink(src)) { ctx.skipped += src.path; return false }
        if (src.isDirectory) {
            if (!target.isDirectory && !target.mkdir()) throw IOException("No se pudo crear ${target.path}")
            val children = src.listFiles() ?: throw IOException("Sin permiso para leer ${src.path}")
            var clean = true
            for (c in children) {
                val t = File(target, c.name)
                val dest = if (t.exists() && ctx.conflict == Conflict.SKIP && !c.isDirectory) { ctx.skipped += c.path; clean = false; continue }
                else if (t.exists() && !c.isDirectory && ctx.conflict == Conflict.KEEP_BOTH) resolveTarget(target, c.name, Conflict.KEEP_BOTH, false)!!
                else t
                try { if (!copyTree(c, dest, ctx)) clean = false }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { ctx.failed += Failure(c.path, e.message ?: "error"); clean = false }
            }
            return clean
        }
        copyFile(src, target, ctx)
        return true
    }

    private suspend fun copyFile(src: File, target: File, ctx: Ctx) {
        val part = File(target.parentFile, target.name + PART)
        try {
            FileInputStream(src).use { input ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(bufferSize)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); ctx.bytes += n; ctx.report(src.name)
                        yield()
                    }
                    out.fd.sync()
                }
            }
            if (part.length() != src.length()) throw IOException("Tamaño distinto tras copiar «${src.name}»")
            part.setLastModified(src.lastModified())
            try { Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (e: AtomicMoveNotSupportedException) { Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } catch (e: Throwable) {
            part.delete(); throw e
        }
    }

    /** Borra elementos. La confirmación explícita es responsabilidad de la UI; aquí nunca se sigue un enlace simbólico. */
    suspend fun delete(targets: List<File>, onProgress: (Progress) -> Unit = {}): Report {
        val ctx = Ctx(0, targets.size, Conflict.SKIP, onProgress)
        try {
            for (t in targets) {
                currentCoroutineContext().ensureActive()
                try { deleteTree(t); ctx.done++ }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { ctx.failed += Failure(t.path, e.message ?: "error") }
                ctx.count++; ctx.report(t.name)
            }
        } catch (e: CancellationException) { return Report(ctx.done, ctx.skipped, ctx.failed, cancelled = true) }
        return Report(ctx.done, ctx.skipped, ctx.failed)
    }

    private fun deleteTree(f: File) {
        if (!LocalFs.isSymlink(f) && f.isDirectory) {
            val kids = f.listFiles() ?: throw IOException("Sin permiso para leer ${f.path}")
            kids.forEach(::deleteTree)
        }
        if (!f.delete() && Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS)) throw IOException("No se pudo borrar ${f.path}")
    }

    fun sizeOf(f: File): Long {
        if (LocalFs.isSymlink(f)) return 0
        if (f.isFile) return f.length()
        return f.listFiles()?.sumOf(::sizeOf) ?: 0
    }
}
