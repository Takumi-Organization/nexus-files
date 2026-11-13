package dev.nexus.files

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.nexus.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class RemoteUi(
    val profiles: List<RemoteProfile> = emptyList(),
    val active: RemoteProfile? = null,
    val path: String = "/",
    val entries: List<RemoteEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

class RemoteViewModel(private val app: Application) : AndroidViewModel(app) {
    private val store = ProfileStore(File(app.filesDir, "remotes.json"), KeystoreSecretBox())
    private val prefs = app.getSharedPreferences("nexus", 0)
    private val _s = MutableStateFlow(RemoteUi())
    val state: StateFlow<RemoteUi> = _s
    private var job: Job? = null

    init { refreshProfiles() }

    fun refreshProfiles() { viewModelScope.launch { val l = withContext(Dispatchers.IO) { store.list() }; _s.update { it.copy(profiles = l) } } }

    /** Contraseña vacía al editar = conservar la guardada. */
    fun save(existingId: String?, name: String, protocol: Protocol, host: String, port: Int, user: String, basePath: String, password: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val p = RemoteProfile(existingId ?: UUID.randomUUID().toString(), name.trim(), protocol, host.trim(), port, user.trim(), basePath.trim())
            store.save(p, password.ifEmpty { null }); refreshProfiles()
        }
    }

    fun delete(id: String) { viewModelScope.launch(Dispatchers.IO) { store.delete(id); refreshProfiles() } }

    /** Abre una conexión nueva por operación: si una sesión FTP queda a medias tras un error, no contamina la siguiente. */
    private fun <T> withFs(p: RemoteProfile, block: (RemoteFs) -> T): T {
        val pw = store.password(p.id) ?: throw RemoteException("No se pudo recuperar la contraseña: edita la conexión y vuelve a escribirla")
        return RemoteFactory.open(p, pw).use(block)
    }

    fun connect(p: RemoteProfile) { _s.update { it.copy(active = p, path = "/", entries = emptyList(), error = null) }; list("/") }
    fun disconnect() { job?.cancel(); _s.update { it.copy(active = null, entries = emptyList(), error = null, loading = false) } }
    fun reload() = list(_s.value.path)
    fun up(): Boolean { val s = _s.value; if (s.active == null) return false; if (s.path == "/") { disconnect(); return true }; list(RemotePaths.parent(s.path)); return true }
    fun open(e: RemoteEntry) { if (e.isDir) list(e.path) }

    private fun list(path: String) {
        val p = _s.value.active ?: return
        job?.cancel()
        _s.update { it.copy(loading = true, error = null, path = path) }
        job = viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { withFs(p) { it.list(path) } } }
            _s.update { s -> if (s.path != path || s.active?.id != p.id) s else r.fold(
                { s.copy(entries = it.sortedWith(compareBy<RemoteEntry> { e -> !e.isDir }.thenBy(String.CASE_INSENSITIVE_ORDER) { e -> e.name }), loading = false) },
                { s.copy(entries = emptyList(), loading = false, error = it.message ?: "No se pudo listar") }) }
        }
    }

    fun mkdir(name: String) = op("Carpeta creada") { fs, p -> fs.mkdir(RemotePaths.child(p, requireName(name))) }
    fun rename(e: RemoteEntry, name: String) = op("Renombrado") { fs, _ -> fs.rename(e.path, RemotePaths.child(RemotePaths.parent(e.path), requireName(name))) }
    fun delete(e: RemoteEntry) = op("Eliminado") { fs, _ -> fs.delete(e.path, e.isDir) }

    private fun requireName(n: String): String = (Names.validate(n) as? Names.Result.Ok)?.name ?: throw RemoteException((Names.validate(n) as Names.Result.Invalid).reason)

    private fun op(ok: String, block: (RemoteFs, String) -> Unit) {
        val p = _s.value.active ?: return; val path = _s.value.path
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { withFs(p) { block(it, path) } } }
            _s.update { it.copy(message = r.fold({ ok }, { e -> e.message ?: "Error" })) }
            list(path)
        }
    }

    fun downloadDir(): File = File(android.os.Environment.getExternalStorageDirectory(), prefs.getString("downloadDir", DEFAULT_DL)!!)

    fun download(e: RemoteEntry) { _s.value.active?.let { Transfers.download(app, it, e, downloadDir()) } }

    /** Copia el contenido elegido con el selector del sistema a caché y lo sube; la copia temporal se borra al acabar. */
    fun upload(uri: android.net.Uri) {
        val p = _s.value.active ?: return; val dir = _s.value.path
        viewModelScope.launch {
            val tmp = withContext(Dispatchers.IO) { runCatching {
                val name = app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    ?.takeIf { RemotePaths.safeName(it) } ?: "subida.bin"
                val dst = File(File(app.cacheDir, "up").apply { mkdirs() }, "${System.nanoTime()}-$name")
                app.contentResolver.openInputStream(uri)!!.use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
                dst to name
            } }
            tmp.fold({ (f, name) ->
                // el nombre real se restituye en el servidor: renombramos la copia a su nombre original dentro de una carpeta propia
                val real = File(f.parentFile, "${System.nanoTime()}").apply { mkdirs() }.resolve(name)
                f.renameTo(real)
                Transfers.upload(app, p, real, dir, deleteAfter = true)
            }, { e -> _s.update { it.copy(message = "No se pudo leer el archivo: ${e.message}") } })
        }
    }

    fun consumeMessage() = _s.update { it.copy(message = null) }
}
