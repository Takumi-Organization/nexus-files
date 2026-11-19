package dev.nexus.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nexus.core.SortBy

/** Hoja de ajustes: tarjetas agrupadas, selector de tema con pieza deslizante y muestras de acento que laten al elegirlas. */
@Composable
fun SettingsScreen(vm: ExplorerViewModel, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val s by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var dl by remember { mutableStateOf(vm.downloadFolder) }
    fun persist() = Nx.save(ctx)
    DragDismiss(onClose) {
        Box(Modifier.fillMaxSize().background(C.bg)) {
            Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp).navigationBarsPadding()) {
                Row(Modifier.padding(start = 10.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Ajustes", style = T.title(52), modifier = Modifier.weight(1f))
                    NxBtn("Listo", onClose, small = true)
                }
                Group("Apariencia") {
                    NxSegment(ThemeMode.entries.map { it to it.label }, Nx.mode, { Nx.mode = it; persist() })
                    Spacer(Modifier.height(18.dp))
                    Text("Color de acento", style = T.label(13)); Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Accent.entries.forEach { a ->
                            val on = a == Nx.accent
                            val sc by androidx.compose.animation.core.animateFloatAsState(if (on) 1f else 0.86f, androidx.compose.animation.core.spring(0.5f, 500f), label = "sw")
                            val h = rememberHaptics()
                            Box(Modifier.weight(1f).aspectRatio(1f).graphicsLayer { scaleX = sc; scaleY = sc }.clip(CircleShape)
                                .background(a.color).then(if (on) Modifier.border(3.dp, C.text, CircleShape) else Modifier)
                                .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { h.confirm(); Nx.accent = a; persist() }, Alignment.Center) {
                                if (on) Text("✓", style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp, color = a.ink))
                            }
                        }
                    }
                    Text(Nx.accent.label, style = T.label(13, C.accent), modifier = Modifier.padding(top = 8.dp))
                }
                Group("Movimiento") {
                    NxToggle(Nx.animations, { Nx.animations = it; persist() }, "Animaciones", sub = "Se desactivan solas con «Reducir animaciones» del sistema")
                    NxToggle(Nx.haptics, { Nx.haptics = it; persist() }, "Respuesta háptica", sub = "Vibración sutil al tocar, seleccionar y confirmar")
                }
                Group("Explorador") {
                    NxToggle(s.showHidden, vm::setHidden, "Mostrar archivos ocultos")
                    NxToggle(s.grid, vm::setGrid, "Vista en cuadrícula")
                    Spacer(Modifier.height(8.dp)); Text("Orden por defecto", style = T.label(13)); Spacer(Modifier.height(8.dp))
                    NxSegment(listOf(SortBy.NAME to "Nombre", SortBy.SIZE to "Tamaño", SortBy.DATE to "Fecha", SortBy.TYPE to "Tipo"), s.sort.by, { vm.setSort(s.sort.copy(by = it)) })
                    NxToggle(s.sort.descending, { vm.setSort(s.sort.copy(descending = it)) }, "Orden descendente")
                    NxToggle(s.sort.foldersFirst, { vm.setSort(s.sort.copy(foldersFirst = it)) }, "Carpetas primero")
                }
                Group("Transferencias remotas") {
                    NxField(dl, { dl = it; vm.setDownloadFolder(it) }, "Carpeta de descargas (dentro del almacenamiento)", error = if (!vm.validDownloadFolder(dl)) "Ruta relativa sin «..»; se usa Download/NEXUS" else null)
                }
                Group("Acerca de") {
                    Text("NEXUS Files 0.1.0", style = T.name(22))
                    Text("Borrar es permanente (sin papelera). Las contraseñas de las conexiones se cifran con Android Keystore. FTP no cifra el tráfico: usa SFTP, FTPS o WebDAV sobre https cuando puedas.", style = TextStyle(fontFamily = Fonts.display, fontSize = 14.sp, color = C.dim, lineHeight = 20.sp), modifier = Modifier.padding(top = 6.dp))
                }
                Spacer(Modifier.height(30.dp))
            }
        }
    }
}

@Composable private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Eyebrow(title, Modifier.padding(top = 22.dp, bottom = 8.dp, start = 0.dp))
    Column(Modifier.fillMaxWidth().clip(Squircle(26.dp)).background(C.surface).border(1.dp, C.hair, Squircle(26.dp)).padding(18.dp), content = content)
}
