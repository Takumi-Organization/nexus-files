package dev.nexus.files

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.drawscope.clipRect
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import dev.nexus.core.Entry
import dev.nexus.core.Kind
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

// ---------------- Tipografía: contraste fuerte, números grandes y tabulares ----------------
object T {
    private const val TNUM = "tnum"
    fun numeral(sp: Int, color: Color = C.text) = TextStyle(fontFamily = Fonts.display, fontWeight = FontWeight.Bold, fontSize = (sp * 0.78f).sp, lineHeight = (sp * 0.96f).sp, color = color, letterSpacing = (-sp * 0.034f).sp, fontFeatureSettings = TNUM)
    fun title(sp: Int = 30, color: Color = C.text) = TextStyle(fontFamily = Fonts.display, fontWeight = FontWeight.Bold, fontSize = (sp * 0.8f).sp, lineHeight = (sp * 1.0f).sp, color = color, letterSpacing = (-sp * 0.022f).sp)
    fun name(sp: Int = 20, color: Color = C.text) = TextStyle(fontFamily = Fonts.display, fontWeight = FontWeight.Medium, fontSize = (sp * 0.82f).sp, lineHeight = (sp * 1.0f).sp, color = color, letterSpacing = (-0.2).sp)
    fun label(sp: Int = 11, color: Color = C.dim) = TextStyle(fontFamily = Fonts.display, fontWeight = FontWeight.SemiBold, fontSize = sp.sp, lineHeight = (sp * 1.35f).sp, color = color, letterSpacing = 0.5.sp)
    fun mono(sp: Int = 13, color: Color = C.text) = TextStyle(fontFamily = Fonts.mono, fontSize = (sp * 0.92f).sp, lineHeight = (sp * 1.4f).sp, color = color)
}

@Composable fun NxText(t: String, style: TextStyle, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) = Text(t, modifier, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)

// ---------------- Forma: esquina continua (squircle) ----------------
private fun squirclePath(w: Float, h: Float, r: Float): Path {
    val e = (r * 1.32f).coerceAtMost(min(w, h) / 2); val k = 0.36f
    return Path().apply {
        moveTo(e, 0f); lineTo(w - e, 0f); cubicTo(w - e * k, 0f, w, e * k, w, e)
        lineTo(w, h - e); cubicTo(w, h - e * k, w - e * k, h, w - e, h)
        lineTo(e, h); cubicTo(e * k, h, 0f, h - e * k, 0f, h - e)
        lineTo(0f, e); cubicTo(0f, e * k, e * k, 0f, e, 0f); close()
    }
}
/**
 * Ryoku Glass usa radios pequeños (6 · 10 · 12 · 16 · 20). Se conserva el nombre y los radios de cada sitio de la app
 * y se traducen a la escala nueva en un solo punto, en vez de tocar cada llamada.
 */
fun glassRadius(r: Dp): Dp = when { r <= 8.dp -> 6.dp; r <= 14.dp -> 10.dp; r <= 22.dp -> 12.dp; r <= 30.dp -> 16.dp; else -> 20.dp }
class Squircle(private val radius: Dp = 16.dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: androidx.compose.ui.unit.LayoutDirection, density: androidx.compose.ui.unit.Density): Outline =
        androidx.compose.foundation.shape.RoundedCornerShape(glassRadius(radius)).createOutline(size, layoutDirection, density)
}
/** Compatibilidad: el antiguo «chaflán» ahora es la esquina continua con ese radio. */
fun Chamfer(cut: Dp = 12.dp, corner: Int = 1): Shape = Squircle(cut * 1.1f)

// ---------------- Movimiento y hápticos ----------------
@Composable fun rememberReduceMotion(): Boolean { val c = LocalContext.current; return remember(Nx.animations) { Nx.reduceMotion(c) } }

