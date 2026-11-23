package dev.bloom.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.zIndex
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bloom.editor.PhotoView
import dev.bloom.editor.ToolTouch
import dev.bloom.editor.model.BrushStep
import dev.bloom.editor.model.ColorStep
import dev.bloom.editor.model.LuminanceStep
import dev.bloom.editor.model.GradientStep
import dev.bloom.editor.model.Mask
import dev.bloom.editor.model.MaskStep
import dev.bloom.editor.model.Stroke as BrushStroke
import dev.bloom.editor.model.with
import kotlin.math.hypot
import kotlin.math.roundToInt

enum class MaskTool { Brush, Gradient, Color, Luminance }

private fun MaskStep.label() = (if (add) "Add " else "Remove ") + when (this) {
    is BrushStep -> "brush"
    is GradientStep -> if (radial) "radial gradient" else "gradient"
    is ColorStep -> "color range"
    is LuminanceStep -> "luminance range"
}
private fun MaskStep.icon() = when (this) {
    is BrushStep -> Icons.Brush
    is GradientStep -> if (radial) Icons.Radial else Icons.Gradient
    is ColorStep -> Icons.Eyedropper
    is LuminanceStep -> Icons.Luminance
}

/** Right slot of a tool's bar: opens mask mode; shows the step count once there is a mask. */
@Composable
fun MaskButton(mask: Mask, onClick: () -> Unit) {
    val n = mask.steps.size
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
            .then(if (n > 0) Modifier.background(Bloom.PollenContainer) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (n == 0) "Mask: none. Add" else "Mask: $n steps. Edit" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Mask, null, tint = if (n > 0) Bloom.OnPollenContainer else Bloom.On)
        if (n > 0) Text("$n", Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 2.dp), fontSize = 10.sp, color = Bloom.OnPollenContainer)
    }
}

