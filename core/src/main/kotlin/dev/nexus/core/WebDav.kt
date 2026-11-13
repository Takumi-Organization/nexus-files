package dev.nexus.core

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.net.URI
import java.net.URLDecoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Cliente WebDAV (RFC 4918) con autenticación Basic. Usar https salvo en redes de confianza: Basic viaja en claro por http. */
class WebDavFs(baseUrl: String, user: String? = null, password: String? = null, timeoutMs: Int = 15_000, pinned: String = "") : RemoteFs {
    private val base = URI(baseUrl.trimEnd('/') + "/")
    private val basePath = base.rawPath.trimEnd('/')
    private val http = MiniHttp(base, user, password, timeoutMs, pinned)

    private fun enc(path: String): String =
        basePath + RemotePaths.normalize(path).split('/').filter { it.isNotEmpty() }.joinToString("") { "/" + java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun fail(op: String, status: Int, path: String): Nothing = throw RemoteException(
        when (status) {
            401, 403 -> "$op: acceso denegado ($status)"
            404 -> "$op: no existe $path"
            405, 409 -> "$op: no permitido o falta la carpeta superior ($status)"
            412 -> "$op: ya existe $path"
            507 -> "$op: sin espacio en el servidor"
            else -> "$op: el servidor respondió $status"
        })

    override fun list(path: String): List<RemoteEntry> {
        val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""
        val bytes = body.toByteArray()
        val self = RemotePaths.normalize(path)
        val xml = http.request("PROPFIND", enc(path).let { if (self == "/") "$it/" else it }, mapOf("Depth" to "1", "Content-Type" to "application/xml"), bytes.inputStream(), bytes.size.toLong()).use { r ->
            if (r.status != 207) fail("Listar", r.status, path)
            r.text()
        }
        return parse(xml, self)
    }

    internal fun parse(xml: String, self: String): List<RemoteEntry> {
        // Sin DOCTYPE no hay entidades ni XXE. Se comprueba el texto porque el parser de Android no admite
        // la característica «disallow-doctype-decl» (lanza ParserConfigurationException); en la JVM se activa además.
        if (xml.contains("<!DOCTYPE", ignoreCase = true) || xml.contains("<!ENTITY", ignoreCase = true)) throw RemoteException("Respuesta WebDAV rechazada (DOCTYPE)")
        val f = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            try { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) {}
            try { isXIncludeAware = false; isExpandEntityReferences = false } catch (_: Exception) {}
            try { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) } catch (_: Exception) {}
        }
        val doc = try { f.newDocumentBuilder().parse(InputSource(StringReader(xml))) } catch (e: Exception) { throw RemoteException("Respuesta WebDAV inválida", e) }
        val out = ArrayList<RemoteEntry>()
        val responses = doc.getElementsByTagNameNS("DAV:", "response")
        for (i in 0 until responses.length) {
            val r = responses.item(i) as Element
            val href = r.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent?.trim() ?: continue
            val hrefPath = try { URLDecoder.decode(URI(href).rawPath.replace("+", "%2B"), "UTF-8") } catch (_: Exception) { continue }
            val prefix = URLDecoder.decode(basePath.replace("+", "%2B"), "UTF-8")
            if (!hrefPath.startsWith(prefix)) continue
            val rel = RemotePaths.normalize(hrefPath.removePrefix(prefix))
            if (rel == self) continue
            if (RemotePaths.parent(rel) != self) continue // ignora cualquier cosa fuera de la carpeta pedida
            val name = RemotePaths.nameOf(rel)
            if (!RemotePaths.safeName(name)) continue
            val isDir = r.getElementsByTagNameNS("DAV:", "collection").length > 0
            val size = r.getElementsByTagNameNS("DAV:", "getcontentlength").item(0)?.textContent?.trim()?.toLongOrNull() ?: 0L
            val mod = r.getElementsByTagNameNS("DAV:", "getlastmodified").item(0)?.textContent?.trim()?.let {
                try { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() } catch (_: Exception) { 0L }
            } ?: 0L
            out.add(RemoteEntry(rel, name, isDir, if (isDir) 0 else size, mod))
        }
        return out
    }

    override fun download(path: String, dest: File, onProgress: (Long) -> Unit) {
        http.request("GET", enc(path)).use { r ->
            if (r.status != 200) fail("Descargar", r.status, path)
            val expected = r.headers["content-length"]?.toLongOrNull()
            var total = 0L
            atomicWrite(dest) { out ->
                val buf = ByteArray(32 * 1024)
                while (true) { val n = r.body.read(buf); if (n < 0) break; out.write(buf, 0, n); total += n; onProgress(total) }
            }
            if (expected != null && expected != total) { dest.delete(); throw RemoteException("Descarga incompleta") }
        }
    }

    override fun upload(src: File, path: String, overwrite: Boolean, onProgress: (Long) -> Unit) {
        val headers = if (overwrite) emptyMap() else mapOf("If-None-Match" to "*")
        src.inputStream().use { i ->
            http.request("PUT", enc(path), headers, i, src.length(), onProgress).use { r ->
                if (r.status !in 200..204) fail("Subir", r.status, path)
            }
        }
    }

    override fun mkdir(path: String) { http.request("MKCOL", enc(path)).use { if (it.status != 201) fail("Crear carpeta", it.status, path) } }

    override fun delete(path: String, isDir: Boolean) {
        if (RemotePaths.normalize(path) == "/") throw RemoteException("No se puede borrar la raíz")
        http.request("DELETE", enc(path)).use { if (it.status !in 200..204) fail("Borrar", it.status, path) }
    }

    override fun rename(from: String, to: String) {
        val dest = URI(base.scheme, base.authority, null, null, null).toString() + enc(to)
        http.request("MOVE", enc(from), mapOf("Destination" to dest, "Overwrite" to "F")).use { if (it.status !in 200..204) fail("Renombrar", it.status, from) }
    }
}
