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
 * Takumi no tiene acento: los cinco valores del enum se conservan solo por lo guardado en preferencias y todos
 * resuelven a la tinta. (Histórico: identidad Ryoku Glass, 2026-11-19): cinco, uno activo, con variante oscura y clara (la clara es más oscura para
 * que el acento como texto o relleno llegue a 4,5:1 sobre el lienzo claro). [ink] es el texto sobre el relleno.
 * Los nombres del enum se conservan por lo guardado en preferencias: BRASA = Bermellón, ULTRAMAR = Egeo,
 * ESMERALDA = Lima, ROSA = Fucsia, AMBAR = Oro. ORQUIDEA se retiró (un valor guardado cae al acento por defecto).
 */
enum class Accent(val label: String) {
    BRASA("Tinta"), ULTRAMAR("Tinta"), ESMERALDA("Tinta"), ROSA("Tinta"), AMBAR("Tinta");

    val color: Color get() = if (Nx.pal.dark) Color(0xFFF2F2F2) else Color(0xFF0A0A0A)
    val ink: Color get() = if (Nx.pal.dark) Color.Black else Color(0xFFF4F4F4)
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

    // Takumi (tokens.json): panel/elevado/línea; «surface3» = control (línea) y «hair» = línea de filas.
    private val dark = Palette(Color(0xFF000000), Color(0xFF080808), Color(0xFF111111), Color(0xFF1C1C1C), Color(0xFFF2F2F2), Color(0xFF8C8C8C), Color(0xFF4A4A4A), Color(0xFF3A3A3A), Color(0xFFF2F2F2), true)
    private val amoled = dark
    private val light = Palette(Color(0xFFF4F4F4), Color(0xFFFFFFFF), Color(0xFFEBEBEB), Color(0xFFE0E0E0), Color(0xFF0A0A0A), Color(0xFF6B6B6B), Color(0xFFB0B0B0), Color(0xFFC0C0C0), Color(0xFF0A0A0A), false)

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
    fun kind(k: Kind): Color {
        val l = when (k) { Kind.FOLDER -> 0xF2; Kind.IMAGE -> 0xD6; Kind.VIDEO -> 0xB8; Kind.AUDIO -> 0x9C; Kind.DOCUMENT -> 0x80; Kind.ARCHIVE -> 0x68; Kind.CODE -> 0x54; Kind.OTHER -> 0x40 }
        return if (Nx.pal.dark) Color(l, l, l) else Color(0xFF - l + 0x0A, 0xFF - l + 0x0A, 0xFF - l + 0x0A)
    }
    /** Texto o icono sobre el relleno de [kind]: contraste máximo según su luminancia. */
    fun kindInk(k: Kind): Color = if (Nx.pal.dark) (if (k == Kind.CODE || k == Kind.OTHER || k == Kind.ARCHIVE) Color.White else Color.Black) else (if (k == Kind.CODE || k == Kind.OTHER || k == Kind.ARCHIVE) Color.Black else Color.White)

    /** Orígenes: cada protocolo se distingue por su nombre (la sigla va siempre en texto) y un gris propio. */
    fun origin(p: Protocol?): Color {
        val l = when (p) { null -> 0x8C; Protocol.FTP -> 0xB0; Protocol.FTPS -> 0xD0; Protocol.SFTP -> 0xF2; Protocol.SMB -> 0x9A; Protocol.WEBDAV -> 0xC4; Protocol.SAF -> 0xE0 }
        return if (Nx.pal.dark) Color(l, l, l) else Color(0xFF - l + 0x0A, 0xFF - l + 0x0A, 0xFF - l + 0x0A)
    }
}

/** Outfit (títulos, texto y números: fina y ancha) y JetBrains Mono (rutas, huellas, datos). Ambas OFL. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
object Fonts {
    private fun outfit(w: Int, key: FontWeight) = Font(R.font.outfit_variable, key, variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(w)))
    /** Pesos de la app → pesos Takumi: lo «Bold» (cifras y títulos) pasa a 200, el cuerpo a 300, lo enfatizado a 500. */
    val display = FontFamily(
        outfit(300, FontWeight.Normal), outfit(400, FontWeight.Medium), outfit(500, FontWeight.SemiBold),
        outfit(200, FontWeight.Bold), outfit(200, FontWeight.ExtraBold), outfit(200, FontWeight.Black))
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
            surfaceVariant = p.surface2, onSurfaceVariant = p.dim, error = p.danger, outline = p.hair, surfaceTint = Color.Transparent, surfaceContainer = p.surface, surfaceContainerHigh = p.surface2,
        ),
        content = content,
    )
}
