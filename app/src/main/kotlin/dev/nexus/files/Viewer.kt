package dev.nexus.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.MediaPlayer
import android.os.ParcelFileDescriptor
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.nexus.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ViewerScreen(entry: Entry, kind: VKind, onClose: () -> Unit, onExternal: () -> Unit, onExtract: (File, Set<String>?) -> Unit) {
    BackHandler(onBack = onClose)
    val tint = C.kind(entry.kind)
    ContainerTransform {
        DragDismiss(onClose) {
            Box(Modifier.fillMaxSize().background(C.bg)) {
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(42.dp).clip(androidx.compose.foundation.shape.CircleShape).background(C.surface3).nxClick(onClose), Alignment.Center) { Text("✕", style = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, color = C.text, fontWeight = FontWeight.Bold)) }
                        Spacer(Modifier.width(12.dp))
                        FileTag(entry, 40.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.name, style = T.name(20), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${entry.extension.uppercase().ifEmpty { "Archivo" }} · ${humanSize(entry.size)}", style = T.label(12))
                        }
                        NxBtn("Abrir con…", onExternal, kind = BtnKind.GHOST, small = true)
                    }
                    Box(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding()) {
                        when (kind) {
                            VKind.IMAGE -> ImageView(entry.file)
                            VKind.TEXT, VKind.JSON, VKind.XML -> TextView(entry.file, kind, onExternal)
                            VKind.PDF -> PdfView(entry.file)
                            VKind.AUDIO -> AudioView(entry.file)
                            VKind.VIDEO -> VideoPlayer(entry.file)
                            VKind.ZIP -> ZipView(entry.file, onExtract)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun Message(t: String) = Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) { Text(t, color = C.dim) }

@Composable private fun ImageView(f: File) {
    var bmp by remember(f) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(f) { mutableStateOf(false) }
    LaunchedEffect(f) {
        bmp = withContext(Dispatchers.IO) {
            runCatching {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(f.path, o)
                var s = 1; while (o.outWidth / s > 2400 || o.outHeight / s > 2400) s *= 2
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
            }.getOrNull()
        }; failed = bmp == null
    }
    if (failed) { Message("No se pudo decodificar la imagen"); return }
    val b = bmp ?: run { Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }; return }
    var scale by remember { mutableFloatStateOf(1f) }; var off by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    Image(b.asImageBitmap(), f.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().clipToBounds()
        .pointerInput(Unit) { detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 8f); off = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else off + pan } }
        .graphicsLayer { scaleX = scale; scaleY = scale; translationX = off.x; translationY = off.y })
}

