package dev.nexus.core

import java.io.File

enum class Kind { FOLDER, IMAGE, VIDEO, AUDIO, DOCUMENT, ARCHIVE, CODE, OTHER }

data class Entry(
    val path: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val hidden: Boolean,
    val readable: Boolean,
) {
    val extension: String get() = if (isDir) "" else name.substringAfterLast('.', "").lowercase()
    val kind: Kind get() = if (isDir) Kind.FOLDER else Kinds.of(extension)
    val file: File get() = File(path)
}

object Kinds {
    private val map: Map<String, Kind> = buildMap {
        "jpg jpeg png gif webp bmp heic heif avif svg".split(' ').forEach { put(it, Kind.IMAGE) }
        "mp4 mkv webm avi mov 3gp m4v".split(' ').forEach { put(it, Kind.VIDEO) }
        "mp3 flac ogg opus wav m4a aac wma".split(' ').forEach { put(it, Kind.AUDIO) }
        "pdf doc docx odt xls xlsx ods ppt pptx odp txt md rtf csv epub".split(' ').forEach { put(it, Kind.DOCUMENT) }
        "zip rar 7z tar gz xz bz2 jar apk".split(' ').forEach { put(it, Kind.ARCHIVE) }
        "kt java rs py js ts json xml html css c cpp h sh yml yaml toml sql gradle".split(' ').forEach { put(it, Kind.CODE) }
    }
    fun of(ext: String): Kind = map[ext] ?: Kind.OTHER
}

enum class SortBy { NAME, SIZE, DATE, TYPE }

data class Sort(val by: SortBy = SortBy.NAME, val descending: Boolean = false, val foldersFirst: Boolean = true) {
    fun apply(list: List<Entry>): List<Entry> {
        val base: Comparator<Entry> = when (by) {
            SortBy.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortBy.SIZE -> compareBy { it.size }
            SortBy.DATE -> compareBy { it.modified }
            SortBy.TYPE -> compareBy<Entry> { it.extension }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        }
        val dir = if (descending) base.reversed() else base
        val cmp = if (foldersFirst) compareBy<Entry> { !it.isDir }.then(dir) else dir
        return list.sortedWith(cmp)
    }
}
