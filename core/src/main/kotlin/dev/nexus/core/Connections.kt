package dev.nexus.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** SAF: carpeta elegida con el selector del sistema (solo Android; [RemoteProfile.host] guarda el URI del árbol). */
enum class Protocol { FTP, FTPS, WEBDAV, SFTP, SMB, SAF }

/** Conexión guardada. [basePath]: ruta base WebDAV o, en SMB, el nombre del recurso compartido. [trusted]: huella del certificado (FTPS/WebDAV) o de la clave de host (SFTP) aceptada por el usuario.
 * La contraseña nunca está aquí: se guarda cifrada aparte ([ProfileStore]). */
data class RemoteProfile(val id: String, val name: String, val protocol: Protocol, val host: String, val port: Int, val user: String, val basePath: String = "", val trusted: String = "")

/** Cifra/descifra secretos. En Android lo implementa Android Keystore; en tests, un doble. */
interface SecretBox { fun encrypt(plain: String): String; fun decrypt(token: String): String }

object RemoteFactory {
    /** WEBDAV: [RemoteProfile.host] puede llevar `http://` o `https://`; sin esquema se asume https. El puerto y la ruta base se añaden aquí. */
    fun webDavUrl(p: RemoteProfile): String {
        val plain = p.host.startsWith("http://", ignoreCase = true)
        val hostPart = p.host.substringAfter("://").trimEnd('/')
        val hasPort = hostPart.substringBefore('/').let { ':' in it && !it.endsWith("]") }
        val port = if (p.port > 0 && !hasPort) ":${p.port}" else ""
        val (h, path) = hostPart.substringBefore('/') to hostPart.substringAfter('/', "").let { if (it.isEmpty()) "" else "/$it" }
        val base = if (p.basePath.isBlank()) "" else "/" + p.basePath.trim('/')
        return "${if (plain) "http" else "https"}://$h$port$path$base"
    }

    fun open(p: RemoteProfile, password: String): RemoteFs = when (p.protocol) {
        Protocol.FTP -> FtpFs(p.host, p.port.takeIf { it > 0 } ?: 21, p.user, password)
        Protocol.FTPS -> FtpFs(p.host, p.port.takeIf { it > 0 } ?: 21, p.user, password, tls = true, pinned = p.trusted)
        Protocol.WEBDAV -> WebDavFs(webDavUrl(p), p.user.ifEmpty { null }, password, pinned = p.trusted)
        Protocol.SFTP -> SftpFs(p.host, p.port.takeIf { it > 0 } ?: 22, p.user, password, p.trusted)
        Protocol.SAF -> throw RemoteException("Las carpetas del sistema solo se abren desde la app")
        Protocol.SMB -> SmbFs(p.host, p.port.takeIf { it > 0 } ?: 445, p.user, password, p.basePath.trim('/').ifEmpty { throw RemoteException("Falta el nombre del recurso compartido") })
    }
}

/** Perfiles en un JSON atómico; contraseñas cifradas con [box]. Si no se puede descifrar, devuelve null (se vuelve a pedir). */
class ProfileStore(private val file: File, private val box: SecretBox) {
    private fun readAll(): JSONArray = if (file.exists()) runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray()) else JSONArray()

    fun list(): List<RemoteProfile> = readAll().let { a ->
        List(a.length()) { a.getJSONObject(it) }.mapNotNull { o -> runCatching {
            RemoteProfile(o.getString("id"), o.getString("name"), Protocol.valueOf(o.getString("protocol")), o.getString("host"), o.getInt("port"), o.getString("user"), o.optString("basePath"), o.optString("trusted"))
        }.getOrNull() }
    }

    fun password(id: String): String? = readAll().let { a ->
        List(a.length()) { a.getJSONObject(it) }.firstOrNull { it.optString("id") == id }?.optString("secret")?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { box.decrypt(it) }.getOrNull() }
    }

    fun save(p: RemoteProfile, password: String?) {
        val old = readAll(); val out = JSONArray()
        var keep: String? = null
        for (i in 0 until old.length()) { val o = old.getJSONObject(i); if (o.optString("id") == p.id) keep = o.optString("secret") else out.put(o) }
        out.put(JSONObject().put("id", p.id).put("name", p.name).put("protocol", p.protocol.name).put("host", p.host).put("port", p.port)
            .put("user", p.user).put("basePath", p.basePath).put("trusted", p.trusted).put("secret", if (password != null) box.encrypt(password) else keep ?: ""))
        write(out)
    }

    fun delete(id: String) { val old = readAll(); val out = JSONArray(); for (i in 0 until old.length()) if (old.getJSONObject(i).optString("id") != id) out.put(old.get(i)); write(out) }

    private fun write(a: JSONArray) {
        val tmp = File(file.parentFile, file.name + ".tmp"); tmp.writeText(a.toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
