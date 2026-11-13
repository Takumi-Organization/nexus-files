package dev.nexus.core

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SearchLibraryTest {
    private lateinit var root: File
    @Before fun up() {
        root = Files.createTempDirectory("nx").toFile()
        File(root, "d").mkdir(); File(root, ".h").mkdir()
        File(root, "foto.JPG").writeBytes(ByteArray(2000)); File(root, "d/Nota.txt").writeText("abc")
        File(root, "d/grande.mp4").writeBytes(ByteArray(10_000)); File(root, ".h/secreto.txt").writeText("s")
    }
    @After fun down() { root.deleteRecursively() }
    private fun s(q: SearchQuery) = runBlocking { Search.run(q) }.entries.map { it.name }.toSet()

    @Test fun `por nombre sin distinguir mayusculas`() = assertEquals(setOf("Nota.txt"), s(SearchQuery(root, name = "NOTA")))
    @Test fun `por extension`() = assertEquals(setOf("foto.JPG"), s(SearchQuery(root, extensions = setOf("jpg"))))
    @Test fun `por tipo`() = assertEquals(setOf("grande.mp4"), s(SearchQuery(root, kind = Kind.VIDEO)))
    @Test fun `por tamano`() = assertEquals(setOf("grande.mp4"), s(SearchQuery(root, minSize = 5000)))
    @Test fun `por fecha`() {
        File(root, "d/Nota.txt").setLastModified(1_000_000)
        assertEquals(setOf("Nota.txt"), s(SearchQuery(root, modifiedBefore = 2_000_000)))
    }
    @Test fun `por ubicacion`() = assertEquals(setOf("Nota.txt", "grande.mp4"), s(SearchQuery(File(root, "d"))))
    @Test fun `oculta por defecto y los incluye si se pide`() {
        assertFalse("secreto.txt" in s(SearchQuery(root))); assertTrue("secreto.txt" in s(SearchQuery(root, includeHidden = true)))
    }
    @Test fun `limite marca truncado`() {
        val r = runBlocking { Search.run(SearchQuery(root), limit = 2) }
        assertEquals(2, r.entries.size); assertTrue(r.truncated)
    }
    @Test fun `no sigue symlinks`() {
        val out = Files.createTempDirectory("out").toFile(); File(out, "fuera.txt").writeText("x")
        Files.createSymbolicLink(File(root, "enlace").toPath(), out.toPath())
        assertFalse("fuera.txt" in s(SearchQuery(root))); out.deleteRecursively()
    }

    @Test fun `biblioteca ida y vuelta JSON`() {
        val d = LibraryData().toggleFavorite("/a").setTags("/a", setOf("trabajo", " ")).addRecent("/b")
        val back = LibraryData.fromJson(d.toJson())
        assertEquals(d, back); assertEquals(listOf("trabajo"), back.allTags)
    }
    @Test fun `favorito alterna y recientes sin duplicados con tope`() {
        var d = LibraryData().toggleFavorite("/a"); assertEquals(listOf("/a"), d.favorites)
        d = d.toggleFavorite("/a"); assertTrue(d.favorites.isEmpty())
        d = LibraryData(); repeat(5) { d = d.addRecent("/x$it", max = 3) }; d = d.addRecent("/x3", 3)
        assertEquals(listOf("/x3", "/x4", "/x2"), d.recents)
    }
    @Test fun `mover conserva favoritos y etiquetas incluso de hijos`() {
        val d = LibraryData().toggleFavorite("/a/b").setTags("/a/b/c.txt", setOf("t")).moved("/a/b", "/a/z")
        assertEquals(listOf("/a/z"), d.favorites); assertEquals(setOf("t"), d.tags["/a/z/c.txt"])
    }
    @Test fun `json corrupto devuelve biblioteca vacia y store es atomico`() {
        assertEquals(LibraryData(), LibraryData.fromJson("{no es json"))
        val st = LibraryStore(File(root, "lib.json")); st.save(LibraryData().toggleFavorite("/q")); assertEquals(listOf("/q"), st.load().favorites)
    }
}
