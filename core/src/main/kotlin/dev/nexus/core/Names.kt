package dev.nexus.core

/** Validación de nombres de archivo/carpeta. Un nombre nunca es una ruta. */
object Names {
    const val MAX_BYTES = 255

    sealed interface Result {
        data class Ok(val name: String) : Result
        data class Invalid(val reason: String) : Result
    }

    fun validate(raw: String): Result {
        val name = raw.trim()
        if (name.isEmpty()) return Result.Invalid("El nombre está vacío")
        if (name == "." || name == "..") return Result.Invalid("Nombre reservado")
        if (name.any { it == '/' || it == '\u0000' }) return Result.Invalid("No puede contener '/' ni caracteres nulos")
        if (name.any { it.isISOControl() }) return Result.Invalid("No puede contener caracteres de control")
        if (name.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return Result.Invalid("Demasiado largo (máx. $MAX_BYTES bytes)")
        return Result.Ok(name)
    }

    /** "foto.jpg" + 2 -> "foto (2).jpg"; los ficheros ocultos sin extensión ("`.bashrc`") no se parten. */
    fun withSuffix(name: String, n: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) "$name ($n)" else "${name.substring(0, dot)} ($n)${name.substring(dot)}"
    }
}
