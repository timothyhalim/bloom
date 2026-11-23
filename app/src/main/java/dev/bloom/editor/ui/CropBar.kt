package dev.bloom.editor.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bloom.editor.PhotoView
import dev.bloom.editor.ToolTouch
import dev.bloom.editor.model.Crop
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Aspect presets as landscape width / height; null = free, 0 = the photo's own. Tapping the selected one turns it. */
private val ratios = listOf("Free" to null, "Original" to 0f, "1:1" to 1f, "4:5" to 5f / 4f, "3:2" to 1.5f, "16:9" to 16f / 9f)

/** Rotate (straighten) and perspective sliders: name, range, read, write, and whether the value is in degrees. */
private class CropParam(
    val name: String, val range: ClosedFloatingPointRange<Float>, val get: (Crop) -> Float, val set: (Crop, Float) -> Crop, val degrees: Boolean = false,
)

private val cropParams = listOf(
    CropParam("Rotate", -45f..45f, { it.angle }, { c, v -> c.copy(angle = v) }, degrees = true),
    CropParam("Vertical", -100f..100f, { it.vertical }, { c, v -> c.copy(vertical = v) }),
    CropParam("Horizontal", -100f..100f, { it.horizontal }, { c, v -> c.copy(horizontal = v) }),
)

/** One Crop slider row, ending in space the width of the More button so it lines up with the row below. */
@Composable
private fun CropSlider(state: EditorState, c: Crop, aspect: Float, p: CropParam) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(p.name, Modifier.width(100.dp).padding(start = 16.dp), fontSize = 12.sp, color = Bloom.OnVariant)
        // straightening or perspective resets the crop to the largest that fits, at the locked ratio or the crop's own
        val set = { v: Float -> state.crop = p.set(c, v).maximized(aspect, state.cropRatio ?: c.size(aspect).let { (w, h) -> w / h }) }
        Slider(p.get(c), { set(if (p.degrees) (it * 10).roundToInt() / 10f else it.roundToInt().toFloat()) }, Modifier.weight(1f), valueRange = p.range, colors = sliderColors())
        Text(if (p.degrees) "%.1f°".format(p.get(c)) else signed(p.get(c)), Modifier.width(48.dp), fontSize = 14.sp, color = Bloom.On)
        Spacer(Modifier.width(48.dp))
    }
}

/**
 * Drags on the photo while the Crop tool shows the whole frame: corners, edges (when no ratio is locked), or inside
 * to move. The crop never leaves the photo; a drag past an edge slides along it.
 */
class CropTouch(private val state: EditorState, private val photoView: PhotoView, private val aspect: Float, private val density: Float) {
    private var grab = -1 // 0..3 corners TL, TR, BR, BL; 4..7 edges L, T, R, B; 8 move
    private var start: Crop? = null
    private var sx = 0f
    private var sy = 0f

