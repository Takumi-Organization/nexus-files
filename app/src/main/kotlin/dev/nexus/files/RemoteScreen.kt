package dev.nexus.files

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.animation.core.animateFloat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.nexus.core.*

private sealed interface RDlg {
    data class Edit(val p: RemoteProfile?) : RDlg
    data class Remove(val p: RemoteProfile) : RDlg
    data object NewFolder : RDlg
    data class Rename(val e: RemoteEntry) : RDlg
    data class Delete(val e: RemoteEntry) : RDlg
    data class Move(val e: RemoteEntry) : RDlg
    data class Menu(val e: RemoteEntry) : RDlg
}

@Composable
fun RemoteScreen(vm: RemoteViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val jobs by Transfers.jobs.collectAsStateWithLifecycle()
    var dlg by remember { mutableStateOf<RDlg?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) vm.upload(uri) }
    val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) vm.addSaf(uri) }
    val toast = rememberToast(s.message, vm::consumeMessage)
    val a = s.active
    val tint = C.origin(a?.protocol)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.statusBarsPadding().padding(top = 14.dp)) {
                Row(Modifier.padding(start = 24.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(a?.name ?: "Remoto", style = T.title(52), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (a == null) { NxBtn("Carpeta", { tree.launch(null) }, kind = BtnKind.GHOST, small = true); Spacer(Modifier.width(8.dp)); NxBtn("Añadir", { dlg = RDlg.Edit(null) }, small = true) }
                }
                if (a != null) {
                    // estado de conexión visible, como una terminal: protocolo, usuario y host con un punto de vida
                    Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        LiveDot(tint, s.loading)
                        Spacer(Modifier.width(8.dp))
                        Text("${a.protocol.name.lowercase()}://${a.user.ifEmpty { "anónimo" }}@${a.host.substringAfter("://")}${if (a.port > 0) ":${a.port}" else ""}", style = T.mono(13, C.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(s.path, style = T.mono(13, tint), modifier = Modifier.padding(horizontal = 24.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { NxChip("Subir", false, { pick.launch("*/*") }) }
                        item { NxChip("Carpeta", false, { dlg = RDlg.NewFolder }) }
                        item { NxChip("Actualizar", false, { vm.reload() }) }
                        item { NxChip("Salir", false, vm::disconnect) }
                    }
                    if (insecure(a)) Row(Modifier.padding(horizontal = 24.dp).padding(bottom = 6.dp).clip(CircleShape).background(C.danger.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 6.dp)) { Text("Sin cifrar: los datos viajan en claro", style = T.label(12, C.danger)) }
                }
            }
            if (jobs.isNotEmpty()) TransfersPanel(jobs, tint)
            if (a == null) {
                if (s.profiles.isEmpty()) NxEmpty(Kind.OTHER, "Sin destinos", "Añade un servidor (FTP, FTPS, SFTP, SMB, WebDAV) o elige una carpeta del sistema.") { NxBtn("Añadir servidor", { dlg = RDlg.Edit(null) }) }
                else LazyColumn(contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 130.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(s.profiles, key = { _, p -> p.id }) { i, p -> ProfileCard(p, i, { vm.connect(p) }) { dlg = if (p.protocol == Protocol.SAF) RDlg.Remove(p) else RDlg.Edit(p) } }
                }
            } else {
                when {
                    s.loading -> Box(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) { NxProgress(null, color = tint) }
                    s.error != null -> NxEmpty(Kind.OTHER, "Sin conexión", s.error!!, danger = true) { NxBtn("Reintentar", vm::reload) }
                    s.entries.isEmpty() -> NxEmpty(Kind.FOLDER, "Vacía", "Esta carpeta remota no contiene nada.")
                    else -> LazyColumn(contentPadding = PaddingValues(bottom = 130.dp)) { itemsIndexed(s.entries, key = { _, e -> e.path }) { i, e ->
                        EntryRow(Entry(e.path, e.name, e.isDir, e.size, e.modified, e.name.startsWith('.'), true), false, { if (e.isDir) vm.open(e) else dlg = RDlg.Menu(e) }, { dlg = RDlg.Menu(e) }, index = i)
                    } }
                }
            }
        }
        NxToast(toast, Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp))
    }

    s.untrusted?.let { u ->
        val changed = s.active?.trusted?.isNotEmpty() == true
        NxDialog(if (changed) "¡La identidad cambió!" else "Servidor desconocido", vm::rejectTrust, {
            NxBtn("Cancelar", vm::rejectTrust, kind = BtnKind.GHOST, small = true)
            NxBtn(if (changed) "Fiarme de la nueva" else "Confiar y conectar", vm::acceptTrust, kind = if (changed) BtnKind.DANGER else BtnKind.PRIMARY, small = true)
        }) {
            Text(if (changed) "Podría ser un ataque o que el servidor se haya reinstalado. No continúes si no lo esperabas." else "Es la primera vez que te conectas. Comprueba que la huella coincide con la del servidor antes de aceptar.", style = TextStyle(fontFamily = Fonts.display, fontSize = 15.sp, color = if (changed) C.danger else C.dim, lineHeight = 21.sp))
            Spacer(Modifier.height(12.dp)); Text(u.kind, style = T.label(12))
            Box(Modifier.padding(top = 4.dp, bottom = 8.dp).fillMaxWidth().clip(Squircle(14.dp)).background(C.surface3).padding(12.dp)) { Text(u.fingerprint, style = T.mono(12)) }
        }
    }

    when (val d = dlg) {
        is RDlg.Edit -> ProfileDialog(d.p, onClose = { dlg = null }, onDelete = { d.p?.let { vm.delete(it.id) }; dlg = null }) { n, pr, h, po, u, b, pw, dom, key, pass -> vm.save(d.p?.id, n, pr, h, po, u, b, pw, dom, key, pass); dlg = null }
        is RDlg.Remove -> NxConfirm("Quitar «${d.p.name}»", "Se revoca el acceso de NEXUS a esta carpeta. No se borra nada de ella.", "Quitar", true, { dlg = null }) { vm.delete(d.p.id); dlg = null }
        RDlg.NewFolder -> NxNameDialog("Nueva carpeta", "", "Crear", { v -> (Names.validate(v) as? Names.Result.Invalid)?.reason }, { dlg = null }) { vm.mkdir(it); dlg = null }
        is RDlg.Rename -> NxNameDialog("Renombrar", d.e.name, "Renombrar", { v -> (Names.validate(v) as? Names.Result.Invalid)?.reason }, { dlg = null }) { vm.rename(d.e, it); dlg = null }
        is RDlg.Move -> { var v by remember { mutableStateOf(s.path) }
            NxDialog("Mover «${d.e.name}»", { dlg = null }, { NxBtn("Cancelar", { dlg = null }, kind = BtnKind.GHOST, small = true); NxBtn("Mover", { vm.move(d.e, v); dlg = null }, enabled = v.isNotBlank(), small = true) }) { NxField(v, { v = it }, "Carpeta de destino (p. ej. /fotos)") } }
        is RDlg.Delete -> NxConfirm("Eliminar «${d.e.name}»", if (d.e.isDir) "Se borra la carpeta y todo su contenido en el servidor. No hay papelera." else "Se borra del servidor. No hay papelera.", "Eliminar", true, { dlg = null }) { vm.delete(d.e); dlg = null }
        is RDlg.Menu -> NxDialog(d.e.name, { dlg = null }, { NxBtn("Cerrar", { dlg = null }, kind = BtnKind.GHOST, small = true) }) {
            MenuItem("Descargar a ${vm.downloadDir().name}", C.accent) { vm.download(d.e); dlg = null }
            MenuItem("Renombrar") { dlg = RDlg.Rename(d.e) }
            MenuItem("Mover a…") { dlg = RDlg.Move(d.e) }
            MenuItem("Eliminar", C.danger) { dlg = RDlg.Delete(d.e) }
        }
        null -> {}
    }
}