/** Bar contents in mask mode: what you add (sign + step types), then Invert and ✓. */
@Composable
fun RowScope.MaskBar(state: EditorState) {
    val mask = state.draft?.mask ?: return
    Box(
        Modifier.size(54.dp, 48.dp).clickable(role = Role.Button) { state.maskMode = false }
            .semantics { contentDescription = "Color mask. Back to Color" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(36.dp, 30.dp).background(Bloom.PollenContainer, Bloom.Petal), contentAlignment = Alignment.Center) {
            Icon(Icons.Mask, null, tint = Bloom.OnPollenContainer)
        }
    }
    Row(Modifier.weight(1f).scrollFade(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val sign = if (state.maskAdd) "Add" else "Remove"
        TypeButton(Icons.Brush, state.maskAdd, state.maskTool == MaskTool.Brush, "$sign brush") { state.maskTool = MaskTool.Brush }
        TypeButton(Icons.Gradient, state.maskAdd, state.maskTool == MaskTool.Gradient, "$sign gradient") { state.addGradient() }
        TypeButton(Icons.Eyedropper, state.maskAdd, state.maskTool == MaskTool.Color, "$sign color range") { state.addStep(ColorStep(add = state.maskAdd)) }
        TypeButton(Icons.Luminance, state.maskAdd, state.maskTool == MaskTool.Luminance, "$sign luminance range") { state.addStep(LuminanceStep.Shadows.copy(add = state.maskAdd)) }
    }
    IconButton({ state.setMask(mask.copy(invert = !mask.invert)) }, Modifier.semantics { selected = mask.invert }) {
        Icon(Icons.Invert, if (mask.invert) "Invert on" else "Invert off", tint = if (mask.invert) Bloom.Pollen else Bloom.On)
    }
    IconButton({ state.maskMode = false }) { Icon(Icons.Check, "Done with mask", tint = Bloom.Pollen) }
}

/** Floating boxes above the bar in mask mode: the steps, then the current tool's settings (More grows that same box). [box] is the box style. */
@Composable
fun MaskRows(state: EditorState, box: Modifier) {
    val mask = state.draft?.mask ?: return
    val sel = state.stepIndex?.let { mask.steps.getOrNull(it) }
    if (mask.steps.isNotEmpty()) Row(box.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).scrollFade().padding(start = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            mask.steps.forEachIndexed { i, s ->
                StepChip(s, i == state.stepIndex, onClick = { state.selectStep(i) }) { by ->
                    val to = (i + by).coerceIn(0, mask.steps.lastIndex)
                    if (to != i) {
                        state.setMask(mask.move(i, to))
                        state.stepIndex = null
                        state.selectStep(to) // also switches to the moved step's tool
                    }
                }
            }
        }
        if (sel != null) {
            val i = state.stepIndex!!
            IconButton({ state.replaceStep(i, sel.with(add = !sel.add)) }) {
                Icon(if (sel.add) Icons.Minus else Icons.Plus, if (sel.add) "Make this step remove" else "Make this step add", tint = Bloom.On)
            }
            IconButton({ state.replaceStep(i, sel.with(visible = !sel.visible)) }) {
                Icon(if (sel.visible) Icons.Eye else Icons.EyeOff, if (sel.visible) "Hide step" else "Show step", tint = Bloom.On)
            }
            IconButton({ state.deleteStep(i) }) { Icon(Icons.Trash, "Delete step", tint = Bloom.On) }
        }
    }
    // One box for the current tool: its main row, plus the More rows above it when open.
    val i = state.stepIndex
    Column(box) {
        if (state.maskMore) {
            MaskActions(state)
            when (state.maskTool) {
                MaskTool.Brush -> {
                    SliderRow("Softness", state.softness, 0f..100f) { state.softness = it }
                    SliderRow("Flow", state.flow, 1f..100f) { state.flow = it }
                }
                MaskTool.Color -> (sel as? ColorStep)?.let { c ->
                    SliderRow("Smooth", c.smooth * 100f, 0f..100f) { state.replaceStep(i!!, c.copy(smooth = it / 100f)) }
                }
                else -> Unit
            }
            if (sel != null) SliderRow("Feather", sel.feather * 100f, 0f..100f) { state.replaceStep(i!!, sel.with(feather = it / 100f)) }
        }
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            SignToggle(state)
            when (state.maskTool) {
                MaskTool.Brush -> {
                    Text("Size", Modifier.width(36.dp), fontSize = 12.sp, color = Bloom.OnVariant)
                    Slider(state.brushSize, { state.brushSize = it.roundToInt().toFloat() }, Modifier.weight(1f), valueRange = 4f..150f, colors = sliderColors())
                    Text("${state.brushSize.roundToInt()}", Modifier.width(36.dp), fontSize = 14.sp, color = Bloom.On)
                }
                MaskTool.Gradient -> Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val g = sel as? GradientStep
                    if (g == null) Hint("Tap the gradient icon to add one")
                    else {
                        Chip("Linear", !g.radial) { state.replaceStep(i!!, g.copy(radial = false)) }
                        Chip("Radial", g.radial) { state.replaceStep(i!!, g.copy(radial = true)) }
                    }
                }
                MaskTool.Color -> ColorControls(state, sel as? ColorStep)
                MaskTool.Luminance -> {
                    val l = sel as? LuminanceStep
                    if (l == null) Hint("Tap the luminance icon to add one", Modifier.weight(1f))
                    else {
                        val preset = presets.entries.firstOrNull { (_, p) -> p.a == l.a && p.b == l.b && p.c == l.c && p.d == l.d }?.key
                        TextButton({
                            val next = presets.values.toList()[(presets.keys.indexOf(preset) + 1) % presets.size]
                            state.replaceStep(i!!, l.copy(a = next.a, b = next.b, c = next.c, d = next.d))
                        }, Modifier.width(96.dp)) { Text(preset ?: "Custom", fontSize = 12.sp, color = Bloom.On) }
                        LuminanceStrip(l, Modifier.weight(1f)) { state.replaceStep(i!!, it) }
                    }
                }
            }
            IconButton({ state.maskMore = !state.maskMore }) {
                Icon(Icons.Up, if (state.maskMore) "Less" else "More settings", Modifier.rotate(if (state.maskMore) 180f else 0f), tint = Bloom.On)
            }
        }
    }
}

