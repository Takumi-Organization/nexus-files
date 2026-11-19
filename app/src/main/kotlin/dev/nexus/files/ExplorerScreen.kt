package dev.nexus.files

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.nexus.core.*
import java.io.File
import java.text.DateFormat
import java.util.Date

private sealed interface Dlg {
    data object NewFolder : Dlg
    data class Rename(val e: Entry) : Dlg
    data class Delete(val paths: Set<String>, val names: List<String>) : Dlg
    data object Sort : Dlg
    data class Zip(val paths: Set<String>, val suggested: String) : Dlg
    data class Tags(val paths: List<String>) : Dlg
    data class Info(val e: Entry) : Dlg
}

fun humanSize(b: Long): String {
    if (b < 1024) return "$b B"
    val u = arrayOf("KB", "MB", "GB", "TB"); var v = b.toDouble(); var i = -1
    do { v /= 1024; i++ } while (v >= 1024 && i < u.lastIndex)
    return "%.1f %s".format(v, u[i])
}

@Composable
fun PermissionGate(onLimited: () -> Unit, onGrant: () -> Unit) {
    Box(Modifier.fillMaxSize().background(C.bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(28.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                Box(Modifier.size(72.dp).clip(Squircle(22.dp)).background(C.accentBrush), Alignment.Center) { Glyph(Kind.FOLDER, 46.dp, ink = C.onAccent) }
                Spacer(Modifier.height(36.dp))
                Text("Acceso a\ntodos tus\narchivos", style = T.title(58), modifier = Modifier.enter(0))
                Spacer(Modifier.height(16.dp))
                Text("Android pide una concesión explícita para que un gestor de archivos vea todo el almacenamiento. NEXUS solo la usa cuando tú lo pides.", style = TextStyle(fontFamily = Fonts.display, fontSize = 16.sp, color = C.dim, lineHeight = 23.sp), modifier = Modifier.enter(1))
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NxBtn("Abrir Ajustes de Android", onGrant, Modifier.fillMaxWidth().enter(2))
                NxBtn("Usar solo carpetas elegidas", onLimited, Modifier.fillMaxWidth().enter(3), kind = BtnKind.GHOST)
                Text("Sin acceso total puedes trabajar con las carpetas que elijas y con servidores remotos. Podrás concederlo más tarde.", style = TextStyle(fontFamily = Fonts.display, fontSize = 13.sp, color = C.dim), modifier = Modifier.padding(horizontal = 6.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExplorerScreen(onSettings: () -> Unit, onShare: (List<File>) -> Unit, onOpenExternal: (File) -> Boolean, onView: (Entry) -> Unit, vm: ExplorerViewModel = viewModel(), home: HomeViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val lib by home.lib.collectAsStateWithLifecycle()
    val h = rememberHaptics()
    var dlg by remember { mutableStateOf<Dlg?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.start() }
    LaunchedEffect(s.message) { s.message?.let { toast = it; vm.consumeMessage(); kotlinx.coroutines.delay(3200); toast = null } }
    BackHandler(enabled = s.selection.isNotEmpty() || (s.dir != null && s.roots.none { it.dir == s.dir })) { vm.up() }

    val list = remember(s.entries, s.sort, s.showHidden) { vm.visible }
    val selected = list.filter { it.path in s.selection }
    val selecting = s.selection.isNotEmpty()

    fun click(e: Entry) {
        if (selecting) { h.tick(); vm.toggle(e.path); return }
        if (e.isDir) vm.open(e.file) else onView(e)
    }
    fun long(e: Entry) { vm.toggle(e.path) }

    SideEffect { Nx.hideNav = selecting }
    DisposableEffect(Unit) { onDispose { Nx.hideNav = false } }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            AnimatedContent(selecting, transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(90)) }, label = "hdr") { sel ->
                if (!sel) Header(s, vm, list.size, onSettings, { dlg = Dlg.Sort }, { dlg = Dlg.NewFolder })
                else SelectionHeader(selected.size, vm::clearSelection, vm::selectAll)
            }
            val depth = s.dir?.path?.count { it == '/' } ?: 0
            var lastDepth by remember { mutableIntStateOf(depth) }
            val slide = remember { Animatable(1f) }
            var dirSign by remember { mutableIntStateOf(1) }
            val reduceNav = rememberReduceMotion()
            LaunchedEffect(s.dir?.path) {
                dirSign = if (depth >= lastDepth) 1 else -1; lastDepth = depth
                if (!reduceNav) { slide.snapTo(0f); slide.animateTo(1f, spring(0.82f, 420f)) }
            }
            Box(Modifier.weight(1f).graphicsLayer { translationX = dirSign * (1f - slide.value) * 56.dp.toPx(); alpha = 0.25f + 0.75f * slide.value }) {
                when {
                    s.loading -> Skeleton()
                    s.error != null -> NxEmpty(Kind.FOLDER, "Sin acceso", s.error ?: "No se puede leer la carpeta.", danger = true) { NxBtn("Reintentar", vm::reload) }
                    list.isEmpty() -> NxEmpty(Kind.FOLDER, "Vacía", "Esta carpeta no contiene nada${if (!s.showHidden) " visible" else ""}.") { NxBtn("Nueva carpeta", { dlg = Dlg.NewFolder }, kind = BtnKind.GHOST, small = true) }
                    s.grid -> LazyVerticalGrid(GridCells.Adaptive(112.dp), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 120.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        itemsIndexed(list, key = { _, e -> e.path }) { i, e -> GridCell(e, e.path in s.selection, { click(e) }, { long(e) }, i, Modifier.animateItem(fadeInSpec = null)) }
                    }
                    else -> LazyColumn(contentPadding = PaddingValues(bottom = 120.dp)) { items(list, key = { e -> e.path }) { e -> EntryRow(e, e.path in s.selection, { click(e) }, { long(e) }, star = e.path in lib.favorites) } }
                }
            }
            BottomPanel(s, vm)
            AnimatedVisibility(selecting, enter = slideInVertically(spring(0.8f, 420f)) { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                ActionRail(selected.size, onCopy = { vm.copyToClip(false) }, onCut = { vm.copyToClip(true) },
                    onShare = { onShare(selected.map { it.file }) }, onRename = if (selected.size == 1) ({ dlg = Dlg.Rename(selected[0]) }) else null,
                    onDelete = { dlg = Dlg.Delete(s.selection, selected.map { it.name }) }, onInfo = if (selected.size == 1) ({ dlg = Dlg.Info(selected[0]) }) else null,
                    onFav = { home.toggleFavorite(s.selection); vm.clearSelection() },
                    onZip = { dlg = Dlg.Zip(s.selection, (selected.singleOrNull()?.name?.substringBeforeLast('.') ?: s.dir?.name ?: "archivo") + ".zip") }, onTags = { dlg = Dlg.Tags(s.selection.toList()) })
            }
        }
        AnimatedVisibility(toast != null, Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp, start = 16.dp, end = 16.dp), enter = slideInVertically(spring(0.7f, 400f)) { it } + fadeIn(), exit = fadeOut()) {
            Box(Modifier.clip(Chamfer(10.dp)).background(C.text).padding(horizontal = 16.dp, vertical = 12.dp)) { Text(toast.orEmpty(), style = T.mono(12, C.bg)) }
        }
    }

    when (val d = dlg) {
        Dlg.NewFolder -> NxNameDialog("Nueva carpeta", "", "Crear", ::nameError, { dlg = null }) { vm.mkdir(it); dlg = null }
        is Dlg.Rename -> NxNameDialog("Renombrar", d.e.name, "Renombrar", ::nameError, { dlg = null }) { vm.rename(d.e.path, it) { from, to -> home.moved(from, to) }; dlg = null }
        is Dlg.Delete -> NxConfirm("¿Eliminar ${d.paths.size}?", d.names.take(6).joinToString("\n") + (if (d.names.size > 6) "\n… y ${d.names.size - 6} más" else "") + "\n\nPermanente: no hay papelera.", "Eliminar", true, { dlg = null }) { vm.delete(d.paths); h.confirm(); dlg = null }
        Dlg.Sort -> SortDialog(s, vm) { dlg = null }
        is Dlg.Zip -> NxNameDialog("Comprimir en ZIP", d.suggested, "Comprimir", ::nameError, { dlg = null }) { vm.compress(d.paths, it); dlg = null }
        is Dlg.Tags -> TagsDialog(d.paths, lib, home) { dlg = null }
        is Dlg.Info -> DetailsDialog(d.e) { dlg = null }
        null -> {}
    }
}

private fun nameError(v: String): String? = (Names.validate(v) as? Names.Result.Invalid)?.reason

/** Cabecera: ruta como migas tipográficas y el nombre de la carpeta en tamaño enorme. */
@Composable private fun Header(s: UiState, vm: ExplorerViewModel, count: Int, onSettings: () -> Unit, onSort: () -> Unit, onNew: () -> Unit) {
    val dir = s.dir
    val root = dir?.let { d -> s.roots.firstOrNull { d.path.startsWith(it.dir.path) } }
    val parts = buildList {
        if (root != null && dir != null) {
            add(root.label.replace("Almacenamiento ", "").replaceFirstChar { it.uppercase() } to root.dir)
            var cur = root.dir
            dir.path.removePrefix(root.dir.path).split('/').filter { it.isNotEmpty() }.forEach { cur = File(cur, it); add(it to cur) }
        }
    }
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 10.dp)) {
        if (s.roots.size > 1) LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(s.roots) { r -> NxChip(r.label, r == root, { vm.open(r.dir) }) }
        }
        val st = rememberLazyListState()
        LaunchedEffect(parts.size) { if (parts.isNotEmpty()) st.animateScrollToItem(parts.lastIndex) }
        LazyRow(state = st, contentPadding = PaddingValues(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            items(parts.size) { i ->
                val (label, f) = parts[i]; val last = i == parts.lastIndex
                Text(label, style = TextStyle(fontFamily = Fonts.display, fontWeight = if (last) FontWeight.SemiBold else FontWeight.Medium, fontSize = 14.sp, color = if (last) C.accent else C.dim), modifier = Modifier.clip(CircleShape).clickable { vm.open(f) }.padding(horizontal = 8.dp, vertical = 10.dp))
                if (!last) Text("›", style = TextStyle(fontSize = 16.sp, color = C.faint))
            }
        }
        AnimatedContent(parts.lastOrNull()?.first ?: "—", transitionSpec = { (slideInVertically(spring(0.85f, 380f)) { it / 2 } + fadeIn(tween(160))) togetherWith (slideOutVertically(tween(140)) { -it / 3 } + fadeOut(tween(100))) }, label = "title") { t ->
            Text(if (parts.size <= 1) "Archivos" else t, style = T.title(56), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp))
        }
        Text("$count elementos${if (s.showHidden) " · con ocultos" else ""}", style = TextStyle(fontFamily = Fonts.display, fontSize = 14.sp, color = C.dim), modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { NxChip(if (s.grid) "Lista" else "Cuadrícula", false, { vm.setGrid(!s.grid) }) }
            item { NxChip("Orden · ${when (s.sort.by) { SortBy.NAME -> "nombre"; SortBy.SIZE -> "tamaño"; SortBy.DATE -> "fecha"; SortBy.TYPE -> "tipo" }}${if (s.sort.descending) " ↓" else " ↑"}", false, onSort) }
            item { NxChip("+ Carpeta", false, onNew) }
            item { NxChip("Ocultos", s.showHidden, { vm.setHidden(!s.showHidden) }) }
            item { NxChip("Actualizar", false, { vm.reload() }) }
            item { NxChip("Ajustes", false, onSettings) }
        }
    }
}

