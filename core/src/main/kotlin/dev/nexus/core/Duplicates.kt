package dev.nexus.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

data class DupGroup(val size: Long, val sha256: String, val files: List<Item>) {
    val wasted: Long get() = size * (files.size - 1)
}

data class DupResult(val groups: List<DupGroup>, val scannedFiles: Int, val unreadable: List<String>) {
    val wasted: Long get() = groups.sumOf { it.wasted }
}

/** Duplicados exactos: tamaño → hash de los primeros 64 KB → SHA-256 completo. Solo lee; nunca borra. */
object Duplicates {
    private const val HEAD = 64 * 1024

    suspend fun find(root: File, minSize: Long = 1, includeHidden: Boolean = false, onProgress: (stage: String, done: Int, total: Int) -> Unit = { _, _, _ -> }): DupResult {
        val bySize = HashMap<Long, MutableList<File>>(); val unreadable = ArrayList<String>(); var scanned = 0
        val stack = ArrayDeque<File>().apply { add(root) }
        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val d = stack.removeLast()
            val kids = d.listFiles() ?: run { unreadable += d.path; continue }
            for (f in kids) {
                if (LocalFs.isSymlink(f)) continue
                if (!includeHidden && f.name.startsWith('.')) continue
                if (f.isDirectory) stack.add(f)
                else if (!f.name.endsWith(FileOps.PART)) { val s = f.length(); if (s >= minSize) { bySize.getOrPut(s) { ArrayList() } += f; scanned++ } }
            }
        }
        val candidates = bySize.filterValues { it.size > 1 }
        val total = candidates.values.sumOf { it.size }; var done = 0
        val groups = ArrayList<DupGroup>()
        for ((size, files) in candidates) {
            val byHead = HashMap<String, MutableList<File>>()
            for (f in files) { currentCoroutineContext().ensureActive(); hash(f, HEAD.toLong())?.let { byHead.getOrPut(it) { ArrayList() } += f }; onProgress("Comparando", ++done, total) }
            for (same in byHead.values.filter { it.size > 1 }) {
                val byFull = HashMap<String, MutableList<File>>()
                for (f in same) { currentCoroutineContext().ensureActive(); hash(f, Long.MAX_VALUE)?.let { byFull.getOrPut(it) { ArrayList() } += f } }
                byFull.forEach { (h, fs) -> if (fs.size > 1) groups += DupGroup(size, h, fs.sortedBy { it.lastModified() }.map { Item(it.path, it.name, false, size, it.lastModified()) }) }
            }
        }
        return DupResult(groups.sortedByDescending { it.wasted }, scanned, unreadable)
    }

    private suspend fun hash(f: File, limit: Long): String? = try {
        val md = MessageDigest.getInstance("SHA-256"); val buf = ByteArray(64 * 1024); var left = limit
        FileInputStream(f).use { i -> while (left > 0) { currentCoroutineContext().ensureActive(); val n = i.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; md.update(buf, 0, n); left -= n } }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: java.io.IOException) { null }

    /** Una selección de borrado es válida solo si en cada grupo se conserva al menos un archivo. */
    fun isSafeSelection(groups: List<DupGroup>, toDelete: Set<String>): Boolean =
        groups.all { g -> g.files.any { it.path !in toDelete } }

    /** Por grupo, marca todos menos el más antiguo (el primero). Solo propone; borrar exige confirmación en la UI. */
    fun suggestExtras(groups: List<DupGroup>): Set<String> = groups.flatMap { it.files.drop(1).map(Item::path) }.toSet()
}
