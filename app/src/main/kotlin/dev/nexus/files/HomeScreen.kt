package dev.nexus.files

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nexus.core.*
import java.io.File

private class Block(val key: String, val label: String, val size: Long, val count: Int?, val kind: Kind?, val onClick: (() -> Unit)?)

@Composable
fun HomeScreen(home: HomeViewModel, onOpen: (Entry) -> Unit, onOpenDir: (File) -> Unit, onCategory: (Kind) -> Unit, onSettings: () -> Unit = {}) {
    val d by home.dash.collectAsStateWithLifecycle()
    val lib by home.lib.collectAsStateWithLifecycle()
    val coll by home.collection.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { home.refreshDash() }
    BackHandler(enabled = coll != null) { home.openCollection(null) }

    coll?.let { c ->
        val entries = remember(c, lib) { c.paths.map(::File).filter { it.exists() }.map(LocalFs::entry) }
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("COLECCIÓN", c.title, trailing = { NxBtn("Volver", { home.openCollection(null) }, kind = BtnKind.GHOST, small = true) })
            if (entries.isEmpty()) NxEmpty(Kind.OTHER, "Vacío", "No hay elementos en esta colección.")
            else LazyColumn { items(entries, key = { it.path }) { e -> EntryRow(e, false, { onOpen(e) }, {}, star = e.path in lib.favorites) } }
        }
        return
    }

    val dirs = remember {
        listOf(Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_DCIM, Environment.DIRECTORY_DOCUMENTS, Environment.DIRECTORY_PICTURES, Environment.DIRECTORY_MUSIC, Environment.DIRECTORY_MOVIES)
            .map { File(home.primaryRoot, it) }.filter { it.isDirectory }
    }
    val recentOpened = remember(lib.recents) { lib.recents.map(::File).filter { it.exists() }.take(8).map(LocalFs::entry) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 130.dp)) {
        item {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 24.dp, end = 14.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(Squircle(11.dp)).background(C.accentBrush), Alignment.Center) { Glyph(Kind.FOLDER, 22.dp, ink = C.onAccent) }
                Spacer(Modifier.width(10.dp)); Text("Nexus", style = T.title(34), modifier = Modifier.weight(1f))
                NxBtn("Ajustes", onSettings, kind = BtnKind.GHOST, small = true)
            }
        }
        item { Hero(d) }
        item { YardMap(d, home, onCategory, onOpenDir) }
        if (lib.favorites.isNotEmpty()) {
            item { Spacer(Modifier.height(26.dp)); Eyebrow("Favoritos"); Spacer(Modifier.height(10.dp)) }
            item { Chips(lib.favorites.take(20).map { File(it).name to { File(it).let { f -> if (f.isDirectory) onOpenDir(f) else onOpen(LocalFs.entry(f)) } } }) }
        }
        if (lib.allTags.isNotEmpty()) {
            item { Spacer(Modifier.height(22.dp)); Eyebrow("Etiquetas"); Spacer(Modifier.height(8.dp)) }
            item { Chips(lib.allTags.map { t -> "# $t" to { home.openCollection(PathGroup("# $t", lib.pathsWithTag(t))) } }) }
        }
        item { Spacer(Modifier.height(22.dp)); Eyebrow("Accesos directos"); Spacer(Modifier.height(8.dp)) }
        item { Chips(dirs.map { it.name to { onOpenDir(it) } }) }
        if (recentOpened.isNotEmpty()) {
            item { Spacer(Modifier.height(26.dp)); Eyebrow("Abiertos hace poco"); Spacer(Modifier.height(8.dp)) }
            items(recentOpened, key = { "r" + it.path }) { e -> EntryRow(e, false, { onOpen(e) }, {}, star = e.path in lib.favorites) }
        }
        item { Spacer(Modifier.height(26.dp)); Eyebrow("Añadidos hace poco"); Spacer(Modifier.height(8.dp)) }
        if (d.recentAdded.isEmpty()) item { Text(if (d.loaded) "NADA QUE MOSTRAR SEGÚN EL ÍNDICE DE ANDROID" else "CARGANDO…", style = T.label(11), modifier = Modifier.padding(20.dp)) }
        itemsIndexed(d.recentAdded, key = { _, e -> "a" + e.path }) { i, e -> EntryRow(e, false, { onOpen(e) }, {}, star = e.path in lib.favorites, index = i) }
        if (lib.favorites.isNotEmpty()) item { NxBtn("Ver todos los favoritos", { home.openCollection(PathGroup("Favoritos", lib.favorites)) }, kind = BtnKind.GHOST, small = true, modifier = Modifier.padding(20.dp)) }
    }
}

private fun <T> androidx.compose.foundation.lazy.LazyListScope.itemsIndexed(items: List<T>, key: (Int, T) -> Any, content: @Composable (Int, T) -> Unit) =
    items(items.size, key = { key(it, items[it]) }) { content(it, items[it]) }

@Composable private fun Chips(items: List<Pair<String, () -> Unit>>) = LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    items(items) { (l, f) -> NxChip(l, false, f) }
}

