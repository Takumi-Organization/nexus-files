package dev.nexus.core

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ConnectionsTest {
    private val box = object : SecretBox { // doble reversible no trivial
        override fun encrypt(plain: String) = "enc:" + plain.reversed().toByteArray().joinToString("") { "%02x".format(it) }
        override fun decrypt(token: String): String { require(token.startsWith("enc:")); return String(token.removePrefix("enc:").chunked(2).map { it.toInt(16).toByte() }.toByteArray()).reversed() }
    }

    @Test fun `la contrasena no aparece en claro en disco y se recupera`() {
        val f = Files.createTempDirectory("pf").toFile().resolve("p.json")
        val s = ProfileStore(f, box)
        val p = RemoteProfile("1", "NAS", Protocol.FTP, "10.0.0.2", 21, "ana")
        s.save(p, "s3cr3t!"); assertFalse(f.readText().contains("s3cr3t!"))
        assertEquals(listOf(p), s.list()); assertEquals("s3cr3t!", s.password("1"))
        s.save(p.copy(name = "NAS2"), null); assertEquals("s3cr3t!", s.password("1")); assertEquals("NAS2", s.list().single().name)
        s.delete("1"); assertTrue(s.list().isEmpty()); assertNull(s.password("1"))
    }

    @Test fun `token corrupto devuelve null y fichero roto no revienta`() {
        val f = Files.createTempDirectory("pf").toFile().resolve("p.json")
        val s = ProfileStore(f, box); s.save(RemoteProfile("1", "a", Protocol.WEBDAV, "h", 0, "u"), "x")
        f.writeText(f.readText().replace("enc:", "xxx:")); assertNull(s.password("1"))
        f.writeText("{no json"); assertTrue(s.list().isEmpty())
    }
}