/** Copy mask from… (another layer's steps replace these) and Clear mask. */
@Composable
private fun MaskActions(state: EditorState) {
    var menu by remember { mutableStateOf(false) }
    val d = state.draft ?: return
    val others = state.doc.layers.withIndex().filter { (_, l) -> l.id != d.id && l.mask.steps.isNotEmpty() }
    Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            TextButton({ menu = true }, enabled = others.isNotEmpty()) { Text("Copy mask from…", fontSize = 12.sp, color = if (others.isNotEmpty()) Bloom.On else Bloom.Outline) }
            DropdownMenu(menu, { menu = false }, containerColor = Bloom.SurfaceHigh) {
                others.forEach { (n, l) ->
                    DropdownMenuItem(text = { Text("Color layer ${n + 1}", color = Bloom.On) }, onClick = {
                        menu = false
                        state.setMask(l.mask)
                        state.stepIndex = null
                    })
                }
            }
        }
        TextButton({ state.setMask(Mask()); state.stepIndex = null }, enabled = d.mask.steps.isNotEmpty() || d.mask.invert) {
            Text("Clear mask", fontSize = 12.sp, color = if (d.mask.steps.isNotEmpty() || d.mask.invert) Bloom.On else Bloom.Outline)
        }
    }
}

private val presets = linkedMapOf("Shadows" to LuminanceStep.Shadows, "Midtones" to LuminanceStep.Midtones, "Highlights" to LuminanceStep.Highlights)

@Composable
private fun RowScope.Hint(text: String, modifier: Modifier = Modifier.weight(1f)) =
    Text(text, modifier, fontSize = 12.sp, color = Bloom.OnVariant)

/** Sample swatches (tap one to drop it), + to add the next tap as another sample, and Range. */
@Composable
private fun RowScope.ColorControls(state: EditorState, c: ColorStep?) {
    if (c == null || c.samples.isEmpty()) {
        Hint(if (c == null) "Tap the color icon to add one" else "Tap the photo to pick a color")
        return
    }
    val i = state.stepIndex!!
    c.samples.chunked(3).forEachIndexed { k, (r, g, b) ->
        Box(
            Modifier.padding(end = 4.dp).size(24.dp).clip(RoundedCornerShape(12.dp)).background(Color(r, g, b))
                .border(1.dp, Bloom.On, RoundedCornerShape(12.dp))
                .clickable(enabled = c.samples.size > 3) { state.replaceStep(i, c.copy(samples = c.samples.filterIndexed { j, _ -> j / 3 != k })) }
                .semantics { contentDescription = "Sample ${k + 1}" + if (c.samples.size > 3) ". Tap to remove" else "" },
        )
    }
    if (c.samples.size / 3 < ColorStep.MAX_SAMPLES) {
        IconButton({ state.addingSample = !state.addingSample }, Modifier.size(36.dp).semantics { selected = state.addingSample }) {
            Icon(Icons.Plus, if (state.addingSample) "Next tap adds a sample. Tap to cancel" else "Add another sample", tint = if (state.addingSample) Bloom.Pollen else Bloom.On)
        }
    }
    Slider(c.range * 100f, { state.replaceStep(i, c.copy(range = it.roundToInt() / 100f)) }, Modifier.weight(1f), valueRange = 0f..100f, colors = sliderColors())
}

