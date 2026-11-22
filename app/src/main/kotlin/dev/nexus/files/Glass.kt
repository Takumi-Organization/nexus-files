package dev.nexus.files

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Takumi no usa cristal ni desenfoque: las capas flotantes son paneles opacos con borde de 1 dp (`lineaAlta`).
 * Se conservan los nombres de la API anterior (`glass`, `glassSource`) para no tocar cada pantalla.
 */
class GlassBackdrop internal constructor()

@Composable fun rememberGlassBackdrop(): GlassBackdrop = remember { GlassBackdrop() }

val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }

@Suppress("UnusedReceiverParameter", "UNUSED_PARAMETER")
fun Modifier.glassSource(backdrop: GlassBackdrop): Modifier = this

@Composable
@Suppress("UNUSED_PARAMETER")
fun Modifier.glass(shape: Shape, blur: Dp = 0.dp, tint: Color? = null): Modifier =
    this.clip(shape).background(tint ?: C.surface).border(1.dp, C.hair, shape)
