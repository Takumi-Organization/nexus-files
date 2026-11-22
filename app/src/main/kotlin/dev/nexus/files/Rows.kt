package dev.nexus.files

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nexus.core.Entry
import dev.nexus.core.Kind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dayFmt = SimpleDateFormat("d MMM yyyy", Locale.forLanguageTag("es"))
fun shortDate(ms: Long): String = dayFmt.format(Date(ms)).replace(".", "")

/** (número, unidad) para mostrar tamaños en grande: «4,2» «MB». */
fun sizeParts(b: Long): Pair<String, String> {
    if (b < 1024) return b.toString() to "B"
    val u = arrayOf("KB", "MB", "GB", "TB"); var v = b.toDouble(); var i = -1
    do { v /= 1024; i++ } while (v >= 1024 && i < u.lastIndex)
    return (if (v >= 100) "%.0f".format(v) else "%.1f".format(v)).replace('.', ',') to u[i]
}

/** Fila de archivo: baldosa tonal con icono fino, nombre, detalle, tamaño. La selección resalta con una cápsula que respira. */
@Composable fun EntryRow(e: Entry, sel: Boolean, onClick: () -> Unit, onLong: () -> Unit, star: Boolean = false, index: Int = 99, subtitle: String? = null, modifier: Modifier = Modifier) {
    val selA by animateFloatAsState(if (sel) 1f else 0f, spring(0.8f, 600f), label = "rowsel")
    val k = e.kind
    Box(modifier.padding(horizontal = 10.dp, vertical = 1.dp)) {
        Row(Modifier.fillMaxWidth().background(if (selA > 0f) C.accent.copy(alpha = 0.16f * selA) else Color.Transparent, Squircle(18.dp))
            .nxClick(onClick, onLong, tick = false, origin = !e.isDir, pressScale = 1f).padding(horizontal = 12.dp, vertical = 9.dp).heightIn(min = 62.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                FileTag(e, 46.dp, solid = sel)
                if (selA > 0f) Box(Modifier.size(46.dp).graphicsLayer { alpha = selA; scaleX = 0.7f + 0.3f * selA; scaleY = 0.7f + 0.3f * selA }.clip(Squircle(14.dp)).background(C.accent), Alignment.Center) { Text("✓", style = TextStyle(fontFamily = Fonts.display, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = C.onAccent)) }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(e.name, style = T.name(21, if (e.hidden) C.dim else C.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle ?: (if (e.isDir) "Carpeta · ${shortDate(e.modified)}" else "${e.extension.uppercase().ifEmpty { "Archivo" }} · ${shortDate(e.modified)}"), style = TextStyle(fontFamily = Fonts.display, fontSize = 13.sp, color = C.dim, letterSpacing = (-0.1).sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (star) Text("★", style = TextStyle(fontSize = 15.sp, color = C.accent), modifier = Modifier.padding(horizontal = 8.dp))
            if (!e.isDir) { val (n, u) = sizeParts(e.size); Text("$n $u", style = TextStyle(fontFamily = Fonts.display, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = C.dim, fontFeatureSettings = "tnum")) }
            else Text("›", style = TextStyle(fontFamily = Fonts.display, fontSize = 26.sp, color = C.faint))
        }
    }
}

/** Celda de cuadrícula: baldosa grande tonal con el icono del tipo. */
@Composable fun GridCell(e: Entry, sel: Boolean, onClick: () -> Unit, onLong: () -> Unit, index: Int = 99, modifier: Modifier = Modifier) {
    val k = e.kind; val c = C.kind(k)
    val selA by animateFloatAsState(if (sel) 1f else 0f, spring(0.75f, 600f), label = "gsel")
    Column(modifier.enter(index).nxClick(onClick, onLong, tick = false, origin = !e.isDir)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).graphicsLayer { val s = 1f - 0.05f * selA; scaleX = s; scaleY = s }.clip(Squircle(26.dp))
            .background(if (selA > 0.5f) c else c.copy(alpha = if (Nx.pal.dark) 0.17f else 0.14f))) {
            Text(if (e.isDir) "" else e.extension.take(4).uppercase(), style = T.label(11, if (selA > 0.5f) Color.White.copy(alpha = 0.85f) else c), modifier = Modifier.padding(12.dp))
            Glyph(k, 66.dp, Modifier.align(Alignment.Center), ink = if (selA > 0.5f) Color.White else c)
            if (selA > 0f) Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).size(28.dp).graphicsLayer { alpha = selA; scaleX = selA; scaleY = selA }.background(C.accent, TkSm), Alignment.Center) { Text("✓", style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp, color = C.onAccent)) }
        }
        Text(e.name, style = T.name(18), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp, start = 2.dp))
        if (!e.isDir) Text(sizeParts(e.size).let { "${it.first} ${it.second}" }, style = TextStyle(fontFamily = Fonts.display, fontSize = 12.sp, color = C.dim), modifier = Modifier.padding(start = 2.dp, bottom = 4.dp))
    }
}

/** Cabecera de pantalla: título grande tipo «Large Title» con subtítulo de sección y halo ambiental. */
@Composable fun ScreenHeader(num: String, title: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().statusBarsPadding().padding(start = 24.dp, end = 16.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(title, style = T.title(52), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke(this)
    }
}
