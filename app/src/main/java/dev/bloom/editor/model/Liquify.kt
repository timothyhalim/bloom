package dev.bloom.editor.model

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Liquify brushes, in the order the bar shows them. */
enum class WarpTool { Push, Bloat, Pinch, Twirl, Restore }

/**
 * One Liquify stroke: dab centres as x,y pairs in upright image coordinates (0..1). [radius] is a fraction of the image
 * height, [strength] and [softness] 0..1 (0: full strength to the edge; 1: fades from the centre, as the mask brush).
 * Push moves pixels along the stroke; the others act around each dab.
 */
@Serializable
data class WarpStroke(
    val tool: WarpTool, val points: List<Float>, val radius: Float, val strength: Float, val softness: Float = 0.5f,
    val reverse: Boolean = false, // Twirl: counterclockwise
) {
    /** Adds dabs from the last one toward (x, y), a sixth of the radius apart (Push follows the finger closely). */
    fun lineTo(x: Float, y: Float, aspect: Float) =
        points.dabsTo(x, y, aspect, radius / 6f).let { if (it === points) this else copy(points = it) }
}

/**
 * Where each pixel reads from: a grid of offsets ([GRID] cells on the long side) over the upright photo, in photo heights.
 * The result at p is the input at p + offset(p), so an all-zero grid changes nothing. Built by replaying strokes; a
 * stroke that grew is continued from where it left off ([update]).
 */
class WarpField(val aspect: Float) {
    val w = if (aspect >= 1f) GRID else (GRID * aspect).roundToInt().coerceAtLeast(2)
    val h = if (aspect >= 1f) (GRID / aspect).roundToInt().coerceAtLeast(2) else GRID
    val d = FloatArray(w * h * 2)
    private var old = FloatArray(w * h * 2)

    /** Cells changed since the last [takeDirty]: x0, y0, x1, y1 (exclusive), or null. */
    private var dirty: IntArray? = intArrayOf(0, 0, w, h) // all of it at first: a new texture holds garbage

    private var strokes: List<WarpStroke> = emptyList()
    private var applied = 0 // dabs of the last stroke already applied

    /** Brings the grid to [all]: continues if [all] only adds to what was applied, else replays from zero. */
    fun update(all: List<WarpStroke>) {
        val n = strokes.size
        // the last stroke continues if only its points grew, from the same first dab
        val grows = all.size >= n && (0 until n - 1).all { all[it] === strokes[it] } &&
            (n == 0 || all[n - 1].let { s ->
                val o = strokes[n - 1]
                s.copy(points = o.points) == o && s.points.size >= applied * 2 && s.points.take(2) == o.points.take(2)
            })
        if (!grows) {
            d.fill(0f)
            dirty = intArrayOf(0, 0, w, h)
            strokes = emptyList()
            applied = 0
        }
        for (i in strokes.size.coerceAtLeast(1) - 1 until all.size) {
            val s = all[i]
            val from = if (i == strokes.size - 1) applied else 0
            for (k in from until s.points.size / 2) dab(s, k)
            if (i == all.lastIndex) applied = s.points.size / 2
        }
        strokes = all
    }

    /** The largest offset, in photo heights: how far a pixel can read from (export tiles overlap by this). */
    fun maxOffset(): Float {
        var m = 0f
        for (i in 0 until w * h) m = maxOf(m, hypot(d[2 * i], d[2 * i + 1]))
        return m
    }

    fun takeDirty(): IntArray? = dirty.also { dirty = null }

    /** Bilinear offset at (x, y) in photo heights, from [old]. */
    private fun sample(x: Float, y: Float, out: FloatArray) {
        val gx = (x / aspect * w - 0.5f).coerceIn(0f, w - 1f)
        val gy = (y * h - 0.5f).coerceIn(0f, h - 1f)
        val x0 = gx.toInt().coerceAtMost(w - 2)
        val y0 = gy.toInt().coerceAtMost(h - 2)
        val fx = gx - x0
        val fy = gy - y0
        for (c in 0..1) {
            val a = old[(y0 * w + x0) * 2 + c] * (1 - fx) + old[(y0 * w + x0 + 1) * 2 + c] * fx
            val b = old[((y0 + 1) * w + x0) * 2 + c] * (1 - fx) + old[((y0 + 1) * w + x0 + 1) * 2 + c] * fx
            out[c] = a * (1 - fy) + b * fy
        }
    }