@Composable private fun MenuItem(text: String, color: Color = C.text, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(Squircle(16.dp)).background(C.surface3).nxClick(onClick).padding(horizontal = 18.dp, vertical = 15.dp)) { Text(text, style = TextStyle(fontFamily = Fonts.display, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = color)) }
}

/** Punto de estado: respira cuando hay actividad. */
@Composable private fun LiveDot(color: Color, busy: Boolean) {
    val reduce = rememberReduceMotion()
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "dot")
    val pulse by t.animateFloat(initialValue = 1f, targetValue = 2.2f, animationSpec = androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1100), androidx.compose.animation.core.RepeatMode.Restart), label = "p")
    Box(Modifier.size(18.dp), Alignment.Center) {
        if (!reduce) Box(Modifier.size(8.dp).graphicsLayer { scaleX = pulse; scaleY = pulse; alpha = (2.2f - pulse) / 1.8f * (if (busy) 1f else 0.5f) }.background(color, CircleShape))
        Box(Modifier.size(8.dp).background(color, CircleShape))
    }
}

@Composable private fun ProfileCard(p: RemoteProfile, index: Int, onOpen: () -> Unit, onMore: () -> Unit) {
    val c = C.origin(p.protocol)
    Row(Modifier.fillMaxWidth().enter(index).clip(Squircle(26.dp)).background(C.surface).border(1.dp, C.hair, Squircle(26.dp))
        .nxClick(onOpen).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(54.dp).clip(Squircle(17.dp)).background(c.copy(alpha = 0.18f)), Alignment.Center) { Text(if (p.protocol == Protocol.SAF) "SAF" else p.protocol.name.take(4), style = TextStyle(fontFamily = Fonts.mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = c)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, style = T.name(22), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (p.protocol == Protocol.SAF) "Carpeta del sistema" else "${p.user.ifEmpty { "anónimo" }}@${p.host.substringAfter("://")}${if (p.port > 0) ":${p.port}" else ""}", style = T.mono(12, C.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        NxBtn(if (p.protocol == Protocol.SAF) "Quitar" else "Editar", onMore, kind = BtnKind.GHOST, small = true)
    }
}

private fun insecure(p: RemoteProfile) = p.protocol == Protocol.FTP || p.protocol == Protocol.SMB || (p.protocol == Protocol.WEBDAV && p.host.startsWith("http://", true))

/** Convoyes: cada transferencia con su velocidad en grande (cifras que ruedan) y una barra viva del color del origen. */
@Composable private fun TransfersPanel(jobs: List<TransferJob>, tint: Color) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        jobs.takeLast(3).forEach { j ->
            val run = j.state == TState.RUNNING
            val speed = rememberSpeed(if (run) j.bytes else 0L)
            Column(Modifier.fillMaxWidth().clip(Squircle(24.dp)).background(C.surface2).border(1.dp, C.hair, Squircle(24.dp)).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(j.title, style = T.name(20), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (run) j.current.ifEmpty { "Conectando…" } else "${j.message}${if (j.bytes > 0) " · ${humanSize(j.bytes)}" else ""}", style = TextStyle(fontFamily = Fonts.display, fontSize = 13.sp, color = if (j.state == TState.FAILED) C.danger else C.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (run) { val (n, u) = sizeParts(speed.toLong()); Column(horizontalAlignment = Alignment.End) { RollingText(n, T.numeral(44)); Text("$u/s", style = T.label(11)) } }
                }
                if (run) { Spacer(Modifier.height(10.dp)); NxProgress(null, color = tint); Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) { Text(humanSize(j.bytes), style = TextStyle(fontFamily = Fonts.display, fontSize = 13.sp, color = C.dim), modifier = Modifier.weight(1f)); NxBtn("Cancelar", { Transfers.cancel(j.id) }, kind = BtnKind.GHOST, small = true) } }
            }
        }
        if (jobs.none { it.state == TState.RUNNING }) NxBtn("Limpiar", Transfers::clearFinished, kind = BtnKind.GHOST, small = true)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ProfileDialog(p: RemoteProfile?, onClose: () -> Unit, onDelete: () -> Unit, onSave: (String, Protocol, String, Int, String, String, String, String, String, String) -> Unit) {
    var name by remember { mutableStateOf(p?.name ?: "") }
    var proto by remember { mutableStateOf(p?.protocol ?: Protocol.WEBDAV) }
    var host by remember { mutableStateOf((p?.host ?: "").substringAfter("://")) }
    var plain by remember { mutableStateOf(p?.host?.startsWith("http://", true) == true) }
    var port by remember { mutableStateOf((p?.port ?: 0).takeIf { it > 0 }?.toString() ?: "") }
    var user by remember { mutableStateOf(p?.user ?: "") }
    var base by remember { mutableStateOf(p?.basePath ?: "") }
    var pw by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf(p?.domain ?: "") }
    var key by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val pickKey = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) key = runCatching { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().take(64 * 1024).toByteArray().toString(Charsets.UTF_8) } }.getOrDefault("") }
    val portN = if (port.isEmpty()) (0) else port.toIntOrNull()
    val valid = name.isNotBlank() && host.isNotBlank() && portN != null && portN in 0..65535 && (proto != Protocol.SMB || base.isNotBlank()) && (p != null || pw.isNotEmpty() || user.isEmpty() || (proto == Protocol.SFTP && key.isNotBlank()))
    val tint = C.origin(proto)
    NxDialog(if (p == null) "Nuevo destino" else "Editar destino", onClose, {
        if (p != null) NxBtn("Borrar", onDelete, kind = BtnKind.DANGER, small = true)
        NxBtn("Cancelar", onClose, kind = BtnKind.GHOST, small = true)
        NxBtn("Guardar", { onSave(name, proto, if (proto == Protocol.WEBDAV && plain) "http://${host.trim()}" else host.trim(), portN ?: 0, user, if (proto == Protocol.WEBDAV || proto == Protocol.SMB || proto == Protocol.FTPS) base else "", pw, if (proto == Protocol.SMB) domain else "", if (proto == Protocol.SFTP) key else "", pass) }, enabled = valid, small = true)
    }) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
            Protocol.entries.forEach { pr ->
                val on = proto == pr; val col = C.origin(pr)
                Box(Modifier.heightIn(min = 40.dp).clip(CircleShape).background(if (on) col else col.copy(alpha = 0.14f)).nxClick({ proto = pr }).padding(horizontal = 16.dp), Alignment.Center) {
                    Text(pr.name, style = TextStyle(fontFamily = Fonts.display, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = if (on) Color.White else col), maxLines = 1)
                }
            }
        }
        NxField(name, { name = it }, "Nombre")
        NxField(host, { host = it }, "Servidor")
        if (proto == Protocol.WEBDAV) NxToggle(plain, { plain = it }, "Sin cifrar (http)")
        NxField(port, { port = it.filter(Char::isDigit) }, "Puerto (opcional)")
        if (proto == Protocol.WEBDAV) NxField(base, { base = it }, "Ruta base, p. ej. /remote.php/dav")
        if (proto == Protocol.FTPS) NxToggle(base == RemoteFactory.TLS12, { base = if (it) RemoteFactory.TLS12 else "" }, "Solo TLS 1.2", sub = "Si el servidor exige reutilizar la sesión TLS")
        if (proto == Protocol.SMB) NxField(base, { base = it }, "Recurso compartido")
        if (proto == Protocol.SMB) NxField(domain, { domain = it }, "Dominio (opcional)")
        NxField(user, { user = it }, "Usuario")
        NxField(pw, { pw = it }, if (p == null) "Contraseña" else "Contraseña (vacío = conservar)", secret = true)
        if (proto == Protocol.SFTP) {
            NxField(key, { key = it }, if (p == null) "Clave privada (pegar, opcional)" else "Clave privada (vacío = conservar)", singleLine = false, minLines = 3, secret = true)
            NxBtn("Elegir archivo de clave…", { pickKey.launch("*/*") }, kind = BtnKind.GHOST, small = true, modifier = Modifier.padding(vertical = 4.dp))
            if (key.isNotBlank()) NxField(pass, { pass = it }, "Frase de paso de la clave (si tiene)", secret = true)
        }
        Text("Contraseña, clave y frase de paso se guardan cifradas con Android Keystore.", style = T.label(12, C.dim).copy(fontWeight = FontWeight.Normal), modifier = Modifier.padding(vertical = 8.dp))
    }
}