class Haptics(private val v: View) {
    private fun go(c: Int) { if (Nx.haptics) v.performHapticFeedback(c) }
    fun tick() = go(HapticFeedbackConstants.CLOCK_TICK)
    fun select() = go(if (android.os.Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_START else HapticFeedbackConstants.LONG_PRESS)
    fun confirm() = go(if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
    fun reject() = go(if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
    fun threshold() = go(if (android.os.Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
}
@Composable fun rememberHaptics(): Haptics { val v = LocalView.current; return remember(v) { Haptics(v) } }

/** Muelles con carácter: rápidos, con un rebote contenido (no elásticos de relleno). */
val springy = spring<Float>(dampingRatio = 0.72f, stiffness = 520f)
val springSoft = spring<Float>(dampingRatio = 0.86f, stiffness = 300f)
fun <T> snappy() = spring<T>(dampingRatio = 0.8f, stiffness = 700f)

/** Entrada escalonada: sube y aparece con un muelle, con retardo por posición. */
@Composable fun Modifier.enter(index: Int, enabled: Boolean = true): Modifier {
    val reduce = rememberReduceMotion()
    if (reduce || !enabled || index > 14) return this
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(index * 34L); p.animateTo(1f, spring(0.78f, 380f)) }
    return this.graphicsLayer { alpha = p.value.coerceIn(0f, 1f); translationY = (1f - p.value) * 28.dp.toPx(); val s = 0.96f + 0.04f * p.value; scaleX = s; scaleY = s }
}

/** Pulsación con muelle (escala) y háptico; guarda el rectángulo tocado para la transición contenedor→visor. */
@OptIn(ExperimentalFoundationApi::class)
@Composable fun Modifier.nxClick(onClick: () -> Unit, onLong: (() -> Unit)? = null, enabled: Boolean = true, tick: Boolean = true, origin: Boolean = false, pressScale: Float = 0.965f): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val reduce = rememberReduceMotion()
    val h = rememberHaptics()
    // Holder sin estado: `onGloballyPositioned` se dispara en cada frame de scroll y un `mutableStateOf` aquí
    // recompondría cada fila visible continuamente (medido: p50 36 ms → 16 ms al quitarlo).
    class Bounds(var r: Rect? = null)
    val bounds = remember { Bounds() }
    val s by animateFloatAsState(if (pressed && !reduce) pressScale else 1f, if (pressed) snappy() else spring(0.5f, 450f), label = "press")
    // En filas de listas largas no se crea capa por elemento (pressScale = 1): ahorra una capa por fila visible.
    val press = if (pressScale != 1f && s != 1f) Modifier.graphicsLayer { scaleX = s; scaleY = s } else Modifier
    return this.then(press).then(if (origin) Modifier.onGloballyPositioned { bounds.r = it.boundsInRoot() } else Modifier)
        .combinedClickable(src, null, enabled = enabled, onClick = { if (tick) h.tick(); if (origin) Nx.origin = bounds.r; onClick() }, onLongClick = onLong?.let { l -> { h.select(); l() } })
}

/** Cifras que ruedan: cada dígito sube/baja con un muelle al cambiar (como un odómetro). */
@Composable fun RollingText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    val reduce = rememberReduceMotion()
    if (reduce) { Text(text, modifier, style = style, maxLines = 1); return }
    Row(modifier) {
        text.forEachIndexed { i, ch ->
            AnimatedContent(ch, transitionSpec = {
                val up = targetState.code > initialState.code
                (slideInVertically(spring(0.82f, 520f)) { if (up) it else -it } + fadeIn(tween(120))) togetherWith (slideOutVertically(spring(0.82f, 520f)) { if (up) -it else it } + fadeOut(tween(90)))
            }, label = "roll$i") { c -> Text(c.toString(), style = style, maxLines = 1, softWrap = false) }
        }
    }
}

// ---------------- Botones, chips, interruptores ----------------
enum class BtnKind { PRIMARY, INK, GHOST, DANGER }

@Composable fun NxBtn(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: BtnKind = BtnKind.PRIMARY, enabled: Boolean = true, small: Boolean = false) {
    val shape = Squircle(if (small) 12.dp else 16.dp)
    val fg = when (kind) { BtnKind.PRIMARY -> C.onAccent; BtnKind.INK -> C.bg; BtnKind.GHOST -> C.text; BtnKind.DANGER -> Color.White }
    val bg: Modifier = when (kind) {
        BtnKind.PRIMARY -> Modifier.background(C.accent, shape)
        BtnKind.INK -> Modifier.background(C.text, shape)
        BtnKind.GHOST -> Modifier.background(C.surface3, shape)
        BtnKind.DANGER -> Modifier.background(C.danger, shape)
    }
    Box(modifier.heightIn(min = if (small) 40.dp else 54.dp).graphicsLayer { alpha = if (enabled) 1f else 0.4f }.then(bg).clip(shape).nxClick(onClick, enabled = enabled).padding(horizontal = if (small) 16.dp else 24.dp), Alignment.Center) {
        Text(text, style = TextStyle(fontFamily = Fonts.display, fontWeight = FontWeight.SemiBold, fontSize = if (small) 14.sp else 16.sp, color = fg, letterSpacing = (-0.2).sp), maxLines = 1)
    }
}

@Composable fun NxChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val bg by animateColorAsState(if (selected) C.accent else C.surface3, snappy(), label = "chipbg")
    val fg by animateColorAsState(if (selected) C.onAccent else C.text, snappy(), label = "chipfg")
    Box(modifier.heightIn(min = 40.dp).graphicsLayer { alpha = if (enabled) 1f else 0.4f }.clip(CircleShape).background(bg).nxClick(onClick, enabled = enabled).padding(horizontal = 16.dp), Alignment.Center) {
        Text(text, style = TextStyle(fontFamily = Fonts.display, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = fg, letterSpacing = (-0.1).sp), maxLines = 1)
    }
}

/** Interruptor estilo iOS: pista que cambia de color y bola con muelle. */
@Composable fun NxSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val track by animateColorAsState(if (checked) C.accent else C.surface3, snappy(), label = "tr")
    val x by animateDpAsState(if (checked) 20.dp else 0.dp, spring(0.65f, 600f), label = "kn")
    Box(Modifier.size(width = 52.dp, height = 32.dp).clip(CircleShape).background(track).padding(3.dp)) {
        Box(Modifier.offset(x = x).size(26.dp).shadow(3.dp, CircleShape).background(Color.White, CircleShape))
    }
}

