package dev.nexus.files

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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
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
    var sec by rememberSaveable { mutableStateOf(Sec.SUMMARY) }
    var inCurrent by rememberSaveable { mutableStateOf(false) }
    var action by remember { mutableStateOf<Item?>(null) }
    var showUnreadable by remember { mutableStateOf(false) }
    var confirmDup by remember { mutableStateOf(false) }
    val toast = rememberToast(s.message, vm::consume)
    val root = if (inCurrent && currentDir != null) currentDir else primaryRoot

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("04", "Analizar")
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { NxChip("Todo", !inCurrent, { inCurrent = false }) }
                item { NxChip("Carpeta actual", inCurrent, { inCurrent = true }, enabled = currentDir != null) }
                item { Spacer(Modifier.width(10.dp)) }
                items(Sec.entries) { x -> NxChip(x.label, sec == x, { sec = x }) }
            }
            Rule(color = C.text)
            when (sec) {
                Sec.DUPS -> DupsSection(vm, s, root, onConfirm = { confirmDup = true })
                else -> {
                    if (s.scanning) ScanProgress(s, vm::cancelScan)
                    else if (s.result == null) NxEmpty(Kind.OTHER, "Sin medir", "Calcula cómo se reparte el espacio. Solo lee: no modifica nada.") {
                        NxBtn(if (inCurrent) "Analizar «${root.name}»" else "Analizar todo", { vm.scan(root) })
                    } else {
                        val r = s.result!!
                        Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("ANALIZADO · ${s.scannedRoot}", style = T.label(10), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            NxBtn("Repetir", { vm.scan(root) }, kind = BtnKind.GHOST, small = true)
                        }
                        if (r.unreadable.isNotEmpty()) Text("⚠ ${r.unreadable.size} CARPETA(S) QUE ANDROID NO DEJA INSPECCIONAR — VER", style = T.label(10, C.accent), modifier = Modifier.clickable { showUnreadable = true }.padding(horizontal = 20.dp, vertical = 6.dp))
                        when (sec) {
                            Sec.SUMMARY -> Summary(r, s.kind, vm::setKind) { action = it }
                            Sec.MAP -> MapSection(s, vm)
                            else -> Lists(r) { action = it }
                        }
                    }
                }
            }
        }
        NxToast(toast, Modifier.align(Alignment.BottomCenter))
    }

    action?.let { it ->
        NxDialog(it.name, { action = null }, {
            NxBtn("Mostrar", { onShow(File(if (it.isDir) it.path else it.parent())); action = null }, kind = BtnKind.GHOST, small = true)
            NxBtn("Eliminar", { vm.deleteItem(it.path); action = null }, kind = BtnKind.DANGER, small = true)
        }) {
            Text("RUTA", style = T.label(10)); Text(it.path, style = T.mono(12), modifier = Modifier.padding(bottom = 8.dp))
            val (n, u) = sizeParts(it.size); Row(verticalAlignment = Alignment.Bottom) { Text(n, style = T.numeral(64)); Text(" $u · ${DateFormat.getDateInstance().format(Date(it.modified))}", style = T.label(11), modifier = Modifier.padding(bottom = 8.dp)) }
            Text("Eliminar es permanente: no hay papelera.", style = T.mono(11, C.dim))
        }
    }
    if (showUnreadable) NxDialog("No inspeccionable", { showUnreadable = false }, { NxBtn("Cerrar", { showUnreadable = false }, small = true) }) {
        Text("Android restringe estas ubicaciones (datos privados de otras apps). Sus tamaños no cuentan en el análisis:", style = T.mono(12, C.dim), modifier = Modifier.padding(bottom = 8.dp))
        s.result?.unreadable?.forEach { Text(it, style = T.mono(11)) }
    }
    if (confirmDup) {
        val sel = s.dupSelection; val wasted = s.dup?.groups?.sumOf { g -> g.files.filter { it.path in sel }.sumOf { it.size } } ?: 0
        NxConfirm("¿Eliminar ${sel.size}?", "Se liberarán ${humanSize(wasted)}. En cada grupo se conserva al menos una copia.\nPermanente: no hay papelera.", "Eliminar", true, { confirmDup = false }) { vm.deleteDuplicates(); confirmDup = false }
    }
}

@Composable private fun ScanProgress(s: AnalyzerUi, onCancel: () -> Unit) = Column(Modifier.padding(20.dp)) {
    Text("MIDIENDO…", style = T.label(11, C.accent))
    Text("${s.scanFiles}", style = T.numeral(120))
    Text("ARCHIVOS · ${humanSize(s.scanBytes)}", style = T.label(12))
    Spacer(Modifier.height(14.dp)); NxProgress(null); Spacer(Modifier.height(14.dp)); NxBtn("Cancelar", onCancel, kind = BtnKind.GHOST, small = true)
}

