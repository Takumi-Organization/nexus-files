package dev.nexus.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nexus.core.SortBy

@Composable
fun SettingsScreen(vm: ExplorerViewModel, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val s by vm.state.collectAsStateWithLifecycle()
    var dl by remember { mutableStateOf(vm.downloadFolder) }
    Column(Modifier.fillMaxSize().background(C.bg).statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("←") }
            Text("Ajustes", fontWeight = FontWeight.Black, fontSize = 22.sp, color = C.accent)
        }
        Section("Explorador")
        SwitchRow("Mostrar archivos ocultos", s.showHidden, vm::setHidden)
        SwitchRow("Vista en cuadrícula", s.grid, vm::setGrid)
        Text("Orden por defecto", color = C.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        Row { listOf(SortBy.NAME to "Nombre", SortBy.SIZE to "Tamaño", SortBy.DATE to "Fecha", SortBy.TYPE to "Tipo").forEach { (b, l) ->
            FilterChip(s.sort.by == b, { vm.setSort(s.sort.copy(by = b)) }, { Text(l) }, modifier = Modifier.padding(end = 6.dp)) } }
        SwitchRow("Orden descendente", s.sort.descending) { vm.setSort(s.sort.copy(descending = it)) }
        SwitchRow("Carpetas primero", s.sort.foldersFirst) { vm.setSort(s.sort.copy(foldersFirst = it)) }
        Section("Transferencias remotas")
        OutlinedTextField(dl, { dl = it; vm.setDownloadFolder(it) }, singleLine = true, label = { Text("Carpeta de descargas (dentro del almacenamiento)") },
            isError = !vm.validDownloadFolder(dl), supportingText = { if (!vm.validDownloadFolder(dl)) Text("Ruta relativa sin «..»; se usa Download/NEXUS") }, modifier = Modifier.fillMaxWidth())
        Section("Acerca de")
        Text("NEXUS Files 0.1.0\nBorrar es permanente (sin papelera). Las contraseñas de conexiones se cifran con Android Keystore.\nFTP no cifra el tráfico; usa WebDAV sobre https cuando puedas.", color = C.dim, fontSize = 13.sp)
    }
}

@Composable private fun Section(t: String) = Text(t, fontWeight = FontWeight.Bold, color = C.accent, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))

@Composable private fun SwitchRow(label: String, v: Boolean, on: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { on(!v) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(v, on)
    }
}
