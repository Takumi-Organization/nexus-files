package dev.nexus.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

data class SearchQuery(
    val root: File,
    val name: String = "",            // subcadena, sin distinguir mayúsculas
    val extensions: Set<String> = emptySet(),
    val kind: Kind? = null,
    val minSize: Long? = null,
    val maxSize: Long? = null,
    val modifiedAfter: Long? = null,
    val modifiedBefore: Long? = null,
    val includeHidden: Boolean = false,
    val includeFolders: Boolean = true,
)

data class SearchResult(val entries: List<Entry>, val truncated: Boolean, val unreadable: Int)

object Search {
    fun matches(e: Entry, q: SearchQuery): Boolean {
        if (e.isDir && !q.includeFolders) return false
        if (q.name.isNotEmpty() && !e.name.contains(q.name, ignoreCase = true)) return false
        val fileOnly = q.extensions.isNotEmpty() || q.kind != null || q.minSize != null || q.maxSize != null
        if (fileOnly && e.isDir && q.kind != Kind.FOLDER) return false
        if (q.extensions.isNotEmpty() && e.extension !in q.extensions) return false
        if (q.kind != null && e.kind != q.kind) return false
        if (q.minSize != null && e.size < q.minSize) return false
        if (q.maxSize != null && e.size > q.maxSize) return false
        if (q.modifiedAfter != null && e.modified < q.modifiedAfter) return false
        if (q.modifiedBefore != null && e.modified > q.modifiedBefore) return false
        return true
    }

    /** Recorre el árbol sin seguir enlaces; las carpetas ilegibles se cuentan (transparencia), no se ocultan. Cancelable. */
    suspend fun run(q: SearchQuery, limit: Int = 500): SearchResult {
        val out = ArrayList<Entry>(); var unreadable = 0; var truncated = false
        val stack = ArrayDeque<File>().apply { add(q.root) }
        while (stack.isNotEmpty() && !truncated) {
            currentCoroutineContext().ensureActive()
            val dir = stack.removeLast()
            val kids = dir.listFiles() ?: run { unreadable++; continue }
            for (f in kids) {
                if (!q.includeHidden && f.name.startsWith('.')) continue
                val e = LocalFs.entry(f)
                if (matches(e, q)) { out += e; if (out.size >= limit) { truncated = true; break } }
                if (e.isDir && !LocalFs.isSymlink(f)) stack.add(f)
            }
        }
        return SearchResult(out, truncated, unreadable)
    }
}
