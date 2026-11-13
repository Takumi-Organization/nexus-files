package dev.nexus.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
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
fun PermissionGate(onGrant: () -> Unit) {
    Column(Modifier.fillMaxSize().background(C.bg).padding(32.dp), verticalArrangement = Arrangement.Center) {
        Text("NEXUS", fontSize = 40.sp, fontWeight = FontWeight.Black, color = C.accent)
        Spacer(Modifier.height(12.dp))
        Text("Para gestionar tus archivos, Android necesita que concedas «Acceso a todos los archivos». Es una concesión explícita tuya en Ajustes; NEXUS no la usa para nada más que explorar y operar sobre archivos cuando tú lo pides.", color = C.dim)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onGrant) { Text("Abrir Ajustes") }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ExplorerScreen(onSettings: () -> Unit, onShare: (List<File>) -> Unit, onOpenExternal: (File) -> Boolean, onView: (Entry) -> Unit, vm: ExplorerViewModel = viewModel(), home: HomeViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val lib by home.lib.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val snack = remember { SnackbarHostState() }
    var dlg by remember { mutableStateOf<Dlg?>(null) }
    LaunchedEffect(Unit) { vm.start() }
    LaunchedEffect(s.message) { s.message?.let { snack.showSnackbar(it); vm.consumeMessage() } }
    BackHandler(enabled = s.selection.isNotEmpty() || (s.dir != null && s.roots.none { it.dir == s.dir })) { vm.up() }

    val list = remember(s.entries, s.sort, s.showHidden) { vm.visible }
    val selected = list.filter { it.path in s.selection }

    fun click(e: Entry) {
        if (s.selection.isNotEmpty()) { vm.toggle(e.path); return }
        if (e.isDir) vm.open(e.file) else onView(e)
    }
    fun long(e: Entry) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); vm.toggle(e.path) }

    Scaffold(
        containerColor = C.bg,
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            if (s.selection.isEmpty()) TopBar(s, vm, onSettings) { dlg = Dlg.Sort }
            else SelectionBar(selected.size, onClose = vm::clearSelection, onAll = vm::selectAll,
                onCopy = { vm.copyToClip(false) }, onCut = { vm.copyToClip(true) },
                onShare = { onShare(selected.map { it.file }) },
                onRename = if (selected.size == 1) ({ dlg = Dlg.Rename(selected[0]) }) else null,
                onDelete = { dlg = Dlg.Delete(s.selection, selected.map { it.name }) },
                onInfo = if (selected.size == 1) ({ dlg = Dlg.Info(selected[0]) }) else null,
                onFav = { home.toggleFavorite(s.selection); vm.clearSelection() }, onZip = { dlg = Dlg.Zip(s.selection, (selected.singleOrNull()?.name?.substringBeforeLast('.') ?: s.dir?.name ?: "archivo") + ".zip") }, onTags = { dlg = Dlg.Tags(s.selection.toList()) })
        },
        floatingActionButton = { if (s.selection.isEmpty() && s.clip == null) FloatingActionButton(onClick = { dlg = Dlg.NewFolder }, containerColor = C.accent, contentColor = Color.Black) { Text("+", fontSize = 28.sp) } },
        bottomBar = { BottomPanel(s, vm) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Breadcrumbs(s, vm)
            when {
                s.loading -> Skeleton()
                s.error != null -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No se puede mostrar esta carpeta", fontWeight = FontWeight.Bold); Spacer(Modifier.height(6.dp))
                        Text(s.error ?: "", color = C.dim); Spacer(Modifier.height(12.dp)); OutlinedButton(onClick = vm::reload) { Text("Reintentar") }
                    }
                }
                list.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Carpeta vacía", color = C.dim) }
                s.grid -> LazyVerticalGrid(GridCells.Adaptive(104.dp), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list, key = { it.path }) { e -> GridCell(e, e.path in s.selection, { click(e) }, { long(e) }) }
                }
                else -> LazyColumn { items(list, key = { it.path }) { e -> EntryRow(e, e.path in s.selection, { click(e) }, { long(e) }, star = e.path in lib.favorites) } }
            }
        }
    }

    when (val d = dlg) {
        Dlg.NewFolder -> NameDialog("Nueva carpeta", "", "Crear", { dlg = null }) { vm.mkdir(it); dlg = null }
        is Dlg.Rename -> NameDialog("Renombrar", d.e.name, "Renombrar", { dlg = null }) { vm.rename(d.e.path, it) { from, to -> home.moved(from, to) }; dlg = null }
        is Dlg.Delete -> AlertDialog(
            onDismissRequest = { dlg = null }, title = { Text("¿Eliminar ${d.paths.size} elemento(s)?") },
            text = { Text(d.names.take(6).joinToString("\n") + (if (d.names.size > 6) "\n… y ${d.names.size - 6} más" else "") + "\n\nEsta acción no se puede deshacer.") },
            confirmButton = { TextButton(onClick = { vm.delete(d.paths); dlg = null }) { Text("Eliminar", color = C.danger) } },
            dismissButton = { TextButton(onClick = { dlg = null }) { Text("Cancelar") } })
        Dlg.Sort -> SortDialog(s, vm) { dlg = null }
        is Dlg.Zip -> NameDialog("Comprimir en ZIP", d.suggested, "Comprimir", { dlg = null }) { vm.compress(d.paths, it); dlg = null }
        is Dlg.Tags -> TagsDialog(d.paths, lib, home) { dlg = null }
        is Dlg.Info -> DetailsDialog(d.e) { dlg = null }
        null -> {}
    }
}