/** Anillo propio: arcos planos con huecos, uno por tipo; al elegir un tipo crece su arco. */
@Composable private fun Ring(r: ScanResult, kinds: List<Kind>, sel: Kind?, modifier: Modifier = Modifier) {
    val reduce = rememberReduceMotion()
    val total = r.totalBytes.coerceAtLeast(1).toFloat()
    val prog = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(r) { if (!reduce) { prog.snapTo(0f); prog.animateTo(1f, spring(0.8f, 40f)) } }
    val thick by animateFloatAsState(1f, label = "t")
    Canvas(modifier) {
        val stroke = size.minDimension * 0.17f
        val d = size.minDimension - stroke
        var start = -90f
        kinds.forEach { k ->
            val frac = (r.bytesByKind[k] ?: 0) / total
            val sweep = (frac * 360f * prog.value - 3f).coerceAtLeast(0.5f)
            val on = sel == null || sel == k
            val w = if (sel == k) stroke * 1.22f else stroke
            drawArc(C.kind(k).copy(alpha = if (on) 1f else 0.25f), start, sweep, false, Offset((size.width - d) / 2, (size.height - d) / 2), Size(d, d), style = Stroke(w, cap = StrokeCap.Butt))
            start += frac * 360f * prog.value
        }
    }
}

@Composable private fun Summary(r: ScanResult, kind: Kind?, onKind: (Kind?) -> Unit, onItem: (Item) -> Unit) {
    val kinds = Kind.entries.filter { (r.bytesByKind[it] ?: 0) > 0 }.sortedByDescending { r.bytesByKind[it] }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(1.15f), Alignment.Center) {
                    Ring(r, kinds, kind, Modifier.fillMaxSize().padding(8.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val (n, u) = sizeParts(r.totalBytes)
                        Row(verticalAlignment = Alignment.Bottom) { Text(n, style = T.numeral(104)); Text(" $u", style = T.title(36), modifier = Modifier.padding(bottom = 14.dp)) }
                        Text("${r.fileCount} ARCHIVOS", style = T.label(11))
                    }
                }
                Spacer(Modifier.height(12.dp))
                kinds.forEachIndexed { i, k ->
                    val b = r.bytesByKind[k] ?: 0; val on = k == kind
                    Row(Modifier.fillMaxWidth().enter(i).nxClick({ onKind(if (on) null else k) }).background(if (on) C.surface2 else Color.Transparent).padding(vertical = 9.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp).clip(Chamfer(8.dp)).background(C.kind(k)), Alignment.Center) { Glyph(k, 28.dp) }
                        Spacer(Modifier.width(12.dp))
                        Text(kindLabel(k).uppercase(), style = T.title(26), modifier = Modifier.weight(1f))
                        val (n, u) = sizeParts(b)
                        Text(n, style = T.numeral(34)); Text(" $u", style = T.label(10), modifier = Modifier.padding(bottom = 4.dp, end = 10.dp))
                        Text("${(b * 100 / r.totalBytes.coerceAtLeast(1))}%", style = T.label(12, C.accent), modifier = Modifier.width(40.dp))
                    }
                    Rule(thick = 1.dp, color = C.rule.copy(alpha = 0.5f))
                }
                if (kind != null) Text("ARCHIVOS MÁS GRANDES · ${kindLabel(kind).uppercase()}", style = T.label(11, C.accent), modifier = Modifier.padding(top = 16.dp))
            }
        }
        if (kind != null) items(r.largestByKind[kind].orEmpty(), key = { "k" + it.path }) { i -> EntryRow(i.entry(), false, { onItem(i) }, {}) }
    }
}

fun kindLabel(k: Kind) = when (k) { Kind.FOLDER -> "Carpetas"; Kind.IMAGE -> "Imágenes"; Kind.VIDEO -> "Vídeos"; Kind.AUDIO -> "Audio"; Kind.DOCUMENT -> "Documentos"; Kind.ARCHIVE -> "Comprimidos"; Kind.CODE -> "Código"; Kind.OTHER -> "Otros" }

