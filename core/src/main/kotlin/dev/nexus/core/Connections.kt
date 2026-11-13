package dev.nexus.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class Protocol { FTP, WEBDAV }

/** Conexión guardada. La contraseña nunca está aquí: se guarda cifrada aparte ([ProfileStore]). */
data class RemoteProfile(val id: String, val name: String, val protocol: Protocol, val host: String, val port: Int, val user: String, val basePath: String = "")

/** Cifra/descifra secretos. En Android lo implementa Android Keystore; en tests, un doble. */
interface SecretBox { fun encrypt(plain: String): String; fun decrypt(token: String): String }

object RemoteFactory {
    /** WEBDAV: [host] puede llevar esquema (`https://servidor/dav`); sin esquema se asume https. */
    fun open(p: RemoteProfile, password: String): RemoteFs = when (p.protocol) {
        Protocol.FTP -> FtpFs(p.host, p.port, p.user, password)
        Protocol.WEBDAV -> {
            val base = if ("://" in p.host) p.host else "https://${p.host}${if (p.port > 0) ":${p.port}" else ""}${p.basePath}"
            WebDavFs(base, p.user.ifEmpty { null }, password)
        }
    }
}

/** Perfiles en un JSON atómico; contraseñas cifradas con [box]. Si no se puede descifrar, devuelve null (se vuelve a pedir). */
class ProfileStore(private val file: File, private val box: SecretBox) {
    private fun readAll(): JSONArray = if (file.exists()) runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray()) else JSONArray()

    fun list(): List<RemoteProfile> = readAll().let { a ->
        List(a.length()) { a.getJSONObject(it) }.mapNotNull { o -> runCatching {
            RemoteProfile(o.getString("id"), o.getString("name"), Protocol.valueOf(o.getString("protocol")), o.getString("host"), o.getInt("port"), o.getString("user"), o.optString("basePath"))
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
            .put("user", p.user).put("basePath", p.basePath).put("secret", if (password != null) box.encrypt(password) else keep ?: ""))
        write(out)
    }

    fun delete(id: String) { val old = readAll(); val out = JSONArray(); for (i in 0 until old.length()) if (old.getJSONObject(i).optString("id") != id) out.put(old.get(i)); write(out) }

    private fun write(a: JSONArray) {
        val tmp = File(file.parentFile, file.name + ".tmp"); tmp.writeText(a.toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
