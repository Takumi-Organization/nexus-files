package dev.nexus.core

import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Opt-in: contra servidores reales (wsgidav en :8081 y pyftpdlib en :2121 con ana/secreto, raíces vacías o desechables).
 * Se ejecuta con `NEXUS_IT=1`; en CI normal se salta.
 */
class RealServersTest {
    private fun on() = assumeTrue(System.getenv("NEXUS_IT") == "1")

    private fun roundTrip(fs: RemoteFs) {
        val tmp = Files.createTempDirectory("it").toFile()
        try {
            val dir = "/it-${System.nanoTime()}"
            fs.mkdir(dir)
            val src = File(tmp, "ñu 1.txt").apply { writeText("contenido") }
            fs.upload(src, "$dir/ñu 1.txt")
            assertThrows(RemoteException::class.java) { fs.upload(src, "$dir/ñu 1.txt") }
            assertEquals(listOf("ñu 1.txt"), fs.list(dir).map { it.name })
            fs.rename("$dir/ñu 1.txt", "$dir/otro.txt")
            val dst = File(tmp, "out"); fs.download("$dir/otro.txt", dst); assertEquals("contenido", dst.readText())
            fs.mkdir("$dir/sub"); fs.delete("$dir/sub", true)
            fs.delete("$dir/otro.txt", false); fs.delete(dir, true)
            assertTrue(fs.list("/").none { it.path == dir })
        } finally { tmp.deleteRecursively() }
    }

    @Test fun `WebDAV real (wsgidav)`() { on(); WebDavFs("http://127.0.0.1:8081", "ana", "secreto").use(::roundTrip) }
    @Test fun `FTP real (pyftpdlib)`() { on(); FtpFs("127.0.0.1", 2121, "ana", "secreto").use(::roundTrip) }

    /** pyftpdlib con TLS y certificado autofirmado; la huella sale de NEXUS_FTPS_FP (openssl x509 -fingerprint -sha256). */
    @Test fun `FTPS real exige huella y cifra datos`() {
        on(); val fp = System.getenv("NEXUS_FTPS_FP") ?: return
        val e = assertThrows(UntrustedHostException::class.java) { FtpFs("127.0.0.1", 2990, "ana", "secreto", tls = true) }
        assertTrue(Fingerprints.same(fp, e.fingerprint))
        FtpFs("127.0.0.1", 2990, "ana", "secreto", tls = true, pinned = fp).use(::roundTrip)
    }
}
