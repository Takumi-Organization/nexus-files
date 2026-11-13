package dev.nexus.files

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nexus.core.*
import java.io.File

@Composable
fun HomeScreen(home: HomeViewModel, onOpen: (Entry) -> Unit, onOpenDir: (File) -> Unit, onCategory: (Kind) -> Unit) {
    val d by home.dash.collectAsStateWithLifecycle()
    val lib by home.lib.collectAsStateWithLifecycle()
    val coll by home.collection.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { home.refreshDash() }
    BackHandler(enabled = coll != null) { home.openCollection(null) }

    coll?.let { c ->
        val entries = remember(c, lib) { c.paths.map(::File).filter { it.exists() }.map(LocalFs::entry) }
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { home.openCollection(null) }) { Text("←") }
                Text(c.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            if (entries.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Vacío", color = C.dim) }
            else LazyColumn { items(entries, key = { it.path }) { e -> EntryRow(e, false, { onOpen(e) }, {}, star = e.path in lib.favorites) } }
        }
        return
    }

    val dirs = remember {
        listOf(Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_DCIM, Environment.DIRECTORY_DOCUMENTS, Environment.DIRECTORY_PICTURES, Environment.DIRECTORY_MUSIC, Environment.DIRECTORY_MOVIES)
            .map { File(home.primaryRoot, it) }.filter { it.isDirectory }
    }
    val recentOpened = remember(lib.recents) { lib.recents.map(::File).filter { it.exists() }.take(8).map(LocalFs::entry) }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Text("NEXUS", fontWeight = FontWeight.Black, fontSize = 22.sp, color = C.accent, modifier = Modifier.padding(16.dp)) }
        item { StorageCard(d) }
        item { Section("Categorías") }
        item {
            val c = d.cats
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tile("Imágenes", c[Kind.IMAGE], d.loaded, C.kind(Kind.IMAGE), Modifier.weight(1f)) { onCategory(Kind.IMAGE) }
                    Tile("Vídeos", c[Kind.VIDEO], d.loaded, C.kind(Kind.VIDEO), Modifier.weight(1f)) { onCategory(Kind.VIDEO) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tile("Audio", c[Kind.AUDIO], d.loaded, C.kind(Kind.AUDIO), Modifier.weight(1f)) { onCategory(Kind.AUDIO) }
                    Tile("Documentos", c[Kind.DOCUMENT], d.loaded, C.kind(Kind.DOCUMENT), Modifier.weight(1f)) { onCategory(Kind.DOCUMENT) }
                }
                Tile("Descargas", d.downloads, d.loaded, C.kind(Kind.ARCHIVE), Modifier.fillMaxWidth()) { onOpenDir(File(home.primaryRoot, Environment.DIRECTORY_DOWNLOADS)) }
            }
        }
        if (lib.favorites.isNotEmpty()) {
            item { Section("Favoritos") }
            item { Chips(lib.favorites.take(20).map { File(it).name to { File(it).let { f -> if (f.isDirectory) onOpenDir(f) else onOpen(LocalFs.entry(f)) } } }) }
        }
        if (lib.allTags.isNotEmpty()) {
            item { Section("Etiquetas") }
            item { Chips(lib.allTags.map { t -> "# $t" to { home.openCollection(PathGroup("# $t", lib.pathsWithTag(t))) } }) }
        }
        item { Section("Carpetas importantes") }
        item { Chips(dirs.map { it.name to { onOpenDir(it) } }) }
        if (recentOpened.isNotEmpty()) {
            item { Section("Abiertos recientemente") }
            items(recentOpened, key = { "r" + it.path }) { e -> EntryRow(e, false, { onOpen(e) }, {}, star = e.path in lib.favorites) }
        }
        item { Section("Añadidos recientemente") }
        if (d.recentAdded.isEmpty()) item { Text(if (d.loaded) "Nada que mostrar (según el índice de Android)" else "Cargando…", color = C.dim, modifier = Modifier.padding(16.dp)) }
        items(d.recentAdded, key = { "a" + it.path }) { e -> EntryRow(e, false, { onOpen(e) }, {}, star = e.path in lib.favorites) }
        if (lib.favorites.isNotEmpty()) item { TextButton(onClick = { home.openCollection(PathGroup("Favoritos", lib.favorites)) }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Ver todos los favoritos") } }
    }
}

