package dev.nexus.files

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.nexus.core.Kind
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NexusTheme {
                var granted by remember { mutableStateOf(Environment.isExternalStorageManager()) }
                val owner = LocalLifecycleOwner.current
                DisposableEffect(owner) {
                    val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) granted = Environment.isExternalStorageManager() }
                    owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
                }
                if (granted) Root(onShare = ::share, onOpenExternal = ::openExternal)
                else PermissionGate {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                }
            }
        }
    }

    private fun uriFor(f: java.io.File) = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)

    private fun mime(f: java.io.File) = android.webkit.MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"

    private fun share(files: List<java.io.File>) {
        val files = files.filter { it.isFile }
        if (files.isEmpty()) return
        val i = Intent(if (files.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = if (files.size == 1) mime(files[0]) else "*/*"
            if (files.size == 1) putExtra(Intent.EXTRA_STREAM, uriFor(files[0]))
            else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files.map(::uriFor)))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, "Compartir"))
    }

    private fun openExternal(f: java.io.File): Boolean = runCatching {
        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(f), mime(f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }.isSuccess
}

private enum class Tab(val label: String, val glyph: String) { HOME("Inicio", "⌂"), FILES("Archivos", "▤"), SEARCH("Buscar", "⌕"), ANALYZE("Analizar", "◔") }

@Composable
private fun Root(onShare: (List<java.io.File>) -> Unit, onOpenExternal: (java.io.File) -> Boolean) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    val explorer: ExplorerViewModel = viewModel()
    val home: HomeViewModel = viewModel()
    val analyzer: AnalyzerViewModel = viewModel()
    val st by explorer.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { explorer.start() }
    fun openEntry(e: dev.nexus.core.Entry) {
        if (e.isDir) { explorer.open(e.file); tab = Tab.FILES }
        else { home.addRecent(e.path); onOpenExternal(e.file) }
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                Tab.HOME -> HomeScreen(home, ::openEntry, { explorer.open(it); tab = Tab.FILES }) { k ->
                    home.updateSearch({ it.copy(kind = k, name = "", ext = "", inCurrent = false) }, st.dir); tab = Tab.SEARCH
                }
                Tab.FILES -> ExplorerScreen(onShare, { home.addRecent(it.path); onOpenExternal(it) }, explorer, home)
                Tab.SEARCH -> SearchScreen(home, st.dir, ::openEntry)
                Tab.ANALYZE -> AnalyzerScreen(analyzer, home.primaryRoot, st.dir) { explorer.open(it); tab = Tab.FILES }
            }
        }
        NavigationBar { Tab.entries.forEach { t -> NavigationBarItem(tab == t, { tab = t }, { Text(t.glyph, fontSize = 20.sp) }, label = { Text(t.label) }) } }
    }
}