/** Brightness strip with four handles: the step rises from a to b, is full to c, and falls to none at d. */
@Composable
private fun LuminanceStrip(l: LuminanceStep, modifier: Modifier, onChange: (LuminanceStep) -> Unit) {
    val current by rememberUpdatedState(l)
    val change by rememberUpdatedState(onChange)
    Canvas(
        modifier.height(40.dp).padding(horizontal = 10.dp)
            .semantics { contentDescription = "Luminance range ${(l.a * 100).roundToInt()} to ${(l.d * 100).roundToInt()} percent" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val v = current.let { floatArrayOf(it.a, it.b, it.c, it.d) }
                    val x0 = down.position.x / size.width
                    // nearest handle; ties (stacked handles) go to the one on the side the finger moves toward later
                    var h = v.indices.minBy { kotlin.math.abs(v[it] - x0) }
                    var first = true
                    var c = down
                    while (true) {
                        val x = (c.position.x / size.width).coerceIn(0f, 1f)
                        if (first && x != x0) {
                            val same = v.indices.filter { v[it] == v[h] }
                            h = if (x > x0) same.max() else same.min()
                            first = false
                        }
                        v[h] = x.coerceIn(if (h > 0) v[h - 1] else 0f, if (h < 3) v[h + 1] else 1f)
                        change(current.copy(a = v[0], b = v[1], c = v[2], d = v[3]))
                        c.consume()
                        c = awaitPointerEvent().changes.firstOrNull { it.id == down.id && it.pressed } ?: break
                    }
                }
            },
    ) {
        val w = size.width
        val mid = size.height / 2
        drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.White)), Offset(0f, mid - 6.dp.toPx()), Size(w, 12.dp.toPx()))
        val path = Path().apply {
            moveTo(l.a * w, mid + 6.dp.toPx()); lineTo(l.b * w, mid - 6.dp.toPx()); lineTo(l.c * w, mid - 6.dp.toPx()); lineTo(l.d * w, mid + 6.dp.toPx()); close()
        }
        drawPath(path, Bloom.Pollen.copy(alpha = 0.55f))
        listOf(l.a, l.b, l.c, l.d).forEachIndexed { k, x ->
            val y = if (k == 0 || k == 3) mid + 10.dp.toPx() else mid - 10.dp.toPx()
            drawCircle(Color.Black, 7.dp.toPx(), Offset(x * w, y))
            drawCircle(Bloom.Pollen, 5.5.dp.toPx(), Offset(x * w, y))
        }
    }
}

/** Add/Remove for the next step: one icon button that flips. */
@Composable
private fun SignToggle(state: EditorState) {
    val add = state.maskAdd
    IconButton({ state.maskAdd = !add }, Modifier.semantics { contentDescription = if (add) "Adding. Tap to remove instead" else "Removing. Tap to add instead" }) {
        Box(Modifier.size(28.dp).background(if (add) Bloom.Pollen else Bloom.On, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Icon(if (add) Icons.Plus else Icons.Minus, null, Modifier.size(20.dp), tint = Bloom.Canvas)
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    // lines up with the Size row below: label after the 48dp sign button, value column, then the More button's width
    Row(Modifier.fillMaxWidth().height(48.dp).padding(end = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(84.dp).padding(start = 16.dp), fontSize = 12.sp, color = Bloom.OnVariant)
        Slider(value, { onChange(it.roundToInt().toFloat()) }, Modifier.weight(1f), valueRange = range, colors = sliderColors())
        Text("${value.roundToInt()}", Modifier.width(36.dp), fontSize = 14.sp, color = Bloom.On)
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, desc: String? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.height(40.dp).clip(shape).then(if (selected) Modifier.background(Bloom.SurfaceHigh) else Modifier)
            .clickable(role = Role.Tab, onClick = onClick).semantics { this.selected = selected; desc?.let { contentDescription = it } }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, null, Modifier.size(18.dp).padding(end = 4.dp), tint = if (selected) Bloom.Pollen else Bloom.OnVariant) }
        Text(label, fontSize = 12.sp, color = if (selected) Bloom.On else Bloom.OnVariant, fontWeight = FontWeight.Medium)
    }
}

/** Step type in the bar: icon only, with the sign of the step it will add. */
@Composable
private fun TypeButton(icon: androidx.compose.ui.graphics.vector.ImageVector, add: Boolean, selected: Boolean, desc: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp, 40.dp).clip(RoundedCornerShape(10.dp)).then(if (selected) Modifier.background(Bloom.SurfaceHigh) else Modifier)
            .clickable(role = Role.Tab, onClick = onClick).semantics { this.selected = selected; contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = if (selected) Bloom.Pollen else Bloom.On)
        SignBadge(add, Modifier.align(Alignment.TopEnd))
    }
}

