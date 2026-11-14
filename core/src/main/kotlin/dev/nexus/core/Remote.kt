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

/** Fichero temporal de una descarga. Si ya existe con datos de un intento interrumpido, [offset] dice desde dónde se puede reanudar. */
class PartFile(val file: File) {
    val offset: Long = if (file.isFile) file.length() else 0L
    /** Abre el temporal: [startAt] 0 lo trunca; si es [offset] (>0) añade al final. */
    fun open(startAt: Long): java.io.OutputStream {
        require(startAt == 0L || startAt == offset) { "Desplazamiento de reanudación no válido" }
        return java.io.FileOutputStream(file, startAt > 0).buffered()
    }
}

/**
 * Escribe [dest] de forma atómica (temporal + rename). Ante un fallo de red pasajero el temporal se CONSERVA para reanudar en el
 * siguiente intento; ante cualquier otro error o cancelación se borra. [write] debe cerrar el flujo que abra con [PartFile.open].
 */
internal fun atomicWrite(dest: File, write: (PartFile) -> Unit) {
    dest.absoluteFile.parentFile?.mkdirs()
    val part = File(dest.path + FileOps.PART)
    try {
        write(PartFile(part))
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) throw IOException("No se pudo mover a ${dest.name}")
    } catch (e: Throwable) { if (!Retry.isTransient(e) || !part.exists()) part.delete(); throw e }
}