@Composable private fun TextView(f: File, kind: VKind, onExternal: () -> Unit) {
    var res by remember(f) { mutableStateOf<Pair<String, String?>?>(null) }
    var err by remember(f) { mutableStateOf<String?>(null) }
    LaunchedEffect(f) { withContext(Dispatchers.IO) { runCatching { readText(f, kind) } }.fold({ res = it }, { err = it.message ?: "Error de lectura" }) }
    err?.let { Message(it); return }
    val (text, note) = res ?: run { Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }; return }
    if (text.isEmpty() && note != null) { Column(Modifier.padding(24.dp)) { Text(note, color = C.dim); TextButton(onClick = onExternal) { Text("Abrir con otra app") } }; return }
    val lines = remember(text) { text.lines() }
    Column {
        note?.let { Text(it, color = C.accent, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        LazyColumn(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
            itemsIndexed(lines) { i, l ->
                Row(Modifier.padding(horizontal = 8.dp)) {
                    Text("${i + 1}".padStart(5), color = C.dim, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Spacer(Modifier.width(10.dp))
                    Text(l, fontFamily = FontFamily.Monospace, fontSize = 12.sp, softWrap = false)
                }
            }
        }
    }
}

@Composable private fun PdfView(f: File) {
    val mutex = remember { Mutex() }
    var r by remember(f) { mutableStateOf<PdfRenderer?>(null) }
    var err by remember(f) { mutableStateOf<String?>(null) }
    DisposableEffect(f) {
        val pfd = runCatching { ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull()
        val rr = pfd?.let { runCatching { PdfRenderer(it) }.getOrNull() }
        r = rr; if (rr == null) err = "No se pudo abrir el PDF (¿protegido o dañado?)"
        onDispose { rr?.close(); pfd?.close() }
    }
    err?.let { Message(it); return }
    val renderer = r ?: return
    val wPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }.toInt().coerceAtLeast(300)
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(8.dp)) {
        items(renderer.pageCount) { i ->
            var bmp by remember { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(i) {
                bmp = withContext(Dispatchers.IO) { mutex.withLock { runCatching { renderer.openPage(i).use { p ->
                    val h = (wPx.toFloat() * p.height / p.width).toInt()
                    Bitmap.createBitmap(wPx, h, Bitmap.Config.ARGB_8888).also { b -> b.eraseColor(android.graphics.Color.WHITE); p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                } }.getOrNull() } }
            }
            bmp?.let { Image(it.asImageBitmap(), "Página ${i + 1}", Modifier.fillMaxWidth()) } ?: Box(Modifier.fillMaxWidth().height(300.dp).background(C.surface), Alignment.Center) { CircularProgressIndicator() }
        }
    }
}

@Composable private fun AudioView(f: File) {
    val mp = remember(f) { MediaPlayer() }
    var ready by remember(f) { mutableStateOf(false) }; var err by remember(f) { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }; var pos by remember { mutableIntStateOf(0) }
    DisposableEffect(f) {
        runCatching { mp.setDataSource(f.path); mp.setOnPreparedListener { ready = true }; mp.setOnCompletionListener { playing = false }; mp.setOnErrorListener { _, _, _ -> err = true; true }; mp.prepareAsync() }.onFailure { err = true }
        onDispose { runCatching { mp.release() } }
    }
    LaunchedEffect(playing) { while (playing) { pos = runCatching { mp.currentPosition }.getOrDefault(0); delay(250) } }
    if (err) { Message("No se puede reproducir este audio"); return }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("♪", fontSize = 72.sp, color = C.kind(Kind.AUDIO))
        if (!ready) CircularProgressIndicator() else {
            val dur = mp.duration.coerceAtLeast(1)
            Slider(pos.toFloat(), { pos = it.toInt(); mp.seekTo(pos) }, valueRange = 0f..dur.toFloat())
            Text("${fmt(pos)} / ${fmt(dur)}", color = C.dim)
            Button(onClick = { if (playing) mp.pause() else mp.start(); playing = !playing }) { Text(if (playing) "Pausa" else "Reproducir") }
        }
    }
}
private fun fmt(ms: Int) = "%d:%02d".format(ms / 60000, ms / 1000 % 60)

@Composable private fun VideoPlayer(f: File) {
    AndroidView(factory = { c -> VideoView(c).apply { setMediaController(MediaController(c).also { it.setAnchorView(this) }); setVideoPath(f.path); setOnPreparedListener { start() } } },
        modifier = Modifier.fillMaxSize(), onRelease = { it.stopPlayback() })
}

@Composable private fun ZipView(f: File, onExtract: (File, Set<String>?) -> Unit) {
    var items by remember(f) { mutableStateOf<List<ZipItem>?>(null) }; var err by remember(f) { mutableStateOf<String?>(null) }
    var sel by remember(f) { mutableStateOf(setOf<String>()) }
    LaunchedEffect(f) { withContext(Dispatchers.IO) { runCatching { Zips.list(f) } }.fold({ items = it }, { err = "ZIP no válido: ${it.message}" }) }
    err?.let { Message(it); return }
    val list = items ?: run { Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }; return }
    Column {
        Text("${list.count { !it.isDir }} archivos · ${humanSize(list.sumOf { maxOf(it.size, 0) })} descomprimidos", color = C.dim, fontSize = 12.sp, modifier = Modifier.padding(16.dp, 4.dp))
        Row(Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = { onExtract(f, null) }) { Text("Extraer todo") }
            TextButton(onClick = { onExtract(f, sel) }, enabled = sel.isNotEmpty()) { Text("Extraer ${sel.size} seleccionado(s)") }
        }
        Text("Se extrae a una carpeta junto al ZIP; no se sobrescribe nada.", color = C.dim, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(list, key = { it.name }) { z ->
                Row(Modifier.fillMaxWidth().clickable { sel = if (z.name in sel) sel - z.name else sel + z.name }.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(z.name in sel, { sel = if (z.name in sel) sel - z.name else sel + z.name })
                    Column(Modifier.weight(1f)) { Text(z.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp); Text(if (z.isDir) "carpeta" else "${humanSize(z.size)} (comprimido ${humanSize(z.compressed)})", color = C.dim, fontSize = 11.sp) }
                }
            }
        }
    }
}
