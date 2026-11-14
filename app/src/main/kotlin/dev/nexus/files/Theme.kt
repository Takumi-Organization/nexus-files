package dev.nexus.files

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
 * Acento principal vibrante + tono claro [hi] (misma familia, para degradados tonales sutiles) + armónico [second]
 * (análogo a ±35° en la rueda, para los halos ambientales). [ink] es el texto sobre el acento.
 */
enum class Accent(val label: String, val color: Color, val hi: Color, val second: Color, val ink: Color) {
    BRASA("Brasa", Color(0xFFFF5A3C), Color(0xFFFF8A5C), Color(0xFFFFB24A), Color(0xFFFFFFFF)),
    ULTRAMAR("Ultramar", Color(0xFF3D6BFF), Color(0xFF6B93FF), Color(0xFF8B6BFF), Color(0xFFFFFFFF)),
    ESMERALDA("Esmeralda", Color(0xFF10C98A), Color(0xFF4FE3AB), Color(0xFF2BC7D8), Color(0xFF03150F)),
    ORQUIDEA("Orquídea", Color(0xFFB84DFF), Color(0xFFD683FF), Color(0xFFFF4DA6), Color(0xFFFFFFFF)),
    ROSA("Rosa", Color(0xFFFF3D7F), Color(0xFFFF7AA3), Color(0xFFFF7A45), Color(0xFFFFFFFF)),
    AMBAR("Ámbar", Color(0xFFFFB020), Color(0xFFFFD35C), Color(0xFFFF7A30), Color(0xFF1A1100)),
}

/** Estado global de apariencia (preferencias). Lo leen los composables: cambiarlo recompone toda la app. */
object Nx {
    var mode by mutableStateOf(ThemeMode.SYSTEM)
    var accent by mutableStateOf(Accent.BRASA)
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

    private val dark = Palette(Color(0xFF090B0F), Color(0xFF12151B), Color(0xFF1A1E26), Color(0xFF242934), Color(0xFFF5F7FB), Color(0xFF9AA4B5), Color(0xFF5E6778), Color(0x14FFFFFF), Color(0xFFFF5C6C), true)
    private val amoled = Palette(Color(0xFF000000), Color(0xFF0B0D11), Color(0xFF14171D), Color(0xFF1E222B), Color(0xFFF7F8FC), Color(0xFF9AA4B5), Color(0xFF5E6778), Color(0x17FFFFFF), Color(0xFFFF5C6C), true)
    private val light = Palette(Color(0xFFF2F4F9), Color(0xFFFFFFFF), Color(0xFFE9ECF3), Color(0xFFDDE2EC), Color(0xFF0A0D14), Color(0xFF5A6578), Color(0xFF98A2B3), Color(0x140A0F1E), Color(0xFFE5303F), false)

    val pal: Palette get() = when (mode) { ThemeMode.LIGHT -> light; ThemeMode.DARK -> dark; ThemeMode.AMOLED -> amoled; ThemeMode.SYSTEM -> if (systemDark) dark else light }

    fun load(c: Context) {
        val p = c.getSharedPreferences("nexus", 0)
        mode = runCatching { ThemeMode.valueOf(p.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM)
        accent = runCatching { Accent.valueOf(p.getString("accent2", "BRASA")!!) }.getOrDefault(Accent.BRASA)
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
    val accentBrush get() = Brush.verticalGradient(listOf(Nx.accent.hi, Nx.accent.color))

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