/**
 * A step in the steps row: sign + type icon. Hidden steps are dimmed and struck through.
 * Long-press then drag sideways to reorder; [onMove] gets how many places it moved.
 */
@Composable
private fun StepChip(s: MaskStep, selected: Boolean, onClick: () -> Unit, onMove: (Int) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    var dx by remember { mutableFloatStateOf(0f) }
    var lifted by remember { mutableStateOf(false) }
    val move by rememberUpdatedState(onMove)
    Box(
        Modifier.size(48.dp, 36.dp)
            .zIndex(if (lifted) 1f else 0f)
            .graphicsLayer { translationX = dx; if (lifted) { scaleX = 1.15f; scaleY = 1.15f; shadowElevation = 12f } }
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { lifted = true },
                    onDragEnd = { move((dx / 54.dp.toPx()).roundToInt()); dx = 0f; lifted = false }, // chip + gap pitch
                    onDragCancel = { dx = 0f; lifted = false },
                ) { c, d -> c.consume(); dx += d.x }
            }
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) Bloom.Pollen else Bloom.Outline, shape)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics {
                this.selected = selected
                contentDescription = s.label() + if (s.visible) "" else ", hidden"
                customActions = listOf(
                    CustomAccessibilityAction("Move left") { move(-1); true },
                    CustomAccessibilityAction("Move right") { move(1); true },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(s.icon(), null, Modifier.size(20.dp), tint = if (s.visible) Bloom.On else Bloom.Outline)
        SignBadge(s.add, Modifier.align(Alignment.TopEnd))
        if (!s.visible) Canvas(Modifier.size(24.dp)) { drawLine(Bloom.On, Offset(0f, size.height), Offset(size.width, 0f), 2.dp.toPx()) }
    }
}

@Composable
private fun SignBadge(add: Boolean, modifier: Modifier) {
    Box(modifier.padding(2.dp).size(14.dp).background(if (add) Bloom.Pollen else Bloom.On, RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
        Icon(if (add) Icons.Plus else Icons.Minus, null, Modifier.size(12.dp), tint = Bloom.Canvas)
    }
}

/** Handles of the selected gradient, drawn over the photo. Touches pass through to the photo view. */
@Composable
fun GradientHandles(g: GradientStep, photoView: PhotoView, transformTick: Int) {
    Canvas(Modifier.fillMaxSize()) {
        transformTick // redraw on zoom or pan
        val (ax, ay) = photoView.toView(g.x0, g.y0)
        val (bx, by) = photoView.toView(g.x1, g.y1)
        val a = Offset(ax, ay)
        val b = Offset(bx, by)
        val dash = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))
        val r = 9.dp.toPx()
        if (g.radial) {
            val rad = hypot(bx - ax, by - ay)
            drawCircle(Color.Black, rad, a, style = Stroke(3.dp.toPx()))
            drawCircle(Color.White, rad, a, style = Stroke(1.5.dp.toPx(), pathEffect = dash))
            drawCircle(Color.White, rad / 2, a, style = Stroke(1.dp.toPx(), pathEffect = dash)) // where the fade starts
        } else {
            drawLine(Color.Black, a, b, 3.dp.toPx())
            drawLine(Color.White, a, b, 1.5.dp.toPx(), pathEffect = dash)
        }
        listOf(a to true, b to false).forEach { (p, full) ->
            drawCircle(Color.Black, r + 1.5.dp.toPx(), p)
            drawCircle(if (full) Bloom.Pollen else Color.White, r, p, style = if (full) androidx.compose.ui.graphics.drawscope.Fill else Stroke(2.5.dp.toPx()))
        }
    }
}

/**
 * One-finger handler for mask mode. Brush: paints into the last Brush step with the current sign, or starts one.
 * Gradient: drags the nearest handle of the selected gradient, or redraws it from the touch point.
 */