@Composable private fun TopBar(s: UiState, vm: ExplorerViewModel, onSettings: () -> Unit, onSort: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("NEXUS", fontWeight = FontWeight.Black, fontSize = 22.sp, color = C.accent, modifier = Modifier.weight(1f))
        TextButton(onClick = { vm.setGrid(!s.grid) }) { Text(if (s.grid) "Lista" else "Cuadrícula") }
        TextButton(onClick = onSort) { Text("Ordenar") }
        Box {
            TextButton(onClick = { menu = true }) { Text("⋮", fontSize = 20.sp) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(if (s.showHidden) "Ocultar archivos ocultos" else "Mostrar archivos ocultos") }, onClick = { vm.setHidden(!s.showHidden); menu = false })
                DropdownMenuItem(text = { Text("Actualizar") }, onClick = { vm.reload(); menu = false })
                DropdownMenuItem(text = { Text("Ajustes") }, onClick = { onSettings(); menu = false })
            }
        }
    }
}

@Composable private fun SelectionBar(n: Int, onClose: () -> Unit, onAll: () -> Unit, onCopy: () -> Unit, onCut: () -> Unit, onShare: () -> Unit, onRename: (() -> Unit)?, onDelete: () -> Unit, onInfo: (() -> Unit)?, onFav: () -> Unit, onTags: () -> Unit, onZip: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(C.surface2).statusBarsPadding()) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("✕") }
            Text("$n seleccionado(s)", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onAll) { Text("Todo") }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
            item { TextButton(onClick = onCopy) { Text("Copiar") } }
            item { TextButton(onClick = onCut) { Text("Cortar") } }
            item { TextButton(onClick = onFav) { Text("★ Favorito") } }
            item { TextButton(onClick = onTags) { Text("Etiquetas") } }
            item { TextButton(onClick = onZip) { Text("Comprimir") } }
            item { TextButton(onClick = onShare) { Text("Compartir") } }
            onRename?.let { item { TextButton(onClick = it) { Text("Renombrar") } } }
            onInfo?.let { item { TextButton(onClick = it) { Text("Detalles") } } }
            item { TextButton(onClick = onDelete) { Text("Eliminar", color = C.danger) } }
        }
    }
}

