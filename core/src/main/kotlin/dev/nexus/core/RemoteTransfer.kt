package dev.nexus.core

import java.io.File
import java.util.concurrent.CancellationException

data class TransferResult(val files: Int, val bytes: Long, val failed: List<String>, val cancelled: Boolean)

/** Descarga/subida de ficheros y carpetas con cancelación cooperativa (se comprueba en cada bloque de datos). */
object RemoteTransfer {
    private const val MAX_DEPTH = 32
    private const val MAX_FILES = 100_000

    fun uniqueLocal(dir: File, name: String): File {
        val first = File(dir, name)
        if (!first.exists()) return first
        return generateSequence(1) { it + 1 }.map { File(dir, Names.withSuffix(name, it)) }.first { !it.exists() }
    }

    /** Descarga [entry] dentro de [destDir] sin sobrescribir nada (si existe, añade « (n)»). */
    fun download(fs: RemoteFs, entry: RemoteEntry, destDir: File, cancelled: () -> Boolean = { false }, onProgress: (bytes: Long, current: String) -> Unit = { _, _ -> }): TransferResult {
        if (!RemotePaths.safeName(entry.name)) return TransferResult(0, 0, listOf("${entry.name}: nombre no seguro"), false)
        destDir.mkdirs()
        var files = 0; var bytes = 0L; val failed = ArrayList<String>()
        fun file(e: RemoteEntry, dest: File) {
            var base = bytes
            try {
                fs.download(e.path, dest) { n -> if (cancelled()) throw CancellationException("cancelado"); onProgress(base + n, e.name) }
                bytes += dest.length(); files++
            } catch (c: CancellationException) { throw c }
            catch (x: Exception) { File(dest.path + FileOps.PART).delete(); failed += "${e.path}: ${x.message}" }
        }
        fun tree(e: RemoteEntry, dest: File, depth: Int) {
            if (cancelled()) throw CancellationException("cancelado")
            if (!e.isDir) return file(e, dest)
            if (depth > MAX_DEPTH) { failed += "${e.path}: demasiado profundo"; return }
            if (!dest.mkdirs() && !dest.isDirectory) { failed += "${e.path}: no se pudo crear la carpeta"; return }
            val children = try { fs.list(e.path) } catch (x: RemoteException) { failed += "${e.path}: ${x.message}"; return }
            for (c in children) { if (files + failed.size > MAX_FILES) { failed += "Límite de $MAX_FILES elementos"; return }; tree(c, File(dest, c.name), depth + 1) }
        }
        return try { tree(entry, uniqueLocal(destDir, entry.name), 0); TransferResult(files, bytes, failed, false) }
        catch (c: CancellationException) { TransferResult(files, bytes, failed, true) }
    }

    fun upload(fs: RemoteFs, src: File, remoteDir: String, cancelled: () -> Boolean = { false }, onProgress: (bytes: Long, current: String) -> Unit = { _, _ -> }): TransferResult {
        if (!src.isFile) return TransferResult(0, 0, listOf("${src.name}: no es un fichero"), false)
        val existing = try { fs.list(remoteDir).map { it.name }.toSet() } catch (x: RemoteException) { return TransferResult(0, 0, listOf("No se pudo listar $remoteDir: ${x.message}"), false) }
        val name = if (src.name !in existing) src.name else generateSequence(1) { it + 1 }.map { Names.withSuffix(src.name, it) }.first { it !in existing }
        return try {
            fs.upload(src, RemotePaths.child(remoteDir, name), overwrite = false) { n -> if (cancelled()) throw CancellationException("cancelado"); onProgress(n, src.name) }
            TransferResult(1, src.length(), emptyList(), false)
        } catch (c: CancellationException) { TransferResult(0, 0, emptyList(), true) }
        catch (x: Exception) { TransferResult(0, 0, listOf("${src.name}: ${x.message}"), false) }
    }
}
