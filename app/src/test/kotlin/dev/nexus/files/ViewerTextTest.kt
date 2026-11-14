package dev.nexus.files

import dev.nexus.core.Entry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ViewerTextTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(name: String, content: ByteArray) = tmp.newFile(name).also { it.writeBytes(content) }
    private fun file(name: String, content: String) = file(name, content.toByteArray())
    private fun entry(name: String, dir: Boolean = false) = Entry(path = "/x/$name", name = name, isDir = dir, size = 0, modified = 0, hidden = false, readable = true)

    @Test fun `elige visor por extension sin distinguir mayusculas`() {
        assertEquals(VKind.IMAGE, Viewers.of(entry("a.PNG")))
        assertEquals(VKind.JSON, Viewers.of(entry("a.json")))
        assertEquals(VKind.ZIP, Viewers.of(entry("a.jar")))
        assertEquals(VKind.TEXT, Viewers.of(entry("build.gradle")))
        assertEquals(VKind.PDF, Viewers.of(entry("a.pdf")))
        assertNull(Viewers.of(entry("a.exe")))
        assertNull(Viewers.of(entry("carpeta.json", dir = true)))
    }

    @Test fun `JSON se formatea con sangria`() {
        val (s, note) = readText(file("a.json", """{"a":1,"b":[1,2]}"""), VKind.JSON)
        assertNull(note)
        assertTrue(s.lines().size > 3)
        assertTrue(s.contains("  \"a\": 1"))
    }

    @Test fun `JSON de array raiz tambien`() {
        val (s, note) = readText(file("a.json", "[1,2]"), VKind.JSON)
        assertNull(note); assertTrue(s.contains("\n"))
    }

    @Test fun `JSON invalido se muestra tal cual con aviso`() {
        val raw = "{no es json"
        val (s, note) = readText(file("a.json", raw), VKind.JSON)
        assertEquals(raw, s)
        assertTrue(note!!.startsWith("JSON no válido"))
    }

    @Test fun `XML se indenta`() {
        val (s, note) = readText(file("a.xml", "<a><b>1</b></a>"), VKind.XML)
        assertNull(note)
        assertTrue(s.lines().count { it.isNotBlank() } >= 3)
    }

    @Test fun `XML invalido se muestra tal cual con aviso`() {
        val raw = "<a><b></a>"
        val (s, note) = readText(file("a.xml", raw), VKind.XML)
        assertEquals(raw, s)
        assertTrue(note!!.startsWith("XML no válido"))
    }

    @Test fun `un archivo con ceros se trata como binario`() {
        val (s, note) = readText(file("a.txt", byteArrayOf(65, 0, 66)), VKind.TEXT)
        assertEquals("", s)
        assertEquals("Parece un archivo binario", note)
    }

    @Test fun `texto mayor de un MB se trunca y no se formatea`() {
        val big = "x".repeat(MAX_TEXT + 500)
        val (s, note) = readText(file("big.json", big), VKind.JSON)
        assertEquals(MAX_TEXT, s.length)
        assertEquals("Mostrando solo el primer MB", note)
    }

    @Test fun `texto exactamente en el limite no se trunca`() {
        val (s, note) = readText(file("lim.txt", "y".repeat(MAX_TEXT)), VKind.TEXT)
        assertEquals(MAX_TEXT, s.length); assertNull(note)
    }

    @Test fun `tamanos en partes`() {
        assertEquals("0" to "B", sizeParts(0))
        assertEquals("1023" to "B", sizeParts(1023))
        assertEquals("1,0" to "KB", sizeParts(1024))
        assertEquals("4,2" to "MB", sizeParts((4.2 * 1024 * 1024).toLong()))
        assertEquals("150" to "GB", sizeParts(150L * 1024 * 1024 * 1024))
    }
}
