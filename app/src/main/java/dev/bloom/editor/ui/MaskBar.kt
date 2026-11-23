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
import dev.bloom.editor.model.GradientStep
import dev.bloom.editor.model.Mask
import dev.bloom.editor.model.MaskStep
import dev.bloom.editor.model.Stroke as BrushStroke
import dev.bloom.editor.model.with
import kotlin.math.hypot
import kotlin.math.roundToInt

enum class MaskTool { Brush, Gradient }

private fun MaskStep.label() = (if (add) "Add " else "Remove ") + if (this is BrushStep) "brush" else if ((this as GradientStep).radial) "radial gradient" else "gradient"
private fun MaskStep.icon() = if (this is BrushStep) Icons.Brush else if ((this as GradientStep).radial) Icons.Radial else Icons.Gradient

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
    val rowModifier = box.height(48.dp)
    if (mask.steps.isNotEmpty()) Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).scrollFade().padding(start = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            mask.steps.forEachIndexed { i, s -> StepChip(s, i == state.stepIndex) { state.selectStep(i) } }
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
    when (state.maskTool) {
        MaskTool.Brush -> Column(box) {
            if (state.maskMore) {
                SliderRow("Softness", state.softness, 0f..100f) { state.softness = it }
                SliderRow("Flow", state.flow, 1f..100f) { state.flow = it }
            }
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                SignToggle(state)
                Text("Size", Modifier.width(36.dp), fontSize = 12.sp, color = Bloom.OnVariant)
                Slider(state.brushSize, { state.brushSize = it.roundToInt().toFloat() }, Modifier.weight(1f), valueRange = 4f..150f, colors = sliderColors())
                Text("${state.brushSize.roundToInt()}", Modifier.width(36.dp), fontSize = 14.sp, color = Bloom.On)
                IconButton({ state.maskMore = !state.maskMore }) {
                    Icon(Icons.Up, if (state.maskMore) "Less" else "More: Softness, Flow", Modifier.rotate(if (state.maskMore) 180f else 0f), tint = Bloom.On)
                }
            }
        }
        MaskTool.Gradient -> Row(rowModifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SignToggle(state)
            val g = sel as? GradientStep
            if (g == null) Text("Tap the gradient icon to add one", fontSize = 12.sp, color = Bloom.OnVariant)
            else {
                Chip("Linear", !g.radial) { state.replaceStep(state.stepIndex!!, g.copy(radial = false)) }
                Chip("Radial", g.radial) { state.replaceStep(state.stepIndex!!, g.copy(radial = true)) }
                Text("Drag the handles", Modifier.padding(start = 8.dp), fontSize = 12.sp, color = Bloom.OnVariant)
            }
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

/** A step in the steps row: sign + type icon. Hidden steps are dimmed and struck through. */
@Composable
private fun StepChip(s: MaskStep, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.size(48.dp, 36.dp).clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) Bloom.Pollen else Bloom.Outline, shape)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected; contentDescription = s.label() + if (s.visible) "" else ", hidden" },
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
class MaskTouch(private val state: EditorState, private val photoView: PhotoView, private val aspect: Float, private val density: Float) {
    private var before: Mask? = null
    private var handle = 0 // 0 = start, 1 = end

    fun on(t: ToolTouch, u: Float, v: Float) {
        val mask = state.draft?.mask ?: return
        when (t) {
            ToolTouch.Down -> {
                before = mask
                if (state.maskTool == MaskTool.Brush) startStroke(mask, u, v) else grabGradient(mask, u, v)
            }
            ToolTouch.Move -> {
                val i = state.stepIndex ?: return
                when (val s = mask.steps.getOrNull(i)) {
                    is BrushStep -> state.replaceStep(i, s.copy(strokes = s.strokes.dropLast(1) + s.strokes.last().lineTo(u, v, aspect)))
                    is GradientStep -> state.replaceStep(i, if (handle == 0) s.copy(x0 = u, y0 = v) else s.copy(x1 = u, y1 = v))
                    null -> Unit
                }
            }
            ToolTouch.Up -> before = null
            ToolTouch.Cancel -> before?.let { state.setMask(it); state.stepIndex = state.stepIndex?.takeIf { i -> i < it.steps.size } }
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
