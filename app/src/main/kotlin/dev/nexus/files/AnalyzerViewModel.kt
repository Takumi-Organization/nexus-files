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

data class AnalyzerUi(
    val scanning: Boolean = false, val scanFiles: Int = 0, val scanBytes: Long = 0, val scannedRoot: String? = null,
    val result: ScanResult? = null, val stack: List<Node> = emptyList(), val kind: Kind? = null,
    val dupRunning: Boolean = false, val dupProgress: Pair<Int, Int>? = null, val dup: DupResult? = null, val dupSelection: Set<String> = emptySet(),
    val message: String? = null, val deleting: Boolean = false,
)

class AnalyzerViewModel(app: Application) : AndroidViewModel(app) {
    private val ops = FileOps()
    private val _s = MutableStateFlow(AnalyzerUi())
    val state: StateFlow<AnalyzerUi> = _s
    private var job: Job? = null
    private var dupJob: Job? = null

    fun scan(root: File) {
        job?.cancel()
        _s.update { it.copy(scanning = true, scanFiles = 0, scanBytes = 0, scannedRoot = root.path, kind = null) }
        job = viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { Analyzer.scan(root) { f, b -> _s.update { it.copy(scanFiles = f, scanBytes = b) } } }
            _s.update { it.copy(scanning = false, result = r, stack = listOf(r.root)) }
        }
    }
    fun cancelScan() { job?.cancel(); _s.update { it.copy(scanning = false) } }

    fun enter(n: Node) { if (n.isDir && n.children.isNotEmpty()) _s.update { it.copy(stack = it.stack + n) } }
    fun up() { _s.update { if (it.stack.size > 1) it.copy(stack = it.stack.dropLast(1)) else it } }
    fun jump(i: Int) { _s.update { it.copy(stack = it.stack.take(i + 1)) } }
    fun setKind(k: Kind?) = _s.update { it.copy(kind = if (it.kind == k) null else k) }
    fun consume() = _s.update { it.copy(message = null) }

    fun findDuplicates(root: File) {
        dupJob?.cancel()
        _s.update { it.copy(dupRunning = true, dupProgress = null, dup = null, dupSelection = emptySet()) }
        dupJob = viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { Duplicates.find(root) { _, d, t -> _s.update { it.copy(dupProgress = d to t) } } }
            _s.update { it.copy(dupRunning = false, dup = r) }
        }
    }
    fun cancelDup() { dupJob?.cancel(); _s.update { it.copy(dupRunning = false) } }
    fun toggleDup(path: String) = _s.update { it.copy(dupSelection = if (path in it.dupSelection) it.dupSelection - path else it.dupSelection + path) }
    fun suggestDup() = _s.update { s -> s.dup?.let { s.copy(dupSelection = Duplicates.suggestExtras(it.groups)) } ?: s }
    fun clearDup() = _s.update { it.copy(dupSelection = emptySet()) }

    /** Borrado explícito (la UI ya pidió confirmación). Valida que cada grupo conserve al menos una copia. */
    fun deleteDuplicates() {
        val s = _s.value; val d = s.dup ?: return
        if (!Duplicates.isSafeSelection(d.groups, s.dupSelection)) { _s.update { it.copy(message = "Cada grupo debe conservar al menos una copia") }; return }
        val sel = s.dupSelection
        delete(sel.toList()) { deleted ->
            val groups = d.groups.mapNotNull { g -> g.copy(files = g.files.filter { it.path !in deleted }).takeIf { it.files.size > 1 } }
            _s.update { it.copy(dup = d.copy(groups = groups), dupSelection = emptySet()) }
        }
    }

    fun deleteItem(path: String) = delete(listOf(path)) { gone -> _s.update { s -> s.copy(result = s.result?.without(gone)) } }

    private fun delete(paths: List<String>, after: (Set<String>) -> Unit) {
        if (_s.value.deleting) return
        _s.update { it.copy(deleting = true) }
        viewModelScope.launch {
            val rep = withContext(Dispatchers.IO) { ops.delete(paths.map(::File)) }
            val failed = rep.failed.map { it.path }.toSet()
            val gone = paths.filter { it !in failed && !File(it).exists() }.toSet()
            after(gone)
            _s.update { it.copy(deleting = false, message = "Eliminados ${gone.size}" + if (rep.failed.isNotEmpty()) " · ${rep.failed.size} con error: ${rep.failed.first().reason}" else "") }
        }
    }

    private fun ScanResult.without(gone: Set<String>): ScanResult {
        fun List<Item>.f() = filter { i -> gone.none { i.path == it || i.path.startsWith("$it/") } }
        return copy(largest = largest.f(), largestByKind = largestByKind.mapValues { it.value.f() }, heaviestFolders = heaviestFolders.f(), old = old.f(), temp = temp.f())
    }
}
