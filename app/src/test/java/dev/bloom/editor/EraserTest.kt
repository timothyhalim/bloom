package dev.bloom.editor

import dev.bloom.editor.model.Eraser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EraserTest {
    @Test
    fun boundsOfTheMarkedPixels() {
        val w = 10
        val m = ByteArray(w * 8).also { it[3 * w + 2] = 1; it[5 * w + 6] = 1 }
        assertArrayEquals(intArrayOf(2, 3, 7, 6), Eraser.bounds(m, w, 8))
        assertNull(Eraser.bounds(ByteArray(80), w, 8))
    }

    @Test
    fun contextIsCentredSquareAtLeastMinAndStaysOnThePhoto() {
        // small hole in the middle of a big photo: a 768 square around it
        assertArrayEquals(intArrayOf(616, 116, 1384, 884), Eraser.context(intArrayOf(990, 490, 1010, 510), 2000, 1000))
        // big hole: 3× its long side (capped by the photo's height here)
        val c = Eraser.context(intArrayOf(800, 400, 1100, 500), 2000, 1000)
        assertEquals(900, c[2] - c[0])
        assertEquals(1000, Eraser.context(intArrayOf(500, 100, 1100, 300), 2000, 1000).let { it[3] - it[1] })
        // near the corner: moved back onto the photo
        assertArrayEquals(intArrayOf(0, 0, 768, 768), Eraser.context(intArrayOf(5, 5, 20, 20), 2000, 1000))
        // photo smaller than 768: the whole photo
        assertArrayEquals(intArrayOf(0, 0, 300, 200), Eraser.context(intArrayOf(100, 50, 120, 70), 300, 200))
    }

    @Test
    fun erodeGrowsTheHole() {
        val w = 9
        val keep = ByteArray(w * w) { if (it == 4 * w + 4) 0 else -1 } // one hole pixel in the middle
        val e = Eraser.erode(keep, w, w, 2)
        val holes = (0 until w * w).filter { e[it].toInt() == 0 }
        assertEquals(25, holes.size) // a 5 x 5 square
        assertEquals(0, e[2 * w + 2].toInt())
        assertEquals(-1, e[1 * w + 4].toInt())
    }
}