    fun on(t: ToolTouch, u: Float, v: Float) {
        val c = state.crop ?: return
        val (fu, fv) = photoView.toOutput(u, v) // frame 0..1
        when (t) {
            ToolTouch.Down -> {
                val (x, y) = photoView.outputToView(fu, fv)
                val hit = 28 * density
                val (l, tp) = photoView.outputToView(c.left, c.top)
                val (r, b) = photoView.outputToView(c.right, c.bottom)
                grab = listOf(l to tp, r to tp, r to b, l to b).indexOfFirst { (px, py) -> hypot(px - x, py - y) < hit }
                if (grab < 0 && state.cropRatio == null) grab = when {
                    abs(x - l) < hit && y in tp..b -> 4
                    abs(y - tp) < hit && x in l..r -> 5
                    abs(x - r) < hit && y in tp..b -> 6
                    abs(y - b) < hit && x in l..r -> 7
                    else -> -1
                }
                if (grab < 0 && x in l..r && y in tp..b) grab = 8
                start = c
                sx = fu
                sy = fv
            }
            ToolTouch.Move -> {
                val s = start ?: return
                if (grab < 0) return
                val dx = fu - sx
                val dy = fv - sy
                val (fw, fh) = s.frame(aspect)
                val minW = MIN / fw // at least MIN photo heights, in frame units
                val minH = MIN / fh
                var l = s.left
                var tp = s.top
                var r = s.right
                var b = s.bottom
                when (grab) {
                    8 -> { l += dx; r += dx; tp += dy; b += dy }
                    else -> {
                        if (grab == 0 || grab == 3 || grab == 4) l = minOf(l + dx, r - minW)
                        if (grab == 1 || grab == 2 || grab == 6) r = maxOf(r + dx, l + minW)
                        if (grab == 0 || grab == 1 || grab == 5) tp = minOf(tp + dy, b - minH)
                        if (grab == 2 || grab == 3 || grab == 7) b = maxOf(b + dy, tp + minH)
                    }
                }
                val ratio = state.cropRatio
                if (ratio != null && grab in 0..3) {
                    // keep the ratio, anchored at the opposite corner
                    var w = (r - l) * fw
                    var h = (b - tp) * fh
                    if (w / h > ratio) h = w / ratio else w = h * ratio
                    if (grab == 0 || grab == 3) l = r - w / fw else r = l + w / fw
                    if (grab == 0 || grab == 1) tp = b - h / fh else b = tp + h / fh
                    state.crop = c.moveToward(c.copy(left = l, top = tp, right = r, bottom = b), aspect)
                } else {
                    // x then y, so a drag into one edge still moves along it
                    val x = c.moveToward(c.copy(left = l, right = r), aspect)
                    state.crop = x.moveToward(x.copy(top = tp, bottom = b), aspect)
                }
            }
            ToolTouch.Up, ToolTouch.Cancel -> { grab = -1; start = null }
        }
    }

    private companion object {
        const val MIN = 0.05f
    }
}

/** The crop over the whole frame: dimmed outside, thirds inside, corner handles. */
@Composable
fun CropOverlay(state: EditorState, photoView: PhotoView, transformTick: Int) {
    Canvas(Modifier.fillMaxSize()) {
        val c = state.crop ?: return@Canvas // read while drawing: a drag redraws only this
        transformTick // redraw on zoom or pan
        val (l, t) = photoView.outputToView(c.left, c.top)
        val (r, b) = photoView.outputToView(c.right, c.bottom)
        val rect = Rect(l, t, r, b)
        drawPath(Path().apply { fillType = PathFillType.EvenOdd; addRect(Rect(Offset.Zero, size)); addRect(rect) }, Color.Black.copy(alpha = 0.55f))
        for (i in 1..2) {
            val x = l + (r - l) * i / 3
            val y = t + (b - t) * i / 3
            drawLine(Color.White.copy(alpha = 0.5f), Offset(x, t), Offset(x, b), 1.dp.toPx())
            drawLine(Color.White.copy(alpha = 0.5f), Offset(l, y), Offset(r, y), 1.dp.toPx())
        }
        drawRect(Color.White, rect.topLeft, Size(r - l, b - t), style = Stroke(1.5.dp.toPx()))
        val k = 18.dp.toPx()
        val w = 4.dp.toPx()
        for ((p, sxy) in listOf(Offset(l, t) to Offset(1f, 1f), Offset(r, t) to Offset(-1f, 1f), Offset(r, b) to Offset(-1f, -1f), Offset(l, b) to Offset(1f, -1f))) {
            drawLine(Color.White, p, p + Offset(k * sxy.x, 0f), w)
            drawLine(Color.White, p, p + Offset(0f, k * sxy.y), w)
        }
    }
}

