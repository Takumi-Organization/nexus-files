package dev.nexus.files

import dev.nexus.core.Entry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.stream.StreamSource

enum class VKind { IMAGE, VIDEO, AUDIO, PDF, ZIP, JSON, XML, TEXT }

object Viewers {
    private val img = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")
    private val vid = setOf("mp4", "mkv", "webm", "3gp", "m4v", "mov")
    private val aud = setOf("mp3", "flac", "ogg", "opus", "wav", "m4a", "aac")
    private val txt = setOf("txt", "md", "csv", "log", "html", "css", "js", "ts", "kt", "java", "rs", "py", "c", "cpp", "h", "sh", "yml", "yaml", "toml", "sql", "gradle", "ini", "conf", "properties", "tsv")
    fun of(e: Entry): VKind? = when (e.extension) {
        in img -> VKind.IMAGE; in vid -> VKind.VIDEO; in aud -> VKind.AUDIO; "pdf" -> VKind.PDF; "zip", "jar" -> VKind.ZIP
        "json" -> VKind.JSON; "xml" -> VKind.XML; in txt -> VKind.TEXT; else -> null
    }
}


const val MAX_TEXT = 1_000_000

fun readText(f: File, kind: VKind): Pair<String, String?> {
    // `readNBytes` exige API 33 y el mínimo es 30: lectura acotada equivalente.
    val bytes = f.inputStream().use { i -> val buf = ByteArray(MAX_TEXT + 1); var off = 0; while (off < buf.size) { val n = i.read(buf, off, buf.size - off); if (n < 0) break; off += n }; if (off == buf.size) buf else buf.copyOf(off) }
    if (bytes.take(8192).any { it == 0.toByte() }) return "" to "Parece un archivo binario"
    val truncated = bytes.size > MAX_TEXT
    var s = String(bytes, 0, minOf(bytes.size, MAX_TEXT), Charsets.UTF_8); var note: String? = if (truncated) "Mostrando solo el primer MB" else null
    if (!truncated) when (kind) {
        VKind.JSON -> s = runCatching { val t = s.trim(); if (t.startsWith("[")) JSONArray(t).toString(2) else JSONObject(t).toString(2) }.getOrElse { note = "JSON no válido: ${it.message?.take(80)}; se muestra tal cual"; s }
        VKind.XML -> s = runCatching {
            val t = TransformerFactory.newInstance().newTransformer().apply { setOutputProperty(OutputKeys.INDENT, "yes"); setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2") }
            StringWriter().also { w -> t.transform(StreamSource(StringReader(s)), StreamResult(w)) }.toString()
        }.getOrElse { note = "XML no válido: ${it.message?.take(80)}; se muestra tal cual"; s }
        else -> {}
    }
    return s to note
}

