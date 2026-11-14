package dev.nexus.core

import java.io.IOException
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.*

/**
 * El servidor presenta una identidad (certificado TLS o clave de host SSH) que no está confiada.
 * La interfaz muestra [fingerprint] y, si el usuario acepta, la guarda en el perfil (TOFU) y reintenta.
 */
class UntrustedHostException(val kind: String, val fingerprint: String, message: String) : IOException(message)

internal interface CapturedMarker { val fingerprint: String; val message: String? }

object Fingerprints {
    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(":") { "%02X".format(it) }
    fun same(a: String, b: String) = a.replace(":", "").equals(b.replace(":", ""), ignoreCase = true)
}

/** TLS cliente con validación del sistema + nombre de host, o fijado a una huella SHA-256 del certificado ([pinned], p. ej. autofirmados). */
object Tls {
    private class Captured(val fp: String, msg: String) : CertificateException(msg), CapturedMarker { override val fingerprint get() = fp }

    private class Tm(private val delegate: X509ExtendedTrustManager, private val pinned: String) : X509ExtendedTrustManager() {
        private fun check(chain: Array<X509Certificate>, run: () -> Unit) {
            val fp = Fingerprints.sha256Hex(chain[0].encoded)
            if (pinned.isNotEmpty()) { if (!Fingerprints.same(fp, pinned)) throw Captured(fp, "La huella del certificado no coincide con la guardada"); return }
            try { run() } catch (e: CertificateException) { throw Captured(fp, e.message ?: "Certificado no confiable") }
        }
        override fun checkServerTrusted(c: Array<X509Certificate>, a: String, s: Socket?) = check(c) { delegate.checkServerTrusted(c, a, s) }
        override fun checkServerTrusted(c: Array<X509Certificate>, a: String, e: SSLEngine?) = check(c) { delegate.checkServerTrusted(c, a, e) }
        override fun checkServerTrusted(c: Array<X509Certificate>, a: String) = check(c) { delegate.checkServerTrusted(c, a) }
        override fun checkClientTrusted(c: Array<X509Certificate>, a: String, s: Socket?) = throw CertificateException("solo cliente")
        override fun checkClientTrusted(c: Array<X509Certificate>, a: String, e: SSLEngine?) = throw CertificateException("solo cliente")
        override fun checkClientTrusted(c: Array<X509Certificate>, a: String) = throw CertificateException("solo cliente")
        override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
    }

    private val contexts = java.util.concurrent.ConcurrentHashMap<String, SSLContext>()

    internal fun engineFor(host: String, peerPort: Int, pinned: String, protocols: Array<String>?): SSLEngine {
        val ctx = contexts.getOrPut(pinned) { context(pinned) }
        return ctx.createSSLEngine(host, peerPort).apply {
            useClientMode = true
            if (protocols != null) enabledProtocols = protocols
            sslParameters = sslParameters.also { it.endpointIdentificationAlgorithm = if (pinned.isEmpty()) "HTTPS" else null }
        }
    }

    private fun context(pinned: String): SSLContext {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
        val d = tmf.trustManagers.filterIsInstance<X509ExtendedTrustManager>().firstOrNull() ?: throw IOException("Sin gestor de confianza del sistema")
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(Tm(d, pinned)), null) }
    }

    /** Misma [SSLContext] (y por tanto misma caché de sesiones) para sockets y motores con igual configuración de confianza. */
    fun factory(pinned: String): SSLSocketFactory = contexts.getOrPut(pinned) { context(pinned) }.socketFactory

    /**
     * Envuelve [raw] en TLS y hace el handshake. Lanza [UntrustedHostException] si el certificado no se acepta.
     * [peerPort] es el puerto con el que el JDK busca una sesión que reanudar; FTPS lo usa en las conexiones de datos con el puerto de control
     * para REUTILIZAR la sesión TLS de control (vsftpd y otros lo exigen: `require_ssl_reuse`). La fábrica se comparte para que la caché de sesiones sea la misma.
     */
    fun wrap(raw: Socket, host: String, port: Int, pinned: String, peerPort: Int = port, protocols: Array<String>? = null): SSLSocket {
        val s = factory(pinned).createSocket(raw, host, peerPort, true) as SSLSocket
        // con huella fijada el nombre no importa (autofirmados); sin ella se exige que el certificado sea del host
        if (protocols != null) s.enabledProtocols = protocols
        s.sslParameters = s.sslParameters.also { it.endpointIdentificationAlgorithm = if (pinned.isEmpty()) "HTTPS" else null }
        try { s.startHandshake() } catch (e: SSLException) {
            try { s.close() } catch (_: IOException) {}
            var c: Throwable? = e; while (c != null && c !is Captured) c = c.cause
            if (c is Captured) throw UntrustedHostException("certificado", c.fp, c.message ?: "Certificado no confiable")
            throw e
        }
        return s
    }
}

/**
 * Conexión TLS sobre [SSLEngine] (flujos bloqueantes). Existe porque `SSLSocket` busca sesiones que reanudar por el puerto REAL del servidor,
 * y FTPS necesita que la conexión de DATOS reanude la sesión TLS de la de CONTROL (que está en otro puerto; vsftpd lo exige con `require_ssl_reuse`).
 * Con `SSLEngine` el puerto de la caché de sesiones es el que indiquemos ([Tls.engineSocket]).
 */
