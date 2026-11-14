package dev.nexus.core

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.TreeMap
import kotlin.concurrent.thread

/** Servidor FTP falso en memoria. [mlsd]/[epsv] permiten forzar los caminos alternativos (LIST, PASV). */
class FakeFtp(val mlsd: Boolean = true, val epsv: Boolean = true, var dropRetrAfter: Long = -1) {
    @Volatile var lastRest = -1L
    private var rest = 0L; private var dropped = false
    val files = TreeMap<String, ByteArray?>().apply { put("/", null) }
    private val ss = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
    val port get() = ss.localPort
    @Volatile var stopped = false
    init { thread(isDaemon = true) { while (!stopped) try { val c = ss.accept(); thread(isDaemon = true) { session(c) } } catch (_: Exception) { break } } }
    fun stop() { stopped = true; ss.close() }
    private fun children(d: String) = files.keys.filter { it != d && it.startsWith(if (d == "/") "/" else "$d/") && '/' !in it.removePrefix(if (d == "/") "/" else "$d/") }

    private fun session(c: Socket) {
        c.use {
            val i = c.getInputStream().bufferedReader(); val o = c.getOutputStream().bufferedWriter()
            fun say(s: String) { o.write(s + "\r\n"); o.flush() }
            say("220 fake")
            var authed = false; var rnfr: String? = null; var data: ServerSocket? = null
            fun dataSock(): Socket { val s = data!!.accept(); data!!.close(); return s }
            while (true) {
                val line = i.readLine() ?: break
                val cmd = line.substringBefore(' ').uppercase(); val arg = line.substringAfter(' ', "")
                if (cmd != "USER" && cmd != "PASS" && cmd != "QUIT" && !authed) { say("530 login"); continue }
                when (cmd) {
                    "USER" -> say(if (arg == "ana") "331 pass" else "530 no")
                    "PASS" -> if (arg == "secreto") { authed = true; say("230 ok") } else say("530 mala")
                    "TYPE" -> say("200 ok")
                    "EPSV" -> if (!epsv) say("500 no") else { data = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")); say("229 Entering (|||${data!!.localPort}|)") }
                    "PASV" -> { data = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")); val p = data!!.localPort; say("227 Entering (6,6,6,6,${p / 256},${p % 256})") } // IP falsa a propósito
                    "MLSD", "LIST" -> {
                        if (cmd == "MLSD" && !mlsd) { say("500 no"); data?.close(); continue }
                        if (arg !in files || files[arg] != null) { say("550 no dir"); data?.close(); continue }
                        say("150 go"); val d = dataSock(); val w = d.getOutputStream().bufferedWriter()
                        children(arg).forEach { k ->
                            val dir = files[k] == null; val n = k.substringAfterLast('/')
                            if (cmd == "MLSD") w.write("type=${if (dir) "dir" else "file"};size=${files[k]?.size ?: 0};modify=20261111100000; $n\r\n")
                            else w.write("${if (dir) "d" else "-"}rw-r--r-- 1 u g ${files[k]?.size ?: 0} Nov 11 2026 $n\r\n")
                        }
                        w.write("type=pdir; ..\r\n".takeIf { cmd == "MLSD" } ?: ""); w.flush(); d.close(); say("226 done")
                    }
                    "SIZE" -> say(if (files[arg] != null) "213 ${files[arg]!!.size}" else "550 no")
                    "REST" -> { rest = arg.toLong(); lastRest = rest; say("350 ok") }
                    "RETR" -> { val f = files[arg]; if (f == null) { say("550 no"); data?.close() } else {
                        say("150 go"); val d = dataSock(); val part = f.copyOfRange(rest.toInt(), f.size); rest = 0
                        if (dropRetrAfter >= 0 && !dropped) { dropped = true; d.getOutputStream().write(part, 0, dropRetrAfter.toInt()); d.close(); say("426 aborted") }
                        else { d.getOutputStream().write(part); d.close(); say("226 done") } } }
                    "STOR" -> { say("150 go"); val d = dataSock(); files[arg] = d.getInputStream().readBytes(); d.close(); say("226 done") }
                    "MKD" -> { files[arg] = null; say("257 \"$arg\"") }
                    "DELE" -> say(if (files.remove(arg) != null) "250 ok" else "550 no")
                    "RMD" -> { if (children(arg).isNotEmpty()) say("550 no vacío") else { files.remove(arg); say("250 ok") } }
                    "RNFR" -> if (arg in files) { rnfr = arg; say("350 ok") } else say("550 no")
                    "RNTO" -> { val f = rnfr; if (f == null) say("503") else { files[arg] = files.remove(f); say("250 ok") } }
                    "QUIT" -> { say("221 bye"); break }
                    else -> say("502 no")
                }
            }
        }
    }
}

class FtpTest {
    private lateinit var tmp: File
    private var srv: FakeFtp? = null
    @Before fun up() { tmp = Files.createTempDirectory("ftp").toFile() }
    @After fun down() { srv?.stop(); tmp.deleteRecursively() }
    private fun connect(mlsd: Boolean = true, epsv: Boolean = true, pass: String = "secreto"): FtpFs { srv = FakeFtp(mlsd, epsv); return FtpFs("127.0.0.1", srv!!.port, "ana", pass) }

    @Test fun `ida y vuelta binaria con MLSD y EPSV`() {
        connect().use { fs ->
            val src = File(tmp, "in").apply { writeBytes(ByteArray(300_000) { (it * 7).toByte() }) }
            fs.mkdir("/docs"); fs.upload(src, "/docs/a b.bin")
            val l = fs.list("/docs"); assertEquals(listOf("a b.bin"), l.map { it.name }); assertEquals(300_000L, l[0].size); assertTrue(l[0].modified > 0)
            val dst = File(tmp, "out"); fs.download("/docs/a b.bin", dst); assertArrayEquals(src.readBytes(), dst.readBytes())
            assertTrue(srv!!.files.keys.none { it.endsWith(FileOps.PART) })
            assertTrue(fs.list("/").first { it.name == "docs" }.isDir)
        }
    }

    @Test fun `sin MLSD ni EPSV usa LIST y PASV ignorando la IP anunciada`() {
        connect(mlsd = false, epsv = false).use { fs ->
            File(tmp, "x").writeText("hola"); fs.mkdir("/d"); fs.upload(File(tmp, "x"), "/d/x.txt")
            val l = fs.list("/d"); assertEquals("x.txt", l.single().name); assertEquals(4L, l.single().size)
            val dst = File(tmp, "o"); fs.download("/d/x.txt", dst); assertEquals("hola", dst.readText())
        }
    }

    @Test fun `contrasena mala falla en la conexion`() {
        assertThrows(RemoteException::class.java) { connect(pass = "mala") }
    }

    @Test fun `no sobrescribe, renombra sin pisar y borra`() {
        connect().use { fs ->
            val f = File(tmp, "x").apply { writeText("uno") }
            fs.upload(f, "/a"); assertThrows(RemoteException::class.java) { fs.upload(f, "/a") }
            f.writeText("dos"); fs.upload(f, "/a", overwrite = true); assertEquals("dos", String(srv!!.files["/a"]!!))
            fs.upload(f, "/b"); assertThrows(RemoteException::class.java) { fs.rename("/a", "/b") }
            fs.rename("/a", "/c"); assertTrue("/c" in srv!!.files && "/a" !in srv!!.files)
            fs.delete("/c", false); assertFalse("/c" in srv!!.files)
            assertThrows(RemoteException::class.java) { fs.delete("/", true) }
            assertThrows(RemoteException::class.java) { fs.download("/nada", File(tmp, "n")) }
            assertFalse(File(tmp, "n").exists())
        }
    }

    @Test fun `parser unix y nombres peligrosos`() {
        connect().use { fs ->
            val e = fs.parseUnix("/x", "-rw-r--r--   1 user group      1234 Jan  5 2025 mi archivo.txt")!!
            assertEquals("mi archivo.txt", e.name); assertEquals(1234L, e.size); assertEquals("/x/mi archivo.txt", e.path)
            assertTrue(fs.parseUnix("/x", "drwxr-xr-x 2 u g 4096 Feb 10 12:30 carpeta")!!.isDir)
            assertNull(fs.parseUnix("/x", "total 12")); assertNull(fs.parseUnix("/x", "drwxr-xr-x 2 u g 4096 Feb 10 12:30 .."))
            assertFalse(RemotePaths.safeName("../x")); assertFalse(RemotePaths.safeName(".."))
        }
    }

    @Test fun `solo rechazos de datos TLS (421, 425, 426, 522) o fallos SSL sugieren reutilizar la sesion`() {
        assertTrue(FtpFs.suggestsSessionReuse(RemoteException("RETR /a: 522 SSL connection failed; session reuse required")))
        assertTrue(FtpFs.suggestsSessionReuse(RemoteException("LIST /: 425 Unable to build data connection: TLS session resumption required")))
        assertTrue(FtpFs.suggestsSessionReuse(javax.net.ssl.SSLHandshakeException("handshake_failure")))
        assertFalse(FtpFs.suggestsSessionReuse(RemoteException("RETR /a: 550 No such file")))
        assertFalse(FtpFs.suggestsSessionReuse(UntrustedHostException("certificado", "AA", "x")))
    }
}
