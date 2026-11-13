package dev.nexus.core

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AnalyzerTest {
    private lateinit var root: File
    @Before fun up() { root = Files.createTempDirectory("an").toFile() }
    @After fun down() { root.deleteRecursively() }
    private fun f(path: String, bytes: Int, content: Byte = 1) = File(root, path).apply { parentFile.mkdirs(); writeBytes(ByteArray(bytes) { content }) }

    @Test fun `suma tamanos y categorias`() = runBlocking {
        f("a/x.jpg", 1000); f("a/y.mp4", 3000); f("b/z.txt", 500); f("b/c/w.pdf", 200)
        val r = Analyzer.scan(root)
        assertEquals(4700L, r.totalBytes); assertEquals(4, r.fileCount)
        assertEquals(1000L, r.bytesByKind[Kind.IMAGE]); assertEquals(3000L, r.bytesByKind[Kind.VIDEO]); assertEquals(700L, r.bytesByKind[Kind.DOCUMENT])
        assertEquals("y.mp4", r.largest.first().name); assertEquals(listOf("y.mp4"), r.largestByKind[Kind.VIDEO]!!.map { it.name })
    }

    @Test fun `carpetas pesadas por bytes propios`() = runBlocking {
        f("a/big.bin", 5000); f("a/sub/s.bin", 100)
        val r = Analyzer.scan(root)
        assertEquals("a", r.heaviestFolders.first().name); assertEquals(5000L, r.heaviestFolders.first().size)
    }

    @Test fun `detecta temporales y antiguos`() = runBlocking {
        f("x.tmp", 10); f("cache/c.dat", 20); f("v.txt~", 5)
        val old = f("viejo.bin", 2 * 1024 * 1024); old.setLastModified(1_000_000)
        val r = Analyzer.scan(root, now = System.currentTimeMillis())
        assertEquals(setOf("x.tmp", "cache", "v.txt~"), r.temp.map { it.name }.toSet())
        assertEquals(listOf("viejo.bin"), r.old.map { it.name })
    }

    @Test fun `poda hijos y conserva el total`() = runBlocking {
        repeat(60) { f("d/f$it.bin", 100 + it) }
        val r = Analyzer.scan(root); val d = r.root.children.first { it.name == "d" }
        assertEquals(Analyzer.MAX_CHILDREN + 1, d.children.size); assertTrue(d.children.last().synthetic)
        assertEquals(d.size, d.children.sumOf { it.size })
    }

    @Test fun `no sigue symlinks`() = runBlocking {
        val out = Files.createTempDirectory("o").toFile(); File(out, "grande").writeBytes(ByteArray(9999))
        Files.createSymbolicLink(File(root, "l").toPath(), out.toPath()); f("a", 1)
        assertEquals(1L, Analyzer.scan(root).totalBytes); out.deleteRecursively(); Unit
    }

    @Test fun `treemap proporcional sin solapes ni salirse`() {
        val w = listOf(600L, 600, 400, 300, 200, 100, 100)
        val b = Rect(0f, 0f, 400f, 300f); val rs = Treemap.layout(w, b)
        val total = w.sum().toFloat()
        rs.forEachIndexed { i, r ->
            assertEquals(b.w * b.h * w[i] / total, r.w * r.h, 1f)
            assertTrue(r.x >= -0.01f && r.y >= -0.01f && r.x + r.w <= b.w + 0.01f && r.y + r.h <= b.h + 0.01f)
        }
        assertEquals(b.w * b.h, rs.sumOf { (it.w * it.h).toDouble() }.toFloat(), 2f)
        for (i in rs.indices) for (j in i + 1 until rs.size) {
            val a = rs[i]; val c = rs[j]
            val ox = minOf(a.x + a.w, c.x + c.w) - maxOf(a.x, c.x); val oy = minOf(a.y + a.h, c.y + c.h) - maxOf(a.y, c.y)
            assertFalse("solape $i-$j", ox > 0.5f && oy > 0.5f)
        }
    }
    @Test fun `treemap ignora pesos cero`() { val rs = Treemap.layout(listOf(0L, 10, 0), Rect(0f, 0f, 10f, 10f)); assertEquals(0f, rs[0].w * rs[0].h, 0f); assertEquals(100f, rs[1].w * rs[1].h, 0.1f) }
}

class DuplicatesTest {
    private lateinit var root: File
    @Before fun up() { root = Files.createTempDirectory("dp").toFile() }
    @After fun down() { root.deleteRecursively() }
    private fun f(path: String, bytes: ByteArray) = File(root, path).apply { parentFile.mkdirs(); writeBytes(bytes) }

    @Test fun `agrupa identicos y distingue mismo tamano distinto contenido`() = runBlocking {
        val a = ByteArray(200_000) { (it % 251).toByte() }
        val b = a.copyOf().also { it[199_999] = 7 }          // mismo tamaño y misma cabecera, difiere al final
        f("1/a.bin", a); f("2/a-copia.bin", a); f("3/otro.bin", b); f("4/unico.bin", ByteArray(5))
        val r = Duplicates.find(root)
        assertEquals(1, r.groups.size); assertEquals(setOf("a.bin", "a-copia.bin"), r.groups[0].files.map { it.name }.toSet())
        assertEquals(200_000L, r.wasted)
    }
    @Test fun `ignora vacios ocultos y temporales`() = runBlocking {
        f("a", ByteArray(0)); f("b", ByteArray(0)); f(".h1", ByteArray(9)); f(".h2", ByteArray(9)); f("p.nexus-part", ByteArray(9)); f("q.nexus-part", ByteArray(9))
        assertTrue(Duplicates.find(root).groups.isEmpty())
    }
    @Test fun `seleccion segura exige conservar uno por grupo`() = runBlocking {
        f("a", ByteArray(10) { 3 }); f("b", ByteArray(10) { 3 })
        val g = Duplicates.find(root).groups
        val extras = Duplicates.suggestExtras(g)
        assertEquals(1, extras.size); assertTrue(Duplicates.isSafeSelection(g, extras))
        assertFalse(Duplicates.isSafeSelection(g, g[0].files.map { it.path }.toSet()))
    }
}
