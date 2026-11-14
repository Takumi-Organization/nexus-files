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
import androidx.compose.material3.Text
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
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
        // Android 13+: sin este permiso el servicio en primer plano corre pero su notificación (con «Cancelar») no se ve. Se pide una sola vez.
        val prefs = getSharedPreferences("nexus", 0)
        if (android.os.Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("askedNotif", false) &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean("askedNotif", true).apply()
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        Nx.load(this)
        debugAppearance()
        setContent {
            NexusTheme {
                val view = androidx.compose.ui.platform.LocalView.current
                SideEffect {
                    window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(C.bg.toArgb()))
                    androidx.core.view.WindowCompat.getInsetsController(window, view).apply { isAppearanceLightStatusBars = !Nx.pal.dark; isAppearanceLightNavigationBars = !Nx.pal.dark }
                }
                var granted by remember { mutableStateOf(Environment.isExternalStorageManager()) }
                val owner = LocalLifecycleOwner.current
                DisposableEffect(owner) {
                    val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) granted = Environment.isExternalStorageManager() }
                    owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
                }
                if (granted) Root(onShare = ::share, onOpenExternal = ::openExternal, initialView = debugViewTarget(), nav = debugNav())
                var limited by rememberSaveable { mutableStateOf(false) }
                if (!granted) {
                    if (limited) LimitedRoot(onBack = { limited = false })
                    else PermissionGate({ limited = true }) {
                        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                    }
                }
            }
        }
    }

    private fun isDebug() = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** Solo depuración: `--es dev.nexus.files.THEME dark|light|amoled`, `--es dev.nexus.files.ACCENT ULTRAMAR` (no se guardan). */
    private fun debugAppearance() {
        if (!isDebug()) return
        intent?.getStringExtra("dev.nexus.files.THEME")?.let { t -> Nx.mode = when (t) { "dark" -> ThemeMode.DARK; "light" -> ThemeMode.LIGHT; "amoled" -> ThemeMode.AMOLED; else -> ThemeMode.SYSTEM } }
        intent?.getStringExtra("dev.nexus.files.ACCENT")?.let { a -> runCatching { Accent.valueOf(a) }.getOrNull()?.let { Nx.accent = it } }
    }

    /** Solo depuración: abre una pestaña (`TAB` 0-4) y/o una carpeta (`OPEN_DIR`). */
    private fun debugNav(): Pair<Int?, String?> = if (!isDebug()) null to null else intent?.getStringExtra("dev.nexus.files.TAB")?.toIntOrNull() to intent?.getStringExtra("dev.nexus.files.OPEN_DIR")

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

private enum class Tab(val label: String) { HOME("Inicio"), FILES("Archivos"), SEARCH("Buscar"), ANALYZE("Analizar"), REMOTE("Remoto") }

