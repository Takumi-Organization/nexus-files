package dev.nexus.files

import android.app.Application
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.nexus.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File

data class CatStat(val count: Int = 0, val size: Long = 0)
data class Dash(
    val total: Long = 0, val free: Long = 0,
    val cats: Map<Kind, CatStat> = emptyMap(), val downloads: CatStat = CatStat(),
    val recentAdded: List<Entry> = emptyList(), val loaded: Boolean = false,
)

data class PathGroup(val title: String, val paths: List<String>)

enum class DateRange(val label: String, val millis: Long?) { ANY("Cualquiera", null), DAY("24 h", 86_400_000L), WEEK("7 días", 7 * 86_400_000L), MONTH("30 días", 30 * 86_400_000L) }
enum class SizeMin(val label: String, val bytes: Long?) { ANY("Cualquiera", null), MB1("> 1 MB", 1L shl 20), MB10("> 10 MB", 10L shl 20), MB100("> 100 MB", 100L shl 20) }

data class SearchUi(
    val name: String = "", val ext: String = "", val kind: Kind? = null,
    val size: SizeMin = SizeMin.ANY, val date: DateRange = DateRange.ANY,
    val inCurrent: Boolean = false, val hidden: Boolean = false,
    val running: Boolean = false, val result: SearchResult? = null,
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val store = LibraryStore(File(app.filesDir, "library.json"))
    private val _lib = MutableStateFlow(LibraryData())
    val lib: StateFlow<LibraryData> = _lib
    private val _dash = MutableStateFlow(Dash())
    val dash: StateFlow<Dash> = _dash
    private val _search = MutableStateFlow(SearchUi())
    val search: StateFlow<SearchUi> = _search
    val collection = MutableStateFlow<PathGroup?>(null)
    private var searchJob: Job? = null
    val primaryRoot: File = Environment.getExternalStorageDirectory()

    init {
        viewModelScope.launch(Dispatchers.IO) { _lib.value = store.load().pruned { File(it).exists() }.also(store::save) }
    }

    private fun edit(f: (LibraryData) -> LibraryData) {
        val n = f(_lib.value); _lib.value = n
        viewModelScope.launch(Dispatchers.IO) { runCatching { store.save(n) } }
    }
    fun toggleFavorite(paths: Collection<String>) {
        val allFav = paths.all { it in _lib.value.favorites }
        edit { d -> paths.fold(d) { acc, p -> if ((p in acc.favorites) == allFav) acc.toggleFavorite(p) else acc } }
    }
    fun setTags(paths: Collection<String>, add: Set<String>, remove: Set<String> = emptySet()) =
        edit { d -> paths.fold(d) { acc, p -> acc.setTags(p, (acc.tags[p].orEmpty() + add) - remove) } }
    fun addRecent(path: String) = edit { it.addRecent(path) }
    fun moved(from: String, to: String) = edit { it.moved(from, to) }

    fun openCollection(c: PathGroup?) { collection.value = c }

    fun refreshDash() {
        viewModelScope.launch(Dispatchers.IO) { runCatching { _dash.value = loadDash() }.onFailure { _dash.update { d -> d.copy(loaded = true) } } }
    }

    private fun loadDash(): Dash {
        val st = StatFs(primaryRoot.path)
        val cats = HashMap<Kind, CatStat>(); var dl = CatStat()
        val dlPrefix = File(primaryRoot, Environment.DIRECTORY_DOWNLOADS).path + "/"
        val recent = ArrayList<Triple<String, Long, Long>>()
        val uri = MediaStore.Files.getContentUri("external")
        getApplication<Application>().contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED), "${MediaStore.MediaColumns.SIZE} > 0", null, null)?.use { c ->
            while (c.moveToNext()) {
                val path = c.getString(0) ?: continue; val size = c.getLong(1); val mod = c.getLong(2)
                val name = path.substringAfterLast('/')
                if (name.startsWith('.') || "/.trashed" in path || "/Android/" in path) continue
                val k = Kinds.of(name.substringAfterLast('.', "").lowercase())
                if (k in setOf(Kind.IMAGE, Kind.VIDEO, Kind.AUDIO, Kind.DOCUMENT)) { val o = cats[k] ?: CatStat(); cats[k] = CatStat(o.count + 1, o.size + size) }
                if (path.startsWith(dlPrefix)) dl = CatStat(dl.count + 1, dl.size + size)
                recent += Triple(path, mod, size)
            }
        }
        val top = recent.sortedByDescending { it.second }.take(15).map { File(it.first) }.filter { it.isFile }.map(LocalFs::entry)
        return Dash(st.totalBytes, st.availableBytes, cats, dl, top, true)
    }

    // ---- búsqueda ----
    fun updateSearch(f: (SearchUi) -> SearchUi, currentDir: File?) { _search.update(f); runSearch(currentDir) }

    fun runSearch(currentDir: File?) {
        searchJob?.cancel()
        val s = _search.value
        val idle = s.name.isBlank() && s.ext.isBlank() && s.kind == null && s.size == SizeMin.ANY && s.date == DateRange.ANY
        if (idle) { _search.update { it.copy(running = false, result = null) }; return }
        _search.update { it.copy(running = true) }
        searchJob = viewModelScope.launch {
            delay(250)
            val q = SearchQuery(
                root = if (s.inCurrent && currentDir != null) currentDir else primaryRoot, name = s.name.trim(),
                extensions = s.ext.split(',', ' ').map { it.trim().trimStart('.').lowercase() }.filter { it.isNotEmpty() }.toSet(),
                kind = s.kind, minSize = s.size.bytes, modifiedAfter = s.date.millis?.let { System.currentTimeMillis() - it },
                includeHidden = s.hidden, includeFolders = s.kind == null || s.kind == Kind.FOLDER,
            )
            val r = withContext(Dispatchers.IO) { Search.run(q) }
            _search.update { it.copy(running = false, result = r) }
        }
    }
}