/** Cabecera de selección: contador que rueda sobre un fondo de acento con degradado tonal. */
@Composable private fun SelectionHeader(n: Int, onClose: () -> Unit, onAll: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(C.accentBrush).statusBarsPadding().padding(start = 24.dp, end = 14.dp, top = 12.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        RollingText("$n", T.numeral(84, C.onAccent))
        Spacer(Modifier.width(12.dp))
        Text(if (n == 1) "elemento\nseleccionado" else "elementos\nseleccionados", style = TextStyle(fontFamily = Fonts.display, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = C.onAccent.copy(alpha = 0.9f), lineHeight = 18.sp), modifier = Modifier.weight(1f))
        NxBtn("Todo", onAll, kind = BtnKind.INK, small = true); Spacer(Modifier.width(8.dp)); NxBtn("Listo", onClose, kind = BtnKind.INK, small = true)
    }
}

/** Raíl de acciones contextual: flota sobre el contenido con sombra y esquinas continuas. */
@Composable private fun ActionRail(n: Int, onCopy: () -> Unit, onCut: () -> Unit, onShare: () -> Unit, onRename: (() -> Unit)?, onDelete: () -> Unit, onInfo: (() -> Unit)?, onFav: () -> Unit, onZip: () -> Unit, onTags: () -> Unit) {
    Box(Modifier.navigationBarsPadding().padding(horizontal = 12.dp).padding(bottom = 8.dp).clip(Squircle(26.dp)).background(C.surface2).border(1.dp, C.hair, Squircle(26.dp))) {
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { NxBtn("Copiar", onCopy, kind = BtnKind.PRIMARY, small = true) }
            item { NxBtn("Cortar", onCut, kind = BtnKind.GHOST, small = true) }
            item { NxBtn("Comprimir", onZip, kind = BtnKind.GHOST, small = true) }
            item { NxBtn("Compartir", onShare, kind = BtnKind.GHOST, small = true) }
            item { NxBtn("★ Favorito", onFav, kind = BtnKind.GHOST, small = true) }
            item { NxBtn("Etiquetas", onTags, kind = BtnKind.GHOST, small = true) }
            onRename?.let { item { NxBtn("Renombrar", it, kind = BtnKind.GHOST, small = true) } }
            onInfo?.let { item { NxBtn("Detalles", it, kind = BtnKind.GHOST, small = true) } }
            item { NxBtn("Eliminar", onDelete, kind = BtnKind.DANGER, small = true) }
        }
    }
}

