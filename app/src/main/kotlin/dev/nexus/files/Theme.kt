package dev.nexus.files

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.nexus.core.Kind
import dev.nexus.core.Protocol

enum class ThemeMode(val label: String) { SYSTEM("Auto"), LIGHT("Claro"), DARK("Oscuro"), AMOLED("AMOLED") }

/**
 * Neutros con matiz (azul-tinta), nunca grises puros. [hair] es el filo de 1 px de las superficies.
 * Capas: bg (fondo) < surface (tarjeta) < surface2 (elevada) < surface3 (control).
 */
class Palette(val bg: Color, val surface: Color, val surface2: Color, val surface3: Color, val text: Color, val dim: Color, val faint: Color, val hair: Color, val danger: Color, val dark: Boolean)

/**
 * Acento (identidad Ryoku Glass, 2026-11-19): cinco, uno activo, con variante oscura y clara (la clara es más oscura para
 * que el acento como texto o relleno llegue a 4,5:1 sobre el lienzo claro). [ink] es el texto sobre el relleno.
 * Los nombres del enum se conservan por lo guardado en preferencias: BRASA = Bermellón, ULTRAMAR = Egeo,
 * ESMERALDA = Lima, ROSA = Fucsia, AMBAR = Oro. ORQUIDEA se retiró (un valor guardado cae al acento por defecto).
 */
enum class Accent(val label: String, private val dark: Color, private val onDark: Color, private val light: Color) {
    BRASA("Bermellón", Color(0xFFFF5A4A), Color(0xFF1B0805), Color(0xFFD12B1F)),
    ULTRAMAR("Egeo", Color(0xFF62B6E8), Color(0xFF04141D), Color(0xFF0B6B99)),
    ESMERALDA("Lima", Color(0xFFB6E04A), Color(0xFF121A00), Color(0xFF4F7A00)),
    ROSA("Fucsia", Color(0xFFFF6FB1), Color(0xFF21040F), Color(0xFFC4166F)),
    AMBAR("Oro", Color(0xFFF2B33D), Color(0xFF1D1300), Color(0xFF8F5F00));

    val color: Color get() = if (Nx.pal.dark) dark else light
    val ink: Color get() = if (Nx.pal.dark) onDark else Color.White
    /** Sin degradados en Glass: las variantes «alta» y «armónica» son el propio acento. */
    val hi: Color get() = color
    val second: Color get() = color
}

/** Estado global de apariencia (preferencias). Lo leen los composables: cambiarlo recompone toda la app. */
object Nx {
    var mode by mutableStateOf(ThemeMode.SYSTEM)
    var accent by mutableStateOf(Accent.AMBAR)
    var haptics by mutableStateOf(true)
    var animations by mutableStateOf(true)
    var systemDark by mutableStateOf(true)
    /** Color ambiental del contexto actual (carpeta, tipo, origen); las pantallas lo fijan y los halos lo animan. */
    var ambient by mutableStateOf<Color?>(null)
    /** Rectángulo (en pantalla) del elemento tocado, origen de la transición contenedor→visor. */
    var origin: Rect? = null
    /** Nº de capas modales abiertas: con >0 el fondo se desenfoca. */
    var modals by mutableIntStateOf(0)
    /** La barra de pestañas se oculta (p. ej. mientras hay una selección y su barra de acciones). */
    var hideNav by mutableStateOf(false)

    private val dark = Palette(Color(0xFF0F1115), Color(0xFF171A20), Color(0xFF232830), Color(0xFF2C323C), Color(0xFFECEFF4), Color(0xFF8E97A6), Color(0xFF5F6877), Color(0x1AECEFF4), Color(0xFFFF7A70), true)
    private val amoled = Palette(Color(0xFF000000), Color(0xFF0D0F13), Color(0xFF181B21), Color(0xFF232730), Color(0xFFF2F4F8), Color(0xFF8E97A6), Color(0xFF5F6877), Color(0x1AECEFF4), Color(0xFFFF7A70), true)
    private val light = Palette(Color(0xFFF4F5F7), Color(0xFFFFFFFF), Color(0xFFE9ECF0), Color(0xFFDDE1E7), Color(0xFF14171C), Color(0xFF5F6773), Color(0xFF9AA2AE), Color(0x1C14171C), Color(0xFFB3261E), false)

    val pal: Palette get() = when (mode) { ThemeMode.LIGHT -> light; ThemeMode.DARK -> dark; ThemeMode.AMOLED -> amoled; ThemeMode.SYSTEM -> if (systemDark) dark else light }