@Composable
private fun Root(onShare: (List<java.io.File>) -> Unit, onOpenExternal: (java.io.File) -> Boolean, initialView: dev.nexus.core.Entry? = null, nav: Pair<Int?, String?> = null to null) {
    var tab by rememberSaveable { mutableStateOf(nav.first?.let { Tab.entries.getOrNull(it) } ?: Tab.HOME) }
    val explorer: ExplorerViewModel = viewModel()
    val home: HomeViewModel = viewModel()
    val analyzer: AnalyzerViewModel = viewModel()
    val remote: RemoteViewModel = viewModel()
    var settings by remember { mutableStateOf(false) }
    val st by explorer.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { explorer.start(); nav.second?.let { explorer.open(java.io.File(it)); if (nav.first == null) tab = Tab.FILES } }
    var viewing by remember { mutableStateOf(initialView) }
    val reduce = rememberReduceMotion()
    fun openEntry(e: dev.nexus.core.Entry) {
        if (e.isDir) { explorer.open(e.file); tab = Tab.FILES }
        else { home.addRecent(e.path); if (Viewers.of(e) != null) viewing = e else onOpenExternal(e.file) }
    }
    val rst by remote.state.collectAsStateWithLifecycle()
    val ast by analyzer.state.collectAsStateWithLifecycle()
    SideEffect { Nx.ambient = when (tab) { Tab.ANALYZE -> ast.kind?.let { C.kind(it) }; Tab.REMOTE -> rst.active?.let { C.origin(it.protocol) }; else -> null } }
    val blurred = Nx.modals > 0 || settings || viewing != null
    val blur by androidx.compose.animation.core.animateDpAsState(if (blurred && !reduce) 18.dp else 0.dp, androidx.compose.animation.core.spring(0.9f, 300f), label = "blur")
    val dim by androidx.compose.animation.core.animateFloatAsState(if (blurred) 0.9f else 1f, androidx.compose.animation.core.spring(0.9f, 300f), label = "dim")
    Box(Modifier.fillMaxSize().background(C.bg)) {
    Column(Modifier.fillMaxSize().then(if (blur > 0.dp) Modifier.blur(blur) else Modifier).graphicsLayer { scaleX = 0.985f + 0.015f * dim.let { (it - 0.9f) * 10f }; scaleY = scaleX }) {
        Box(Modifier.weight(1f)) {
            androidx.compose.animation.AnimatedContent(tab, transitionSpec = {
                if (reduce) androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
                else {
                    val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.spring(0.85f, 420f)) { it / 6 * dir } + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(160)) + androidx.compose.animation.scaleIn(androidx.compose.animation.core.spring(0.85f, 420f), 0.96f)) togetherWith
                        (androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(180)) { -it / 10 * dir } + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(110)))
                }
            }, label = "tabs") { t ->
                when (t) {
                    Tab.HOME -> HomeScreen(home, ::openEntry, { explorer.open(it); tab = Tab.FILES }, { k ->
                        home.updateSearch({ it.copy(kind = k, name = "", ext = "", inCurrent = false) }, st.dir); tab = Tab.SEARCH
                    }, onSettings = { settings = true })
                    Tab.FILES -> ExplorerScreen({ settings = true }, onShare, { home.addRecent(it.path); onOpenExternal(it) }, ::openEntry, explorer, home)
                    Tab.SEARCH -> SearchScreen(home, st.dir, ::openEntry)
                    Tab.REMOTE -> { BackHandler(enabled = rst.active != null) { remote.up() }; RemoteScreen(remote) }
                    Tab.ANALYZE -> AnalyzerScreen(analyzer, home.primaryRoot, st.dir) { explorer.open(it); tab = Tab.FILES }
                }
            }
            androidx.compose.animation.AnimatedVisibility(!Nx.hideNav, Modifier.align(Alignment.BottomCenter), enter = androidx.compose.animation.slideInVertically(androidx.compose.animation.core.spring(0.8f, 450f)) { it } + androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.spring(0.9f, 600f)) { it } + androidx.compose.animation.fadeOut()) { Box {
                Box(Modifier.matchParentSize().background(C.bg))
                Box(Modifier.align(Alignment.TopCenter).offset(y = (-44).dp).fillMaxWidth().height(44.dp).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, C.bg))))
                NxNav(Tab.entries.map { it.label }, tab.ordinal) { tab = Tab.entries[it] }
            } }
        }
    }
    androidx.compose.animation.AnimatedVisibility(settings, enter = androidx.compose.animation.slideInVertically(androidx.compose.animation.core.spring(0.85f, 380f)) { it / 3 } + androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.slideOutVertically { it / 3 } + androidx.compose.animation.fadeOut()) {
        SettingsScreen(explorer) { settings = false }
    }
    viewing?.let { v -> Viewers.of(v)?.let { k ->
        Box(Modifier.fillMaxSize()) {
            ViewerScreen(v, k, onClose = { viewing = null }, onExternal = { onOpenExternal(v.file) }, onExtract = { z, only -> explorer.extract(z, only); viewing = null; tab = Tab.FILES })
        }
    } }
    }
}

