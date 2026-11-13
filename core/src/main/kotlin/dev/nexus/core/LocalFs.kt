package dev.nexus.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/** Acceso de lectura/estructura al sistema de ficheros local. Las operaciones largas viven en [FileOps]. */
object LocalFs {
    fun entry(f: File) = Entry(
        path = f.path, name = f.name.ifEmpty { f.path }, isDir = f.isDirectory,
        size = if (f.isFile) f.length() else 0L, modified = f.lastModified(),
        hidden = f.name.startsWith('.'), readable = f.canRead(),
    )

    /** Lista un directorio. Lanza [IOException] si no se puede leer (p. ej. permisos), nunca devuelve vacío por error. */
    fun list(dir: File): List<Entry> {
        if (!dir.isDirectory) throw IOException("No es una carpeta: ${dir.path}")
        val children = dir.listFiles() ?: throw IOException("Sin permiso para leer ${dir.path}")
        return children.map(::entry)
    }

    fun isSymlink(f: File): Boolean = Files.isSymbolicLink(f.toPath())

    /** true si [child] es [parent] o está dentro de él (por ruta canónica). */
    fun isInside(parent: File, child: File): Boolean {
        val p = parent.canonicalFile.toPath(); val c = child.canonicalFile.toPath()
        return c.startsWith(p)
    }

    fun mkdir(parent: File, rawName: String): File {
        val name = (Names.validate(rawName) as? Names.Result.Ok)?.name
            ?: throw IllegalArgumentException((Names.validate(rawName) as Names.Result.Invalid).reason)
        val target = File(parent, name)
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) throw IOException("Ya existe «$name»")
        if (!target.mkdir()) throw IOException("No se pudo crear «$name» (¿permisos?)")
        return target
    }

    /** Renombra dentro de la misma carpeta; nunca sobrescribe. */
    fun rename(src: File, rawName: String): File {
        val name = (Names.validate(rawName) as? Names.Result.Ok)?.name
            ?: throw IllegalArgumentException((Names.validate(rawName) as Names.Result.Invalid).reason)
        val target = File(src.parentFile, name)
        if (target == src) return src
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) throw IOException("Ya existe «$name»")
        if (!src.renameTo(target)) throw IOException("No se pudo renombrar «${src.name}»")
        return target
    }
}