@Composable private fun BottomPanel(s: UiState, vm: ExplorerViewModel) {
    Column(Modifier.padding(bottom = if (Nx.hideNav) 0.dp else 90.dp)) {
        s.op?.let { op ->
            val speed = rememberSpeed(op.progress?.doneBytes ?: 0L)
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).clip(Squircle(24.dp)).background(C.surface2).border(1.dp, C.hair, Squircle(24.dp)).padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(op.title, style = T.label(12, C.accent))
                        Text(op.progress?.current ?: "Preparando…", style = T.name(20), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val (n, u) = sizeParts(speed.toLong())
                    Column(horizontalAlignment = Alignment.End) { RollingText(n, T.numeral(48)); Text("$u/s", style = T.label(12)) }
                }
                Spacer(Modifier.height(12.dp))
                NxProgress(op.progress?.fraction?.takeIf { (op.progress?.totalBytes ?: 0) > 0 || (op.progress?.totalItems ?: 0) > 0 })
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(op.progress?.let { "${humanSize(it.doneBytes)} de ${humanSize(it.totalBytes)}" } ?: "", style = TextStyle(fontFamily = Fonts.display, fontSize = 13.sp, color = C.dim), modifier = Modifier.weight(1f))
                    NxBtn("Parar", vm::cancelOp, kind = BtnKind.GHOST, small = true)
                }
            }
        }
        s.clip?.let { c ->
            if (s.op == null) Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth().clip(Squircle(24.dp)).background(C.accentBrush).padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                RollingText("${c.paths.size}", T.numeral(44, C.onAccent)); Spacer(Modifier.width(10.dp))
                Text("para ${if (c.cut) "mover" else "copiar"}", style = TextStyle(fontFamily = Fonts.display, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = C.onAccent), modifier = Modifier.weight(1f))
                NxBtn("Descartar", vm::clearClip, kind = BtnKind.INK, small = true); Spacer(Modifier.width(8.dp)); NxBtn("Pegar aquí", vm::paste, kind = BtnKind.INK, small = true)
            }
        }
    }
}

