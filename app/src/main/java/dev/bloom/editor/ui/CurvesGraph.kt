package dev.bloom.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.bloom.editor.model.ColorEffect
import dev.bloom.editor.model.Curves
import dev.bloom.editor.model.curveAt
import kotlin.math.hypot
import kotlin.math.roundToInt

private val channels = listOf("RGB", "R", "G", "B")
private val channelColors = listOf(Color.White, Color(0xFFFF6B5E), Color(0xFF6BD66B), Color(0xFF6BA8FF))
private const val MIN_GAP = 0.01f // points keep this far apart in x, so the curve stays a function

/** The Curves row: channel chips and Reset above a 120dp graph. Tap adds a point, drag moves it, drag off the top or bottom removes it. */
@Composable
fun CurvesRow(state: EditorState, box: Modifier) {
    val d = state.draft ?: return
    val c = d.effect as? ColorEffect ?: return
    val ch = state.curveChannel
    val pts = c.curves[ch]
    Column(box.height(170.dp).padding(horizontal = 8.dp)) {
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            channels.forEachIndexed { i, name ->
                Chip(name, i == ch, desc = "$name curve" + if (c.curves[i] != Curves.LINE) ", edited" else "") { state.curveChannel = i }
            }
            Spacer(Modifier.weight(1f))
            IconButton({ state.draft = d.copy(effect = c.copy(curves = c.curves.with(ch, Curves.LINE))) }) {
                Icon(Icons.Reset, "Reset ${channels[ch]} curve", tint = Bloom.On)
            }
        }
        CurveGraph(pts, channelColors[ch], channels[ch], Modifier.fillMaxWidth().height(120.dp)) {
            val l = state.draft ?: return@CurveGraph
            val lc = l.effect as? ColorEffect ?: return@CurveGraph
            state.draft = l.copy(effect = lc.copy(curves = lc.curves.with(ch, it)))
        }
    }
}

@Composable
private fun CurveGraph(pts: List<Float>, color: Color, name: String, modifier: Modifier, onChange: (List<Float>) -> Unit) {
    val current by rememberUpdatedState(pts)
    val change by rememberUpdatedState(onChange)
    val n = pts.size / 2
    Canvas(
        modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)
            .semantics {
                contentDescription = "$name tone curve, " + (0 until n).joinToString { "${pct(pts[2 * it])} to ${pct(pts[2 * it + 1])}" }
                customActions = (0 until n).flatMap { i ->
                    fun nudge(by: Float) = change(pts.toMutableList().also { it[2 * i + 1] = (it[2 * i + 1] + by).coerceIn(0f, 1f) })
                    listOf(
                        CustomAccessibilityAction("Raise point at ${pct(pts[2 * i])}") { nudge(0.05f); true },
                        CustomAccessibilityAction("Lower point at ${pct(pts[2 * i])}") { nudge(-0.05f); true },
                    )
                }
            }
            .pointerInput(Unit) {
                val reach = 24.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val p = current.toMutableList()
                    // grab the nearest point, else add one on the curve under the finger
                    var k = (0 until p.size / 2).minBy { hypot(p[2 * it] * w - down.position.x, (1 - p[2 * it + 1]) * h - down.position.y) }
                    if (hypot(p[2 * k] * w - down.position.x, (1 - p[2 * k + 1]) * h - down.position.y) > reach) {
                        val x = (down.position.x / w).coerceIn(0f, 1f)
                        val at = (0 until p.size / 2).firstOrNull { p[2 * it] > x } ?: return@awaitEachGesture
                        if (p.size / 2 >= Curves.MAX_POINTS || at == 0 || x - p[2 * at - 2] < MIN_GAP || p[2 * at] - x < MIN_GAP) return@awaitEachGesture
                        p.addAll(2 * at, listOf(x, curveAt(p, x)))
                        k = at
                    }
                    val end = k == 0 || k == p.size / 2 - 1
                    var c = down
                    while (true) {
                        val pos = c.position
                        val off = !end && p.size / 2 > 2 && (pos.y < -reach || pos.y > h + reach)
                        val out = p.toMutableList()
                        if (off) {
                            out.subList(2 * k, 2 * k + 2).clear()
                        } else {
                            val lo = if (k > 0) p[2 * k - 2] + MIN_GAP else 0f
                            val hi = if (k < p.size / 2 - 1) p[2 * k + 2] - MIN_GAP else 1f
                            out[2 * k] = (pos.x / w).coerceIn(lo, hi)
                            out[2 * k + 1] = (1 - pos.y / h).coerceIn(0f, 1f)
                        }
                        change(out)
                        c.consume()
                        c = awaitPointerEvent().changes.firstOrNull { it.id == down.id && it.pressed } ?: break
                    }
                }
            },
    ) {
        val w = size.width
        val h = size.height
        for (q in 1..3) {
            drawLine(Bloom.OutlineVariant, Offset(w * q / 4, 0f), Offset(w * q / 4, h))
            drawLine(Bloom.OutlineVariant, Offset(0f, h * q / 4), Offset(w, h * q / 4))
        }
        drawLine(Bloom.OutlineVariant, Offset(0f, h), Offset(w, 0f))
        val path = Path()
        for (i in 0..64) {
            val x = i / 64f
            val o = Offset(x * w, (1 - curveAt(pts, x)) * h)
            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        drawPath(path, color, style = Stroke(2.dp.toPx()))
        for (i in 0 until n) {
            val o = Offset(pts[2 * i] * w, (1 - pts[2 * i + 1]) * h)
            drawCircle(Color.Black, 6.dp.toPx(), o)
            drawCircle(color, 4.5f.dp.toPx(), o)
        }
    }
}

private fun pct(v: Float) = "${(v * 100).roundToInt()}%"