@Composable private fun MapSection(s: AnalyzerUi, vm: AnalyzerViewModel) {
    val node = s.stack.lastOrNull() ?: return
    val reduce = rememberReduceMotion()
    Column(Modifier.padding(horizontal = 14.dp)) {
        LazyRow(verticalAlignment = Alignment.CenterVertically) {
            items(s.stack.size) { i ->
                Text((if (i == 0 && s.stack[i].name == "0") "INTERNO" else s.stack[i].name.uppercase()), style = T.label(11, if (i == s.stack.lastIndex) C.accent else C.dim), modifier = Modifier.clickable { vm.jump(i) }.padding(horizontal = 6.dp, vertical = 10.dp))
                if (i < s.stack.lastIndex) Text("/", style = T.label(11, C.rule))
            }
        }
        val (n, u) = sizeParts(node.size)
        Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.Bottom) { Text(n, style = T.numeral(72)); Text(" $u", style = T.title(28), modifier = Modifier.padding(bottom = 10.dp)); Text("   TOCA UNA CARPETA PARA ENTRAR", style = T.label(10), modifier = Modifier.padding(bottom = 12.dp)) }
        if (node.children.isEmpty()) { NxEmpty(Kind.FOLDER, "Sin contenido", "Nada medible aquí."); return }
        BoxWithConstraints(Modifier.fillMaxWidth().height(430.dp).clipToBounds()) {
            val d = LocalDensity.current
            val wPx = with(d) { maxWidth.toPx() }; val hPx = with(d) { maxHeight.toPx() }
            val rects = remember(node, wPx, hPx) { Treemap.layout(node.children.map { it.size }, Rect(0f, 0f, wPx, hPx)) }
            node.children.forEachIndexed { i, n2 ->
                val r = rects[i]; if (r.w < 2f || r.h < 2f) return@forEachIndexed
                val kind = if (n2.isDir) Kind.FOLDER else if (n2.synthetic) Kind.OTHER else Kinds.of(n2.name.substringAfterLast('.', "").lowercase())
                // las carpetas recorren la familia del acento (acento → armónico) según su rango; los archivos llevan el color de su tipo
                val color = if (n2.isDir) androidx.compose.ui.graphics.lerp(C.accent, C.accent2, (i.coerceAtMost(9) / 9f)) else C.kind(kind)
                val wDp = with(d) { r.w.toDp() }; val hDp = with(d) { r.h.toDp() }
                val p = remember(node, i) { Animatable(if (reduce) 1f else 0f) }
                LaunchedEffect(node) { if (!reduce) { kotlinx.coroutines.delay(minOf(i, 14) * 40L); p.animateTo(1f, spring(0.7f, 300f)) } }
                Box(Modifier.offset(with(d) { r.x.toDp() }, with(d) { r.y.toDp() }).size(wDp, hDp).padding(2.dp)
                    .graphicsLayer { val sc = 0.9f + 0.1f * p.value; scaleX = sc; scaleY = sc; alpha = p.value.coerceIn(0f, 1f) }
                    .clip(Squircle(if (wDp > 80.dp && hDp > 60.dp) 18.dp else 8.dp)).background(color)
                    .drawBehind { drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.14f), Color.Transparent, Color.Black.copy(alpha = 0.12f)))) }
                    .nxClick({ vm.enter(n2) }, enabled = n2.isDir).padding(9.dp)) {
                    if (wDp > 50.dp && hDp > 36.dp) Column {
                        Text(n2.name, style = T.title(if (wDp > 120.dp) 22 else 15, Color.White), maxLines = 1, overflow = TextOverflow.Clip, softWrap = false)
                        sizeParts(n2.size).let { Text("${it.first} ${it.second}", style = androidx.compose.ui.text.TextStyle(fontFamily = Fonts.display, fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f)), maxLines = 1) }
                    }
                }
            }
        }
        if (s.stack.size > 1) NxBtn("← Subir", vm::up, kind = BtnKind.GHOST, small = true, modifier = Modifier.padding(6.dp))
    }
}

@Composable private fun Lists(r: ScanResult, onItem: (Item) -> Unit) {
    var l by rememberSaveable { mutableStateOf(Lst.BIG) }
    val items = when (l) { Lst.BIG -> r.largest; Lst.FOLDERS -> r.heaviestFolders; Lst.OLD -> r.old; Lst.TEMP -> r.temp }
    Column {
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(Lst.entries) { x -> NxChip(x.label, l == x, { l = x }) } }
        Text(when (l) { Lst.BIG -> "LOS ARCHIVOS QUE MÁS OCUPAN"; Lst.FOLDERS -> "CARPETAS POR EL PESO DE SUS PROPIOS ARCHIVOS (SIN SUBCARPETAS)"; Lst.OLD -> "MÁS DE 1 AÑO SIN MODIFICAR Y MÁS DE 1 MB"; Lst.TEMP -> "CACHÉS, .TMP, .BAK Y SIMILARES: POR NOMBRE; REVISA ANTES DE BORRAR" },
            style = T.label(10), modifier = Modifier.padding(20.dp, 10.dp))
        LazyColumn { items(items, key = { l.name + it.path }) { i -> EntryRow(i.entry(), false, { onItem(i) }, {}) }
            if (items.isEmpty()) item { NxEmpty(Kind.OTHER, "Nada", "No hay elementos en esta lista.") } }
    }
}

