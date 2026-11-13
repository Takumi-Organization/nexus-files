package dev.nexus.files

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.nexus.core.Kind

object C {
    val bg = Color(0xFF0E1013); val surface = Color(0xFF171A1F); val surface2 = Color(0xFF1F242B)
    val text = Color(0xFFE8EAED); val dim = Color(0xFF8B929C); val accent = Color(0xFFFFB300); val danger = Color(0xFFFF5252)
    fun kind(k: Kind) = when (k) {
        Kind.FOLDER -> Color(0xFFFFB300); Kind.IMAGE -> Color(0xFF26C6DA); Kind.VIDEO -> Color(0xFFEF5350)
        Kind.AUDIO -> Color(0xFFAB47BC); Kind.DOCUMENT -> Color(0xFF66BB6A); Kind.ARCHIVE -> Color(0xFF8D6E63)
        Kind.CODE -> Color(0xFF5C9DFF); Kind.OTHER -> Color(0xFF78909C)
    }
}

@Composable
fun NexusTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.accent, onPrimary = Color.Black, background = C.bg, surface = C.surface, onSurface = C.text,
            onBackground = C.text, surfaceVariant = C.surface2, onSurfaceVariant = C.dim, error = C.danger,
            surfaceContainerHigh = C.surface2, surfaceContainer = C.surface,
        ),
        content = content,
    )
}
