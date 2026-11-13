package dev.nexus.files

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nexus.core.*
import java.io.File
import java.text.DateFormat
import java.util.Date

private enum class Sec(val label: String) { SUMMARY("Resumen"), MAP("Mapa"), LISTS("Listas"), DUPS("Duplicados") }
private enum class Lst(val label: String) { BIG("Más grandes"), FOLDERS("Carpetas pesadas"), OLD("Antiguos"), TEMP("Temporales") }

private fun Item.entry() = Entry(path, name, isDir, size, modified, name.startsWith('.'), true)
private fun Item.parent() = path.substringBeforeLast('/')

@Composable
fun AnalyzerScreen(vm: AnalyzerViewModel, primaryRoot: File, currentDir: File?, onShow: (File) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    var sec by rememberSaveable { mutableStateOf(Sec.SUMMARY) }
    var inCurrent by rememberSaveable { mutableStateOf(false) }
    var action by remember { mutableStateOf<Item?>(null) }
    var showUnreadable by remember { mutableStateOf(false) }
    var confirmDup by remember { mutableStateOf(false) }
    LaunchedEffect(s.message) { s.message?.let { snack.showSnackbar(it); vm.consume() } }
    val root = if (inCurrent && currentDir != null) currentDir else primaryRoot

    Scaffold(containerColor = C.bg, snackbarHost = { SnackbarHost(snack) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Text("Analizar", fontWeight = FontWeight.Black, fontSize = 22.sp, color = C.accent, modifier = Modifier.padding(16.dp))
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!inCurrent, { inCurrent = false }, { Text("Todo el almacenamiento") })
                FilterChip(inCurrent, { inCurrent = true }, { Text("Carpeta actual") }, enabled = currentDir != null)
            }
            LazyRow(contentPadding = PaddingValues(16.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Sec.entries) { x -> FilterChip(sec == x, { sec = x }, { Text(x.label) }) }
            }
            when (sec) {
                Sec.DUPS -> DupsSection(vm, s, root, onConfirm = { confirmDup = true })
                else -> {
                    if (s.scanning) ScanProgress(s, vm::cancelScan)
                    else if (s.result == null) Column(Modifier.padding(16.dp)) {
                        Text("Calcula cómo se reparte el espacio. Solo lee: no modifica nada.", color = C.dim)
                        Spacer(Modifier.height(12.dp)); Button(onClick = { vm.scan(root) }) { Text(if (inCurrent) "Analizar «${root.name}»" else "Analizar todo el almacenamiento") }
                    } else {
                        val r = s.result!!
                        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Analizado: ${s.scannedRoot}", color = C.dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            TextButton(onClick = { vm.scan(root) }) { Text("Reanalizar") }
                        }
                        if (r.unreadable.isNotEmpty()) Text("⚠ ${r.unreadable.size} carpeta(s) que Android no permite inspeccionar — ver", color = C.accent, fontSize = 12.sp,
                            modifier = Modifier.clickable { showUnreadable = true }.padding(horizontal = 16.dp, vertical = 4.dp))
                        when (sec) {
                            Sec.SUMMARY -> Summary(r, s.kind, vm::setKind) { action = it }
                            Sec.MAP -> MapSection(s, vm)
                            else -> Lists(r) { action = it }
                        }
                    }
                }
            }
        }
    }

    action?.let { it ->
        AlertDialog(onDismissRequest = { action = null }, title = { Text(it.name, maxLines = 2) },
            text = { Text("${it.path}\n${humanSize(it.size)} · ${DateFormat.getDateInstance().format(Date(it.modified))}\n\nEliminar es permanente: no hay papelera.") },
            confirmButton = { TextButton(onClick = { vm.deleteItem(it.path); action = null }) { Text("Eliminar", color = C.danger) } },
            dismissButton = { Row { TextButton(onClick = { onShow(File(if (it.isDir) it.path else it.parent())); action = null }) { Text("Mostrar") }; TextButton(onClick = { action = null }) { Text("Cancelar") } } })
    }
    if (showUnreadable) AlertDialog(onDismissRequest = { showUnreadable = false }, confirmButton = { TextButton(onClick = { showUnreadable = false }) { Text("Cerrar") } },
        title = { Text("No inspeccionable") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Android restringe estas ubicaciones (datos privados de otras apps). Sus tamaños no cuentan en el análisis:", color = C.dim); Spacer(Modifier.height(8.dp))
            s.result?.unreadable?.forEach { Text(it, fontSize = 12.sp) } } })
    if (confirmDup) {
        val sel = s.dupSelection; val wasted = s.dup?.groups?.sumOf { g -> g.files.filter { it.path in sel }.sumOf { it.size } } ?: 0
        AlertDialog(onDismissRequest = { confirmDup = false }, title = { Text("¿Eliminar ${sel.size} archivo(s)?") },
            text = { Text("Se liberarán ${humanSize(wasted)}. En cada grupo se conserva al menos una copia.\nEsta acción es permanente: no hay papelera.") },
            confirmButton = { TextButton(onClick = { vm.deleteDuplicates(); confirmDup = false }) { Text("Eliminar", color = C.danger) } },
            dismissButton = { TextButton(onClick = { confirmDup = false }) { Text("Cancelar") } })
    }
}

