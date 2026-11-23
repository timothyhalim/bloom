package dev.bloom.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bloom.editor.PhotoView
import dev.bloom.editor.ToolTouch
import dev.bloom.editor.model.Layer
import dev.bloom.editor.model.WarpEffect
import dev.bloom.editor.model.WarpStroke
import dev.bloom.editor.model.WarpTool

/**
 * One finger paints the selected Liquify brush; each touch is one stroke (one undo step once applied).
 * Bloat, Pinch, Twirl and Restore keep working while the finger rests: a dab at the finger every frame.
 */
class LiquifyTouch(private val state: EditorState, private val photoView: PhotoView, private val aspect: Float, private val density: Float) {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val hold = object : Runnable {
        override fun run() {
            // stop if the tool changed mid-hold: Up then goes to another handler
            val d = state.draft?.takeIf { !state.maskMode } ?: return
            val warp = (d.effect as? WarpEffect)?.strokes ?: return
            val last = warp.lastOrNull() ?: return
            val n = last.points.size
            state.draft = d.copy(effect = WarpEffect(warp.dropLast(1) + last.copy(points = last.points + last.points[n - 2] + last.points[n - 1])))
            handler.postDelayed(this, HOLD_MS)
        }
    }

    fun on(t: ToolTouch, u: Float, v: Float) {
        val d = state.draft ?: return
        val warp = (d.effect as? WarpEffect)?.strokes.orEmpty()
        when (t) {
            ToolTouch.Down -> {
                handler.removeCallbacks(hold)
                if (state.warpTool != WarpTool.Push) handler.postDelayed(hold, HOLD_MS)
                val s = WarpStroke(
                    state.warpTool, listOf(u, v), state.warpSize * density / photoView.imageHeightPx, state.warpStrength / 100f,
                    state.warpSoftness / 100f, reverse = state.warpReverse,
                )
                state.draft = d.copy(effect = WarpEffect(warp + s))
            }
            ToolTouch.Move -> {
                val last = warp.lastOrNull() ?: return
                val next = last.lineTo(u, v, aspect)
                if (next !== last) state.draft = d.copy(effect = WarpEffect(warp.dropLast(1) + next))
            }
            ToolTouch.Up, ToolTouch.Cancel -> handler.removeCallbacks(hold)
        }
    }

    private companion object {
        const val HOLD_MS = 33L
    }
}

/** A brush's outline mid-photo while its Size or Softness slider moves. */
@Composable
fun BrushRing(sizeDp: Float, softness: Float, photoView: PhotoView) {
    Canvas(Modifier.fillMaxSize()) {
        val (x, y) = photoView.outputToView(0.5f, 0.5f)
        val c = Offset(x, y)
        val r = sizeDp.dp.toPx()
        // as on the photo: solid where the brush is at full strength, dashed at its edge when it fades out there
        val soft = softness > 0.02f
        val dash = if (soft) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())) else null
        drawCircle(Color.Black.copy(alpha = 0.5f), r, c, style = Stroke(3.dp.toPx(), pathEffect = dash))
        drawCircle(Color.White, r, c, style = Stroke(1.5.dp.toPx(), pathEffect = dash))
        if (soft) {
            drawCircle(Color.Black.copy(alpha = 0.5f), r * (1 - softness), c, style = Stroke(3.dp.toPx()))
            drawCircle(Color.White, r * (1 - softness), c, style = Stroke(1.5.dp.toPx()))
        }
    }
}

private val WarpTool.icon get() = when (this) {
    WarpTool.Push -> Icons.Push
    WarpTool.Bloat -> Icons.Bloat
    WarpTool.Pinch -> Icons.Pinch
    WarpTool.Twirl -> Icons.Twirl
    WarpTool.Restore -> Icons.Restore
}

/** One box above the Liquify bar, like the mask brush's: Size, and More for Strength and Softness. */
@Composable
fun LiquifyRows(state: EditorState, box: Modifier) {
    ToolBox(state.warpMore, { state.warpMore = !state.warpMore }, box, moreRows = {
        SliderRow("Strength", state.warpStrength, 5f..100f) { state.warpStrength = it }
        SliderRow("Softness", state.warpSoftness, 0f..100f) { state.warpSoftness = it; state.showRing(state.warpSize, it) }
        // Twirl's direction, as its own pair of buttons, with the other settings
        if (state.warpTool == WarpTool.Twirl) Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Direction", Modifier.width(84.dp).padding(start = 16.dp), fontSize = 12.sp, color = Bloom.OnVariant)
            ParamChip(Icons.Rotate, "Clockwise", false, !state.warpReverse) { state.warpReverse = false }
            ParamChip(Icons.RotateBack, "Counterclockwise", false, state.warpReverse) { state.warpReverse = true }
        }
    }) {
        // the selected brush's name, as Color names its slider; the slider under it is the brush size
        MainSlider(state.warpTool.name, "Size", state.warpSize, 10f..150f) { state.warpSize = it; state.showRing(it, state.warpSoftness) }
    }
}

/** The Liquify bar: back, the five brushes, mask, apply. */
@Composable
fun RowScope.LiquifyBar(state: EditorState, d: Layer, onDiscard: () -> Unit) {
    ToolIcon(Icons.Liquify, "Liquify. Back to tools", onDiscard)
    Row(Modifier.weight(1f).scrollFade(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        for (t in WarpTool.entries) ParamChip(t.icon, t.name, false, state.warpTool == t) { state.warpTool = t }
    }
    MaskAndApply(state, d)
}
