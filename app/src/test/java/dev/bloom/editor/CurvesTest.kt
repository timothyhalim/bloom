package dev.bloom.editor

import dev.bloom.editor.model.Curves
import dev.bloom.editor.model.curveAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurvesTest {
    private val s = listOf(0f, 0f, 0.25f, 0.15f, 0.5f, 0.5f, 0.75f, 0.85f, 1f, 1f)

    @Test
    fun passesThroughItsPoints() {
        for (i in 0 until s.size / 2) assertEquals(s[2 * i + 1], curveAt(s, s[2 * i]), 1e-6f)
    }

    @Test
    fun risingPointsGiveARisingCurveWithoutOvershoot() {
        // a steep step between flat parts is where plain cubic splines overshoot
        val p = listOf(0f, 0.1f, 0.4f, 0.1f, 0.6f, 0.9f, 1f, 0.9f)
        var last = -1f
        for (i in 0..1000) {
            val y = curveAt(p, i / 1000f)
            assertTrue(y >= last - 1e-6f)
            assertTrue(y in 0.1f - 1e-6f..0.9f + 1e-6f)
            last = y
        }
    }

    @Test
    fun defaultCurveChangesNothing() {
        assertFalse(Curves().active)
        val lut = Curves().lut(256)
        for (i in 0 until 256) for (c in 0..2) assertEquals(i / 255f, lut[i * 4 + c], 1e-6f)
    }

    @Test
    fun channelCurveAppliesAfterRgb() {
        val c = Curves(rgb = listOf(0f, 0.5f, 1f, 1f), r = listOf(0f, 1f, 1f, 0f)) // rgb lifts black to 0.5; r inverts
        val lut = c.lut(2)
        assertEquals(0.5f, lut[0], 1e-6f) // r: 1 - 0.5
        assertEquals(0.5f, lut[1], 1e-6f) // g: 0.5
        assertEquals(0f, lut[4], 1e-6f) // r at white: 1 - 1
        assertTrue(c.active)
    }
}