/** Tarjeta principal: el número enorme (rueda al cargar), barra de capacidad segmentada por tipo y leyenda. */
@Composable private fun Hero(d: Dash) {
    val used = (d.total - d.free).coerceAtLeast(0)
    val (n, u) = sizeParts(used)
    val frac = if (d.total > 0) used.toFloat() / d.total else 0f
    val tot = sizeParts(d.total)
    Column(Modifier.padding(horizontal = 14.dp).padding(top = 18.dp).clip(Squircle(34.dp)).background(C.surface).border(1.dp, C.hair, Squircle(34.dp))) {
        Box {
            Column(Modifier.padding(horizontal = 22.dp, vertical = 22.dp)) {
                Text("Almacenamiento interno", style = T.label(14, C.dim))
                Row(verticalAlignment = Alignment.Bottom) {
                    RollingText(if (d.loaded) n else "···", T.numeral(150))
                    Text(" $u", style = T.title(48, C.dim), modifier = Modifier.padding(bottom = 16.dp))
                }
                Text("en uso de ${tot.first} ${tot.second} · ${sizeParts(d.free).let { "${it.first} ${it.second}" }} libres", style = TextStyle(fontFamily = Fonts.display, fontSize = 15.sp, color = if (frac > 0.9f) C.danger else C.dim))
                Spacer(Modifier.height(18.dp))
                CapacityBar(d)
            }
        }
    }
}

/** Barra de capacidad estilo «Almacenamiento» de Apple: segmentos de color por tipo que crecen con muelle. */
@Composable private fun CapacityBar(d: Dash) {
    val reduce = rememberReduceMotion()
    val total = d.total.coerceAtLeast(1).toFloat()
    val segs = listOf(Kind.IMAGE to (d.cats[Kind.IMAGE]?.size ?: 0L), Kind.VIDEO to (d.cats[Kind.VIDEO]?.size ?: 0L), Kind.AUDIO to (d.cats[Kind.AUDIO]?.size ?: 0L), Kind.DOCUMENT to (d.cats[Kind.DOCUMENT]?.size ?: 0L))
    val known = segs.sumOf { it.second }; val used = (d.total - d.free).coerceAtLeast(0)
    val p by animateFloatAsState(if (d.loaded) 1f else 0f, if (reduce) snap() else spring(0.8f, 90f), label = "cap")
    Row(Modifier.fillMaxWidth().height(16.dp).background(C.surface3), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        segs.forEach { (k, sz) -> if (sz > 0) Box(Modifier.weight((sz / total * p).coerceAtLeast(0.001f)).fillMaxHeight().background(C.kind(k))) }
        val other = (used - known).coerceAtLeast(0); if (other > 0) Box(Modifier.weight((other / total * p).coerceAtLeast(0.001f)).fillMaxHeight().background(C.faint))
        Spacer(Modifier.weight((d.free / total + (1f - p)).coerceAtLeast(0.001f)))
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        listOf(Kind.IMAGE to "Imágenes", Kind.VIDEO to "Vídeo", Kind.AUDIO to "Audio", Kind.DOCUMENT to "Docs").forEach { (k, l) ->
            Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(8.dp).background(C.kind(k))); Spacer(Modifier.width(5.dp)); Text(l, style = TextStyle(fontFamily = Fonts.display, fontSize = 12.sp, color = C.dim)) }
        }
    }
}

/** Bento: baldosas de categoría (número grande + tamaño) con tinte radial del color del tipo; entran con muelle escalonado. */
@Composable private fun YardMap(d: Dash, home: HomeViewModel, onCategory: (Kind) -> Unit, onOpenDir: (File) -> Unit) {
    val img = d.cats[Kind.IMAGE]; val vid = d.cats[Kind.VIDEO]; val aud = d.cats[Kind.AUDIO]; val doc = d.cats[Kind.DOCUMENT]
    Spacer(Modifier.height(12.dp))
    Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("Imágenes", Kind.IMAGE, img, d.loaded, 0, Modifier.weight(1f).height(238.dp), big = true) { onCategory(Kind.IMAGE) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("Vídeo", Kind.VIDEO, vid, d.loaded, 1, Modifier.fillMaxWidth().height(114.dp)) { onCategory(Kind.VIDEO) }
                Tile("Audio", Kind.AUDIO, aud, d.loaded, 2, Modifier.fillMaxWidth().height(114.dp)) { onCategory(Kind.AUDIO) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("Documentos", Kind.DOCUMENT, doc, d.loaded, 3, Modifier.weight(1f).height(114.dp)) { onCategory(Kind.DOCUMENT) }
            Tile("Descargas", Kind.ARCHIVE, d.downloads, d.loaded, 4, Modifier.weight(1f).height(114.dp)) { onOpenDir(File(home.primaryRoot, Environment.DIRECTORY_DOWNLOADS)) }
        }
    }
}

