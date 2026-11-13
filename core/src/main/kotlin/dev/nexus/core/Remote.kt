package dev.nexus.core

import java.io.File
import java.io.IOException

/** Entrada de un servidor remoto. [path] es absoluto dentro del servidor ("/a/b"), siempre con '/'. */
data class RemoteEntry(val path: String, val name: String, val isDir: Boolean, val size: Long, val modified: Long)

/** Cliente de un sistema de ficheros remoto. Todas las operaciones bloquean: llamar fuera del hilo principal. */
interface RemoteFs : AutoCloseable {
    fun list(path: String): List<RemoteEntry>
    /** Descarga a [dest] de forma atómica (`.nexus-part` + rename); si falla no deja un fichero a medias. */
    fun download(path: String, dest: File, onProgress: (Long) -> Unit = {})
    /** Sube [src]. Sin [overwrite] falla si el destino ya existe. */
    fun upload(src: File, path: String, overwrite: Boolean = false, onProgress: (Long) -> Unit = {})
    fun mkdir(path: String)
    fun delete(path: String, isDir: Boolean)
    /** Renombra/mueve sin sobrescribir. */
    fun rename(from: String, to: String)
    override fun close() {}
}

class RemoteException(message: String, cause: Throwable? = null) : IOException(message, cause)

object RemotePaths {
    fun normalize(p: String): String {
        val parts = ArrayList<String>()
        for (s in p.split('/')) when (s) {
            "", "." -> {}
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            else -> parts.add(s)
        }
        return "/" + parts.joinToString("/")
    }
    fun child(dir: String, name: String): String = normalize("$dir/$name")
    fun parent(p: String): String = normalize(p).let { if (it == "/") "/" else it.substringBeforeLast('/').ifEmpty { "/" } }
    fun nameOf(p: String): String = normalize(p).substringAfterLast('/')

    /** Un nombre recibido del servidor nunca debe poder escapar de la carpeta local de descarga. */
    fun safeName(n: String): Boolean = n.isNotEmpty() && n != "." && n != ".." && '/' !in n && '\\' !in n && '\u0000' !in n
}

internal inline fun atomicWrite(dest: File, write: (java.io.OutputStream) -> Unit) {
    dest.absoluteFile.parentFile?.mkdirs()
    val part = File(dest.path + FileOps.PART)
    try {
        part.outputStream().buffered().use(write)
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) throw IOException("No se pudo mover a ${dest.name}")
    } catch (e: Throwable) { part.delete(); throw e }
}
