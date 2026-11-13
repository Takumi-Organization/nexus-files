package dev.nexus.core

import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileOpsTest {
    private lateinit var root: File
    private lateinit var a: File
    private lateinit var b: File
    private val ops = FileOps(bufferSize = 4096)

    @Before fun setUp() { root = Files.createTempDirectory("nexus").toFile(); a = File(root, "a").apply { mkdir() }; b = File(root, "b").apply { mkdir() } }
    @After fun tearDown() { root.deleteRecursively() }

    private fun file(dir: File, name: String, text: String = name) = File(dir, name).apply { writeText(text) }
    private fun parts() = root.walkTopDown().filter { it.name.endsWith(FileOps.PART) }.toList()

    @Test fun `copia un archivo y conserva el origen`() = runBlocking {
        val f = file(a, "x.txt", "hola")
        val r = ops.copy(listOf(f), b)
        assertTrue(r.ok); assertEquals(1, r.done)
        assertEquals("hola", File(b, "x.txt").readText()); assertTrue(f.exists()); assertTrue(parts().isEmpty())
    }

    @Test fun `conflicto KEEP_BOTH crea nombre con sufijo y no sobrescribe`() = runBlocking {
        file(b, "x.txt", "viejo"); val f = file(a, "x.txt", "nuevo")
        ops.copy(listOf(f), b, Conflict.KEEP_BOTH)
        assertEquals("viejo", File(b, "x.txt").readText()); assertEquals("nuevo", File(b, "x (1).txt").readText())
    }

    @Test fun `conflicto SKIP no toca el destino`() = runBlocking {
        file(b, "x.txt", "viejo"); val f = file(a, "x.txt", "nuevo")
        val r = ops.copy(listOf(f), b, Conflict.SKIP)
        assertEquals("viejo", File(b, "x.txt").readText()); assertEquals(1, r.skipped.size); assertEquals(0, r.done)
    }

    @Test fun `conflicto REPLACE sobrescribe`() = runBlocking {
        file(b, "x.txt", "viejo"); val f = file(a, "x.txt", "nuevo")
        ops.copy(listOf(f), b, Conflict.REPLACE)
        assertEquals("nuevo", File(b, "x.txt").readText())
    }

    @Test fun `copiar carpeta dentro de si misma falla sin crear nada`() = runBlocking {
        val d = File(a, "d").apply { mkdir() }; file(d, "f")
        val inner = File(d, "inner").apply { mkdir() }
        val r = ops.copy(listOf(d), inner)
        assertEquals(1, r.failed.size); assertFalse(File(inner, "d").exists())
    }

    @Test fun `copia carpetas recursivas`() = runBlocking {
        val d = File(a, "d").apply { mkdir() }; file(d, "1"); File(d, "s").mkdir(); file(File(d, "s"), "2", "dos")
        val r = ops.copy(listOf(d), b)
        assertTrue(r.ok); assertEquals("dos", File(b, "d/s/2").readText())
    }

    @Test fun `mover borra el origen solo tras copiar`() = runBlocking {
        val f = file(a, "m.txt", "dato")
        val r = ops.move(listOf(f), b)
        assertTrue(r.ok); assertFalse(f.exists()); assertEquals("dato", File(b, "m.txt").readText())
    }

    @Test fun `mover a la misma carpeta se omite`() = runBlocking {
        val f = file(a, "m.txt"); val r = ops.move(listOf(f), a)
        assertTrue(f.exists()); assertEquals(1, r.skipped.size)
    }

    @Test fun `mover un origen inexistente reporta fallo`() = runBlocking {
        val r = ops.move(listOf(File(a, "nada")), b)
        assertEquals(1, r.failed.size)
    }

    @Test fun `no sigue enlaces simbolicos al copiar ni al borrar`() = runBlocking {
        val outside = File(root, "outside").apply { mkdir() }; file(outside, "secreto")
        val d = File(a, "d").apply { mkdir() }
        Files.createSymbolicLink(File(d, "link").toPath(), outside.toPath())
        val r = ops.copy(listOf(d), b)
        assertFalse(File(b, "d/link").exists()); assertEquals(1, r.skipped.size)
        ops.delete(listOf(d))
        assertTrue(File(outside, "secreto").exists())
    }

    @Test fun `borrar elimina arbol completo`() = runBlocking {
        val d = File(a, "d").apply { mkdir() }; file(d, "1"); File(d, "s").mkdir()
        val r = ops.delete(listOf(d)); assertTrue(r.ok); assertFalse(d.exists())
    }

    @Test fun `cancelar no deja temporales ni archivo final y conserva el origen`() = runBlocking {
        val big = File(a, "big.bin").apply { writeBytes(ByteArray(8 * 1024 * 1024)) }
        var report: Report? = null
        val job = launch(Dispatchers.Default) {
            report = ops.copy(listOf(big), b) { if (it.doneBytes > 64 * 1024) throw CancellationException() }
        }
        job.join()
        assertTrue(parts().isEmpty()); assertFalse(File(b, "big.bin").exists()); assertTrue(big.exists())
    }

    @Test fun `cancelar la corrutina devuelve informe cancelado`() = runBlocking {
        val big = File(a, "big.bin").apply { writeBytes(ByteArray(16 * 1024 * 1024)) }
        var report: Report? = null
        val job = launch(Dispatchers.Default) { report = ops.copy(listOf(big), b) }
        while (parts().isEmpty() && job.isActive) delay(1)
        job.cancelAndJoin()
        assertTrue(parts().isEmpty())
        if (report?.cancelled == true) assertFalse(File(b, "big.bin").exists())
    }

    @Test fun `progreso termina en 100 por cien`() = runBlocking {
        val f = File(a, "p.bin").apply { writeBytes(ByteArray(100_000)) }
        var last: Progress? = null
        ops.copy(listOf(f), b) { last = it }
        assertEquals(1f, last!!.fraction, 0.001f)
    }
}

class NamesTest {
    @Test fun `rechaza nombres peligrosos`() {
        listOf("", "  ", ".", "..", "a/b", "../x", "a\u0000b", "a\nb", "x".repeat(300)).forEach {
            assertTrue("«$it»", Names.validate(it) is Names.Result.Invalid)
        }
    }
    @Test fun `acepta nombres normales y unicode`() {
        listOf("foto.jpg", ".bashrc", "año ñ 日本.txt", "a b").forEach { assertTrue(it, Names.validate(it) is Names.Result.Ok) }
    }
    @Test fun `sufijo respeta extension y ocultos`() {
        assertEquals("a (2).txt", Names.withSuffix("a.txt", 2)); assertEquals(".bashrc (1)", Names.withSuffix(".bashrc", 1))
        assertEquals("x.tar (1).gz", Names.withSuffix("x.tar.gz", 1))
    }
}