@Composable fun NxToggle(checked: Boolean, onChange: (Boolean) -> Unit, label: String, modifier: Modifier = Modifier, sub: String? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).nxClick({ onChange(!checked) }, pressScale = 0.985f).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(label, style = T.name(20)); if (sub != null) Text(sub, style = T.label(12, C.dim).copy(fontWeight = FontWeight.Normal)) }
        NxSwitch(checked, onChange)
    }
}

/** Control segmentado: cápsula con una pieza deslizante que se mueve con muelle. */
@Composable fun <T> NxSegment(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val idx = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val h = rememberHaptics()
    BoxWithConstraints(modifier.fillMaxWidth().height(44.dp).clip(CircleShape).background(C.surface3).padding(3.dp)) {
        val w = maxWidth / options.size
        val x by animateDpAsState(w * idx, spring(0.72f, 520f), label = "seg")
        Box(Modifier.offset(x = x).width(w).fillMaxHeight().background(C.surface2, CircleShape).border(1.dp, C.hair, CircleShape))
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, (v, l) ->
                Box(Modifier.weight(1f).fillMaxHeight().clip(CircleShape).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { if (i != idx) { h.tick(); onSelect(v) } }, Alignment.Center) {
                    Text(l, style = TextStyle(fontFamily = Fonts.display, fontWeight = if (i == idx) FontWeight.SemiBold else FontWeight.Medium, fontSize = 13.sp, color = if (i == idx) C.text else C.dim), maxLines = 1)
                }
            }
        }
    }
}

// ---------------- Campo de texto ----------------
@Composable fun NxField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, error: String? = null, secret: Boolean = false, singleLine: Boolean = true, minLines: Int = 1, keyboard: KeyboardOptions = KeyboardOptions.Default) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val ring by animateColorAsState(if (error != null) C.danger else if (focused) C.accent else Color.Transparent, snappy(), label = "ring")
    Column(modifier.padding(vertical = 5.dp)) {
        Text(label, style = T.label(12, if (focused) C.accent else C.dim), modifier = Modifier.padding(start = 4.dp, bottom = 5.dp))
        Box(Modifier.fillMaxWidth().clip(Squircle(14.dp)).background(C.surface3).border(2.dp, ring, Squircle(14.dp)).padding(horizontal = 14.dp, vertical = 13.dp)) {
            BasicTextField(value, onChange, Modifier.fillMaxWidth(), textStyle = TextStyle(fontFamily = Fonts.display, fontSize = 16.sp, color = C.text), cursorBrush = SolidColor(C.accent), singleLine = singleLine, minLines = minLines,
                interactionSource = src, visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation('●') else VisualTransformation.None, keyboardOptions = keyboard)
        }
        if (error != null) Text(error, style = T.label(12, C.danger).copy(fontWeight = FontWeight.Normal), modifier = Modifier.padding(start = 4.dp, top = 4.dp))
    }
}

