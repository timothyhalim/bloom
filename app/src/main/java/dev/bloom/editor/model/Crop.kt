package dev.bloom.editor.model

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Crop and geometry, applied after all layers, so layers and masks keep the uncropped photo's coordinates.
 * The photo is flipped ([flip]), turned clockwise by [turns] quarter turns, keystoned ([vertical], [horizontal],
 * -100..100), then straightened by [angle] degrees. The frame is the box around the result, so all of the photo
 * can be reached; [left]..[bottom] is the crop inside it (0..1), kept on the photo.
 */
data class Crop(
    val turns: Int = 0, val flip: Boolean = false, val angle: Float = 0f,
    val vertical: Float = 0f, val horizontal: Float = 0f,
    val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f,
) {
    /** The frame: the box around the warped photo, centred coordinates in photo heights (x0, y0, x1, y1). */
    private fun bounds(aspect: Float): FloatArray {
        val f = forward(aspect)
        val c = listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f).map { (x, y) -> map(f, x * aspect / 2, y / 2)!! }
        return floatArrayOf(c.minOf { it.first }, c.minOf { it.second }, c.maxOf { it.first }, c.maxOf { it.second })
    }

    /** Frame width and height, in upright photo heights. */
    fun frame(aspect: Float) = bounds(aspect).let { it[2] - it[0] to it[3] - it[1] }

    /** Output width and height, in upright photo heights. */
    fun size(aspect: Float) = frame(aspect).let { (fw, fh) -> fw * (right - left) to fh * (bottom - top) }

    /** Row-major 3×3: output 0..1 (the crop, or with [full] the whole frame) to upright photo 0..1. Projective. */
    fun matrix(aspect: Float, full: Boolean = false): FloatArray {
        val (x0, y0, x1, y1) = bounds(aspect)
        val fw = x1 - x0
        val fh = y1 - y0
        val (l, t, r, b) = if (full) listOf(0f, 0f, 1f, 1f) else listOf(left, top, right, bottom)
        // output -> frame, centred, in photo heights
        val out = floatArrayOf((r - l) * fw, 0f, x0 + l * fw, 0f, (b - t) * fh, y0 + t * fh, 0f, 0f, 1f)
        // centred photo heights -> photo 0..1
        val unit = floatArrayOf(1f / aspect, 0f, 0.5f, 0f, 1f, 0.5f, 0f, 0f, 1f)
        return mul(unit, mul(inverse(forward(aspect)), out))
    }

    /** Photo -> frame, both centred in photo heights. */
    private fun forward(aspect: Float): FloatArray {
        // the turned photo's width and height, in upright photo heights
        val (fw, fh) = if (turns % 2 == 0) aspect to 1f else 1f to aspect
        val fl = floatArrayOf(if (flip) -1f else 1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        var turn = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        repeat(turns) { turn = mul(floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f), turn) } // (x, y) -> (-y, x): clockwise, y down
        // keystone: at the turned photo's edge the scale is 1 ± KEYSTONE × value
        val key = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, KEYSTONE * horizontal / 100f / (fw / 2), KEYSTONE * vertical / 100f / (fh / 2), 1f)
        val a = Math.toRadians(angle.toDouble())
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        val rot = floatArrayOf(c, -s, 0f, s, c, 0f, 0f, 0f, 1f)
        return mul(rot, mul(key, mul(turn, fl)))
    }

    /** Every corner of the crop lands on the photo. */
    fun inside(aspect: Float) = fits(outline(aspect))

    /**
     * The photo's corners in the frame (0..1), x, y × 4 around it: a convex quad, as the warp is projective.
     * Then the winding (±1): which side of each edge is inside, reversed by a flip.
     */
    private fun outline(aspect: Float): FloatArray {
        val m = inverse(matrix(aspect, full = true))
        val o = FloatArray(9)
        for ((k, uv) in listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f).withIndex()) {
            val (x, y) = map(m, uv.first, uv.second)!!
            o[2 * k] = x
            o[2 * k + 1] = y
        }
        var area = 0f
        for (a in 0..3) { val b = (a + 1) % 4; area += o[2 * a] * o[2 * b + 1] - o[2 * b] * o[2 * a + 1] }
        o[8] = if (area > 0) 1f else -1f
        return o
    }

    /** Every corner of the crop is inside [outline] (the geometry is the same for every copy). Allocation-free: searches call it thousands of times. */
    private fun fits(o: FloatArray): Boolean {
        for (c in 0..3) {
            val x = if (c == 0 || c == 3) left else right
            val y = if (c < 2) top else bottom
            for (a in 0..3) {
                val b = (a + 1) % 4
                val cross = (o[2 * b] - o[2 * a]) * (y - o[2 * a + 1]) - (o[2 * b + 1] - o[2 * a + 1]) * (x - o[2 * a])
                if (cross * o[8] < -EPS) return false
            }
        }
        return true
    }

    /** Where the photo's centre is in the frame, 0..1. */
    private fun centre(aspect: Float) = bounds(aspect).let { (x0, y0, x1, y1) -> -x0 / (x1 - x0) to -y0 / (y1 - y0) }

    /** This crop, shrunk toward the photo's centre until it fits on the photo. */
    fun fit(aspect: Float) = centre(aspect).let { (cx, cy) -> copy(left = cx, top = cy, right = cx, bottom = cy).moveToward(this, aspect) }

    /** As far from this (which fits) toward [to]'s rectangle as stays on the photo. */
    fun moveToward(to: Crop, aspect: Float): Crop {
        val o = outline(aspect)
        val end = copy(left = to.left, top = to.top, right = to.right, bottom = to.bottom)
        if (end.fits(o)) return end
        val lerp = { t: Float -> copy(left = left + (to.left - left) * t, top = top + (to.top - top) * t, right = right + (to.right - right) * t, bottom = bottom + (to.bottom - bottom) * t) }
        return lerp(bisect(1f, 0f, 20) { lerp(it).fits(o) })
    }

    /** Turned a quarter clockwise, crop included. */
    fun turned() = copy(turns = (turns + 1) % 4, left = 1 - bottom, top = left, right = 1 - top, bottom = right, horizontal = 0f - vertical, vertical = horizontal) // 0f - x: no -0f, so equals() holds

    /** Mirrored left to right as seen, crop included. */
    fun flipped() = copy(flip = !flip, turns = (4 - turns) % 4, angle = 0f - angle, horizontal = 0f - horizontal, left = 1 - right, right = 1 - left)

    /**
     * The largest crop at output [ratio] (width / height in pixels; null = this crop's) that fits on the photo.
     * Among equally large ones, the one nearest the photo's centre.
     */
    fun maximized(aspect: Float, ratio: Float? = null): Crop {
        val (fw, fh) = frame(aspect)
        val r = ratio ?: size(aspect).let { (w, h) -> w / h }
        val o = outline(aspect)
        val at = { cx: Float, cy: Float, h: Float -> copy(left = cx - h * r / fw / 2, right = cx + h * r / fw / 2, top = cy - h / fh / 2, bottom = cy + h / fh / 2) }
        // height of the largest crop centred at (cx, cy); concave in the centre, since the photo is convex in the frame
        val biggest = { cx: Float, cy: Float -> bisect(minOf(fw / r, fh), 0f, 14) { at(cx, cy, it).fits(o) } }
        val (x0, y0) = centre(aspect)
        // one axis at a time: ternary search for the best, then back toward the photo's centre while just as big
        val best = { from: Float, f: (Float) -> Float ->
            var lo = 0f
            var hi = 1f
            repeat(16) { val a = lo + (hi - lo) / 3; val b = hi - (hi - lo) / 3; if (f(a) < f(b)) lo = a else hi = b }
            val top = f((lo + hi) / 2) - 1e-4f
            if (f(from) >= top) from else bisect(from, (lo + hi) / 2, 12) { f(it) >= top }
        }
        var cx = x0
        var cy = y0
        repeat(2) {
            cy = best(y0) { biggest(cx, it) }
            cx = best(x0) { biggest(it, cy) }
        }
        return at(cx, cy, biggest(cx, cy))
    }

    val isIdentity get() = this == Crop()

    companion object {
        const val KEYSTONE = 0.4f
        private const val EPS = 1e-5f

        fun mul(a: FloatArray, b: FloatArray) = FloatArray(9) { i -> (0..2).sumOf { k -> (a[i / 3 * 3 + k] * b[k * 3 + i % 3]).toDouble() }.toFloat() }

        fun inverse(m: FloatArray): FloatArray {
            val (a, b, c, d, e) = m
            val f = m[5]; val g = m[6]; val h = m[7]; val i = m[8]
            val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
            require(abs(det) > 1e-12f) { "singular" }
            return floatArrayOf(
                e * i - f * h, c * h - b * i, b * f - c * e,
                f * g - d * i, a * i - c * g, c * d - a * f,
                d * h - e * g, b * g - a * h, a * e - b * d,
            ).also { for (k in 0..8) it[k] /= det }
        }

        /** Maps (x, y) through projective [m]; null when it lands behind the projection (w ≤ 0). */
        fun map(m: FloatArray, x: Float, y: Float): Pair<Float, Float>? {
            val w = m[6] * x + m[7] * y + m[8]
            if (w <= 1e-6f) return null
            return (m[0] * x + m[1] * y + m[2]) / w to (m[3] * x + m[4] * y + m[5]) / w
        }
    }
}

/** Halves the gap between [good] (passes [ok]) and [bad] [n] times; returns the last value that passes. */
private inline fun bisect(bad: Float, good: Float, n: Int, ok: (Float) -> Boolean): Float {
    var b = bad
    var g = good
    repeat(n) { val mid = (b + g) / 2; if (ok(mid)) g = mid else b = mid }
    return g
}
