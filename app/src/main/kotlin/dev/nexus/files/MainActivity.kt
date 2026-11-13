package dev.nexus.files

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
        // Android 14+ hace que ZipFile lance excepción ante entradas con ".." y no deja ni listar el ZIP. Los quitamos del
        // validador del sistema porque Zips.safeTarget ya descarta cada ruta insegura al extraer (cubierto por tests).
        if (android.os.Build.VERSION.SDK_INT >= 34) dalvik.system.ZipPathValidator.clearCallback()
        setContent {
            NexusTheme {
                var granted by remember { mutableStateOf(Environment.isExternalStorageManager()) }
                val owner = LocalLifecycleOwner.current
                DisposableEffect(owner) {
                    val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) granted = Environment.isExternalStorageManager() }
                    owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
                }
                if (granted) Root(onShare = ::share, onOpenExternal = ::openExternal, initialView = debugViewTarget())
                else PermissionGate {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                }
            }
        }
    }

    /** Solo en builds depurables: `--es dev.nexus.files.VIEW_PATH /ruta` abre el visor directamente (para pruebas sin tocar la pantalla). */
    private fun debugViewTarget(): dev.nexus.core.Entry? {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
        val p = intent?.getStringExtra("dev.nexus.files.VIEW_PATH") ?: return null
        return java.io.File(p).takeIf { it.isFile }?.let(dev.nexus.core.LocalFs::entry)
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

private enum class Tab(val label: String, val glyph: String) { HOME("Inicio", "⌂"), FILES("Archivos", "▤"), SEARCH("Buscar", "⌕"), ANALYZE("Analizar", "◔"), REMOTE("Remoto", "⇄") }

@Composable
private fun Root(onShare: (List<java.io.File>) -> Unit, onOpenExternal: (java.io.File) -> Boolean, initialView: dev.nexus.core.Entry? = null) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    val explorer: ExplorerViewModel = viewModel()
    val home: HomeViewModel = viewModel()
    val analyzer: AnalyzerViewModel = viewModel()
    val remote: RemoteViewModel = viewModel()
    var settings by remember { mutableStateOf(false) }
    val st by explorer.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { explorer.start() }
    var viewing by remember { mutableStateOf(initialView) }
    fun openEntry(e: dev.nexus.core.Entry) {
        if (e.isDir) { explorer.open(e.file); tab = Tab.FILES }
        else { home.addRecent(e.path); if (Viewers.of(e) != null) viewing = e else onOpenExternal(e.file) }
    }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                Tab.HOME -> HomeScreen(home, ::openEntry, { explorer.open(it); tab = Tab.FILES }) { k ->
                    home.updateSearch({ it.copy(kind = k, name = "", ext = "", inCurrent = false) }, st.dir); tab = Tab.SEARCH
                }
                Tab.FILES -> ExplorerScreen({ settings = true }, onShare, { home.addRecent(it.path); onOpenExternal(it) }, ::openEntry, explorer, home)
                Tab.SEARCH -> SearchScreen(home, st.dir, ::openEntry)
                Tab.REMOTE -> { BackHandler(enabled = remote.state.value.active != null) { remote.up() }; RemoteScreen(remote) }
                Tab.ANALYZE -> AnalyzerScreen(analyzer, home.primaryRoot, st.dir) { explorer.open(it); tab = Tab.FILES }
            }
        }
        NavigationBar { Tab.entries.forEach { t -> NavigationBarItem(tab == t, { tab = t }, { Text(t.glyph, fontSize = 20.sp) }, label = { Text(t.label) }) } }
    }
    if (settings) SettingsScreen(explorer) { settings = false }
    viewing?.let { v -> Viewers.of(v)?.let { k ->
        Box(Modifier.fillMaxSize()) {
            ViewerScreen(v, k, onClose = { viewing = null }, onExternal = { onOpenExternal(v.file) }, onExtract = { z, only -> explorer.extract(z, only); viewing = null; tab = Tab.FILES })
        }
    } }
    }
}
