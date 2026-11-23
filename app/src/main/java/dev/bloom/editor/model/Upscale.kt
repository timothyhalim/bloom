package dev.bloom.editor.model

import kotlin.math.roundToInt

/** How on-device upscaling splits a photo into overlapping tiles, and how their seams blend. */
object Upscale {
    const val TILE = 256
    const val OVERLAP = 32 // Real-ESRGAN reads ~33 px around each pixel

    /**
     * Tiles along an axis of [size] px: their length (the same for all, so the model keeps one input shape) and starts.
     * Neighbours overlap by at least [OVERLAP] px, never three on one pixel.
     */
    fun tiles(size: Int): Pair<Int, IntArray> {
        if (size <= TILE) return size to intArrayOf(0)
        val n = (size - OVERLAP + TILE - OVERLAP - 1) / (TILE - OVERLAP)
        val len = (size + (n - 1) * OVERLAP + n - 1) / n
        return len to IntArray(n) { i -> (i.toFloat() * (size - len) / (n - 1)).roundToInt() }
    }

    /**
     * Tile [i]'s blend weight for each of its [len] × [by] output pixels along the axis: 1 inside, ramping linearly
     * across each overlap, so the weights of neighbouring tiles add up to 1.
     */
    fun weights(starts: IntArray, len: Int, i: Int, by: Int): FloatArray {
        val s = starts[i]
        return FloatArray(len * by) { o ->
            val x = s + (o + 0.5f) / by
            val up = if (i == 0) 1f else (x - s) / (starts[i - 1] + len - s)
            val down = if (i == starts.lastIndex) 1f else 1f - (x - starts[i + 1]) / (s + len - starts[i + 1])
            minOf(up, down).coerceIn(0f, 1f)
        }
    }
}
