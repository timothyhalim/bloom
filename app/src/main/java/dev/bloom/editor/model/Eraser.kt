package dev.bloom.editor.model

/** Rectangle and mask helpers for the AI eraser. Rectangles are IntArray(left, top, right, bottom), right/bottom exclusive. */
object Eraser {
    /** The box around every non-zero byte of [mask] (w × h), or null when it is empty. */
    fun bounds(mask: ByteArray, w: Int, h: Int): IntArray? {
        var l = w
        var t = h
        var r = -1
        var b = -1
        for (y in 0 until h) for (x in 0 until w) if (mask[y * w + x].toInt() != 0) {
            if (x < l) l = x
            if (x > r) r = x
            if (y < t) t = y
            if (y > b) b = y
        }
        return if (r < 0) null else intArrayOf(l, t, r + 1, b + 1)
    }

    /** [r] grown by [by] on every side, kept inside w × h. */
    fun inflate(r: IntArray, by: Int, w: Int, h: Int) =
        intArrayOf(maxOf(0, r[0] - by), maxOf(0, r[1] - by), minOf(w, r[2] + by), minOf(h, r[3] + by))

    /**
     * The square the model sees around hole [r]: [CONTEXT] times its long side, at least [MIN] (both capped by the
     * photo), centred on the hole and moved back onto the photo where it overhangs. Enough surroundings to copy from.
     */
    fun context(r: IntArray, w: Int, h: Int): IntArray {
        val side = maxOf(CONTEXT * maxOf(r[2] - r[0], r[3] - r[1]), MIN)
        val sw = minOf(side, w)
        val sh = minOf(side, h)
        val l = ((r[0] + r[2]) / 2 - sw / 2).coerceIn(0, w - sw)
        val t = ((r[1] + r[3]) / 2 - sh / 2).coerceIn(0, h - sh)
        return intArrayOf(l, t, l + sw, t + sh)
    }

    /** Shrinks the non-zero area of [m] (w × h) by [r] px (square), so the zero area grows by as much. */
    fun erode(m: ByteArray, w: Int, h: Int, r: Int): ByteArray {
        // separable: a row min, then a column min, each over 2r + 1 pixels
        val rows = ByteArray(m.size)
        for (y in 0 until h) for (x in 0 until w) {
            var keep = true
            for (k in maxOf(0, x - r)..minOf(w - 1, x + r)) if (m[y * w + k].toInt() == 0) { keep = false; break }
            rows[y * w + x] = if (keep) m[y * w + x] else 0
        }
        val out = ByteArray(m.size)
        for (y in 0 until h) for (x in 0 until w) {
            var keep = true
            for (k in maxOf(0, y - r)..minOf(h - 1, y + r)) if (rows[k * w + x].toInt() == 0) { keep = false; break }
            out[y * w + x] = if (keep) rows[y * w + x] else 0
        }
        return out
    }

    const val CONTEXT = 2
    const val MIN = 512
}
