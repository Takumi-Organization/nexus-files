package dev.nexus.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** SAF: carpeta elegida con el selector del sistema (solo Android; [RemoteProfile.host] guarda el URI del árbol). */
enum class Protocol { FTP, FTPS, WEBDAV, SFTP, SMB, SAF }

/** Conexión guardada. [basePath]: ruta base WebDAV o, en SMB, el nombre del recurso compartido. [trusted]: huella del certificado (FTPS/WebDAV) o de la clave de host (SFTP) aceptada por el usuario.
 * La contraseña nunca está aquí: se guarda cifrada aparte ([ProfileStore]). */
data class RemoteProfile(val id: String, val name: String, val protocol: Protocol, val host: String, val port: Int, val user: String, val basePath: String = "", val trusted: String = "", val domain: String = "")

/** Credenciales de una conexión. [privateKey] es el texto de la clave privada (PEM/OpenSSH) para SFTP; [passphrase] su frase de paso. */
data class Secrets(val password: String = "", val privateKey: String = "", val passphrase: String = "")

/** Cifra/descifra secretos. En Android lo implementa Android Keystore; en tests, un doble. */
interface SecretBox { fun encrypt(plain: String): String; fun decrypt(token: String): String }

object RemoteFactory {
    /** Valor de [RemoteProfile.basePath] en FTPS para limitar a TLS 1.2 (reanudación de sesión por identificador; la piden servidores como vsftpd). */
    const val TLS12 = "tls12"

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

    fun open(p: RemoteProfile, password: String): RemoteFs = open(p, Secrets(password))

    fun open(p: RemoteProfile, s: Secrets): RemoteFs = when (p.protocol) {
        Protocol.FTP -> FtpFs(p.host, p.port.takeIf { it > 0 } ?: 21, p.user, s.password)
        Protocol.FTPS -> FtpFs(p.host, p.port.takeIf { it > 0 } ?: 21, p.user, s.password, tls = true, pinned = p.trusted, tlsProtocols = if (p.basePath == TLS12) arrayOf("TLSv1.2") else null)
        Protocol.WEBDAV -> WebDavFs(webDavUrl(p), p.user.ifEmpty { null }, s.password, pinned = p.trusted)
        Protocol.SFTP -> SftpFs(p.host, p.port.takeIf { it > 0 } ?: 22, p.user, s.password, p.trusted, privateKey = s.privateKey, passphrase = s.passphrase)
        Protocol.SAF -> throw RemoteException("Las carpetas del sistema solo se abren desde la app")
        Protocol.SMB -> SmbFs(p.host, p.port.takeIf { it > 0 } ?: 445, p.user, s.password, p.basePath.trim('/').ifEmpty { throw RemoteException("Falta el nombre del recurso compartido") }, domain = p.domain)
    }
}

/** Perfiles en un JSON atómico; contraseñas cifradas con [box]. Si no se puede descifrar, devuelve null (se vuelve a pedir). */
class ProfileStore(private val file: File, private val box: SecretBox) {
    private fun readAll(): JSONArray = if (file.exists()) runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray()) else JSONArray()

    fun list(): List<RemoteProfile> = readAll().let { a ->
        List(a.length()) { a.getJSONObject(it) }.mapNotNull { o -> runCatching {
            RemoteProfile(o.getString("id"), o.getString("name"), Protocol.valueOf(o.getString("protocol")), o.getString("host"), o.getInt("port"), o.getString("user"), o.optString("basePath"), o.optString("trusted"), o.optString("domain"))
        }.getOrNull() }
    }

    private fun raw(id: String): JSONObject? = readAll().let { a -> List(a.length()) { a.getJSONObject(it) }.firstOrNull { it.optString("id") == id } }
    private fun dec(o: JSONObject, k: String): String? = o.optString(k).takeIf { it.isNotEmpty() }?.let { runCatching { box.decrypt(it) }.getOrNull() }

    /** null si no hay perfil o su contraseña guardada no se puede descifrar (se vuelve a pedir). Sin contraseña guardada devuelve "". */
    fun secrets(id: String): Secrets? {
        val o = raw(id) ?: return null
        val pw = if (o.optString("secret").isEmpty()) "" else dec(o, "secret") ?: return null
        val key = if (o.optString("key").isEmpty()) "" else dec(o, "key") ?: return null
        return Secrets(pw, key, dec(o, "keyPass") ?: "")
    }

    fun password(id: String): String? = raw(id)?.optString("secret")?.takeIf { it.isNotEmpty() }?.let { runCatching { box.decrypt(it) }.getOrNull() }

    /** [password], [privateKey] y [passphrase]: null conserva lo guardado, "" lo borra. */
    fun save(p: RemoteProfile, password: String?, privateKey: String? = null, passphrase: String? = null) {
        val old = readAll(); val out = JSONArray()
        var prev: JSONObject? = null
        for (i in 0 until old.length()) { val o = old.getJSONObject(i); if (o.optString("id") == p.id) prev = o else out.put(o) }
        fun field(v: String?, k: String) = if (v == null) prev?.optString(k) ?: "" else if (v.isEmpty()) "" else box.encrypt(v)
        out.put(JSONObject().put("id", p.id).put("name", p.name).put("protocol", p.protocol.name).put("host", p.host).put("port", p.port)
            .put("user", p.user).put("basePath", p.basePath).put("trusted", p.trusted).put("domain", p.domain)
            .put("secret", field(password, "secret")).put("key", field(privateKey, "key")).put("keyPass", field(passphrase, "keyPass")))
        write(out)
    }

    fun delete(id: String) { val old = readAll(); val out = JSONArray(); for (i in 0 until old.length()) if (old.getJSONObject(i).optString("id") != id) out.put(old.get(i)); write(out) }

    private fun write(a: JSONArray) {
        val tmp = File(file.parentFile, file.name + ".tmp"); tmp.writeText(a.toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
