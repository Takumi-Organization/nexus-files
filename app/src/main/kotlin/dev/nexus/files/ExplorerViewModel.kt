package dev.nexus.files

import android.app.Application
import android.os.Environment
import android.os.storage.StorageManager
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

data class Root(val label: String, val dir: File, val removable: Boolean)
data class Clip(val paths: List<String>, val cut: Boolean)
data class OpState(val title: String, val progress: Progress?)

data class UiState(
    val roots: List<Root> = emptyList(),
    val dir: File? = null,
    val entries: List<Entry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val selection: Set<String> = emptySet(),
    val clip: Clip? = null,
    val grid: Boolean = false,
    val sort: Sort = Sort(),
    val showHidden: Boolean = false,
    val op: OpState? = null,
    val message: String? = null,
)

const val DEFAULT_DL = "Download/NEXUS"

class ExplorerViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("nexus", 0)
    private val ops = FileOps()
    private var opJob: Job? = null
    private var loadJob: Job? = null
    private val _s = MutableStateFlow(UiState(
        grid = prefs.getBoolean("grid", false),
        showHidden = prefs.getBoolean("hidden", false),
        sort = Sort(SortBy.valueOf(prefs.getString("sortBy", "NAME")!!), prefs.getBoolean("desc", false)),
    ))
    val state: StateFlow<UiState> = _s

    fun roots(): List<Root> {
        val sm = getApplication<Application>().getSystemService(StorageManager::class.java)
        return sm.storageVolumes.mapNotNull { v ->
            v.directory?.takeIf { it.exists() }?.let { Root(if (v.isPrimary) "Almacenamiento interno" else v.getDescription(getApplication()), it, v.isRemovable) }
        }.ifEmpty { listOf(Root("Almacenamiento interno", Environment.getExternalStorageDirectory(), false)) }
    }

    fun start() { val r = roots(); _s.update { it.copy(roots = r) }; if (_s.value.dir == null) open(r.first().dir) }

    fun open(dir: File) {
        loadJob?.cancel()
        _s.update { it.copy(dir = dir, loading = true, error = null, selection = emptySet()) }
        loadJob = viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { LocalFs.list(dir) } }
            _s.update { s ->
                if (s.dir != dir) s else r.fold(
                    { s.copy(entries = it, loading = false) },
                    { s.copy(entries = emptyList(), loading = false, error = it.message ?: "No se puede leer la carpeta") })
            }
        }
    }

    fun reload() = _s.value.dir?.let(::open)

    /** Sube un nivel sin salir de la raíz de almacenamiento. @return false si ya estamos en la raíz. */
    fun up(): Boolean {
        val s = _s.value
        if (s.selection.isNotEmpty()) { _s.update { it.copy(selection = emptySet()) }; return true }
        val d = s.dir ?: return false
        if (s.roots.any { it.dir == d }) return false
        d.parentFile?.let(::open) ?: return false
        return true
    }

    val visible: List<Entry> get() = _s.value.let { s -> s.sort.apply(s.entries.filter { s.showHidden || !it.hidden }) }

    fun toggle(path: String) = _s.update { it.copy(selection = if (path in it.selection) it.selection - path else it.selection + path) }
    fun selectAll() = _s.update { it.copy(selection = visible.map { e -> e.path }.toSet()) }
    fun clearSelection() = _s.update { it.copy(selection = emptySet()) }

    fun setGrid(v: Boolean) { prefs.edit().putBoolean("grid", v).apply(); _s.update { it.copy(grid = v) } }
    fun setHidden(v: Boolean) { prefs.edit().putBoolean("hidden", v).apply(); _s.update { it.copy(showHidden = v) } }
    fun setSort(s: Sort) { prefs.edit().putString("sortBy", s.by.name).putBoolean("desc", s.descending).apply(); _s.update { it.copy(sort = s) } }
    val downloadFolder: String get() = prefs.getString("downloadDir", DEFAULT_DL)!!
    fun validDownloadFolder(v: String) = v.isNotBlank() && !v.startsWith("/") && v.split('/').none { it == ".." } && '\u0000' !in v
    fun setDownloadFolder(v: String) { if (validDownloadFolder(v)) prefs.edit().putString("downloadDir", v.trim('/')).apply() }
    fun consumeMessage() = _s.update { it.copy(message = null) }

    fun copyToClip(cut: Boolean) = _s.update { it.copy(clip = Clip(it.selection.toList(), cut), selection = emptySet()) }
    fun clearClip() = _s.update { it.copy(clip = null) }

    fun paste() {
        val s = _s.value; val clip = s.clip ?: return; val dest = s.dir ?: return
        run(if (clip.cut) "Moviendo" else "Copiando", clip.cut) { cb -> 
            val files = clip.paths.map(::File)
            if (clip.cut) ops.move(files, dest, Conflict.KEEP_BOTH, cb) else ops.copy(files, dest, Conflict.KEEP_BOTH, cb)
        }
    }

    fun delete(paths: Set<String>) = run("Eliminando", false) { cb -> ops.delete(paths.map(::File), cb) }

    private fun run(title: String, clearClipOnOk: Boolean, block: suspend (cb: (Progress) -> Unit) -> Report) {
        if (opJob?.isActive == true) { _s.update { it.copy(message = "Ya hay una operación en curso") }; return }
        _s.update { it.copy(op = OpState(title, null), selection = emptySet()) }
        opJob = viewModelScope.launch(Dispatchers.IO) {
            val r = block { p -> _s.update { it.copy(op = OpState(title, p)) } }
            val msg = buildString {
                append(if (r.cancelled) "Cancelado" else "Listo"); append(": ${r.done} elemento(s)")
                if (r.skipped.isNotEmpty()) append(", ${r.skipped.size} omitido(s)")
                if (r.failed.isNotEmpty()) append(", ${r.failed.size} con error — ${r.failed.first().reason}")
            }
            _s.update { it.copy(op = null, message = msg, clip = if (clearClipOnOk && r.ok) null else it.clip) }
            reload()
        }
    }

    fun cancelOp() { opJob?.cancel() }

    fun compress(paths: Set<String>, rawName: String) {
        val dir = _s.value.dir ?: return
        val name = (Names.validate(rawName) as? Names.Result.Ok)?.name ?: run { _s.update { it.copy(message = "Nombre no válido") }; return }
        val final = if (name.endsWith(".zip", true)) name else "$name.zip"
        run("Comprimiendo", false) { cb -> Zips.create(paths.map(::File), File(dir, final), cb) }
    }

    /** Extrae a una carpeta nueva junto al ZIP (nunca mezcla con carpetas existentes). */
    fun extract(zip: File, only: Set<String>?) {
        val parent = zip.parentFile ?: return
        val base = zip.nameWithoutExtension.ifEmpty { "extraido" }
        val dest = generateSequence(0) { it + 1 }.map { File(parent, if (it == 0) base else Names.withSuffix(base, it)) }.first { !it.exists() }
        run("Extrayendo", false) { cb -> Zips.extract(zip, dest, only, Conflict.KEEP_BOTH, cb) }
    }

    fun mkdir(name: String) = attempt { LocalFs.mkdir(_s.value.dir!!, name) }
    fun rename(path: String, name: String, onMoved: (String, String) -> Unit = { _, _ -> }) = attempt { val r = LocalFs.rename(File(path), name); onMoved(path, r.path) }

    private fun attempt(block: () -> Unit) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching(block) }
            r.exceptionOrNull()?.let { e -> _s.update { it.copy(message = e.message ?: "Error") } }
            _s.update { it.copy(selection = emptySet()) }; reload()
        }
    }
}
