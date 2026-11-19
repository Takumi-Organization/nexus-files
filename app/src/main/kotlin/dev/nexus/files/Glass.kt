package dev.nexus.files

import android.os.Build
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * Cristal de la identidad Ryoku Glass (misma técnica que LUMINA, adaptada a la paleta `C`/`Nx`).
 * Se graba el contenido de detrás en una capa ([glassSource]) y cada superficie [glass] la dibuja desenfocada bajo su
 * velo. La superficie de cristal debe ser HERMANA del contenido grabado, no hija. Con API < 31 (sin RenderEffect) o sin
 * fondo registrado pasa a opaca con el mismo diseño.
 */
class GlassBackdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var origin by mutableStateOf(Offset.Zero)
}

@Composable fun rememberGlassBackdrop(): GlassBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassBackdrop(layer) }
}

val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }

fun Modifier.glassSource(backdrop: GlassBackdrop): Modifier = this
    .onGloballyPositioned { backdrop.origin = it.positionInRoot() }
    .drawWithContent {
        backdrop.layer.record { this@drawWithContent.drawContent() }
        drawLayer(backdrop.layer)
    }

@Composable
fun Modifier.glass(shape: Shape, blur: Dp = 30.dp, tint: Color? = null): Modifier {
    val backdrop = LocalGlassBackdrop.current
    val dark = Nx.pal.dark
    val canBlur = backdrop != null && Build.VERSION.SDK_INT >= 31
    val veil = (tint ?: C.surface).copy(alpha = if (!canBlur) 0.96f else if (dark) 0.84f else 0.88f)
    val top = if (dark) Color(0x24FFFFFF) else Color(0xE6FFFFFF)
    val edge = if (dark) Color(0x1FECEFF4) else Color(0x1A14171C)
    val own = rememberGraphicsLayer()
    var pos by remember { mutableStateOf(Offset.Zero) }
    return this
        .onGloballyPositioned { pos = it.positionInRoot() }
        .clip(shape)
        .drawBehind {
            if (canBlur && backdrop != null) {
                val r = blur.toPx()
                own.renderEffect = BlurEffect(r, r, TileMode.Clamp)
                val dx = backdrop.origin.x - pos.x
                val dy = backdrop.origin.y - pos.y
                val density = this
                own.record(density, layoutDirection, IntSize(size.width.toInt(), size.height.toInt())) {
                    translate(dx, dy) { drawLayer(backdrop.layer) }
                }
                drawLayer(own)
            }
            drawRect(veil)
        }
        .border(1.dp, Brush.verticalGradient(listOf(top, edge)), shape)
}
