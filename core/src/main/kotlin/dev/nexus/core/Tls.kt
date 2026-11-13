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

object Fingerprints {
    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(":") { "%02X".format(it) }
    fun same(a: String, b: String) = a.replace(":", "").equals(b.replace(":", ""), ignoreCase = true)
}

/** TLS cliente con validación del sistema + nombre de host, o fijado a una huella SHA-256 del certificado ([pinned], p. ej. autofirmados). */
object Tls {
    private class Captured(val fp: String, msg: String) : CertificateException(msg)

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

    fun factory(pinned: String): SSLSocketFactory {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
        val d = tmf.trustManagers.filterIsInstance<X509ExtendedTrustManager>().firstOrNull() ?: throw IOException("Sin gestor de confianza del sistema")
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(Tm(d, pinned)), null) }.socketFactory
    }

    /** Envuelve [raw] en TLS y hace el handshake. Lanza [UntrustedHostException] si el certificado no se acepta. */
    fun wrap(raw: Socket, host: String, port: Int, pinned: String): SSLSocket {
        val s = factory(pinned).createSocket(raw, host, port, true) as SSLSocket
        // con huella fijada el nombre no importa (autofirmados); sin ella se exige que el certificado sea del host
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
