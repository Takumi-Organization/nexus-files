package dev.nexus.core

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ResumeTest {
    private lateinit var tmp: File
    private val data = ByteArray(400_000) { (it * 31).toByte() }
    @Before fun up() { tmp = Files.createTempDirectory("rs").toFile() }
    @After fun down() { tmp.deleteRecursively() }

    @Test fun `FTP reanuda con REST desde un temporal previo`() {
        val srv = FakeFtp(); srv.files["/g.bin"] = data
        try {
            val dest = File(tmp, "g.bin"); File(tmp, "g.bin" + FileOps.PART).writeBytes(data.copyOfRange(0, 150_000))
            FtpFs("127.0.0.1", srv.port, "ana", "secreto").use { it.download("/g.bin", dest) }
            assertArrayEquals(data, dest.readBytes()); assertEquals(150_000L, srv.lastRest)
            assertFalse(File(tmp, "g.bin" + FileOps.PART).exists())
        } finally { srv.stop() }
    }

    @Test fun `FTP cortado a mitad se reanuda solo con RetryingFs y el resultado es identico`() {
        val srv = FakeFtp(dropRetrAfter = 120_000); srv.files["/g.bin"] = data
        try {
            val fs = RetryingFs({ FtpFs("127.0.0.1", srv.port, "ana", "secreto") }, RetryPolicy(3, 1, 1), sleep = {})
            val dest = File(tmp, "g.bin"); fs.download("/g.bin", dest); fs.close()
            assertArrayEquals(data, dest.readBytes())
            assertTrue("debió reanudar, no empezar de cero: REST=${srv.lastRest}", srv.lastRest in 1..120_000)
        } finally { srv.stop() }
    }

    @Test fun `WebDAV reanuda con Range y respeta servidores que lo ignoran`() {
        val d = FakeDav(); d.files["/g.bin"] = data
        try {
            val fs = WebDavFs("http://127.0.0.1:${d.port}/dav", "u", "p")
            File(tmp, "a.bin" + FileOps.PART).writeBytes(data.copyOfRange(0, 99_999))
            fs.download("/g.bin", File(tmp, "a.bin")); assertArrayEquals(data, File(tmp, "a.bin").readBytes()); assertEquals("bytes=99999-", d.lastRange)
        } finally { d.stop() }
    }

    @Test fun `WebDAV cortado a mitad se reanuda con RetryingFs`() {
        val d = FakeDav(dropGetAfter = 100_000); d.files["/g.bin"] = data
        try {
            val fs = RetryingFs({ WebDavFs("http://127.0.0.1:${d.port}/dav", "u", "p") }, RetryPolicy(3, 1, 1), sleep = {})
            fs.download("/g.bin", File(tmp, "b.bin")); fs.close()
            assertArrayEquals(data, File(tmp, "b.bin").readBytes()); assertNotNull(d.lastRange)
        } finally { d.stop() }
    }

    @Test fun `un temporal mas largo que el original no corrompe y se reinicia`() {
        val srv = FakeFtp(); srv.files["/g.bin"] = data.copyOf(1000)
        try {
            File(tmp, "g.bin" + FileOps.PART).writeBytes(ByteArray(5000))
            FtpFs("127.0.0.1", srv.port, "ana", "secreto").use { it.download("/g.bin", File(tmp, "g.bin")) }
            assertArrayEquals(data.copyOf(1000), File(tmp, "g.bin").readBytes())
        } finally { srv.stop() }
    }

    @Test fun `cancelar borra el temporal y no se reanuda lo cancelado`() {
        val srv = FakeFtp(); srv.files["/g.bin"] = ByteArray(2_000_000)
        try {
            val fs = FtpFs("127.0.0.1", srv.port, "ana", "secreto"); var n = 0
            val r = RemoteTransfer.download(fs, fs.list("/").single(), File(tmp, "dl"), cancelled = { ++n > 3 })
            assertTrue(r.cancelled); assertTrue(File(tmp, "dl").walkTopDown().none { it.isFile }); fs.close()
        } finally { srv.stop() }
    }
}
