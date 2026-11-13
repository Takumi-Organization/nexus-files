package dev.nexus.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Favoritos, etiquetas y recientes; viven en un único JSON. La escritura es atómica (temporal + rename). */
data class LibraryData(
    val favorites: List<String> = emptyList(),
    val tags: Map<String, Set<String>> = emptyMap(),   // ruta -> etiquetas
    val recents: List<String> = emptyList(),
) {
    fun toggleFavorite(path: String) = copy(favorites = if (path in favorites) favorites - path else favorites + path)
    fun addRecent(path: String, max: Int = 30) = copy(recents = (listOf(path) + recents.filter { it != path }).take(max))

    fun setTags(path: String, t: Set<String>): LibraryData {
        val clean = t.map { it.trim() }.filter { it.isNotEmpty() && it.length <= 40 }.toSet()
        return copy(tags = if (clean.isEmpty()) tags - path else tags + (path to clean))
    }
    val allTags: List<String> get() = tags.values.flatten().distinct().sortedBy { it.lowercase() }
    fun pathsWithTag(tag: String) = tags.filterValues { tag in it }.keys.toList()

    /** Quita rutas que ya no existen; no se inventan ni se reasignan entradas. */
    fun pruned(exists: (String) -> Boolean) = LibraryData(
        favorites.filter(exists), tags.filterKeys(exists), recents.filter(exists))

    /** Una ruta renombrada/movida conserva su favorito y etiquetas. */
    fun moved(from: String, to: String): LibraryData {
        fun r(p: String) = if (p == from) to else if (p.startsWith("$from/")) to + p.removePrefix(from) else p
        return LibraryData(favorites.map(::r), tags.mapKeys { r(it.key) }, recents.map(::r))
    }

    fun toJson(): String = JSONObject()
        .put("favorites", JSONArray(favorites))
        .put("recents", JSONArray(recents))
        .put("tags", JSONObject().also { o -> tags.forEach { (k, v) -> o.put(k, JSONArray(v.toList())) } })
        .toString()

    companion object {
        fun fromJson(s: String): LibraryData = runCatching {
            val o = JSONObject(s)
            fun arr(a: JSONArray?) = if (a == null) emptyList() else List(a.length()) { a.getString(it) }
            val t = o.optJSONObject("tags")
            LibraryData(arr(o.optJSONArray("favorites")), buildMap { t?.keys()?.forEach { k -> put(k, arr(t.getJSONArray(k)).toSet()) } }, arr(o.optJSONArray("recents")))
        }.getOrDefault(LibraryData())
    }
}

class LibraryStore(private val file: File) {
    fun load(): LibraryData = if (file.exists()) LibraryData.fromJson(file.readText()) else LibraryData()
    fun save(d: LibraryData) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(d.toJson()); if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
