package dev.nexus.core

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread

class TlsTest {
    private lateinit var dir: File
    private lateinit var server: SSLServerSocket
    private lateinit var certFp: String
    private lateinit var serverCtx: SSLContext

    @Before fun up() {
        dir = Files.createTempDirectory("tls").toFile()
        val ks = File(dir, "k.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val p = ProcessBuilder(keytool, "-genkeypair", "-alias", "s", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=127.0.0.1",
            "-ext", "san=ip:127.0.0.1", "-keystore", ks.path, "-storetype", "PKCS12", "-storepass", "changeit").redirectErrorStream(true).start()
        assertEquals(p.inputStream.readBytes().toString(Charsets.UTF_8), 0, p.waitFor())
        val store = KeyStore.getInstance("PKCS12").apply { ks.inputStream().use { load(it, "changeit".toCharArray()) } }
        certFp = Fingerprints.sha256Hex(store.getCertificate("s").encoded)
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "changeit".toCharArray()) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        serverCtx = ctx
        server = ctx.serverSocketFactory.createServerSocket(0, 5, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        thread(isDaemon = true) { while (!server.isClosed) try { server.accept().use { s -> s.getInputStream().read(); s.getOutputStream().write(7) } } catch (_: Exception) {} }
    }
    @After fun down() { server.close(); dir.deleteRecursively() }

    private fun connect(pin: String) = Tls.wrap(Socket("127.0.0.1", server.localPort), "127.0.0.1", server.localPort, pin)

    @Test fun `autofirmado sin huella guardada se rechaza y se informa de la huella`() {
        val e = assertThrows(UntrustedHostException::class.java) { connect("") }
        assertEquals(certFp, e.fingerprint)
    }

    @Test fun `con la huella correcta conecta y cifra`() {
        connect(certFp.lowercase().replace(":", "")).use { s -> s.getOutputStream().write(1); assertEquals(7, s.getInputStream().read()) }
    }

    @Test fun `con una huella distinta se rechaza`() {
        val e = assertThrows(UntrustedHostException::class.java) { connect("00:11:22") }
        assertEquals(certFp, e.fingerprint)
    }

    @Test fun `la conexion de datos reutiliza la sesion TLS de control (peerPort)`() {
        val second = server.let { s1 -> // segundo servidor con el MISMO contexto = misma caché de sesiones del servidor
            val ctx = serverCtx
            ctx.serverSocketFactory.createServerSocket(0, 5, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        }
        thread(isDaemon = true) { while (!second.isClosed) try { second.accept().use { s -> s.getInputStream().read(); s.getOutputStream().write(7) } } catch (_: Exception) {} }
        try {
            val control = Tls.wrap(Socket("127.0.0.1", server.localPort), "127.0.0.1", server.localPort, certFp, protocols = arrayOf("TLSv1.2"))
            control.getOutputStream().write(1); control.getInputStream().read()
            val reused = Tls.engineSocket(Socket("127.0.0.1", second.localPort), "127.0.0.1", server.localPort, certFp, protocols = arrayOf("TLSv1.2"))
            reused.getOutputStream().write(1); reused.getInputStream().read()
            val fresh = Tls.wrap(Socket("127.0.0.1", second.localPort), "127.0.0.1", second.localPort, certFp, protocols = arrayOf("TLSv1.2"))
            fresh.getOutputStream().write(1); fresh.getInputStream().read()
            // TLS 1.2 para poder comparar identificadores de sesión (en 1.3 se reanuda con tickets, mismo mecanismo de caché por host:puerto)
            assertArrayEquals("con peerPort de control se reanuda la sesión", control.session.id, reused.session.id)
            assertFalse("sin peerPort es una sesión nueva", control.session.id.contentEquals(fresh.session.id))
        } finally { second.close() }
    }
}