@Composable private fun Breadcrumbs(s: UiState, vm: ExplorerViewModel) {
    val dir = s.dir ?: return
    val root = s.roots.firstOrNull { dir.path.startsWith(it.dir.path) } ?: return
    val parts = buildList {
        add(root.label to root.dir)
        var cur = root.dir
        dir.path.removePrefix(root.dir.path).split('/').filter { it.isNotEmpty() }.forEach { cur = File(cur, it); add(it to cur) }
    }
    Column {
        if (s.roots.size > 1) LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(s.roots) { r -> FilterChip(selected = r == root, onClick = { vm.open(r.dir) }, label = { Text(r.label) }) }
        }
        val st = rememberLazyListState()
        LaunchedEffect(parts.size) { st.animateScrollToItem(parts.lastIndex) }
        LazyRow(state = st, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            items(parts.size) { i ->
                val (label, f) = parts[i]
                Text(label, color = if (i == parts.lastIndex) C.text else C.dim, fontWeight = if (i == parts.lastIndex) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { vm.open(f) }.padding(horizontal = 6.dp, vertical = 6.dp))
                if (i < parts.lastIndex) Text("›", color = C.dim)
            }
        }
    }
}

@Composable private fun BottomPanel(s: UiState, vm: ExplorerViewModel) {
    Column {
        s.op?.let { op ->
            Column(Modifier.fillMaxWidth().background(C.surface2).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(op.title, fontWeight = FontWeight.Bold)
                        Text(op.progress?.let { "${it.current} · ${humanSize(it.doneBytes)} / ${humanSize(it.totalBytes)}" } ?: "Preparando…", color = C.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = vm::cancelOp) { Text("Cancelar") }
                }
                Spacer(Modifier.height(6.dp))
                val f = op.progress?.fraction
                if (f == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
            }
        }
        s.clip?.let { c ->
            if (s.op == null) Row(Modifier.fillMaxWidth().background(C.surface2).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${c.paths.size} para ${if (c.cut) "mover" else "copiar"}", modifier = Modifier.weight(1f))
                TextButton(onClick = vm::clearClip) { Text("Descartar") }
                Button(onClick = vm::paste) { Text("Pegar aquí") }
            }
        }
    }
}

@Composable internal fun Badge(e: Entry, size: Int) {
    val c = C.kind(e.kind)
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(10.dp)).background(c.copy(alpha = 0.18f)), Alignment.Center) {
        Text(if (e.isDir) "▤" else e.extension.take(4).uppercase().ifEmpty { "·" }, color = c, fontWeight = FontWeight.Bold, fontSize = if (e.isDir) (size * 0.45f).sp else (size * 0.26f).sp, maxLines = 1)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable fun EntryRow(e: Entry, sel: Boolean, onClick: () -> Unit, onLong: () -> Unit, star: Boolean = false) {
    Row(Modifier.fillMaxWidth().background(if (sel) C.accent.copy(alpha = 0.16f) else Color.Transparent)
        .combinedClickable(onClick = onClick, onLongClick = onLong).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Badge(e, 44); Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (e.hidden) C.dim else C.text)
            Text(if (e.isDir) DateFormat.getDateInstance().format(Date(e.modified)) else "${humanSize(e.size)} · ${DateFormat.getDateInstance().format(Date(e.modified))}", color = C.dim, fontSize = 12.sp)
        }
        if (star) Text("★", color = C.accent, modifier = Modifier.padding(horizontal = 6.dp))
        if (sel) Text("✓", color = C.accent, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun GridCell(e: Entry, sel: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(12.dp)).background(if (sel) C.accent.copy(alpha = 0.22f) else C.surface)
        .combinedClickable(onClick = onClick, onLongClick = onLong).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Badge(e, 56); Spacer(Modifier.height(6.dp))
        Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
    }
}

