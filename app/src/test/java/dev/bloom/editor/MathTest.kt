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

    @Test
    fun panLimitLetsThePhotosEdgeReachTheViewsMiddleButNoFurther() {
        assertEquals(1.5f, panLimit(1.5f, 1f), 0f) // bigger than the view: pan = half-size puts its edge in the middle
        assertEquals(0.8f, panLimit(0.8f, 1f), 0f) // smaller (not zoomed): the same, its edge stops at the middle
        assertEquals(1f, panLimit(1f, 1f), 0f) // exactly fits
    }
}
