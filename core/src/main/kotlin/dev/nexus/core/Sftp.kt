package dev.nexus.core

import com.jcraft.jsch.*
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

/** Huella de clave de host al estilo OpenSSH (`SHA256:base64` sin relleno), la misma que muestra `ssh-keygen -l`. */
object SshFingerprint {
    fun of(blob: ByteArray): String = "SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(blob))
}

/**
 * Cliente SFTP sobre JSch (fork mwiede, sin dependencias). La clave de host se comprueba ANTES de enviar credenciales: si [trusted]
 * está vacío o no coincide, se lanza [UntrustedHostException] con la huella presentada (TOFU: la interfaz la muestra y, si el usuario
 * acepta, la guarda en el perfil y reintenta).
 */
class SftpFs(host: String, port: Int = 22, user: String, password: String, private val trusted: String = "", timeoutMs: Int = 15_000) : RemoteFs {
    private val session: Session
    private val ch: ChannelSftp

    private class Repo(val trusted: String) : HostKeyRepository {
        @Volatile var seen: String = ""
        override fun check(host: String?, key: ByteArray): Int { seen = SshFingerprint.of(key); return if (trusted.isNotEmpty() && seen == trusted) HostKeyRepository.OK else HostKeyRepository.NOT_INCLUDED }
        override fun add(hostkey: HostKey?, ui: UserInfo?) {}
        override fun remove(host: String?, type: String?) {}
        override fun remove(host: String?, type: String?, key: ByteArray?) {}
        override fun getKnownHostsRepositoryID(): String = "nexus"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    init {
        val repo = Repo(trusted)
        val js = JSch().apply { hostKeyRepository = repo }
        val s = js.getSession(user, host, port).apply {
            setPassword(password)
            setConfig("StrictHostKeyChecking", "yes")
            setConfig("PreferredAuthentications", "password,keyboard-interactive")
            timeout = timeoutMs
        }
        try {
            s.connect(timeoutMs)
            ch = (s.openChannel("sftp") as ChannelSftp).also { it.connect(timeoutMs) }
        } catch (e: JSchException) {
            s.disconnect()
            if (repo.seen.isNotEmpty() && !(trusted.isNotEmpty() && repo.seen == trusted))
                throw UntrustedHostException("clave de host SSH", repo.seen, if (trusted.isEmpty()) "Servidor desconocido" else "¡La clave del servidor ha cambiado!")
            throw RemoteException(if (e.message?.contains("Auth fail", true) == true) "Usuario o contraseña incorrectos" else "SFTP: ${e.message}", e)
        }
        session = s
    }

    private fun p(path: String) = RemotePaths.normalize(path)
    private fun <T> sftp(op: String, block: () -> T): T = try { block() } catch (e: SftpException) {
        throw RemoteException("$op: " + when (e.id) { ChannelSftp.SSH_FX_NO_SUCH_FILE -> "no existe"; ChannelSftp.SSH_FX_PERMISSION_DENIED -> "permiso denegado"; else -> e.message ?: "error SFTP" }, e)
    }

    @Suppress("UNCHECKED_CAST")
    override fun list(path: String): List<RemoteEntry> = sftp("Listar") {
        (ch.ls(p(path)) as java.util.Vector<ChannelSftp.LsEntry>).filter { it.filename != "." && it.filename != ".." && RemotePaths.safeName(it.filename) }.map {
            val a = it.attrs; RemoteEntry(RemotePaths.child(path, it.filename), it.filename, a.isDir, if (a.isDir) 0 else a.size, a.mTime * 1000L)
        }
    }

    private class Mon(val onProgress: (Long) -> Unit) : SftpProgressMonitor {
        var total = 0L; var cancelled: CancellationException? = null
        override fun init(op: Int, src: String?, dest: String?, max: Long) {}
        override fun count(n: Long): Boolean { total += n; return try { onProgress(total); true } catch (c: CancellationException) { cancelled = c; false } }
        override fun end() {}
    }

    override fun download(path: String, dest: File, onProgress: (Long) -> Unit) {
        val m = Mon(onProgress)
        try {
            atomicWrite(dest) { pf ->
                val size = try { ch.stat(p(path)).size } catch (e: SftpException) { -1L }
                val from = if (pf.offset > 0 && (size < 0 || pf.offset <= size)) pf.offset else 0L
                m.total = from
                pf.open(from).use { out -> sftp("Descargar") { if (from > 0) ch.get(p(path), out, m, ChannelSftp.RESUME, from) else ch.get(p(path), out, m) } }
                if (size >= 0 && m.total != size) throw RemoteException("Descarga incompleta", java.io.EOFException())
            }
        }
        catch (e: Throwable) { m.cancelled?.let { throw it }; throw e }
    }

    private fun exists(path: String) = try { ch.stat(p(path)); true } catch (e: SftpException) { if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) false else throw RemoteException("Comprobar: ${e.message}", e) }

    override fun upload(src: File, path: String, overwrite: Boolean, onProgress: (Long) -> Unit) {
        val target = p(path)
        if (!overwrite && exists(target)) throw RemoteException("Subir: ya existe $target")
        val tmp = target + FileOps.PART; val m = Mon(onProgress)
        try {
            src.inputStream().use { i -> sftp("Subir") { ch.put(i, tmp, m, ChannelSftp.OVERWRITE) } }
            if (overwrite && exists(target)) sftp("Subir") { ch.rm(target) }
            sftp("Subir") { ch.rename(tmp, target) }
        } catch (e: Throwable) { try { ch.rm(tmp) } catch (_: Exception) {}; m.cancelled?.let { throw it }; throw e }
    }

    override fun mkdir(path: String) = sftp("Crear carpeta") { ch.mkdir(p(path)) }

    override fun delete(path: String, isDir: Boolean) {
        if (p(path) == "/") throw RemoteException("No se puede borrar la raíz")
        sftp("Borrar") { if (isDir) rmTree(p(path)) else ch.rm(p(path)) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun rmTree(dir: String) {
        for (e in ch.ls(dir) as java.util.Vector<ChannelSftp.LsEntry>) {
            if (e.filename == "." || e.filename == "..") continue
            val c = "$dir/${e.filename}"; if (e.attrs.isDir) rmTree(c) else ch.rm(c)
        }
        ch.rmdir(dir)
    }

    override fun rename(from: String, to: String) {
        if (exists(to)) throw RemoteException("Renombrar: ya existe ${p(to)}")
        sftp("Renombrar") { ch.rename(p(from), p(to)) }
    }

    override fun close() { try { ch.disconnect() } catch (_: Exception) {}; try { session.disconnect() } catch (_: Exception) {} }
}