class MaskTouch(
    private val state: EditorState, private val photoView: PhotoView, private val aspect: Float, private val density: Float,
    /** Reads the color of the layers below the masked one at (u, v), asynchronously. */
    private val sample: (Float, Float, (FloatArray) -> Unit) -> Unit,
) {
    private var before: Mask? = null
    private var handle = 0 // 0 = start, 1 = end

    fun on(t: ToolTouch, u: Float, v: Float) {
        val mask = state.draft?.mask ?: return
        when (t) {
            ToolTouch.Down -> {
                before = mask
                when (state.maskTool) {
                    MaskTool.Brush -> startStroke(mask, u, v)
                    MaskTool.Gradient -> grabGradient(mask, u, v)
                    MaskTool.Color -> pick(u, v, append = state.addingSample)
                    MaskTool.Luminance -> Unit
                }
            }
            ToolTouch.Move -> {
                val i = state.stepIndex ?: return
                when (val s = mask.steps.getOrNull(i)) {
                    is BrushStep -> state.replaceStep(i, s.copy(strokes = s.strokes.dropLast(1) + s.strokes.last().lineTo(u, v, aspect)))
                    is GradientStep -> state.replaceStep(i, if (handle == 0) s.copy(x0 = u, y0 = v) else s.copy(x1 = u, y1 = v))
                    is ColorStep -> pick(u, v, append = false, replaceLast = true) // dragging fine-tunes the sample
                    else -> Unit
                }
            }
            ToolTouch.Up -> before = null
            ToolTouch.Cancel -> before?.let { state.setMask(it); state.stepIndex = state.stepIndex?.takeIf { i -> i < it.steps.size } }
        }
    }

    private var sampling = false

    /** Samples the color under the finger into the selected Color step (adding one if none is selected). */
    private fun pick(u: Float, v: Float, append: Boolean, replaceLast: Boolean = false) {
        if (sampling) return // one readback at a time while dragging
        val i = state.stepIndex?.takeIf { state.draft?.mask?.steps?.getOrNull(it) is ColorStep } ?: run { state.addStep(ColorStep(add = state.maskAdd)); state.stepIndex!! }
        sampling = true
        sample(u, v) { rgb ->
            sampling = false
            val c = state.draft?.mask?.steps?.getOrNull(i) as? ColorStep ?: return@sample
            val kept = when {
                replaceLast -> c.samples.dropLast(3)
                append && c.samples.size / 3 < ColorStep.MAX_SAMPLES -> c.samples
                else -> emptyList()
            }
            state.replaceStep(i, c.copy(samples = kept + rgb.toList()))
            state.addingSample = false
        }
    }

    private fun startStroke(mask: Mask, u: Float, v: Float) {
        val stroke = BrushStroke(listOf(u, v), state.brushSize * density / photoView.imageHeightPx, state.softness / 100f, state.flow / 100f)
        val last = mask.steps.lastOrNull()
        // consecutive strokes with the same sign merge into one Brush step
        val steps = if (last is BrushStep && last.add == state.maskAdd && last.visible) mask.steps.dropLast(1) + last.copy(strokes = last.strokes + stroke)
        else mask.steps + BrushStep(listOf(stroke), state.maskAdd)
        state.setMask(mask.copy(steps = steps))
        state.stepIndex = steps.lastIndex
    }

    private fun grabGradient(mask: Mask, u: Float, v: Float) {
        val i = state.stepIndex?.takeIf { mask.steps.getOrNull(it) is GradientStep } ?: run { state.addGradient(); state.stepIndex!! }
        val g = state.draft!!.mask.steps[i] as GradientStep
        val (x, y) = photoView.toView(u, v)
        val d = listOf(g.x0 to g.y0, g.x1 to g.y1).map { (gu, gv) -> photoView.toView(gu, gv).let { (vx, vy) -> hypot(vx - x, vy - y) } }
        val reach = 32 * density
        handle = if (d.min() <= reach) d.indexOf(d.min()) else 1
        // away from both handles: start a new gradient line at the finger
        if (d.min() > reach) state.replaceStep(i, g.copy(x0 = u, y0 = v, x1 = u, y1 = v))
    }
}
