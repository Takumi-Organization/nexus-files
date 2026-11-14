package dev.nexus.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files
import java.util.TreeMap

/** Servidor WebDAV falso en memoria (raíz /dav/). */
class FakeDav(val user: String = "u", val pass: String = "p", var dropGetAfter: Long = -1) {
    @Volatile var lastRange: String? = null
    private var dropped = false
    val files = TreeMap<String, ByteArray?>() // null = carpeta
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
    val port get() = server.address.port
    init {
        files["/"] = null
        server.createContext("/dav") { ex -> try { handle(ex) } catch (e: Throwable) { ex.sendResponseHeaders(500, -1) } finally { ex.close() } }
        server.start()
    }
    fun stop() = server.stop(0)
    private fun path(ex: HttpExchange) = URLDecoder.decode(ex.requestURI.rawPath.removePrefix("/dav"), "UTF-8").ifEmpty { "/" }.let { if (it.length > 1) it.trimEnd('/') else it }
    private fun reply(ex: HttpExchange, code: Int, body: ByteArray? = null, type: String? = null) {
        if (type != null) ex.responseHeaders.add("Content-Type", type)
        if (body == null) ex.sendResponseHeaders(code, -1) else { ex.sendResponseHeaders(code, if (body.size > 100) 0 else body.size.toLong()); ex.responseBody.write(body) }
    }
    private fun handle(ex: HttpExchange) {
        val auth = ex.requestHeaders.getFirst("Authorization")
        if (auth != "Basic " + java.util.Base64.getEncoder().encodeToString("$user:$pass".toByteArray())) return reply(ex, 401)
        val p = path(ex)
        val bodyIn = ex.requestBody.readBytes()
        when (ex.requestMethod) {
            "PROPFIND" -> {
                if (p !in files) return reply(ex, 404)
                val sb = StringBuilder("""<?xml version="1.0"?><D:multistatus xmlns:D="DAV:">""")
                fun item(k: String) {
                    val d = files[k] == null
                    val href = "/dav" + k.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") } + if (d && k != "/") "/" else ""
                    sb.append("<D:response><D:href>$href</D:href><D:propstat><D:prop>")
                    sb.append(if (d) "<D:resourcetype><D:collection/></D:resourcetype>" else "<D:resourcetype/><D:getcontentlength>${files[k]!!.size}</D:getcontentlength><D:getlastmodified>Wed, 11 Nov 2026 10:00:00 GMT</D:getlastmodified>")
                    sb.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>")
                }
                item(p)
                files.keys.filter { it != p && (if (p == "/") "" else p).let { pr -> it.startsWith("$pr/") && '/' !in it.removePrefix("$pr/") } }.forEach(::item)
                // respuesta maliciosa: entrada fuera de la carpeta y con nombre de salto de directorio
                sb.append("<D:response><D:href>/dav/../../etc/passwd</D:href><D:propstat><D:prop><D:resourcetype/></D:prop></D:propstat></D:response>")
                sb.append("</D:multistatus>")
                reply(ex, 207, sb.toString().toByteArray(), "application/xml")
            }
            "GET" -> {
                val f = files[p] ?: return reply(ex, 404)
                val range = ex.requestHeaders.getFirst("Range"); lastRange = range
                val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                val body = f.copyOfRange(start, f.size)
                if (range != null) ex.responseHeaders.add("Content-Range", "bytes $start-${f.size - 1}/${f.size}")
                ex.sendResponseHeaders(if (range != null) 206 else 200, body.size.toLong())
                if (dropGetAfter >= 0 && !dropped) { dropped = true; ex.responseBody.write(body, 0, dropGetAfter.toInt()); ex.responseBody.flush(); ex.close(); return }
                ex.responseBody.write(body)
            }
            "PUT" -> {
                if (files[p.substringBeforeLast('/').ifEmpty { "/" }.let { it }] != null || p.substringBeforeLast('/').ifEmpty { "/" } !in files) return reply(ex, 409)
                if (ex.requestHeaders.getFirst("If-None-Match") == "*" && p in files) return reply(ex, 412)
                val existed = p in files; files[p] = bodyIn; reply(ex, if (existed) 204 else 201)
            }
            "MKCOL" -> if (p in files) reply(ex, 405) else if (p.substringBeforeLast('/').ifEmpty { "/" } !in files) reply(ex, 409) else { files[p] = null; reply(ex, 201) }
            "DELETE" -> if (p !in files) reply(ex, 404) else { files.keys.filter { it == p || it.startsWith("$p/") }.forEach(files::remove); reply(ex, 204) }
            "MOVE" -> {
                val dest = URLDecoder.decode(java.net.URI(ex.requestHeaders.getFirst("Destination")).rawPath.removePrefix("/dav"), "UTF-8")
                if (p !in files) return reply(ex, 404)
                if (dest in files) return reply(ex, 412)
                files.keys.filter { it == p || it.startsWith("$p/") }.toList().forEach { k -> files[dest + k.removePrefix(p)] = files.remove(k) }
                reply(ex, 201)
            }
            else -> reply(ex, 501)
        }
    }
}