@Composable private fun Tile(label: String, kind: Kind, s: CatStat?, loaded: Boolean, index: Int, modifier: Modifier, big: Boolean = false, onClick: () -> Unit) {
    val reduce = rememberReduceMotion()
    val col = C.kind(kind); val dark = Nx.pal.dark
    val p = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduce) { kotlinx.coroutines.delay(120L + index * 70L); p.animateTo(1f, spring(0.68f, 300f)) } }
    Box(modifier.graphicsLayer { val sc = 0.86f + 0.14f * p.value; scaleX = sc; scaleY = sc; alpha = p.value.coerceIn(0f, 1f) }
        
        .clip(Squircle(28.dp)).background(C.surface)
        .border(1.dp, C.hair, Squircle(28.dp)).nxClick(onClick).padding(if (big) 18.dp else 14.dp)) {
        Column(Modifier.fillMaxSize()) {
            val (n, u) = sizeParts(s?.size ?: 0)
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(if (big) 46.dp else 36.dp).clip(Squircle(if (big) 15.dp else 12.dp)).background(C.surface2).border(1.dp, C.hair, Squircle(if (big) 15.dp else 12.dp)), Alignment.Center) { Glyph(kind, if (big) 33.dp else 26.dp, ink = C.text) }
                if (!big) { Spacer(Modifier.weight(1f)); Text(if (!loaded) "·" else "${s?.count ?: 0}", style = T.numeral(54), maxLines = 1) }
            }
            Spacer(Modifier.weight(1f))
            if (big) Text(if (!loaded) "·" else "${s?.count ?: 0}", style = T.numeral(96), maxLines = 1)
            Text(label, style = TextStyle(fontFamily = Fonts.display, fontWeight = FontWeight.Medium, fontSize = if (big) 18.sp else 15.sp, color = C.text), maxLines = 1)
            if (loaded) Text("$n $u", style = TextStyle(fontFamily = Fonts.display, fontSize = 13.sp, color = C.dim), maxLines = 1)
        }
    }
}

@Composable
fun SearchScreen(home: HomeViewModel, currentDir: File?, onOpen: (Entry) -> Unit) {
    val s by home.search.collectAsStateWithLifecycle()
    val lib by home.lib.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    fun upd(f: (SearchUi) -> SearchUi) = home.updateSearch(f, currentDir)
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column {
                ScreenHeader("03", "Buscar")
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    NxField(s.name, { v -> upd { it.copy(name = v) } }, "Nombre contiene")
                    NxBtn(if (open) "Ocultar filtros ▴" else "Filtros ▾", { open = !open }, kind = BtnKind.GHOST, small = true)
                    if (open) {
                        NxField(s.ext, { v -> upd { it.copy(ext = v) } }, "Extensión (pdf, jpg…)")
                        FilterRow("Tipo") {
                            item { NxChip("Todos", s.kind == null, { upd { it.copy(kind = null) } }) }
                            items(listOf(Kind.FOLDER to "Carpetas", Kind.IMAGE to "Imágenes", Kind.VIDEO to "Vídeos", Kind.AUDIO to "Audio", Kind.DOCUMENT to "Documentos", Kind.ARCHIVE to "Comprimidos", Kind.CODE to "Código")) { (k, l) -> NxChip(l, s.kind == k, { upd { it.copy(kind = k) } }) }
                        }
                        FilterRow("Tamaño") { items(SizeMin.entries) { z -> NxChip(z.label, s.size == z, { upd { it.copy(size = z) } }) } }
                        FilterRow("Modificado en") { items(DateRange.entries) { z -> NxChip(z.label, s.date == z, { upd { it.copy(date = z) } }) } }
                        FilterRow("Dónde") {
                            item { NxChip("Todo el almacenamiento", !s.inCurrent, { upd { it.copy(inCurrent = false) } }) }
                            item { NxChip("Carpeta actual", s.inCurrent, { upd { it.copy(inCurrent = true) } }, enabled = currentDir != null) }
                        }
                        NxToggle(s.hidden, { v -> upd { it.copy(hidden = v) } }, "Incluir ocultos")
                    }
                    if (s.running) NxProgress(null)
                    Text(s.result?.let { r -> "${r.entries.size}${if (r.truncated) "+" else ""} RESULTADO(S)" + (if (r.unreadable > 0) " · ${r.unreadable} CARPETA(S) SIN PERMISO" else "") + (if (r.truncated) " · AFINA LA BÚSQUEDA" else "") } ?: "ESCRIBE O ELIGE UN FILTRO", style = T.label(11))
                }
            }
        }
        if (s.result?.entries.isNullOrEmpty() && s.result != null && !s.running) item { NxEmpty(Kind.OTHER, "Sin resultados", "Prueba con otro nombre o menos filtros.") }
        items(s.result?.entries.orEmpty(), key = { it.path }) { e -> EntryRow(e, false, { onOpen(e) }, {}, star = e.path in lib.favorites) }
    }
}

@Composable private fun FilterRow(label: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Text(label.uppercase(), style = T.label(10), modifier = Modifier.padding(top = 6.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}