// ---------------- Diálogo: tarjeta que emerge con muelle sobre el fondo desenfocado ----------------
@Composable fun NxDialog(title: String, onDismiss: () -> Unit, actions: @Composable RowScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    DisposableEffect(Unit) { Nx.modals++; onDispose { Nx.modals-- } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        (LocalView.current.parent as? DialogWindowProvider)?.window?.setDimAmount(0f)
        val reduce = rememberReduceMotion()
        val p = remember { Animatable(if (reduce) 1f else 0f) }
        LaunchedEffect(Unit) { if (!reduce) p.animateTo(1f, spring(0.74f, 420f)) }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f * p.value)).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDismiss() }.statusBarsPadding().navigationBarsPadding().imePadding(), Alignment.Center) {
            Column(Modifier.padding(horizontal = 20.dp).fillMaxWidth().heightIn(max = 700.dp)
                .graphicsLayer { val s = 0.9f + 0.1f * p.value; scaleX = s; scaleY = s; alpha = p.value.coerceIn(0f, 1f) }
                .clip(Squircle(30.dp)).background(C.surface).border(1.dp, C.hair, Squircle(30.dp))
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}) {
                Text(title, style = T.title(34), modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 10.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), content = content)
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically, content = actions)
            }
        }
    }
}

@Composable fun NxConfirm(title: String, body: String, ok: String, danger: Boolean, onDismiss: () -> Unit, onOk: () -> Unit) =
    NxDialog(title, onDismiss, { NxBtn("Cancelar", onDismiss, kind = BtnKind.GHOST, small = true); NxBtn(ok, onOk, kind = if (danger) BtnKind.DANGER else BtnKind.PRIMARY, small = true) }) {
        Text(body, style = TextStyle(fontFamily = Fonts.display, fontSize = 15.sp, color = C.dim, lineHeight = 21.sp), modifier = Modifier.padding(bottom = 10.dp))
    }

@Composable fun NxNameDialog(title: String, initial: String, ok: String, validate: (String) -> String?, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    val err = validate(v)
    NxDialog(title, onDismiss, { NxBtn("Cancelar", onDismiss, kind = BtnKind.GHOST, small = true); NxBtn(ok, { onOk(v) }, enabled = err == null, small = true) }) {
        NxField(v, { v = it }, "Nombre", error = if (v.isEmpty()) null else err)
    }
}

// ---------------- Cabeceras y separadores ----------------
@Composable fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = C.dim) =
    Text(text.uppercase(), style = T.label(12, color).copy(letterSpacing = 0.9.sp), modifier = modifier.padding(horizontal = 24.dp))

@Composable fun Rule(modifier: Modifier = Modifier, thick: Dp = 1.dp, color: Color = C.hair) = Box(modifier.fillMaxWidth().height(1.dp).background(color))

// ---------------- Progreso: cápsula plana (sin degradado ni brillo) ----------------
@Composable fun NxProgress(fraction: Float?, modifier: Modifier = Modifier, running: Boolean = true, color: Color = C.accent) {
    val reduce = rememberReduceMotion()
    val t = rememberInfiniteTransition(label = "pg")
    val sh by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "sh")
    val f by animateFloatAsState(fraction ?: 1f, spring(0.9f, 140f), label = "f")
    Box(modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(C.surface3)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = if (fraction == null) size.width * 0.32f else (size.width * f.coerceIn(0f, 1f)).coerceAtLeast(size.height)
            val x0 = if (fraction == null) (size.width - w) * (if (reduce) 0f else (0.5f - 0.5f * kotlin.math.cos(sh * 6.2832f))) else 0f
            val r = size.height / 2
            drawRoundRect(color, Offset(x0, 0f), Size(w, size.height), androidx.compose.ui.geometry.CornerRadius(r, r))
        }
    }
}

