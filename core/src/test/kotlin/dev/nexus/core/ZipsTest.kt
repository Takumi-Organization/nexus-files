package dev.nexus.core

import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipsTest {
    private lateinit var root: File
    @Before fun up() { root = Files.createTempDirectory("zp").toFile() }
    @After fun down() { root.deleteRecursively() }
    private fun f(p: String, t: String) = File(root, p).apply { parentFile.mkdirs(); writeText(t) }
    private fun zipOf(vararg e: Pair<String, String>) = File(root, "t.zip").also { z -> ZipOutputStream(z.outputStream()).use { o -> e.forEach { (n, c) -> o.putNextEntry(ZipEntry(n)); o.write(c.toByteArray()); o.closeEntry() } } }

    @Test fun `ida y vuelta conserva contenido y estructura`() = runBlocking<Unit> {
        f("src/a.txt", "alfa"); f("src/sub/b.txt", "beta"); File(root, "src/vacia").mkdirs()
        val z = File(root, "out.zip")
        assertTrue(Zips.create(listOf(File(root, "src")), z).ok)
        assertEquals(setOf("src/", "src/a.txt", "src/sub/", "src/sub/b.txt", "src/vacia/"), Zips.list(z).map { it.name }.toSet())
        val dst = File(root, "dst"); assertTrue(Zips.extract(z, dst).ok)
        assertEquals("alfa", File(dst, "src/a.txt").readText()); assertEquals("beta", File(dst, "src/sub/b.txt").readText()); assertTrue(File(dst, "src/vacia").isDirectory)
        assertTrue(root.walkTopDown().none { it.name.endsWith(FileOps.PART) })
    }

    @Test fun `zip-slip se rechaza y no escribe fuera`() = runBlocking<Unit> {
        val z = zipOf("../evil.txt" to "x", "ok.txt" to "bien", "/abs.txt" to "x", "a/../../evil2.txt" to "x", "win\\evil3.txt" to "x")
        val dst = File(root, "dst"); val r = Zips.extract(z, dst)
        assertEquals(1, r.done); assertEquals(4, r.failed.size)
        assertFalse(File(root, "evil.txt").exists()); assertFalse(File(root, "evil2.txt").exists()); assertEquals("bien", File(dst, "ok.txt").readText())
    }

    @Test fun `extraer solo seleccion`() = runBlocking<Unit> {
        val z = zipOf("a.txt" to "1", "d/b.txt" to "2", "d/c.txt" to "3")
        val dst = File(root, "dst"); Zips.extract(z, dst, only = setOf("d/b.txt"))
        assertTrue(File(dst, "d/b.txt").exists()); assertFalse(File(dst, "a.txt").exists()); assertFalse(File(dst, "d/c.txt").exists())
    }

    @Test fun `conflictos no sobrescriben por defecto`() = runBlocking<Unit> {
        val z = zipOf("a.txt" to "nuevo"); val dst = File(root, "dst"); f("dst/a.txt", "viejo")
        Zips.extract(z, dst); assertEquals("viejo", File(dst, "a.txt").readText()); assertEquals("nuevo", File(dst, "a (1).txt").readText())
        Zips.extract(z, dst, conflict = Conflict.SKIP); assertEquals(2, dst.listFiles()!!.size)
        Zips.extract(z, dst, conflict = Conflict.REPLACE); assertEquals("nuevo", File(dst, "a.txt").readText())
    }

    @Test fun `crear no sobrescribe un zip existente`() = runBlocking<Unit> {
        f("a.txt", "x"); val z = File(root, "o.zip"); z.writeText("previo")
        Zips.create(listOf(File(root, "a.txt")), z)
        assertEquals("previo", z.readText()); assertTrue(File(root, "o (1).zip").length() > 22)
    }

    @Test fun `zip dañado devuelve error sin excepcion`() = runBlocking<Unit> {
        val z = File(root, "mal.zip").apply { writeText("esto no es un zip") }
        val r = Zips.extract(z, File(root, "d")); assertFalse(r.ok)
    }

    @Test fun `cancelar crear limpia temporal y no deja zip`() = runBlocking<Unit> {
        File(root, "big").mkdirs(); repeat(20) { File(root, "big/f$it").writeBytes(ByteArray(2_000_000) { b -> (b * 31 + it).toByte() }) }
        val z = File(root, "big.zip"); var rep: Report? = null
        val job = launch(Dispatchers.Default) { rep = Zips.create(listOf(File(root, "big")), z) }
        while (root.walkTopDown().none { it.name.endsWith(FileOps.PART) } && job.isActive) delay(1)
        job.cancelAndJoin()
        assertFalse(z.exists() && rep?.cancelled == true); assertTrue(root.walkTopDown().none { it.name.endsWith(FileOps.PART) })
    }

    @Test fun `no sigue symlinks al comprimir`() = runBlocking<Unit> {
        val out = Files.createTempDirectory("o").toFile(); File(out, "secreto.txt").writeText("s")
        File(root, "d").mkdirs(); Files.createSymbolicLink(File(root, "d/l").toPath(), out.toPath())
        val z = File(root, "z.zip"); val r = Zips.create(listOf(File(root, "d")), z)
        assertTrue(Zips.list(z).none { it.name.contains("secreto") }); assertEquals(1, r.skipped.size); out.deleteRecursively()
    }
}