@Composable private fun DupsSection(vm: AnalyzerViewModel, s: AnalyzerUi, root: File, onConfirm: () -> Unit) {
    val d = s.dup
    Column(Modifier.fillMaxSize()) {
        when {
            s.dupRunning -> Column(Modifier.padding(20.dp)) {
                Text("BUSCANDO DUPLICADOS…", style = T.label(11, C.accent))
                Text(s.dupProgress?.let { "${it.first}/${it.second}" } ?: "···", style = T.numeral(110)); Text("CANDIDATOS COMPARADOS", style = T.label(11))
                Spacer(Modifier.height(14.dp)); NxProgress(s.dupProgress?.let { if (it.second > 0) it.first.toFloat() / it.second else null }); Spacer(Modifier.height(14.dp)); NxBtn("Cancelar", vm::cancelDup, kind = BtnKind.GHOST, small = true)
            }
            d == null -> NxEmpty(Kind.ARCHIVE, "Copias", "Compara por tamaño y hash SHA-256: solo agrupa archivos idénticos byte a byte. Ignora vacíos y ocultos.") { NxBtn("Buscar duplicados", { vm.findDuplicates(root) }) }
            else -> {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        val (n, u) = sizeParts(d.wasted)
                        Row(verticalAlignment = Alignment.Bottom) { Text(n, style = T.numeral(76)); Text(" $u", style = T.title(28), modifier = Modifier.padding(bottom = 10.dp)) }
                        Text("${d.groups.size} GRUPO(S) · RECUPERABLES", style = T.label(10))
                    }
                    NxBtn("Repetir", { vm.findDuplicates(root) }, kind = BtnKind.GHOST, small = true)
                }
                if (d.unreadable.isNotEmpty()) Text("${d.unreadable.size} CARPETA(S) NO INSPECCIONABLES", style = T.label(10), modifier = Modifier.padding(horizontal = 20.dp))
                Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NxBtn("Marcar sobrantes", vm::suggestDup, small = true, enabled = d.groups.isNotEmpty())
                    if (s.dupSelection.isNotEmpty()) NxBtn("Limpiar", vm::clearDup, kind = BtnKind.GHOST, small = true)
                }
                Text("CONSERVA EL MÁS ANTIGUO DE CADA GRUPO", style = T.label(9), modifier = Modifier.padding(horizontal = 20.dp))
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(14.dp, 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (d.groups.isEmpty()) item { NxEmpty(Kind.ARCHIVE, "Limpio", "No hay duplicados.") }
                    items(d.groups, key = { it.sha256 + it.files.first().path }) { g ->
                        Column(Modifier.fillMaxWidth().clip(Chamfer(14.dp)).background(C.surface).border(2.dp, C.rule, Chamfer(14.dp)).padding(12.dp)) {
                            Row(verticalAlignment = Alignment.Bottom) { Text("${g.files.size}×", style = T.numeral(40, C.accent)); Spacer(Modifier.width(8.dp)); Text("${humanSize(g.size)} C/U", style = T.label(11), modifier = Modifier.padding(bottom = 4.dp)) }
                            g.files.forEach { f ->
                                val on = f.path in s.dupSelection
                                Row(Modifier.fillMaxWidth().nxClick({ vm.toggleDup(f.path) }).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(28.dp).border(2.dp, if (on) C.danger else C.rule).background(if (on) C.danger else Color.Transparent), Alignment.Center) { if (on) Text("✕", style = T.title(20, Color.White)) }
                                    Spacer(Modifier.width(12.dp))
                                    Column { Text(f.name, style = T.name(19), maxLines = 1, overflow = TextOverflow.Ellipsis); Text(f.parent() + " · " + DateFormat.getDateInstance().format(Date(f.modified)), style = T.mono(10, C.dim), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                }
                            }
                        }
                    }
                }
                if (s.dupSelection.isNotEmpty()) {
                    val ok = Duplicates.isSafeSelection(d.groups, s.dupSelection)
                    Column(Modifier.fillMaxWidth().background(C.text).padding(14.dp)) {
                        if (!ok) Text("CADA GRUPO DEBE CONSERVAR AL MENOS UNA COPIA", style = T.label(10, C.danger), modifier = Modifier.padding(bottom = 6.dp))
                        NxBtn("Eliminar ${s.dupSelection.size} seleccionado(s)…", onConfirm, Modifier.fillMaxWidth(), kind = BtnKind.DANGER, enabled = ok && !s.deleting)
                    }
                }
            }
        }
    }
}