/** The box above the Crop bar: aspect presets, and More for Rotate (straighten) and Vertical / Horizontal perspective. */
@Composable
fun CropRows(state: EditorState, c: Crop, aspect: Float, box: Modifier) {
    val frame = c.frame(aspect).let { (fw, fh) -> fw / fh }
    ToolBox(state.cropMore, { state.cropMore = !state.cropMore }, box, "Rotate and perspective", moreRows = {
        for (p in cropParams) CropSlider(state, c, aspect, p)
    }) {
        Row(
            Modifier.weight(1f).padding(start = 6.dp).scrollFade(),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            for ((name, landscape) in ratios) {
                val wide = landscape?.let { if (it == 0f) maxOf(frame, 1f / frame) else it }
                val sel = state.cropRatio.let { r -> if (wide == null) r == null else r != null && (abs(r - wide) < 1e-3f || abs(r - 1f / wide) < 1e-3f) }
                val portrait = state.cropRatio?.let { it < 1f } ?: false
                val label = if (sel && portrait && name.contains(':')) name.split(':').let { "${it[1]}:${it[0]}" } else name
                Chip(label, sel) {
                    if (wide == null) state.cropRatio = null
                    else {
                        val (w, h) = c.size(aspect)
                        // the selected preset turns; a new one follows the crop's current orientation (or the photo's, for Original)
                        val r = when {
                            sel -> 1f / state.cropRatio!!
                            landscape == 0f -> frame
                            w < h -> 1f / wide
                            else -> wide
                        }
                        state.cropRatio = r
                        state.crop = c.maximized(aspect, r)
                    }
                }
            }
        }
    }
}

/** The Crop bar: back, rotate, flip, reset all, apply. */
@Composable
fun RowScope.CropBar(state: EditorState, c: Crop, onDiscard: () -> Unit) {
    ToolIcon(Icons.Crop, "Crop. Back to tools", onDiscard)
    IconButton({ state.crop = c.turned(); state.cropRatio = state.cropRatio?.let { 1f / it } }) { Icon(Icons.Turn, "Rotate 90° clockwise", tint = Bloom.On) }
    IconButton({ state.crop = c.flipped() }) { Icon(Icons.Flip, "Flip horizontally", tint = Bloom.On) }
    Spacer(Modifier.weight(1f))
    IconButton({ state.crop = Crop(); state.cropRatio = null }, enabled = !c.isIdentity) { Icon(Icons.Reset, "Reset crop", tint = if (c.isIdentity) Bloom.Outline else Bloom.On) }
    IconButton(state::applyCrop) { Icon(Icons.Check, "Apply crop", tint = Bloom.Pollen) }
}

/** A small copy of [preview] as [c] shows it (the minimap), sampled on the CPU. */
fun cropThumb(preview: Bitmap, c: Crop, full: Boolean): Bitmap {
    val aspect = preview.width.toFloat() / preview.height
    val m = c.matrix(aspect, full)
    val (w, h) = if (full) c.frame(aspect) else c.size(aspect)
    val s = 132f / maxOf(w, h)
    val tw = (w * s).toInt().coerceAtLeast(1)
    val th = (h * s).toInt().coerceAtLeast(1)
    val k = 256f / maxOf(preview.width, preview.height)
    val small = Bitmap.createScaledBitmap(preview, (preview.width * k).toInt().coerceAtLeast(1), (preview.height * k).toInt().coerceAtLeast(1), true)
    val src = IntArray(small.width * small.height).also { small.getPixels(it, 0, small.width, 0, 0, small.width, small.height) }
    val out = IntArray(tw * th) { i ->
        val (u, v) = Crop.map(m, (i % tw + 0.5f) / tw, (i / tw + 0.5f) / th) ?: (-1f to -1f)
        if (u !in 0f..1f || v !in 0f..1f) 0xFF0E0E0E.toInt()
        else src[(v * (small.height - 1)).toInt() * small.width + (u * (small.width - 1)).toInt()]
    }
    if (small !== preview) small.recycle()
    return Bitmap.createBitmap(out, tw, th, Bitmap.Config.ARGB_8888)
}