class WebDavTest {
    private lateinit var dav: FakeDav
    private lateinit var tmp: File
    private lateinit var fs: WebDavFs
    @Before fun up() { dav = FakeDav(); tmp = Files.createTempDirectory("wd").toFile(); fs = WebDavFs("http://127.0.0.1:${dav.port}/dav", "u", "p") }
    @After fun down() { dav.stop(); tmp.deleteRecursively() }

    @Test fun `ida y vuelta con nombres con espacios y tildes`() {
        val src = File(tmp, "in.bin").apply { writeBytes(ByteArray(200_000) { (it % 251).toByte() }) }
        fs.mkdir("/Mis fotos")
        fs.upload(src, "/Mis fotos/camión 1.bin")
        val l = fs.list("/Mis fotos"); assertEquals(1, l.size)
        assertEquals("camión 1.bin", l[0].name); assertEquals(200_000L, l[0].size); assertFalse(l[0].isDir); assertTrue(l[0].modified > 0)
        val dst = File(tmp, "out.bin"); var last = 0L
        fs.download("/Mis fotos/camión 1.bin", dst) { last = it }
        assertArrayEquals(src.readBytes(), dst.readBytes()); assertEquals(200_000L, last)
        assertTrue(tmp.listFiles()!!.none { it.name.endsWith(FileOps.PART) })
    }

    @Test fun `listar raiz distingue carpetas e ignora entradas fuera de la carpeta`() {
        fs.mkdir("/a"); File(tmp, "x").writeText("1"); fs.upload(File(tmp, "x"), "/f.txt")
        val l = fs.list("/")
        assertEquals(setOf("a", "f.txt"), l.map { it.name }.toSet())
        assertTrue(l.first { it.name == "a" }.isDir)
    }

    @Test fun `subir no sobrescribe salvo que se pida`() {
        val f = File(tmp, "x").apply { writeText("uno") }
        fs.upload(f, "/x.txt")
        f.writeText("dos")
        assertThrows(RemoteException::class.java) { fs.upload(f, "/x.txt") }
        assertEquals("uno", String(dav.files["/x.txt"]!!))
        fs.upload(f, "/x.txt", overwrite = true); assertEquals("dos", String(dav.files["/x.txt"]!!))
    }

    @Test fun `renombrar no pisa y borrar carpeta es recursivo`() {
        val f = File(tmp, "x").apply { writeText("1") }
        fs.mkdir("/d"); fs.upload(f, "/d/a"); fs.upload(f, "/d/b")
        assertThrows(RemoteException::class.java) { fs.rename("/d/a", "/d/b") }
        fs.rename("/d/a", "/d/c"); assertTrue("/d/c" in dav.files && "/d/a" !in dav.files)
        fs.delete("/d", true); assertTrue(dav.files.keys.none { it.startsWith("/d") })
        assertThrows(RemoteException::class.java) { fs.delete("/", true) }
    }

    @Test fun `credenciales malas y recurso inexistente dan error claro`() {
        val bad = WebDavFs("http://127.0.0.1:${dav.port}/dav", "u", "mala")
        assertTrue(assertThrows(RemoteException::class.java) { bad.list("/") }.message!!.contains("denegado"))
        assertTrue(assertThrows(RemoteException::class.java) { fs.download("/nada", File(tmp, "n")) }.message!!.contains("no existe"))
        assertFalse(File(tmp, "n").exists())
    }

    @Test fun `XML con DOCTYPE se rechaza`() {
        val evil = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><d:multistatus xmlns:d="DAV:"/>"""
        assertThrows(RemoteException::class.java) { fs.parse(evil, "/") }
    }
}