@Composable private fun ScanProgress(s: AnalyzerUi, onCancel: () -> Unit) = Column(Modifier.padding(16.dp)) {
    Text("Analizando… ${s.scanFiles} archivos · ${humanSize(s.scanBytes)}"); Spacer(Modifier.height(8.dp))
    LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = onCancel) { Text("Cancelar") }
}

@Composable private fun Summary(r: ScanResult, kind: Kind?, onKind: (Kind?) -> Unit, onItem: (Item) -> Unit) {
    val kinds = Kind.entries.filter { (r.bytesByKind[it] ?: 0) > 0 }.sortedByDescending { r.bytesByKind[it] }
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Column(Modifier.padding(16.dp)) {
                Text(humanSize(r.totalBytes), fontSize = 30.sp, fontWeight = FontWeight.Black)
                Text("${r.fileCount} archivos", color = C.dim)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(6.dp))) {
                    kinds.forEach { k -> Box(Modifier.weight((r.bytesByKind[k] ?: 1).toFloat()).fillMaxHeight().background(C.kind(k).copy(alpha = if (kind == null || kind == k) 1f else 0.25f)).clickable { onKind(k) }) }
                }
                Spacer(Modifier.height(10.dp))
                kinds.forEach { k ->
                    val b = r.bytesByKind[k] ?: 0
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onKind(k) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(C.kind(k))); Spacer(Modifier.width(10.dp))
                        Text(kindLabel(k), modifier = Modifier.weight(1f), fontWeight = if (k == kind) FontWeight.Bold else FontWeight.Normal)
                        Text("${humanSize(b)} · ${(b * 100 / r.totalBytes.coerceAtLeast(1))}%", color = C.dim, fontSize = 13.sp)
                    }
                }
                if (kind != null) Text("Archivos más grandes: ${kindLabel(kind)}", color = C.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
            }
        }
        if (kind != null) items(r.largestByKind[kind].orEmpty(), key = { "k" + it.path }) { i -> EntryRow(i.entry(), false, { onItem(i) }, {}) }
    }
}

fun kindLabel(k: Kind) = when (k) { Kind.FOLDER -> "Carpetas"; Kind.IMAGE -> "Imágenes"; Kind.VIDEO -> "Vídeos"; Kind.AUDIO -> "Audio"; Kind.DOCUMENT -> "Documentos"; Kind.ARCHIVE -> "Comprimidos"; Kind.CODE -> "Código"; Kind.OTHER -> "Otros" }