// ---------------- Iconografía propia: trazo fino, extremos redondos, un icono por tipo ----------------
fun DrawScope.drawGlyph(kind: Kind, ink: Color) {
    val s = size.minDimension; val w = s * 0.052f; val st = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * s, y * s)
    fun rr(x: Float, y: Float, wd: Float, ht: Float, r: Float) { drawRoundRect(ink, p(x, y), Size(wd * s, ht * s), androidx.compose.ui.geometry.CornerRadius(r * s), style = st) }
    when (kind) {
        Kind.FOLDER -> { val path = Path().apply { moveTo(.18f * s, .34f * s); quadraticTo(.18f * s, .26f * s, .26f * s, .26f * s); lineTo(.42f * s, .26f * s); lineTo(.50f * s, .35f * s); lineTo(.74f * s, .35f * s); quadraticTo(.82f * s, .35f * s, .82f * s, .43f * s); lineTo(.82f * s, .70f * s); quadraticTo(.82f * s, .78f * s, .74f * s, .78f * s); lineTo(.26f * s, .78f * s); quadraticTo(.18f * s, .78f * s, .18f * s, .70f * s); close() }; drawPath(path, ink, style = st) }
        Kind.IMAGE -> { rr(.17f, .24f, .66f, .52f, .09f); drawCircle(ink, .055f * s, p(.38f, .41f), style = st); val m = Path().apply { moveTo(.20f * s, .70f * s); lineTo(.42f * s, .52f * s); lineTo(.57f * s, .64f * s); lineTo(.67f * s, .56f * s); lineTo(.80f * s, .68f * s) }; drawPath(m, ink, style = st) }
        Kind.VIDEO -> { rr(.17f, .27f, .66f, .46f, .09f); val t = Path().apply { moveTo(.45f * s, .39f * s); lineTo(.62f * s, .50f * s); lineTo(.45f * s, .61f * s); close() }; drawPath(t, ink, style = st) }
        Kind.AUDIO -> { val hs = floatArrayOf(.14f, .30f, .44f, .24f, .36f); hs.forEachIndexed { i, h -> val x = (.25f + i * .125f) * s; drawLine(ink, Offset(x, (.5f - h / 2) * s), Offset(x, (.5f + h / 2) * s), strokeWidth = w * 1.5f, cap = StrokeCap.Round) } }
        Kind.DOCUMENT -> { val path = Path().apply { moveTo(.30f * s, .20f * s); lineTo(.55f * s, .20f * s); lineTo(.72f * s, .37f * s); lineTo(.72f * s, .76f * s); quadraticTo(.72f * s, .80f * s, .68f * s, .80f * s); lineTo(.32f * s, .80f * s); quadraticTo(.28f * s, .80f * s, .28f * s, .76f * s); lineTo(.28f * s, .24f * s); quadraticTo(.28f * s, .20f * s, .30f * s, .20f * s); close() }; drawPath(path, ink, style = st); drawLine(ink, p(.55f, .20f), p(.55f, .37f), strokeWidth = w, cap = StrokeCap.Round); drawLine(ink, p(.55f, .37f), p(.72f, .37f), strokeWidth = w, cap = StrokeCap.Round); drawLine(ink, p(.38f, .53f), p(.62f, .53f), strokeWidth = w, cap = StrokeCap.Round); drawLine(ink, p(.38f, .65f), p(.56f, .65f), strokeWidth = w, cap = StrokeCap.Round) }
        Kind.ARCHIVE -> { rr(.22f, .22f, .56f, .56f, .08f); for (i in 0..3) drawLine(ink, p(.5f, .24f + i * .12f), p(.5f, .30f + i * .12f), strokeWidth = w * 1.6f, cap = StrokeCap.Round); rr(.43f, .60f, .14f, .12f, .03f) }
        Kind.CODE -> { val a = Path().apply { moveTo(.38f * s, .32f * s); lineTo(.20f * s, .50f * s); lineTo(.38f * s, .68f * s) }; val b = Path().apply { moveTo(.62f * s, .32f * s); lineTo(.80f * s, .50f * s); lineTo(.62f * s, .68f * s) }; drawPath(a, ink, style = st); drawPath(b, ink, style = st); drawLine(ink, p(.54f, .27f), p(.46f, .73f), strokeWidth = w, cap = StrokeCap.Round) }
        Kind.OTHER -> { rr(.26f, .26f, .48f, .48f, .1f); drawCircle(ink, .04f * s, p(.5f, .5f)) }
    }
}

@Composable fun Glyph(kind: Kind, size: Dp, modifier: Modifier = Modifier, ink: Color = C.kind(kind)) = Canvas(modifier.size(size)) { drawGlyph(kind, ink) }

/** Icono del tipo en una baldosa tonal (el color del tipo sobre su propio tinte): identidad de cada archivo. */
@Composable fun FileTag(e: Entry, size: Dp, modifier: Modifier = Modifier, solid: Boolean = false) {
    val k = e.kind; val c = C.kind(k)
    Box(modifier.size(size).clip(Squircle(size / 3.4f)).background(if (solid) c else c.copy(alpha = if (Nx.pal.dark) 0.17f else 0.14f)), Alignment.Center) { Glyph(k, size * 0.74f, ink = if (solid) Color.White else c) }
}

