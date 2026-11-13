package dev.nexus.core

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Cliente FTP pasivo (RFC 959/3659: EPSV/PASV, MLSD con LIST como alternativa). FTP simple va **sin cifrar**
 * (usuario, contraseña y datos en claro); con [tls] usa FTPS explícito (AUTH TLS) validando el certificado (o su huella [pinned]). La conexión de datos se abre siempre contra
 * el host de control, ignorando la IP que anuncie PASV (evita servidores que redirigen a terceros).
 */
class FtpFs(private val host: String, private val port: Int = 21, private val user: String = "anonymous", password: String = "anonymous@", private val timeoutMs: Int = 15_000, private val tls: Boolean = false, private val pinned: String = "") : RemoteFs {
    private var sock: Socket = Socket().apply { connect(InetSocketAddress(host, this@FtpFs.port), timeoutMs); soTimeout = timeoutMs }
    private var ctl = sock.getInputStream().bufferedReader(Charsets.UTF_8)
    private var out = sock.getOutputStream().bufferedWriter(Charsets.UTF_8)

    private class Reply(val code: Int, val text: String)

    init {
        try {
            expect(reply(), 220, "Conexión")
            if (tls) { // FTPS explícito (RFC 4217): AUTH TLS y luego PBSZ 0 / PROT P para cifrar también los datos
                expect(cmd("AUTH TLS"), 234, "AUTH TLS")
                sock = Tls.wrap(sock, host, this@FtpFs.port, pinned)
                ctl = sock.getInputStream().bufferedReader(Charsets.UTF_8); out = sock.getOutputStream().bufferedWriter(Charsets.UTF_8)
            }
            val u = cmd("USER $user")
            if (u.code == 331) expect(cmd("PASS ${clean(password)}"), 230, "Autenticación") else expect(u, 230, "Autenticación")
            if (tls) { ok(cmd("PBSZ 0"), "PBSZ"); ok(cmd("PROT P"), "PROT") }
            expect(cmd("TYPE I"), 200, "Modo binario")
        } catch (e: Throwable) { close(); throw e }
    }

    private fun clean(s: String): String { require('\r' !in s && '\n' !in s) { "Carácter no válido" }; return s }

    private fun reply(): Reply {
        val first = ctl.readLine() ?: throw RemoteException("El servidor cerró la conexión", java.io.EOFException())
        if (first.length < 3 || !first.substring(0, 3).all { it.isDigit() }) throw RemoteException("Respuesta FTP inválida")
        val code = first.substring(0, 3).toInt()
        val sb = StringBuilder(first)
        if (first.length > 3 && first[3] == '-') {
            while (true) {
                val l = ctl.readLine() ?: throw RemoteException("Respuesta FTP truncada", java.io.EOFException())
                sb.append('\n').append(l)
                if (l.startsWith("$code ")) break
            }
        }
        return Reply(code, sb.toString())
    }

    private fun cmd(c: String): Reply { out.write(clean(c) + "\r\n"); out.flush(); return reply() }
    private fun expect(r: Reply, code: Int, what: String) { if (r.code != code) throw RemoteException("$what: ${r.text.lineSequence().first()}") }
    private fun ok(r: Reply, what: String) { if (r.code !in 200..299) throw RemoteException("$what: ${r.text.lineSequence().first()}") }

    private fun openData(): Socket {
        var p = -1
        val e = cmd("EPSV")
        if (e.code == 229) p = Regex("""\(\|\|\|(\d+)\|\)""").find(e.text)?.groupValues?.get(1)?.toInt() ?: -1
        if (p < 0) {
            val r = cmd("PASV"); expect(r, 227, "Modo pasivo")
            val n = Regex("""(\d+),(\d+),(\d+),(\d+),(\d+),(\d+)""").find(r.text)?.groupValues?.drop(1)?.map { it.toInt() } ?: throw RemoteException("PASV inválido")
            p = n[4] * 256 + n[5]
        }
        if (p !in 1..65535) throw RemoteException("Puerto de datos inválido")
        val d = Socket().apply { connect(InetSocketAddress(host, p), timeoutMs); soTimeout = timeoutMs }
        return if (tls) Tls.wrap(d, host, p, pinned) else d
    }

    private fun <T> withData(command: String, use: (Socket) -> T): T {
        openData().use { d ->
            val start = cmd(command)
            if (start.code !in listOf(125, 150)) throw RemoteException("$command: ${start.text.lineSequence().first()}")
            val result = use(d)
            d.close()
            ok(reply(), command.substringBefore(' '))
            return result
        }
    }

    override fun list(path: String): List<RemoteEntry> {
        val dir = RemotePaths.normalize(path)
        val mlsd = try {
            withData("MLSD $dir") { d -> d.getInputStream().bufferedReader(Charsets.UTF_8).readLines() }
        } catch (e: RemoteException) { null }
        val entries = mlsd?.mapNotNull { parseMlsd(dir, it) }
            ?: withData("LIST $dir") { d -> d.getInputStream().bufferedReader(Charsets.UTF_8).readLines() }.mapNotNull { parseUnix(dir, it) }
        return entries.filter { RemotePaths.safeName(it.name) }
    }

