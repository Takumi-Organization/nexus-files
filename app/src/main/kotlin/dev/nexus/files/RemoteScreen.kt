package dev.nexus.files

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(s.message) { s.message?.let { snack.showSnackbar(it); vm.consumeMessage() } }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(s.active?.name ?: "Remoto", fontWeight = FontWeight.Black, fontSize = 22.sp, color = C.accent, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (s.active != null) {
                    TextButton(onClick = { pick.launch("*/*") }) { Text("Subir") }
                    TextButton(onClick = { dlg = RDlg.NewFolder }) { Text("Carpeta") }
                    TextButton(onClick = vm::disconnect) { Text("Salir") }
                } else { TextButton(onClick = { tree.launch(null) }) { Text("Carpeta") }; TextButton(onClick = { dlg = RDlg.Edit(null) }) { Text("Añadir") } }
            }
            if (jobs.isNotEmpty()) TransfersPanel(jobs)
            val a = s.active
            if (a == null) {
                if (s.profiles.isEmpty()) Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                    Text("Sin conexiones. Añade un servidor (FTP, FTPS, SFTP, SMB, WebDAV) o una carpeta del sistema.", color = C.dim)
                } else LazyColumn { items(s.profiles, key = { it.id }) { p ->
                    Row(Modifier.fillMaxWidth().clickable { vm.connect(p) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.Bold)
                            Text(if (p.protocol == Protocol.SAF) "Carpeta del sistema (acceso concedido por ti)" else "${p.protocol} · ${p.user.ifEmpty { "anónimo" }}@${p.host}${if (p.port > 0) ":${p.port}" else ""}", color = C.dim, fontSize = 13.sp)
                        }
                        TextButton(onClick = { dlg = if (p.protocol == Protocol.SAF) RDlg.Remove(p) else RDlg.Edit(p) }) { Text(if (p.protocol == Protocol.SAF) "Quitar" else "Editar") }
                    }
                } }
            } else {
                Text(s.path, color = C.dim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                if (insecure(a)) Text("Conexión sin cifrar: los datos viajan en claro (la contraseña, solo en FTP y WebDAV http).", color = C.danger, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
                when {
                    s.loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                    s.error != null -> Column(Modifier.padding(16.dp)) { Text(s.error!!, color = C.danger); TextButton(onClick = vm::reload) { Text("Reintentar") } }
                    s.entries.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) { Text("Carpeta vacía", color = C.dim) }
                    else -> LazyColumn { items(s.entries, key = { it.path }) { e ->
                        Row(Modifier.fillMaxWidth().clickable { if (e.isDir) vm.open(e) else dlg = RDlg.Menu(e) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (e.isDir) "▤" else "▫", color = if (e.isDir) C.accent else C.dim, fontSize = 20.sp, modifier = Modifier.width(32.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (!e.isDir) Text(humanSize(e.size), color = C.dim, fontSize = 12.sp)
                            }
                            TextButton(onClick = { dlg = RDlg.Menu(e) }) { Text("⋮") }
                        }
                    } }
                }
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }

    s.untrusted?.let { u ->
        val changed = s.active?.trusted?.isNotEmpty() == true
        AlertDialog(onDismissRequest = vm::rejectTrust, title = { Text(if (changed) "¡La identidad del servidor ha cambiado!" else "Servidor desconocido") },
            text = { Column {
                Text(if (changed) "Podría ser un ataque o que el servidor se haya reinstalado. No continúes si no lo esperabas." else "Es la primera vez que te conectas. Comprueba que la huella coincide con la del servidor antes de aceptar.", color = if (changed) C.danger else C.text)
                Spacer(Modifier.height(8.dp)); Text(u.kind, color = C.dim, fontSize = 12.sp)
                Text(u.fingerprint, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 12.sp)
            } },
            confirmButton = { TextButton(onClick = vm::acceptTrust) { Text(if (changed) "Fiarme de la nueva" else "Confiar y conectar", color = if (changed) C.danger else C.accent) } },
            dismissButton = { TextButton(onClick = vm::rejectTrust) { Text("Cancelar") } })
    }

    when (val d = dlg) {
        is RDlg.Edit -> ProfileDialog(d.p, onClose = { dlg = null }, onDelete = { d.p?.let { vm.delete(it.id) }; dlg = null }) { n, pr, h, po, u, b, pw, dom, key, pass -> vm.save(d.p?.id, n, pr, h, po, u, b, pw, dom, key, pass); dlg = null }
        is RDlg.Remove -> AlertDialog(onDismissRequest = { dlg = null }, title = { Text("Quitar «${d.p.name}»") }, text = { Text("Se revoca el acceso de NEXUS a esta carpeta. No se borra nada de ella.") },
            confirmButton = { TextButton(onClick = { vm.delete(d.p.id); dlg = null }) { Text("Quitar", color = C.danger) } }, dismissButton = { TextButton(onClick = { dlg = null }) { Text("Cancelar") } })
        RDlg.NewFolder -> NameDlg("Nueva carpeta", "", "Crear", { dlg = null }) { vm.mkdir(it); dlg = null }
        is RDlg.Rename -> NameDlg("Renombrar", d.e.name, "Renombrar", { dlg = null }) { vm.rename(d.e, it); dlg = null }
        is RDlg.Move -> PathDlg("Mover «${d.e.name}» a", s.path, "Mover", { dlg = null }) { vm.move(d.e, it); dlg = null }
        is RDlg.Delete -> AlertDialog(onDismissRequest = { dlg = null }, title = { Text("Eliminar «${d.e.name}»") },
            text = { Text(if (d.e.isDir) "Se borra la carpeta y todo su contenido en el servidor. No hay papelera." else "Se borra del servidor. No hay papelera.") },
            confirmButton = { TextButton(onClick = { vm.delete(d.e); dlg = null }) { Text("Eliminar", color = C.danger) } },
            dismissButton = { TextButton(onClick = { dlg = null }) { Text("Cancelar") } })
        is RDlg.Menu -> AlertDialog(onDismissRequest = { dlg = null }, title = { Text(d.e.name) }, confirmButton = { TextButton(onClick = { dlg = null }) { Text("Cerrar") } },
            text = { Column {
                TextButton(onClick = { vm.download(d.e); dlg = null }) { Text("Descargar a ${vm.downloadDir().name}") }
                TextButton(onClick = { dlg = RDlg.Rename(d.e) }) { Text("Renombrar") }
                TextButton(onClick = { dlg = RDlg.Move(d.e) }) { Text("Mover a…") }
                TextButton(onClick = { dlg = RDlg.Delete(d.e) }) { Text("Eliminar", color = C.danger) }
            } })
        null -> {}
    }
}

private fun insecure(p: RemoteProfile) = p.protocol == Protocol.FTP || p.protocol == Protocol.SMB || (p.protocol == Protocol.WEBDAV && p.host.startsWith("http://", true))

@Composable private fun TransfersPanel(jobs: List<TransferJob>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        jobs.takeLast(4).forEach { j ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(j.title, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (j.state == TState.RUNNING) "${j.current} · ${humanSize(j.bytes)}" else "${j.message}${if (j.bytes > 0) " · ${humanSize(j.bytes)}" else ""}",
                        fontSize = 12.sp, color = if (j.state == TState.FAILED) C.danger else C.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (j.state == TState.RUNNING) TextButton(onClick = { Transfers.cancel(j.id) }) { Text("Cancelar") }
            }
            if (j.state == TState.RUNNING) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (jobs.none { it.state == TState.RUNNING }) TextButton(onClick = Transfers::clearFinished) { Text("Limpiar") }
    }
}

@Composable private fun PathDlg(title: String, initial: String, ok: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(v, { v = it }, singleLine = true, label = { Text("Carpeta de destino (ruta, p. ej. /fotos)") }) },
        confirmButton = { TextButton(enabled = v.isNotBlank(), onClick = { onOk(v) }) { Text(ok) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

@Composable private fun NameDlg(title: String, initial: String, ok: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    val check = Names.validate(v)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(v, { v = it }, singleLine = true, isError = check is Names.Result.Invalid, supportingText = { (check as? Names.Result.Invalid)?.let { Text(it.reason) } }) },
        confirmButton = { TextButton(enabled = check is Names.Result.Ok, onClick = { onOk(v) }) { Text(ok) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
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
    AlertDialog(onDismissRequest = onClose, title = { Text(if (p == null) "Nueva conexión" else "Editar conexión") },
        text = { Column {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Protocol.entries.forEach { FilterChip(proto == it, { proto = it }, { Text(it.name) }) } }
            OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Nombre") })
            OutlinedTextField(host, { host = it }, singleLine = true, label = { Text("Servidor") })
            if (proto == Protocol.WEBDAV) Row(Modifier.clickable { plain = !plain }, verticalAlignment = Alignment.CenterVertically) { Checkbox(plain, { plain = it }); Text("Sin cifrar (http)", fontSize = 13.sp) }
            OutlinedTextField(port, { port = it.filter(Char::isDigit) }, singleLine = true, label = { Text("Puerto (opcional)") })
            if (proto == Protocol.WEBDAV) OutlinedTextField(base, { base = it }, singleLine = true, label = { Text("Ruta base, p. ej. /remote.php/dav") })
            if (proto == Protocol.SMB) OutlinedTextField(base, { base = it }, singleLine = true, label = { Text("Recurso compartido") })
            OutlinedTextField(user, { user = it }, singleLine = true, label = { Text("Usuario") })
            OutlinedTextField(pw, { pw = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(), label = { Text(if (p == null) "Contraseña" else "Contraseña (vacío = conservar)") })
            if (proto == Protocol.SMB) OutlinedTextField(domain, { domain = it }, singleLine = true, label = { Text("Dominio (opcional)") })
            if (proto == Protocol.SFTP) {
                OutlinedTextField(key, { key = it }, minLines = 3, maxLines = 4, label = { Text(if (p == null) "Clave privada (pegar, opcional)" else "Clave privada (vacío = conservar)") }, visualTransformation = PasswordVisualTransformation())
                TextButton(onClick = { pickKey.launch("*/*") }) { Text("Elegir archivo de clave…") }
                if (key.isNotBlank()) OutlinedTextField(pass, { pass = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(), label = { Text("Frase de paso de la clave (si tiene)") })
            }
            Text("Contraseña, clave y frase de paso se guardan cifradas con Android Keystore.", color = C.dim, fontSize = 12.sp)
        } },
        confirmButton = { TextButton(enabled = valid, onClick = { onSave(name, proto, if (proto == Protocol.WEBDAV && plain) "http://${host.trim()}" else host.trim(), portN ?: 0, user, if (proto == Protocol.WEBDAV || proto == Protocol.SMB) base else "", pw, if (proto == Protocol.SMB) domain else "", if (proto == Protocol.SFTP) key else "", pass) }) { Text("Guardar") } },
        dismissButton = { Row { if (p != null) TextButton(onClick = onDelete) { Text("Borrar", color = C.danger) }; TextButton(onClick = onClose) { Text("Cancelar") } } })
}
