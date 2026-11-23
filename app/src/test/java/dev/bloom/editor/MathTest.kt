package dev.bloom.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class MathTest {
    @Test
    fun sampleSizeKeepsLongSideUnderMax() {
        assertEquals(1, ImageIo.sampleSize(2048, 1536, 2048))
        assertEquals(2, ImageIo.sampleSize(4000, 3000, 2048))
        assertEquals(4, ImageIo.sampleSize(8000, 6000, 2048)) // 48 MP
        assertEquals(4, ImageIo.sampleSize(3000, 8000, 2048)) // portrait
    }

    @Test
    fun fitScaleLetterboxesCorrectAxis() {
        assertEquals(1f to 0.5f, fitScale(2000, 1000, 1000, 1000)) // wide image: bars top/bottom
        assertEquals(0.5f to 1f, fitScale(1000, 2000, 1000, 1000)) // tall image: bars left/right
        assertEquals(1f to 1f, fitScale(1600, 900, 1920, 1080))
    }
}