class TlsEngineSocket internal constructor(private val raw: Socket, private val engine: SSLEngine) : Socket() {
    private val net = engine.session.packetBufferSize
    private var inNet = java.nio.ByteBuffer.allocate(net * 2).also { it.flip() }   // modo lectura (vacío)
    private var inApp = java.nio.ByteBuffer.allocate(engine.session.applicationBufferSize).also { it.flip() }
    private var eof = false
    private val rawIn = raw.getInputStream(); private val rawOut = raw.getOutputStream()
    val session: javax.net.ssl.SSLSession get() = engine.session

    private fun fillNet(): Boolean {
        inNet.compact()
        if (!inNet.hasRemaining()) { inNet = java.nio.ByteBuffer.allocate(inNet.capacity() * 2).also { it.put(inNet.flip() as java.nio.ByteBuffer) }; }
        val n = rawIn.read(inNet.array(), inNet.arrayOffset() + inNet.position(), inNet.remaining())
        if (n > 0) inNet.position(inNet.position() + n)
        inNet.flip()
        return n >= 0
    }

    private fun wrapOut(src: java.nio.ByteBuffer) {
        while (true) {
            val out = java.nio.ByteBuffer.allocate(engine.session.packetBufferSize)
            val r = engine.wrap(src, out)
            out.flip(); if (out.hasRemaining()) { rawOut.write(out.array(), 0, out.limit()); rawOut.flush() }
            when (r.status) {
                SSLEngineResult.Status.BUFFER_OVERFLOW -> continue
                SSLEngineResult.Status.CLOSED -> return
                else -> if (!src.hasRemaining() && r.handshakeStatus != SSLEngineResult.HandshakeStatus.NEED_WRAP) return
            }
        }
    }

    /** Avanza el handshake (o responde a mensajes posteriores) hasta que no haya nada pendiente de enviar. */
    private fun drive(): Boolean {
        while (true) when (engine.handshakeStatus) {
            SSLEngineResult.HandshakeStatus.NEED_TASK -> { var t = engine.delegatedTask; while (t != null) { t.run(); t = engine.delegatedTask } }
            SSLEngineResult.HandshakeStatus.NEED_WRAP -> wrapOut(java.nio.ByteBuffer.allocate(0))
            SSLEngineResult.HandshakeStatus.NEED_UNWRAP, SSLEngineResult.HandshakeStatus.NEED_UNWRAP_AGAIN -> { if (!unwrapSome()) return false }
            else -> return true
        }
    }

    /** Descifra lo que haya en [inNet]; lee del socket si hace falta. false = fin de flujo. */
    private fun unwrapSome(): Boolean {
        while (true) {
            if (inNet.hasRemaining()) {
                inApp.compact()
                if (inApp.remaining() < engine.session.applicationBufferSize) { inApp = java.nio.ByteBuffer.allocate(inApp.capacity() + engine.session.applicationBufferSize).also { it.put(inApp.flip() as java.nio.ByteBuffer) } }
                val r = engine.unwrap(inNet, inApp); inApp.flip()
                when (r.status) {
                    SSLEngineResult.Status.OK -> return true
                    SSLEngineResult.Status.CLOSED -> { eof = true; return false }
                    SSLEngineResult.Status.BUFFER_UNDERFLOW -> {}
                    SSLEngineResult.Status.BUFFER_OVERFLOW -> continue
                }
            }
            if (!fillNet()) { eof = true; return false }
        }
    }

    internal fun handshake() { engine.beginHandshake(); if (!drive()) throw javax.net.ssl.SSLHandshakeException("Conexión cerrada durante el handshake TLS") }

    private val input = object : java.io.InputStream() {
        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (!inApp.hasRemaining()) { if (eof) return -1; if (!unwrapSome()) return -1; if (engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP) drive() }
            val n = minOf(len, inApp.remaining()); inApp.get(b, off, n); return n
        }
    }
    private val output = object : java.io.OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) { wrapOut(java.nio.ByteBuffer.wrap(b, off, len)) }
        override fun flush() { rawOut.flush() }
    }
    override fun getInputStream(): java.io.InputStream = input
    override fun getOutputStream(): java.io.OutputStream = output
    override fun isClosed(): Boolean = raw.isClosed
    override fun close() {
        try { if (!raw.isClosed) { engine.closeOutbound(); try { wrapOut(java.nio.ByteBuffer.allocate(0)) } catch (_: Exception) {} } } finally { raw.close() }
    }
    override fun setSoTimeout(timeout: Int) { raw.soTimeout = timeout }
    override fun getSoTimeout(): Int = raw.soTimeout
}

/** TLS cliente sobre SSLEngine con handshake hecho; [peerPort] es la clave de la caché de sesiones. */
fun Tls.engineSocket(raw: Socket, host: String, peerPort: Int, pinned: String, protocols: Array<String>? = null): TlsEngineSocket {
    val e = engineFor(host, peerPort, pinned, protocols)
    val s = TlsEngineSocket(raw, e)
    try { s.handshake() } catch (x: SSLException) {
        try { raw.close() } catch (_: IOException) {}
        var c: Throwable? = x; while (c != null && c !is CapturedMarker) c = c.cause
        (c as? CapturedMarker)?.let { throw UntrustedHostException("certificado", it.fingerprint, it.message ?: "Certificado no confiable") }
        throw x
    }
    return s
}
