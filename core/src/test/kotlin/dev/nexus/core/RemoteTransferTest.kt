package dev.nexus.core

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RemoteTransferTest {
    private lateinit var tmp: File
    private val stop = ArrayList<() -> Unit>()
    @Before fun up() { tmp = Files.createTempDirectory("rt").toFile() }
    @After fun down() { stop.forEach { it() }; tmp.deleteRecursively() }
    private fun webdav(): Pair<FakeDav, WebDavFs> { val d = FakeDav(); stop += { d.stop() }; return d to WebDavFs("http://127.0.0.1:${d.port}/dav", "u", "p") }
    private fun ftp(): Pair<FakeFtp, FtpFs> { val s = FakeFtp(); stop += { s.stop() }; return s to FtpFs("127.0.0.1", s.port, "ana", "secreto").also { c -> stop += { c.close() } } }

    @Test fun `descarga una carpeta entera por WebDAV sin pisar lo existente`() {
        val (d, fs) = webdav()
        d.files["/p"] = null; d.files["/p/a.txt"] = "A".toByteArray(); d.files["/p/s"] = null; d.files["/p/s/b.txt"] = "BB".toByteArray()
        val dest = File(tmp, "dl")
        val r = RemoteTransfer.download(fs, fs.list("/").first { it.name == "p" }, dest)
        assertEquals(2, r.files); assertEquals(3L, r.bytes); assertTrue(r.failed.isEmpty())
        assertEquals("BB", File(dest, "p/s/b.txt").readText())
        val r2 = RemoteTransfer.download(fs, fs.list("/").first { it.name == "p" }, dest)
        assertEquals(2, r2.files); assertEquals("A", File(dest, "p (1)/a.txt").readText()); assertEquals("A", File(dest, "p/a.txt").readText())
    }

    @Test fun `cancelar a mitad deja la descarga sin ficheros parciales`() {
        val (d, fs) = webdav()
        d.files["/big.bin"] = ByteArray(2_000_000) { it.toByte() }
        var calls = 0
        val r = RemoteTransfer.download(fs, fs.list("/").single(), File(tmp, "dl"), cancelled = { ++calls > 3 })
        assertTrue(r.cancelled); assertEquals(0, r.files)
        assertTrue(File(tmp, "dl").walkTopDown().none { it.isFile })
    }

    @Test fun `subir renombra si el nombre ya existe en el servidor (FTP)`() {
        val (s, fs) = ftp()
        val f = File(tmp, "foto.jpg").apply { writeText("x") }
        assertEquals(1, RemoteTransfer.upload(fs, f, "/").files)
        assertEquals(1, RemoteTransfer.upload(fs, f, "/").files)
        assertEquals(setOf("/", "/foto.jpg", "/foto (1).jpg"), s.files.keys.toSet())
    }

    @Test fun `cancelar una subida FTP no deja el temporal`() {
        val (s, _) = ftp()
        val c = FtpFs("127.0.0.1", s.port, "ana", "secreto")
        val f = File(tmp, "big").apply { writeBytes(ByteArray(3_000_000)) }
        var calls = 0
        val r = RemoteTransfer.upload(c, f, "/", cancelled = { ++calls > 2 })
        assertTrue(r.cancelled); c.close()
        Thread.sleep(300)
        assertTrue(s.files.keys.none { it.endsWith(FileOps.PART) })
    }
}