    fun load(c: Context) {
        val p = c.getSharedPreferences("nexus", 0)
        mode = runCatching { ThemeMode.valueOf(p.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM)
        accent = runCatching { Accent.valueOf(p.getString("accent2", "AMBAR")!!) }.getOrDefault(Accent.AMBAR)
        haptics = p.getBoolean("haptics", true); animations = p.getBoolean("animations", true)
        systemDark = (c.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
    fun save(c: Context) = c.getSharedPreferences("nexus", 0).edit().putString("theme", mode.name).putString("accent2", accent.name).putBoolean("haptics", haptics).putBoolean("animations", animations).apply()

    /** «Reducir animaciones»: la del sistema (escala 0) o el ajuste propio. */
    fun reduceMotion(c: Context): Boolean = !animations || Settings.Global.getFloat(c.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}

/** Colores de la app. */
object C {
    val bg get() = Nx.pal.bg; val surface get() = Nx.pal.surface; val surface2 get() = Nx.pal.surface2; val surface3 get() = Nx.pal.surface3
    val text get() = Nx.pal.text; val dim get() = Nx.pal.dim; val faint get() = Nx.pal.faint; val rule get() = Nx.pal.hair; val hair get() = Nx.pal.hair; val danger get() = Nx.pal.danger
    val accent get() = Nx.accent.color; val accentHi get() = Nx.accent.hi; val accent2 get() = Nx.accent.second; val onAccent get() = Nx.accent.ink
    /** Sin degradados en Glass: el «pincel» de acento es plano. */
    val accentBrush: Brush get() = SolidColor(Nx.accent.color)

    /** Tipos de archivo: mismo croma y luminosidad en toda la rueda para que convivan; el color significa el tipo. */
    fun kind(k: Kind) = when (k) {
        Kind.FOLDER -> Nx.accent.color; Kind.IMAGE -> Color(0xFFFF5C93); Kind.VIDEO -> Color(0xFF9A7CFF)
        Kind.AUDIO -> Color(0xFF2ED3A3); Kind.DOCUMENT -> Color(0xFF4DA3FF); Kind.ARCHIVE -> Color(0xFFFFB547)
        Kind.CODE -> Color(0xFF3CD6D0); Kind.OTHER -> Color(0xFF8A93A3)
    }
    fun kindInk(k: Kind) = Color(0xFFFFFFFF)

    /** Orígenes: cada protocolo tiene su color propio (y la interfaz se tiñe de él al conectar). */
    fun origin(p: Protocol?) = when (p) {
        null -> Color(0xFF8A93A3)
        Protocol.FTP -> Color(0xFFFFB547); Protocol.FTPS -> Color(0xFF2ED3A3); Protocol.SFTP -> Color(0xFF34D17A)
        Protocol.SMB -> Color(0xFF4DA3FF); Protocol.WEBDAV -> Color(0xFF9A7CFF); Protocol.SAF -> Color(0xFFFF5C93)
    }
}

/** Inter (texto y números, SF-like) y JetBrains Mono (rutas, huellas, datos). Ambas OFL, instancias estáticas de las variables. */
object Fonts {
    val display = FontFamily(
        Font(R.font.inter_regular, FontWeight.Normal), Font(R.font.inter_medium, FontWeight.Medium), Font(R.font.inter_semibold, FontWeight.SemiBold),
        Font(R.font.inter_bold, FontWeight.Bold), Font(R.font.inter_extrabold, FontWeight.ExtraBold), Font(R.font.inter_extrabold, FontWeight.Black))
    val mono = FontFamily(Font(R.font.jetbrains_mono_regular, FontWeight.Normal), Font(R.font.jetbrains_mono_semibold, FontWeight.Bold), Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold))
}

@Composable
fun NexusTheme(content: @Composable () -> Unit) {
    val sys = isSystemInDarkTheme()
    SideEffect { if (Nx.systemDark != sys) Nx.systemDark = sys }
    val p = Nx.pal
    val a = Nx.accent
    MaterialTheme(
        colorScheme = (if (p.dark) darkColorScheme() else lightColorScheme()).copy(
            primary = a.color, onPrimary = a.ink, background = p.bg, surface = p.bg, onSurface = p.text, onBackground = p.text,
            surfaceVariant = p.surface2, onSurfaceVariant = p.dim, error = p.danger, outline = p.hair, surfaceContainer = p.surface, surfaceContainerHigh = p.surface2,
        ),
        content = content,
    )
}