    /** Applies dab [k] of [s]. Each brush moves where pixels read from: new(p) = old(q) + (q - p) for its own q(p). */
    private fun dab(s: WarpStroke, k: Int) {
        val cx = s.points[2 * k] * aspect
        val cy = s.points[2 * k + 1]
        val r = s.radius
        // Push: this dab's step along the stroke (the first dab has none)
        val vx = if (k > 0) cx - s.points[2 * k - 2] * aspect else 0f
        val vy = if (k > 0) cy - s.points[2 * k - 1] else 0f
        if (s.tool == WarpTool.Push && vx == 0f && vy == 0f) return
        val x0 = ((cx - r) / aspect * w).toInt().coerceIn(0, w)
        val x1 = ((cx + r) / aspect * w + 1).toInt().coerceIn(0, w)
        val y0 = ((cy - r) * h).toInt().coerceIn(0, h)
        val y1 = ((cy + r) * h + 1).toInt().coerceIn(0, h)
        if (x0 >= x1 || y0 >= y1) return
        // brushes read old offsets at most a quarter radius away: copy just those rows
        val mg = (r * h / 4).toInt() + 2
        val from = maxOf(0, y0 - mg) * w * 2
        System.arraycopy(d, from, old, from, minOf(h, y1 + mg) * w * 2 - from)
        val o = FloatArray(2)
        // the mask brush's falloff: full inside e, smoothstep over the soft outer part
        val e = 1f - maxOf(s.softness, 0.001f)
        for (j in y0 until y1) for (i in x0 until x1) {
            val px = (i + 0.5f) / w * aspect
            val py = (j + 0.5f) / h
            val dist = hypot(px - cx, py - cy) / r
            if (dist >= 1f) continue
            val f = s.strength * (1f - ((dist - e) / (1f - e)).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) })
            val at = (j * w + i) * 2
            if (s.tool == WarpTool.Restore) {
                for (c in 0..1) { val v = old[at + c] * (1 - f); d[at + c] = if (abs(v) < SNAP) 0f else v }
                continue
            }
            var qx: Float
            var qy: Float
            when (s.tool) {
                WarpTool.Push -> { qx = px - f * vx; qy = py - f * vy }
                WarpTool.Bloat -> { qx = px - f * SCALE * (px - cx); qy = py - f * SCALE * (py - cy) }
                WarpTool.Pinch -> { qx = px + f * SCALE * (px - cx); qy = py + f * SCALE * (py - cy) }
                else -> {
                    // reading from points turned back makes the picture turn forward: clockwise on screen (y down) by default
                    val t = f * TWIRL * if (s.reverse) 1f else -1f
                    val c = cos(t)
                    val sn = sin(t)
                    qx = cx + c * (px - cx) - sn * (py - cy)
                    qy = cy + sn * (px - cx) + c * (py - cy)
                }
            }
            sample(qx, qy, o)
            d[at] = o[0] + qx - px
            d[at + 1] = o[1] + qy - py
        }
        dirty = dirty?.let { intArrayOf(minOf(it[0], x0), minOf(it[1], y0), maxOf(it[2], x1), maxOf(it[3], y1)) } ?: intArrayOf(x0, y0, x1, y1)
    }

    companion object {
        const val GRID = 512
        const val SCALE = 0.06f // Bloat / Pinch: share of the distance to the centre, per dab at full strength
        const val TWIRL = 0.12f // radians per dab at full strength
        const val SNAP = 1e-5f // Restore: offsets this small become exactly zero, so restored pixels are the original
    }
}
