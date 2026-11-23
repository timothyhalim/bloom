package dev.bloom.editor

import dev.bloom.editor.model.Upscale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpscaleTest {
    private val sizes = listOf(1, 100, 255, 256, 257, 300, 480, 481, 704, 1000, 3000, 4033)

    @Test
    fun tilesCoverTheAxisWithTheOverlapAndNoTriples() {
        for (size in sizes) {
            val (len, starts) = Upscale.tiles(size)
            assertTrue("$size: length $len", len in 1..Upscale.TILE)
            assertEquals("$size starts at 0", 0, starts.first())
            assertEquals("$size ends at the edge", size, starts.last() + len)
            for (i in 1 until starts.size) {
                assertTrue("$size: overlap ${starts[i - 1] + len - starts[i]}", starts[i - 1] + len - starts[i] >= Upscale.OVERLAP - 1)
                if (i >= 2) assertTrue("$size: three tiles on one pixel", starts[i - 2] + len <= starts[i])
            }
        }
        // the fewest tiles that still overlap by 32: 3000 px = 14 tiles
        assertEquals(14, Upscale.tiles(3000).second.size)
    }

    @Test
    fun blendWeightsAddUpToOneOnEveryOutputPixel() {
        for (size in sizes) for (by in listOf(2, 4)) {
            val (len, starts) = Upscale.tiles(size)
            val sum = FloatArray(size * by)
            for (i in starts.indices) Upscale.weights(starts, len, i, by).forEachIndexed { o, w -> sum[starts[i] * by + o] += w }
            sum.forEachIndexed { o, s -> assertEquals("size $size ×$by at $o", 1f, s, 1e-5f) }
        }
    }

    @Test
    fun weightsRampOnlyInTheOverlap() {
        val (len, starts) = Upscale.tiles(600)
        val w = Upscale.weights(starts, len, 1, 2)
        val left = starts[0] + len - starts[1] // overlap with tile 0, px
        assertEquals(1f, w[left * 2], 0f)
        assertTrue(w[0] < 0.05f && w[left] in 0.45f..0.55f)
    }
}