// ---------------- Estados vacíos y de error ----------------
@Composable fun NxEmpty(kind: Kind, title: String, sub: String, modifier: Modifier = Modifier, danger: Boolean = false, action: (@Composable () -> Unit)? = null) {
    val c = if (danger) C.danger else C.kind(kind)
    Column(modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(104.dp).clip(Squircle(32.dp)).background(c.copy(alpha = 0.16f)), Alignment.Center) { Glyph(kind, 70.dp, ink = c) }
        Spacer(Modifier.height(20.dp))
        Text(title, style = T.title(34))
        Spacer(Modifier.height(8.dp))
        Text(sub, style = TextStyle(fontFamily = Fonts.display, fontSize = 15.sp, color = C.dim, lineHeight = 21.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center))
        if (action != null) { Spacer(Modifier.height(20.dp)); action() }
    }
}

/** Aviso breve: cápsula flotante con sombra que sube con un muelle. */
@Composable fun NxToast(msg: String?, modifier: Modifier = Modifier) {
    AnimatedVisibility(msg != null, modifier.padding(bottom = 16.dp, start = 16.dp, end = 16.dp), enter = slideInVertically(spring(0.72f, 450f)) { it } + fadeIn(), exit = slideOutVertically { it / 2 } + fadeOut()) {
        Box(Modifier.clip(CircleShape).background(C.text).padding(horizontal = 20.dp, vertical = 13.dp)) { Text(msg.orEmpty(), style = TextStyle(fontFamily = Fonts.display, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = C.bg), maxLines = 2) }
    }
}

/** Texto que se conserva el tiempo de un aviso. */
@Composable fun rememberToast(source: String?, clear: () -> Unit): String? {
    var shown by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(source) { if (source != null) { shown = source; clear(); kotlinx.coroutines.delay(3200); shown = null } }
    return shown
}

// ---------------- Gestos: arrastrar para cerrar (con goma elástica) y transición contenedor→pantalla ----------------
/** Contenido que se cierra arrastrando hacia abajo: escala, atenúa y vuelve con muelle si no se llega al umbral. */
@Composable fun DragDismiss(onDismiss: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {
    val off = remember { Animatable(0f) }
    val h = rememberHaptics()
    val scope = rememberCoroutineScope()
    var crossed by remember { mutableStateOf(false) }
    val reduce = rememberReduceMotion()
    Box(modifier.fillMaxSize().pointerInput(enabled) {
        if (!enabled) return@pointerInput
        detectVerticalDragGestures(
            onDragEnd = { scope.launch { if (off.value > 260f || crossed) { off.animateTo(size.height.toFloat(), snappy()); onDismiss() } else off.animateTo(0f, spring(0.7f, 420f)); crossed = false } },
            onDragCancel = { scope.launch { off.animateTo(0f, spring(0.7f, 420f)); crossed = false } },
        ) { _, dy ->
            val cur = off.value
            // goma elástica hacia arriba (no se puede subir más allá de un pequeño rebote)
            val next = if (cur + dy < 0f) cur + dy * 0.25f else cur + dy
            scope.launch { off.snapTo(next.coerceAtLeast(-60f)) }
            if (!crossed && next > 260f) { crossed = true; h.threshold() } else if (crossed && next < 220f) crossed = false
        }
    }.graphicsLayer {
        val p = (off.value / size.height).coerceIn(0f, 1f)
        translationY = off.value; val sc = 1f - 0.14f * p; scaleX = sc; scaleY = sc; alpha = 1f - 0.5f * p
        clip = true; shape = Squircle((26 * p).dp)
    }) { content() }
}

/** Pantalla que crece desde el elemento tocado ([Nx.origin]) hasta ocupar todo, con muelle. */
@Composable fun ContainerTransform(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val reduce = rememberReduceMotion()
    val origin = remember { Nx.origin }
    val p = remember { Animatable(if (reduce || origin == null) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduce && origin != null) p.animateTo(1f, spring(0.82f, 330f)) }
    Box(modifier.fillMaxSize().graphicsLayer {
        val o = origin
        if (o != null && p.value < 1f) {
            val sx = o.width / size.width; val sy = o.height / size.height
            val t = p.value
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = sx + (1f - sx) * t; scaleY = sy + (1f - sy) * t
            translationX = o.left * (1f - t); translationY = o.top * (1f - t)
            alpha = (0.25f + t * 1.4f).coerceIn(0f, 1f)
            clip = true; shape = Squircle(((1f - t) * 22).dp)
        }
    }) { content() }
}

fun Modifier.alpha(a: Float) = this.graphicsLayer { alpha = a }
