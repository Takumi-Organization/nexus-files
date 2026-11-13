package dev.nexus.core

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Cliente HTTP/1.1 mínimo sobre sockets. Existe porque `HttpURLConnection` no admite PROPFIND/MKCOL/MOVE
 * (ni en la JVM ni en Android) y así evitamos añadir OkHttp. Una petición por conexión (`Connection: close`),
 * sin redirecciones (se tratan como error), https con verificación de nombre de host.
 */
internal class MiniHttp(private val base: URI, private val user: String?, private val password: String?, private val timeoutMs: Int = 15_000) {
    class Response(val status: Int, val headers: Map<String, String>, val body: InputStream, private val socket: Socket) : AutoCloseable {
        override fun close() { try { socket.close() } catch (_: IOException) {} }
        fun text(limit: Int = 16 shl 20): String {
            val out = ByteArrayOutputStream(); val buf = ByteArray(8192); var total = 0
            while (true) { val n = body.read(buf); if (n < 0) break; total += n; if (total > limit) throw IOException("Respuesta demasiado grande"); out.write(buf, 0, n) }
            return out.toString("UTF-8")
        }
    }

    fun request(method: String, path: String, headers: Map<String, String> = emptyMap(), body: InputStream? = null, length: Long = 0, onSent: (Long) -> Unit = {}): Response {
        val https = base.scheme.equals("https", true)
        require(https || base.scheme.equals("http", true)) { "Solo http/https" }
        val port = if (base.port > 0) base.port else if (https) 443 else 80
        val raw = Socket().apply { connect(InetSocketAddress(base.host, port), timeoutMs); soTimeout = timeoutMs }
        val sock: Socket = if (https) {
            ((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, base.host, port, true) as SSLSocket).apply {
                sslParameters = SSLParameters().also { it.endpointIdentificationAlgorithm = "HTTPS" }
                startHandshake()
            }
        } else raw
        try {
            val out = sock.getOutputStream().buffered()
            val h = StringBuilder("$method $path HTTP/1.1\r\nHost: ${base.host}${if (base.port > 0) ":${base.port}" else ""}\r\nConnection: close\r\nUser-Agent: NexusFiles\r\n")
            if (user != null) h.append("Authorization: Basic ").append(java.util.Base64.getEncoder().encodeToString("$user:${password ?: ""}".toByteArray())).append("\r\n")
            for ((k, v) in headers) { require('\r' !in v && '\n' !in v); h.append(k).append(": ").append(v).append("\r\n") }
            if (body != null || method == "PUT") h.append("Content-Length: $length\r\n")
            h.append("\r\n")
            out.write(h.toString().toByteArray(Charsets.ISO_8859_1))
            if (body != null) copyExactly(body, out, length, onSent)
            out.flush()
            val input = sock.getInputStream().buffered()
            val statusLine = readLine(input) ?: throw EOFException("El servidor cerró la conexión")
            val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("Respuesta HTTP inválida: $statusLine")
            val hdrs = LinkedHashMap<String, String>()
            while (true) { val l = readLine(input) ?: break; if (l.isEmpty()) break; val i = l.indexOf(':'); if (i > 0) hdrs[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim() }
            val stream: InputStream = when {
                method == "HEAD" || status == 204 || status == 304 -> ByteArray(0).inputStream()
                hdrs["transfer-encoding"]?.contains("chunked", true) == true -> ChunkedInputStream(input)
                hdrs["content-length"] != null -> LimitedInputStream(input, hdrs["content-length"]!!.toLongOrNull() ?: throw IOException("Content-Length inválido"))
                else -> input
            }
            return Response(status, hdrs, stream, sock)
        } catch (e: Throwable) { try { sock.close() } catch (_: IOException) {}; throw e }
    }

    private fun copyExactly(src: InputStream, dst: OutputStream, length: Long, onSent: (Long) -> Unit) {
        val buf = ByteArray(32 * 1024); var left = length; var sent = 0L
        while (left > 0) {
            val n = src.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IOException("El fichero cambió de tamaño durante la subida")
            dst.write(buf, 0, n); left -= n; sent += n; onSent(sent)
        }
    }

    private fun readLine(i: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = i.read(); if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar()); if (sb.length > 16_384) throw IOException("Cabecera demasiado larga")
        }
    }

    private class LimitedInputStream(i: InputStream, private var left: Long) : FilterInputStream(i) {
        override fun read(): Int { if (left <= 0) return -1; val c = super.read(); if (c < 0) throw EOFException("Respuesta truncada"); left--; return c }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = super.read(b, off, minOf(len.toLong(), left).toInt()); if (n < 0) throw EOFException("Respuesta truncada"); left -= n; return n
        }
    }

    private class ChunkedInputStream(private val i: InputStream) : InputStream() {
        private var left = 0L; private var done = false
        private fun line(): String { val sb = StringBuilder(); while (true) { val c = i.read(); if (c < 0) throw EOFException("Respuesta truncada"); if (c == '\n'.code) return sb.toString().trim(); sb.append(c.toChar()) } }
        private fun next() {
            if (left == 0L && !done) {
                val size = line().substringBefore(';').toLongOrNull(16) ?: throw IOException("Chunk inválido")
                if (size == 0L) { done = true } else left = size
            }
        }
        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            next(); if (done) return -1
            val n = i.read(b, off, minOf(len.toLong(), left).toInt()); if (n < 0) throw EOFException("Respuesta truncada")
            left -= n; if (left == 0L) line()
            return n
        }
    }
}