@Composable private fun Skeleton() {
    val t = rememberInfiniteTransition(label = "sk")
    val a by t.animateFloat(0.25f, 0.6f, infiniteRepeatable(androidx.compose.animation.core.tween(800), androidx.compose.animation.core.RepeatMode.Reverse), label = "a")
    Column(Modifier.padding(16.dp)) {
        repeat(8) {
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(C.surface2.copy(alpha = a)))
                Spacer(Modifier.width(14.dp))
                Column { Box(Modifier.width(180.dp).height(12.dp).background(C.surface2.copy(alpha = a))); Spacer(Modifier.height(8.dp)); Box(Modifier.width(100.dp).height(10.dp).background(C.surface2.copy(alpha = a))) }
            }
        }
    }
}

@Composable private fun NameDialog(title: String, initial: String, ok: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    val check = Names.validate(v)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(v, { v = it }, singleLine = true, isError = check is Names.Result.Invalid, supportingText = { (check as? Names.Result.Invalid)?.let { Text(it.reason) } }) },
        confirmButton = { TextButton(enabled = check is Names.Result.Ok, onClick = { onOk(v) }) { Text(ok) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

@Composable private fun SortDialog(s: UiState, vm: ExplorerViewModel, onClose: () -> Unit) {
    AlertDialog(onDismissRequest = onClose, confirmButton = { TextButton(onClick = onClose) { Text("Cerrar") } }, title = { Text("Ordenar por") },
        text = {
            Column {
                listOf(SortBy.NAME to "Nombre", SortBy.SIZE to "Tamaño", SortBy.DATE to "Fecha", SortBy.TYPE to "Tipo").forEach { (by, label) ->
                    Row(Modifier.fillMaxWidth().clickable { vm.setSort(s.sort.copy(by = by)) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(s.sort.by == by, { vm.setSort(s.sort.copy(by = by)) }); Text(label)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(s.sort.descending, { vm.setSort(s.sort.copy(descending = it)) }); Text("Descendente") }
            }
        })
}

@Composable private fun TagsDialog(paths: List<String>, lib: LibraryData, home: HomeViewModel, onClose: () -> Unit) {
    var v by remember { mutableStateOf("") }
    val common = lib.allTags.filter { t -> paths.all { t in lib.tags[it].orEmpty() } }
    AlertDialog(onDismissRequest = onClose, title = { Text("Etiquetas (${paths.size})") },
        confirmButton = { TextButton(onClick = { if (v.isNotBlank()) home.setTags(paths, setOf(v)); onClose() }) { Text(if (v.isBlank()) "Cerrar" else "Añadir") } },
        text = {
            Column {
                OutlinedTextField(v, { v = it }, singleLine = true, label = { Text("Nueva etiqueta") })
                Spacer(Modifier.height(8.dp))
                if (lib.allTags.isEmpty()) Text("Aún no hay etiquetas", color = C.dim)
                lib.allTags.forEach { t ->
                    val on = t in common
                    Row(Modifier.fillMaxWidth().clickable { if (on) home.setTags(paths, emptySet(), setOf(t)) else home.setTags(paths, setOf(t)); onClose() }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(on, null); Text(t)
                    }
                }
            }
        })
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
    AlertDialog(onDismissRequest = onClose, confirmButton = { TextButton(onClick = onClose) { Text("Cerrar") } }, title = { Text(e.name, maxLines = 2) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Ruta: ${e.path}\nTipo: ${if (e.isDir) "Carpeta" else e.extension.ifEmpty { "archivo" }}\nTamaño: $extra\nModificado: ${DateFormat.getDateTimeInstance().format(Date(e.modified))}\nPermisos: ${if (f.canRead()) "lectura" else "sin lectura"} · ${if (f.canWrite()) "escritura" else "solo lectura"}${if (e.hidden) " · oculto" else ""}")
            if (!e.isDir) { Spacer(Modifier.height(8.dp)); if (sha == null) TextButton(onClick = { wantHash = true }, enabled = !wantHash) { Text(if (wantHash) "Calculando SHA-256…" else "Calcular SHA-256") } else Text("SHA-256:\n$sha", fontSize = 11.sp) }
        } })
}