@Composable private fun MapSection(s: AnalyzerUi, vm: AnalyzerViewModel) {
    val node = s.stack.lastOrNull() ?: return
    Column(Modifier.padding(horizontal = 16.dp)) {
        LazyRow(verticalAlignment = Alignment.CenterVertically) {
            items(s.stack.size) { i ->
                Text(if (i == 0 && s.stack[i].name == "0") "Almacenamiento interno" else s.stack[i].name, color = if (i == s.stack.lastIndex) C.text else C.dim, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { vm.jump(i) }.padding(6.dp))
                if (i < s.stack.lastIndex) Text("›", color = C.dim)
            }
        }
        Text("${humanSize(node.size)} · toca una carpeta para entrar", color = C.dim, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
        if (node.children.isEmpty()) { Box(Modifier.fillMaxWidth().height(200.dp), Alignment.Center) { Text("Sin contenido medible", color = C.dim) }; return }
        BoxWithConstraints(Modifier.fillMaxWidth().height(440.dp).clip(RoundedCornerShape(10.dp))) {
            val d = LocalDensity.current
            val wPx = with(d) { maxWidth.toPx() }; val hPx = with(d) { maxHeight.toPx() }
            val rects = remember(node, wPx, hPx) { Treemap.layout(node.children.map { it.size }, Rect(0f, 0f, wPx, hPx)) }
            node.children.forEachIndexed { i, n ->
                val r = rects[i]; if (r.w < 2f || r.h < 2f) return@forEachIndexed
                val color = if (n.isDir) C.kind(Kind.FOLDER) else if (n.synthetic) C.kind(Kind.OTHER) else C.kind(Kinds.of(n.name.substringAfterLast('.', "").lowercase()))
                val wDp = with(d) { r.w.toDp() }; val hDp = with(d) { r.h.toDp() }
                Box(Modifier.offset(with(d) { r.x.toDp() }, with(d) { r.y.toDp() }).size(wDp, hDp).padding(1.dp)
                    .background(color.copy(alpha = if (n.isDir) 0.38f else 0.3f)).border(1.dp, color.copy(alpha = 0.7f))
                    .clickable(enabled = n.isDir) { vm.enter(n) }.padding(4.dp)) {
                    if (wDp > 56.dp && hDp > 34.dp) Column {
                        Text(n.name, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(humanSize(n.size), fontSize = 10.sp, color = C.dim, maxLines = 1)
                    }
                }
            }
        }
        if (s.stack.size > 1) TextButton(onClick = vm::up) { Text("← Subir") }
    }
}

@Composable private fun Lists(r: ScanResult, onItem: (Item) -> Unit) {
    var l by rememberSaveable { mutableStateOf(Lst.BIG) }
    val items = when (l) { Lst.BIG -> r.largest; Lst.FOLDERS -> r.heaviestFolders; Lst.OLD -> r.old; Lst.TEMP -> r.temp }
    Column {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(Lst.entries) { x -> FilterChip(l == x, { l = x }, { Text(x.label) }) } }
        Text(when (l) { Lst.BIG -> "Los archivos que más ocupan"; Lst.FOLDERS -> "Carpetas por el peso de sus propios archivos (sin subcarpetas)"; Lst.OLD -> "Más de 1 año sin modificar y más de 1 MB"; Lst.TEMP -> "Cachés, .tmp, .bak y similares: identificados por nombre; revisa antes de borrar" },
            color = C.dim, fontSize = 12.sp, modifier = Modifier.padding(16.dp, 8.dp))
        LazyColumn { items(items, key = { l.name + it.path }) { i -> EntryRow(i.entry(), false, { onItem(i) }, {}) }
            if (items.isEmpty()) item { Text("Nada que mostrar", color = C.dim, modifier = Modifier.padding(16.dp)) } }
    }
}

@Composable private fun DupsSection(vm: AnalyzerViewModel, s: AnalyzerUi, root: File, onConfirm: () -> Unit) {
    val d = s.dup
    Column(Modifier.fillMaxSize()) {
        when {
            s.dupRunning -> Column(Modifier.padding(16.dp)) {
                Text("Buscando duplicados… " + (s.dupProgress?.let { "${it.first}/${it.second} candidatos" } ?: "listando archivos")); Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = vm::cancelDup) { Text("Cancelar") }
            }
            d == null -> Column(Modifier.padding(16.dp)) {
                Text("Compara por tamaño y hash SHA-256: solo se agrupan archivos idénticos byte a byte. Se ignoran vacíos y ocultos.", color = C.dim)
                Spacer(Modifier.height(12.dp)); Button(onClick = { vm.findDuplicates(root) }) { Text("Buscar duplicados") }
            }
            else -> {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${d.groups.size} grupo(s) · ${humanSize(d.wasted)} recuperables", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    TextButton(onClick = { vm.findDuplicates(root) }) { Text("Repetir") }
                }
                if (d.unreadable.isNotEmpty()) Text("${d.unreadable.size} carpeta(s) no inspeccionables", color = C.dim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
                Row(Modifier.padding(horizontal = 8.dp)) {
                    TextButton(onClick = vm::suggestDup, enabled = d.groups.isNotEmpty()) { Text("Marcar sobrantes (conservar el más antiguo)") }
                    if (s.dupSelection.isNotEmpty()) TextButton(onClick = vm::clearDup) { Text("Limpiar") }
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp, 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (d.groups.isEmpty()) item { Text("No hay duplicados 🎉", color = C.dim) }
                    items(d.groups, key = { it.sha256 + it.files.first().path }) { g ->
                        Column(Modifier.clip(RoundedCornerShape(12.dp)).background(C.surface).padding(10.dp)) {
                            Text("${g.files.size} copias · ${humanSize(g.size)} c/u", fontWeight = FontWeight.Bold)
                            g.files.forEach { f ->
                                Row(Modifier.fillMaxWidth().clickable { vm.toggleDup(f.path) }, verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(f.path in s.dupSelection, { vm.toggleDup(f.path) })
                                    Column { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(f.parent() + " · " + DateFormat.getDateInstance().format(Date(f.modified)), color = C.dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                }
                            }
                        }
                    }
                }
                if (s.dupSelection.isNotEmpty()) {
                    val ok = Duplicates.isSafeSelection(d.groups, s.dupSelection)
                    Column(Modifier.fillMaxWidth().background(C.surface2).padding(12.dp)) {
                        if (!ok) Text("Cada grupo debe conservar al menos una copia", color = C.danger, fontSize = 12.sp)
                        Button(onClick = onConfirm, enabled = ok && !s.deleting, colors = ButtonDefaults.buttonColors(containerColor = C.danger, contentColor = Color.White), modifier = Modifier.fillMaxWidth()) { Text("Eliminar ${s.dupSelection.size} seleccionado(s)…") }
                    }
                }
            }
        }
    }
}
