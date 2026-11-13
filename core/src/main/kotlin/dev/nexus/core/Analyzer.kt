package dev.nexus.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.PriorityQueue

/** Nodo del árbol de uso. Los hijos se podan a los [Analyzer.MAX_CHILDREN] mayores; el resto se agrega en un nodo sintético. */
class Node(val name: String, val path: String, val isDir: Boolean, var size: Long, val synthetic: Boolean = false) {
    val children = ArrayList<Node>()
}

data class Item(val path: String, val name: String, val isDir: Boolean, val size: Long, val modified: Long)

data class ScanResult(
    val root: Node,
    val totalBytes: Long,
    val fileCount: Int,
    val bytesByKind: Map<Kind, Long>,
    val largest: List<Item>,
    val largestByKind: Map<Kind, List<Item>>,
    val heaviestFolders: List<Item>,   // por bytes propios de la carpeta (no recursivos): evita listar padres redundantes
    val old: List<Item>,
    val temp: List<Item>,
    val unreadable: List<String>,
)

class TopN<T>(private val n: Int, private val key: (T) -> Long) {
    private val q = PriorityQueue<T>(compareBy(key))
    fun add(t: T) { if (q.size < n) q.add(t) else if (key(t) > key(q.peek())) { q.poll(); q.add(t) } }
    fun sorted(): List<T> = q.sortedByDescending(key)
}

object Analyzer {
    const val MAX_CHILDREN = 40
    private const val TOP = 50
    private val TEMP_EXT = setOf("tmp", "temp", "bak", "old", "swp", "crdownload", "part", "nexus-part")
    private val TEMP_DIRS = setOf("cache", "code_cache", ".cache", "thumbnails", ".thumbnails", ".trash", "tmp", "temp")

    fun isTempFile(name: String) = name.substringAfterLast('.', "").lowercase() in TEMP_EXT || name.endsWith("~")
    fun isTempDir(name: String) = name.lowercase() in TEMP_DIRS

    private class Acc(val oldCutoff: Long, val onProgress: (Int, Long) -> Unit) {
        var files = 0; var bytes = 0L
        val byKind = HashMap<Kind, Long>()
        val largest = TopN<Item>(TOP) { it.size }
        val perKind = HashMap<Kind, TopN<Item>>()
        val folders = TopN<Item>(TOP) { it.size }
        val old = TopN<Item>(TOP) { it.size }
        val temp = TopN<Item>(TOP) { it.size }
        val unreadable = ArrayList<String>()
    }

    suspend fun scan(root: File, oldAfterMillis: Long = 365L * 86_400_000, now: Long = System.currentTimeMillis(),
                     onProgress: (files: Int, bytes: Long) -> Unit = { _, _ -> }): ScanResult {
        val acc = Acc(now - oldAfterMillis, onProgress)
        val node = walk(root, acc, insideTemp = false)
        return ScanResult(
            node, node.size, acc.files, acc.byKind, acc.largest.sorted(),
            Kind.entries.associateWith { acc.perKind[it]?.sorted().orEmpty() }.filterValues { it.isNotEmpty() },
            acc.folders.sorted(), acc.old.sorted(), acc.temp.sorted(), acc.unreadable,
        )
    }

    private suspend fun walk(dir: File, acc: Acc, insideTemp: Boolean): Node {
        currentCoroutineContext().ensureActive()
        val node = Node(dir.name.ifEmpty { dir.path }, dir.path, true, 0)
        val kids = dir.listFiles() ?: run { acc.unreadable += dir.path; return node }
        val tempDir = !insideTemp && Analyzer.isTempDir(dir.name)
        var direct = 0L
        val children = ArrayList<Node>()
        for (f in kids) {
            if (LocalFs.isSymlink(f)) continue
            if (f.isDirectory) { children += walk(f, acc, insideTemp || tempDir) ; continue }
            val size = f.length(); val mod = f.lastModified()
            val item = Item(f.path, f.name, false, size, mod)
            direct += size; acc.files++; acc.bytes += size
            val kind = Kinds.of(f.name.substringAfterLast('.', "").lowercase())
            acc.byKind.merge(kind, size, Long::plus)
            acc.largest.add(item); acc.perKind.getOrPut(kind) { TopN(TOP) { it.size } }.add(item)
            if (size >= 1L shl 20 && mod < acc.oldCutoff) acc.old.add(item)
            if (!insideTemp && !tempDir && isTempFile(f.name)) acc.temp.add(item)
            children += Node(f.name, f.path, false, size)
            if (acc.files % 500 == 0) { currentCoroutineContext().ensureActive(); acc.onProgress(acc.files, acc.bytes) }
        }
        node.size = direct + children.filter { it.isDir }.sumOf { it.size }
        if (direct > 0) acc.folders.add(Item(dir.path, node.name, true, direct, dir.lastModified()))
        if (tempDir && node.size > 0) acc.temp.add(Item(dir.path, dir.name, true, node.size, dir.lastModified()))
        children.sortByDescending { it.size }
        node.children += children.take(MAX_CHILDREN)
        val rest = children.drop(MAX_CHILDREN)
        if (rest.isNotEmpty()) node.children += Node("otros (${rest.size})", dir.path, false, rest.sumOf { it.size }, synthetic = true)
        return node
    }
}

/** Rectángulo en coordenadas arbitrarias. */
data class Rect(val x: Float, val y: Float, val w: Float, val h: Float)

/** Treemap «squarified» (Bruls et al.): bloques proporcionales al peso y lo más cuadrados posible. */
object Treemap {
    fun layout(weights: List<Long>, bounds: Rect): List<Rect> {
        val idx = weights.indices.filter { weights[it] > 0 }.sortedByDescending { weights[it] }
        val out = arrayOfNulls<Rect>(weights.size)
        val total = idx.sumOf { weights[it] }.toDouble()
        if (total <= 0 || bounds.w <= 0 || bounds.h <= 0) return weights.map { Rect(bounds.x, bounds.y, 0f, 0f) }
        val scale = bounds.w.toDouble() * bounds.h / total
        var rest = idx; var r = bounds
        while (rest.isNotEmpty()) {
            val side = minOf(r.w, r.h).toDouble()
            var row = 0; var best = Double.MAX_VALUE; var sum = 0.0
            while (row < rest.size) {
                val s2 = sum + weights[rest[row]] * scale
                val worst = worst(rest.subList(0, row + 1).map { weights[it] * scale }, s2, side)
                if (worst > best) break
                best = worst; sum = s2; row++
            }
            val rowItems = rest.subList(0, row); rest = rest.subList(row, rest.size)
            val horizontal = r.w >= r.h   // fila a lo largo del lado corto
            val thick = (sum / side).toFloat()
            var off = 0f
            for (i in rowItems) {
                val len = (weights[i] * scale / sum * side).toFloat()
                out[i] = if (horizontal) Rect(r.x, r.y + off, thick, len) else Rect(r.x + off, r.y, len, thick)
                off += len
            }
            r = if (horizontal) Rect(r.x + thick, r.y, r.w - thick, r.h) else Rect(r.x, r.y + thick, r.w, r.h - thick)
        }
        return out.mapIndexed { i, v -> v ?: Rect(bounds.x, bounds.y, 0f, 0f) }
    }

    private fun worst(areas: List<Double>, sum: Double, side: Double): Double {
        val max = areas.max(); val min = areas.min()
        return maxOf(side * side * max / (sum * sum), sum * sum / (side * side * min))
    }
}