/** Barra de pestañas flotante: cápsula translúcida con sombra, iconos de trazo fino y una pastilla que se desliza con muelle. */
@Composable
private fun NxNav(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val h = rememberHaptics()
    val shape = Squircle(30.dp)
    BoxWithConstraints(Modifier.navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth().height(70.dp)
        .androidx_shadow(shape).clip(shape).background(C.surface2.copy(alpha = 0.985f)).border(1.dp, C.hair, shape).padding(5.dp)) {
        val w = maxWidth / labels.size
        val x by androidx.compose.animation.core.animateDpAsState(w * selected, androidx.compose.animation.core.spring(0.72f, 520f), label = "nx")
        Box(Modifier.offset(x = x).width(w).fillMaxHeight().clip(Squircle(24.dp)).background(C.accent.copy(alpha = 0.16f)))
        Row(Modifier.fillMaxSize()) {
            labels.forEachIndexed { i, l ->
                val on = i == selected
                val col by androidx.compose.animation.animateColorAsState(if (on) C.accent else C.dim, androidx.compose.animation.core.spring(stiffness = 700f), label = "tc")
                val lift by androidx.compose.animation.core.animateFloatAsState(if (on) -2f else 0f, androidx.compose.animation.core.spring(0.6f, 600f), label = "lift")
                Column(Modifier.weight(1f).fillMaxHeight().clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { if (!on) { h.tick(); onSelect(i) } }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    androidx.compose.foundation.Canvas(Modifier.size(26.dp).graphicsLayer { translationY = lift.dp.toPx() }) { drawTabIcon(i, col) }
                    Text(l, style = androidx.compose.ui.text.TextStyle(fontFamily = Fonts.display, fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Medium, fontSize = 11.sp, color = col), maxLines = 1)
                }
            }
        }
    }
}

private fun Modifier.androidx_shadow(shape: androidx.compose.ui.graphics.Shape) = this.shadow(26.dp, shape, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.45f))

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTabIcon(i: Int, c: Color) {
    val s = size.minDimension; val w = s * 0.085f
    val st = androidx.compose.ui.graphics.drawscope.Stroke(width = w, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
    fun o(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x * s, y * s)
    when (i) {
        0 -> { val p = androidx.compose.ui.graphics.Path().apply { moveTo(.14f * s, .48f * s); lineTo(.5f * s, .16f * s); lineTo(.86f * s, .48f * s); moveTo(.24f * s, .42f * s); lineTo(.24f * s, .82f * s); lineTo(.76f * s, .82f * s); lineTo(.76f * s, .42f * s) }; drawPath(p, c, style = st); drawLine(c, o(.42f, .82f), o(.42f, .60f), strokeWidth = w, cap = androidx.compose.ui.graphics.StrokeCap.Round); drawLine(c, o(.58f, .82f), o(.58f, .60f), strokeWidth = w, cap = androidx.compose.ui.graphics.StrokeCap.Round) }
        1 -> drawGlyph(dev.nexus.core.Kind.FOLDER, c)
        2 -> { drawCircle(c, .27f * s, o(.44f, .44f), style = st); drawLine(c, o(.64f, .64f), o(.86f, .86f), strokeWidth = w * 1.1f, cap = androidx.compose.ui.graphics.StrokeCap.Round) }
        3 -> { drawArc(c, -90f, 250f, false, o(.16f, .16f), androidx.compose.ui.geometry.Size(.68f * s, .68f * s), style = st); drawArc(c, 170f, 70f, false, o(.28f, .28f), androidx.compose.ui.geometry.Size(.44f * s, .44f * s), style = st) }
        else -> { drawRoundRect(c, o(.16f, .18f), androidx.compose.ui.geometry.Size(.68f * s, .26f * s), androidx.compose.ui.geometry.CornerRadius(.09f * s), style = st); drawRoundRect(c, o(.16f, .56f), androidx.compose.ui.geometry.Size(.68f * s, .26f * s), androidx.compose.ui.geometry.CornerRadius(.09f * s), style = st); drawCircle(c, .03f * s, o(.30f, .31f)); drawCircle(c, .03f * s, o(.30f, .69f)) }
    }
}

/** Sin «acceso a todos los archivos»: solo carpetas concedidas por SAF y servidores remotos. */
@Composable
private fun LimitedRoot(onBack: () -> Unit) {
    val remote: RemoteViewModel = viewModel()
    BackHandler { if (!remote.up()) onBack() }
    Column(Modifier.fillMaxSize().background(C.bg)) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).statusBarsPadding().clip(androidx.compose.foundation.shape.CircleShape).background(C.accent.copy(alpha = 0.16f)).padding(horizontal = 14.dp, vertical = 7.dp)) {
            Text("Modo limitado · solo las carpetas que elijas", style = T.label(12, C.accent))
        }
        Box(Modifier.weight(1f)) { RemoteScreen(remote) }
    }
}
