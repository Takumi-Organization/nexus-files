package dev.nexus.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.SocketException
import java.util.concurrent.CancellationException

class RetryTest {
    /** RemoteFs que falla [fails] veces con [error] en list() y luego responde. Cuenta aperturas y cierres. */
    private class Flaky(var fails: Int, val error: () -> Throwable) {
        var opened = 0; var closed = 0; var calls = 0
        fun open(): RemoteFs = object : RemoteFs {
            init { opened++ }
            override fun list(path: String): List<RemoteEntry> { calls++; if (fails-- > 0) throw error(); return listOf(RemoteEntry("/a", "a", false, 1, 0)) }
            override fun download(path: String, dest: File, onProgress: (Long) -> Unit) {}
            override fun upload(src: File, path: String, overwrite: Boolean, onProgress: (Long) -> Unit) {}
            override fun mkdir(path: String) {}
            override fun delete(path: String, isDir: Boolean) {}
            override fun rename(from: String, to: String) {}
            override fun close() { closed++ }
        }
    }

    @Test fun `espera exponencial con tope`() {
        val p = RetryPolicy(maxAttempts = 6, baseMs = 1000, maxMs = 5000)
        assertEquals(listOf(1000L, 2000L, 4000L, 5000L, 5000L), (1..5).map { p.delayBefore(it) })
    }

    @Test fun `reconecta y reintenta tras fallos de red hasta tener exito`() {
        val f = Flaky(2) { SocketException("Connection reset") }
        val waits = ArrayList<Long>(); val notes = ArrayList<Int>()
        val fs = RetryingFs(f::open, RetryPolicy(4, 10, 100), sleep = { waits += it }, onRetry = { a, _, _ -> notes += a })
        assertEquals(1, fs.list("/").size)
        assertEquals(3, f.calls); assertEquals(3, f.opened); assertEquals(2, f.closed); assertEquals(listOf(2, 3), notes)
        assertTrue(waits.isNotEmpty())
    }

    @Test fun `al agotar los intentos lanza el ultimo error`() {
        val f = Flaky(100) { SocketException("caída") }
        val fs = RetryingFs(f::open, RetryPolicy(3, 1, 1), sleep = {})
        assertEquals("caída", assertThrows(SocketException::class.java) { fs.list("/") }.message)
        assertEquals(3, f.calls)
    }

    @Test fun `errores permanentes no se reintentan`() {
        for (e in listOf<Throwable>(RemoteException("Listar: permiso denegado"), UntrustedHostException("certificado", "AA", "x"), IllegalStateException("bug"))) {
            val f = Flaky(100) { e }; val fs = RetryingFs(f::open, RetryPolicy(4, 1, 1), sleep = {})
            assertThrows(e.javaClass) { fs.list("/") }; assertEquals(1, f.calls)
        }
    }

    @Test fun `un RemoteException con causa de red si se reintenta`() {
        val f = Flaky(1) { RemoteException("El servidor cerró la conexión", java.io.EOFException()) }
        assertEquals(1, RetryingFs(f::open, RetryPolicy(3, 1, 1), sleep = {}).list("/").size); assertEquals(2, f.calls)
    }

    @Test fun `cancelar durante la espera corta el reintento`() {
        val f = Flaky(100) { SocketException("x") }; var cancel = false
        val fs = RetryingFs(f::open, RetryPolicy(5, 5000, 5000), cancelled = { cancel }, sleep = { cancel = true })
        assertThrows(CancellationException::class.java) { fs.list("/") }; assertEquals(1, f.calls)
    }

    @Test fun `integracion contra FTP real en memoria que corta la conexion`() {
        val srv = FakeFtp(); val tmp = java.nio.file.Files.createTempDirectory("rt").toFile()
        try {
            srv.files["/a.txt"] = "hola".toByteArray()
            var opens = 0
            val fs = RetryingFs({ opens++; if (opens == 1) FtpFs("127.0.0.1", srv.port, "ana", "secreto").also { it.close() } else FtpFs("127.0.0.1", srv.port, "ana", "secreto") }, RetryPolicy(3, 1, 1), sleep = {})
            fs.download("/a.txt", File(tmp, "o")); assertEquals("hola", File(tmp, "o").readText()); assertEquals(2, opens)
            fs.close()
        } finally { srv.stop(); tmp.deleteRecursively() }
    }
}

class SftpClassifyTest {
    @Test fun `caida de conexion es transitoria y los errores de servidor no`() {
        assertTrue(SftpFs.connectionLost(com.jcraft.jsch.SftpException(com.jcraft.jsch.ChannelSftp.SSH_FX_CONNECTION_LOST, "x"), true))
        assertTrue(SftpFs.connectionLost(com.jcraft.jsch.SftpException(com.jcraft.jsch.ChannelSftp.SSH_FX_FAILURE, "Failure", java.io.IOException("Pipe closed")), true))
        assertTrue(SftpFs.connectionLost(com.jcraft.jsch.SftpException(com.jcraft.jsch.ChannelSftp.SSH_FX_FAILURE, "Failure"), false))
        assertFalse(SftpFs.connectionLost(com.jcraft.jsch.SftpException(com.jcraft.jsch.ChannelSftp.SSH_FX_PERMISSION_DENIED, "denied"), true))
        assertFalse(SftpFs.connectionLost(com.jcraft.jsch.SftpException(com.jcraft.jsch.ChannelSftp.SSH_FX_NO_SUCH_FILE, "no"), true))
    }
}