    internal fun parseMlsd(dir: String, line: String): RemoteEntry? {
        val sp = line.indexOf(' '); if (sp < 0) return null
        val name = line.substring(sp + 1)
        val facts = line.substring(0, sp).split(';').filter { '=' in it }.associate { it.substringBefore('=').lowercase() to it.substringAfter('=') }
        val type = facts["type"]?.lowercase() ?: return null
        if (type == "cdir" || type == "pdir") return null
        val mod = facts["modify"]?.let { try { LocalDateTime.parse(it.substringBefore('.'), DateTimeFormatter.ofPattern("yyyyMMddHHmmss")).toInstant(ZoneOffset.UTC).toEpochMilli() } catch (_: Exception) { 0L } } ?: 0L
        val isDir = type == "dir"
        return RemoteEntry(RemotePaths.child(dir, name), name, isDir, if (isDir) 0 else facts["size"]?.toLongOrNull() ?: 0L, mod)
    }

    internal fun parseUnix(dir: String, line: String): RemoteEntry? {
        val m = Regex("""^([-dl])[rwxsStT-]{9}\S*\s+\d+\s+\S+\s+\S+\s+(\d+)\s+(\w{3})\s+(\d+)\s+(\d{4}|\d{1,2}:\d{2})\s+(.+)$""").find(line) ?: return null
        val (t, size, mon, day, yt, rawName) = m.destructured
        val name = if (t == "l") rawName.substringBefore(" -> ") else rawName
        if (name == "." || name == "..") return null
        val mod = try {
            val month = java.time.Month.from(DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH).parse(mon.replaceFirstChar { it.uppercase() })).value
            if (':' in yt) { val now = LocalDate.now(); var d = LocalDate.of(now.year, month, day.toInt()); if (d.isAfter(now.plusDays(1))) d = d.minusYears(1); d.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }
            else LocalDate.of(yt.toInt(), month, day.toInt()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (_: Exception) { 0L }
        return RemoteEntry(RemotePaths.child(dir, name), name, t == "d", if (t == "d") 0 else size.toLong(), mod)
    }

    override fun download(path: String, dest: File, onProgress: (Long) -> Unit) {
        val size = cmd("SIZE ${RemotePaths.normalize(path)}").let { if (it.code == 213) it.text.substringAfter(' ').trim().toLongOrNull() else null }
        withData("RETR ${RemotePaths.normalize(path)}") { d ->
            var total = 0L
            atomicWrite(dest) { o ->
                val buf = ByteArray(32 * 1024); val i = d.getInputStream()
                while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); total += n; onProgress(total) }
            }
            if (size != null && size != total) { dest.delete(); throw RemoteException("Descarga incompleta", java.io.EOFException()) }
        }
    }

    override fun upload(src: File, path: String, overwrite: Boolean, onProgress: (Long) -> Unit) {
        val target = RemotePaths.normalize(path)
        if (!overwrite && exists(target)) throw RemoteException("Subir: ya existe $target")
        val tmp = target + FileOps.PART
        try {
            withData("STOR $tmp") { d ->
                var sent = 0L; val buf = ByteArray(32 * 1024)
                src.inputStream().use { i -> val o = d.getOutputStream(); while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); sent += n; onProgress(sent) }; o.flush() }
            }
            if (overwrite && exists(target)) cmd("DELE $target")
            expect(cmd("RNFR $tmp"), 350, "Subir")
            ok(cmd("RNTO $target"), "Subir")
        } catch (e: Throwable) { try { cmd("DELE $tmp") } catch (_: Exception) {}; throw e }
    }

    private fun exists(p: String): Boolean = cmd("SIZE $p").code == 213 || RemotePaths.parent(p).let { par -> try { list(par).any { it.path == p } } catch (_: RemoteException) { false } }

    override fun mkdir(path: String) { val r = cmd("MKD ${RemotePaths.normalize(path)}"); if (r.code != 257) throw RemoteException("Crear carpeta: ${r.text.lineSequence().first()}") }

    override fun delete(path: String, isDir: Boolean) {
        if (RemotePaths.normalize(path) == "/") throw RemoteException("No se puede borrar la raíz")
        ok(cmd((if (isDir) "RMD " else "DELE ") + RemotePaths.normalize(path)), "Borrar")
    }

    override fun rename(from: String, to: String) {
        val t = RemotePaths.normalize(to)
        if (exists(t)) throw RemoteException("Renombrar: ya existe $t")
        expect(cmd("RNFR ${RemotePaths.normalize(from)}"), 350, "Renombrar")
        ok(cmd("RNTO $t"), "Renombrar")
    }

    override fun close() { try { out.write("QUIT\r\n"); out.flush() } catch (_: Exception) {}; try { sock.close() } catch (_: IOException) {} }
}
