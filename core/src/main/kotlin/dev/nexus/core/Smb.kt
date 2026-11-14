package dev.nexus.core

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.File
import java.util.EnumSet
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Cliente SMB2/3 sobre smbj. [share] es el recurso compartido (p. ej. `Documentos`); las rutas remotas son relativas a él.
 * SMB no tiene clave de host: la autenticación NTLM protege la contraseña, pero los datos van sin cifrar salvo que el servidor lo exija (SMB3).
 */
class SmbFs(host: String, port: Int = 445, user: String, password: String, private val share: String, domain: String = "", timeoutMs: Long = 15_000) : RemoteFs {
    private val client = SMBClient(SmbConfig.builder().withTimeout(timeoutMs, TimeUnit.MILLISECONDS).withSoTimeout(timeoutMs, TimeUnit.MILLISECONDS).build())
    private val conn: Connection
    private val session: Session
    private val disk: DiskShare

    init {
        var c: Connection? = null
        try {
            c = client.connect(host, port)
            session = c.authenticate(AuthenticationContext(user, password.toCharArray(), domain))
            disk = session.connectShare(share) as? DiskShare ?: throw RemoteException("«$share» no es una carpeta compartida")
            conn = c
        } catch (e: RemoteException) { try { c?.close() } catch (_: Exception) {}; throw e }
        catch (e: Exception) {
            try { c?.close() } catch (_: Exception) {}
            throw RemoteException(when {
                e is SMBApiException && e.status == NtStatus.STATUS_LOGON_FAILURE -> "Usuario o contraseña incorrectos"
                e is SMBApiException && e.status == NtStatus.STATUS_BAD_NETWORK_NAME -> "No existe el recurso compartido «$share»"
                else -> "SMB: ${e.message ?: e.javaClass.simpleName}"
            }, e)
        }
    }

    private fun p(path: String) = RemotePaths.normalize(path).trimStart('/').replace('/', '\\')
    private fun <T> smb(op: String, block: () -> T): T = try { block() } catch (e: SMBApiException) {
        throw RemoteException("$op: " + when (e.status) {
            NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND, NtStatus.STATUS_NO_SUCH_FILE -> "no existe"
            NtStatus.STATUS_ACCESS_DENIED -> "permiso denegado"
            NtStatus.STATUS_OBJECT_NAME_COLLISION -> "ya existe"
            else -> e.status.name
        }, e)
    }

    override fun list(path: String): List<RemoteEntry> = smb("Listar") {
        disk.list(p(path)).filter { it.fileName != "." && it.fileName != ".." && RemotePaths.safeName(it.fileName) }.map {
            val dir = (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
            RemoteEntry(RemotePaths.child(path, it.fileName), it.fileName, dir, if (dir) 0 else it.endOfFile, it.lastWriteTime.toEpochMillis())
        }
    }

    private fun openRead(path: String) = disk.openFile(p(path), EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)

    override fun download(path: String, dest: File, onProgress: (Long) -> Unit) = smb("Descargar") {
        openRead(path).use { f ->
            // se lee por desplazamientos sin pasar del tamaño: pedir bytes más allá del final hace que algunos servidores (impacket) respondan paquetes que smbj no puede interpretar
            val size = f.fileInformation.standardInformation.endOfFile
            atomicWrite(dest) { pf ->
                var off = if (pf.offset in 1..size) pf.offset else 0L; val buf = ByteArray(64 * 1024)
                pf.open(off).use { out ->
                    while (off < size) {
                        val n = f.read(buf, off, 0, minOf(buf.size.toLong(), size - off).toInt())
                        if (n <= 0) break
                        out.write(buf, 0, n); off += n; onProgress(off)
                    }
                }
                if (off != size) throw RemoteException("Descarga incompleta", java.io.EOFException())
            }
        }
    }

    private fun exists(path: String): Boolean = try { disk.fileExists(p(path)) || disk.folderExists(p(path)) } catch (e: SMBApiException) {
        if (e.status == NtStatus.STATUS_NO_SUCH_FILE || e.status == NtStatus.STATUS_OBJECT_NAME_NOT_FOUND || e.status == NtStatus.STATUS_OBJECT_PATH_NOT_FOUND) false
        else throw RemoteException("Comprobar: ${e.status.name}", e)
    }

    override fun upload(src: File, path: String, overwrite: Boolean, onProgress: (Long) -> Unit) {
        val target = RemotePaths.normalize(path)
        if (!overwrite && exists(target)) throw RemoteException("Subir: ya existe $target")
        val tmp = target + FileOps.PART
        try {
            smb("Subir") {
                disk.openFile(p(tmp), EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.DELETE), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null).use { f ->
                    var sent = 0L; val buf = ByteArray(64 * 1024)
                    f.outputStream.use { o -> src.inputStream().use { i -> while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); sent += n; onProgress(sent) } } }
                    if (overwrite && exists(target)) disk.rm(p(target))
                    f.rename(p(target))
                }
            }
        } catch (e: Throwable) { try { if (exists(tmp)) disk.rm(p(tmp)) } catch (_: Exception) {}; throw e }
    }

    override fun mkdir(path: String) = smb("Crear carpeta") { disk.mkdir(p(path)) }

    override fun delete(path: String, isDir: Boolean) {
        if (RemotePaths.normalize(path) == "/") throw RemoteException("No se puede borrar la raíz")
        smb("Borrar") { if (isDir) disk.rmdir(p(path), true) else disk.rm(p(path)) }
    }

    override fun rename(from: String, to: String) {
        if (exists(to)) throw RemoteException("Renombrar: ya existe ${RemotePaths.normalize(to)}")
        smb("Renombrar") {
            val isDir = (disk.getFileInformation(p(from)).basicInformation.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
            val h = if (isDir) disk.openDirectory(p(from), EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
                    else disk.openFile(p(from), EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
            h.use { (it as com.hierynomus.smbj.share.DiskEntry).rename(p(to), false) }
        }
    }

    override fun close() { try { disk.close() } catch (_: Exception) {}; try { session.close() } catch (_: Exception) {}; try { conn.close() } catch (_: Exception) {}; try { client.close() } catch (_: Exception) {} }
}
