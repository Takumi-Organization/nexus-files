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

    /** Sube 300 KB, deja un temporal con la primera parte y comprueba que la descarga reanuda y el resultado es idéntico. */
    private fun resume(fs: RemoteFs) {
        val tmp = Files.createTempDirectory("itr").toFile()
        try {
            val dir = "/itr-${System.nanoTime()}"; fs.mkdir(dir)
            val data = ByteArray(300_000) { (it * 13).toByte() }; val src = File(tmp, "g.bin").apply { writeBytes(data) }
            fs.upload(src, "$dir/g.bin")
            val dst = File(tmp, "out.bin"); File(tmp, "out.bin" + FileOps.PART).writeBytes(data.copyOf(120_000))
            fs.download("$dir/g.bin", dst); assertArrayEquals(data, dst.readBytes())
            fs.delete(dir, true)
        } finally { tmp.deleteRecursively() }
    }

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

    @Test fun `WebDAV real (wsgidav)`() { on(); WebDavFs("http://127.0.0.1:8081", "ana", "secreto").use { roundTrip(it); resume(it) } }
    @Test fun `FTP real (pyftpdlib)`() { on(); FtpFs("127.0.0.1", 2121, "ana", "secreto").use { roundTrip(it); resume(it) } }

    /** pyftpdlib con TLS y certificado autofirmado; la huella sale de NEXUS_FTPS_FP (openssl x509 -fingerprint -sha256). */
    @Test fun `FTPS real exige huella y cifra datos`() {
        on(); val fp = System.getenv("NEXUS_FTPS_FP") ?: return
        val e = assertThrows(UntrustedHostException::class.java) { FtpFs("127.0.0.1", 2990, "ana", "secreto", tls = true) }
        assertTrue(Fingerprints.same(fp, e.fingerprint))
        FtpFs("127.0.0.1", 2990, "ana", "secreto", tls = true, pinned = fp).use(::roundTrip)
    }

    /** asyncssh en :2222 (ana/secreto); la huella sale de NEXUS_SFTP_FP (`ssh-keygen -lf hostkey.pub`). */
    @Test fun `SFTP real con TOFU de clave de host`() {
        on(); val fp = System.getenv("NEXUS_SFTP_FP") ?: return
        val e = assertThrows(UntrustedHostException::class.java) { SftpFs("127.0.0.1", 2222, "ana", "secreto") }
        assertEquals(fp, e.fingerprint)
        assertThrows(UntrustedHostException::class.java) { SftpFs("127.0.0.1", 2222, "ana", "secreto", trusted = "SHA256:otra") }
        assertThrows(RemoteException::class.java) { SftpFs("127.0.0.1", 2222, "ana", "mala", trusted = fp) }
        SftpFs("127.0.0.1", 2222, "ana", "secreto", trusted = fp).use { roundTrip(it); resume(it) }
    }

    /** impacket-smbserver en :4445, recurso «share», ana/secreto. */
    @Test fun `SMB real (impacket)`() {
        on(); if (System.getenv("NEXUS_SMB") != "1") return
        assertThrows(RemoteException::class.java) { SmbFs("127.0.0.1", 4445, "ana", "mala", "share") }
        SmbFs("127.0.0.1", 4445, "ana", "secreto", "share").use { roundTrip(it); resume(it) }
        SmbFs("127.0.0.1", 4445, "ana", "secreto", "share", domain = "WORKGROUP").use(::roundTrip)
    }

    /** Claves en NEXUS_SFTP_KEYDIR: rsa (PEM), ed (OpenSSH), rsa_pass (PEM con frase «frase-de-paso»). */
    @Test fun `SFTP real con clave privada`() {
        on(); val fp = System.getenv("NEXUS_SFTP_FP") ?: return; val dir = System.getenv("NEXUS_SFTP_KEYDIR") ?: return
        fun key(n: String) = File(dir, n).readText()
        SftpFs("127.0.0.1", 2222, "ana", "", trusted = fp, privateKey = key("rsa")).use(::roundTrip)
        SftpFs("127.0.0.1", 2222, "ana", "", trusted = fp, privateKey = key("ed")).use(::roundTrip)
        SftpFs("127.0.0.1", 2222, "ana", "", trusted = fp, privateKey = key("rsa_pass"), passphrase = "frase-de-paso").use(::roundTrip)
        assertThrows(RemoteException::class.java) { SftpFs("127.0.0.1", 2222, "ana", "", trusted = fp, privateKey = key("rsa_pass"), passphrase = "mala") }
        assertThrows(RemoteException::class.java) { SftpFs("127.0.0.1", 2222, "ana", "", trusted = fp, privateKey = "basura") }
    }
}