@Composable private fun Section(t: String) = Text(t.uppercase(), color = C.dim, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp))

@Composable private fun Chips(items: List<Pair<String, () -> Unit>>) = LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    items(items) { (l, f) -> AssistChip(onClick = f, label = { Text(l, maxLines = 1) }) }
}

@Composable private fun StorageCard(d: Dash) {
    val used = (d.total - d.free).coerceAtLeast(0); val frac = if (d.total > 0) used.toFloat() / d.total else 0f
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.surface).padding(16.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(humanSize(used), fontSize = 30.sp, fontWeight = FontWeight.Black)
            Text("  de ${humanSize(d.total)}", color = C.dim, modifier = Modifier.padding(bottom = 4.dp))
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(C.surface2)) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(if (frac > 0.9f) C.danger else C.accent))
        }
        Spacer(Modifier.height(8.dp))
        Text("${humanSize(d.free)} disponibles · ${(frac * 100).toInt()}% usado", color = C.dim, fontSize = 13.sp)
    }
}

@Composable private fun Tile(title: String, s: CatStat?, loaded: Boolean, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.14f)).clickable(onClick = onClick).padding(14.dp)) {
        Text(title, color = color, fontWeight = FontWeight.Bold)
        Text(if (!loaded) "…" else "${s?.count ?: 0} · ${humanSize(s?.size ?: 0)}", color = C.dim, fontSize = 12.sp)
    }
}

@Composable
fun SearchScreen(home: HomeViewModel, currentDir: File?, onOpen: (Entry) -> Unit) {
    val s by home.search.collectAsStateWithLifecycle()
    val lib by home.lib.collectAsStateWithLifecycle()
    var open by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    fun upd(f: (SearchUi) -> SearchUi) = home.updateSearch(f, currentDir)
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding()) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Buscar", fontWeight = FontWeight.Black, fontSize = 22.sp, color = C.accent)
                OutlinedTextField(s.name, { v -> upd { it.copy(name = v) } }, label = { Text("Nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { open = !open }) { Text(if (open) "Ocultar filtros ▴" else "Filtros ▾") }
                if (open) {
                OutlinedTextField(s.ext, { v -> upd { it.copy(ext = v) } }, label = { Text("Extensión (pdf, jpg…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Tipo", color = C.dim, fontSize = 12.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(s.kind == null, { upd { it.copy(kind = null) } }, { Text("Todos") }) }
                    items(listOf(Kind.FOLDER to "Carpetas", Kind.IMAGE to "Imágenes", Kind.VIDEO to "Vídeos", Kind.AUDIO to "Audio", Kind.DOCUMENT to "Documentos", Kind.ARCHIVE to "Comprimidos", Kind.CODE to "Código")) { (k, l) ->
                        FilterChip(s.kind == k, { upd { it.copy(kind = k) } }, { Text(l) })
                    }
                }
                Text("Tamaño", color = C.dim, fontSize = 12.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(SizeMin.entries) { z -> FilterChip(s.size == z, { upd { it.copy(size = z) } }, { Text(z.label) }) } }
                Text("Modificado en", color = C.dim, fontSize = 12.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(DateRange.entries) { z -> FilterChip(s.date == z, { upd { it.copy(date = z) } }, { Text(z.label) }) } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!s.inCurrent, { upd { it.copy(inCurrent = false) } }, { Text("Todo el almacenamiento") })
                    FilterChip(s.inCurrent, { upd { it.copy(inCurrent = true) } }, { Text("Carpeta actual") }, enabled = currentDir != null)
                }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(s.hidden, { v -> upd { it.copy(hidden = v) } }); Text("Incluir ocultos") }
                }
                if (s.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                s.result?.let { r ->
                    Text("${r.entries.size}${if (r.truncated) "+" else ""} resultado(s)" + (if (r.unreadable > 0) " · ${r.unreadable} carpeta(s) sin permiso de lectura" else "") + (if (r.truncated) " · mostrando los primeros; afina la búsqueda" else ""), color = C.dim, fontSize = 12.sp)
                } ?: Text("Escribe o elige un filtro", color = C.dim, fontSize = 12.sp)
            }
        }
        items(s.result?.entries.orEmpty(), key = { it.path }) { e -> EntryRow(e, false, { onOpen(e) }, {}, star = e.path in lib.favorites) }
    }
}