/** Velocidad instantánea (bytes/s) a partir de los bytes acumulados, suavizada. */
@Composable fun rememberSpeed(bytes: Long): Float {
    var last by remember { mutableStateOf(bytes to System.nanoTime()) }
    var speed by remember { mutableStateOf(0f) }
    LaunchedEffect(bytes) {
        val now = System.nanoTime(); val dt = (now - last.second) / 1e9f
        if (dt > 0.15f && bytes >= last.first) { val inst = (bytes - last.first) / dt; speed = if (speed == 0f) inst else speed * 0.6f + inst * 0.4f; last = bytes to now }
    }
    return speed
}

@Composable private fun Skeleton() {
    val reduce = rememberReduceMotion()
    val t = rememberInfiniteTransition(label = "sk")
    val a by t.animateFloat(0.4f, 1f, infiniteRepeatable(tween(750, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a")
    val alpha = if (reduce) 0.7f else a
    Column(Modifier.padding(horizontal = 22.dp, vertical = 12.dp)) {
        repeat(8) {
            Row(Modifier.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).clip(Squircle(14.dp)).background(C.surface3.copy(alpha = alpha)))
                Spacer(Modifier.width(14.dp))
                Column { Box(Modifier.width((130 + it * 17 % 90).dp).height(14.dp).clip(CircleShape).background(C.surface3.copy(alpha = alpha))); Spacer(Modifier.height(8.dp)); Box(Modifier.width(90.dp).height(10.dp).clip(CircleShape).background(C.surface3.copy(alpha = alpha))) }
            }
        }
    }
}

@Composable private fun SortDialog(s: UiState, vm: ExplorerViewModel, onClose: () -> Unit) =
    NxDialog("Ordenar", onClose, { NxBtn("Listo", onClose, small = true) }) {
        NxSegment(listOf(SortBy.NAME to "Nombre", SortBy.SIZE to "Tamaño", SortBy.DATE to "Fecha", SortBy.TYPE to "Tipo"), s.sort.by, { vm.setSort(s.sort.copy(by = it)) })
        Spacer(Modifier.height(8.dp))
        NxToggle(s.sort.descending, { vm.setSort(s.sort.copy(descending = it)) }, "Descendente")
        NxToggle(s.sort.foldersFirst, { vm.setSort(s.sort.copy(foldersFirst = it)) }, "Carpetas primero")
    }

@Composable private fun TagsDialog(paths: List<String>, lib: LibraryData, home: HomeViewModel, onClose: () -> Unit) {
    var v by remember { mutableStateOf("") }
    val common = lib.allTags.filter { t -> paths.all { t in lib.tags[it].orEmpty() } }
    NxDialog("Etiquetas · ${paths.size}", onClose, { NxBtn(if (v.isBlank()) "Cerrar" else "Añadir", { if (v.isNotBlank()) home.setTags(paths, setOf(v)); onClose() }, small = true) }) {
        NxField(v, { v = it }, "Nueva etiqueta")
        Spacer(Modifier.height(8.dp))
        if (lib.allTags.isEmpty()) Text("AÚN NO HAY ETIQUETAS", style = T.label(11))
        lib.allTags.forEach { t -> val on = t in common; NxToggle(on, { if (on) home.setTags(paths, emptySet(), setOf(t)) else home.setTags(paths, setOf(t)); onClose() }, "# $t") }
    }
}

@Composable private fun DetailsDialog(e: Entry, onClose: () -> Unit) {
    var extra by remember(e.path) { mutableStateOf("Calculando…") }
    var sha by remember(e.path) { mutableStateOf<String?>(null) }
    var wantHash by remember(e.path) { mutableStateOf(false) }
    LaunchedEffect(e.path) {
        extra = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (e.isDir) { val kids = e.file.list()?.size; "${kids ?: "?"} elementos · ${humanSize(FileOps().sizeOf(e.file))} en total" } else humanSize(e.size) + " (${e.size} bytes)"
        }
    }
    LaunchedEffect(wantHash) {
        if (wantHash) sha = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { val md = java.security.MessageDigest.getInstance("SHA-256"); e.file.inputStream().use { i -> val b = ByteArray(65536); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }; md.digest().joinToString("") { "%02x".format(it) } }.getOrElse { "Error: ${it.message}" }
        }
    }
    val f = e.file
    NxDialog(e.name, onClose, { NxBtn("Cerrar", onClose, small = true) }) {
        Row(verticalAlignment = Alignment.CenterVertically) { FileTag(e, 56.dp); Spacer(Modifier.width(12.dp)); Text(if (e.isDir) "CARPETA" else e.extension.uppercase().ifEmpty { "ARCHIVO" }, style = T.title(36, C.accent)) }
        Spacer(Modifier.height(10.dp))
        listOf("Ruta" to e.path, "Tamaño" to extra, "Modificado" to DateFormat.getDateTimeInstance().format(Date(e.modified)), "Permisos" to "${if (f.canRead()) "lectura" else "sin lectura"} · ${if (f.canWrite()) "escritura" else "solo lectura"}${if (e.hidden) " · oculto" else ""}").forEach { (k, v) ->
            Text(k.uppercase(), style = T.label(10)); Text(v, style = T.mono(13), modifier = Modifier.padding(bottom = 8.dp))
        }
        if (!e.isDir) { if (sha == null) NxBtn(if (wantHash) "Calculando SHA-256…" else "Calcular SHA-256", { wantHash = true }, kind = BtnKind.GHOST, small = true, enabled = !wantHash) else { Text("SHA-256", style = T.label(10)); Text(sha!!, style = T.mono(11)) } }
    }
}
