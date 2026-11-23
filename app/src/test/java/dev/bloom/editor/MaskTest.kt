package dev.bloom.editor

import dev.bloom.editor.model.BrushStep
import dev.bloom.editor.model.GradientStep
import dev.bloom.editor.model.Mask
import dev.bloom.editor.model.Stroke
import dev.bloom.editor.model.with
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class MaskTest {
    @Test
    fun strokeDabsAreAQuarterRadiusApart() {
        // 2:1 image, so a move of 0.5 in x is 1.0 image heights.
        var s = Stroke(emptyList(), radius = 0.1f, softness = 0f, flow = 1f).lineTo(0f, 0.5f, 2f)
        s = s.lineTo(0.5f, 0.5f, 2f)
        assertEquals(41, s.points.size / 2)
        val gaps = (1 until s.points.size / 2).map { i -> hypot((s.points[2 * i] - s.points[2 * i - 2]) * 2f, s.points[2 * i + 1] - s.points[2 * i - 1]) }
        gaps.forEach { assertEquals(0.025f, it, 1e-4f) }
        // a move shorter than one gap adds nothing; the remainder carries to the next move
        assertEquals(s, s.lineTo(0.505f, 0.5f, 2f))
    }

    @Test
    fun maskIsActiveOnlyWithAVisibleStep() {
        assertFalse(Mask(invert = true).active)
        val m = Mask(listOf(BrushStep(), GradientStep(false, 0f, 0f, 1f, 1f)))
        assertTrue(m.active)
        assertFalse(m.copy(steps = m.steps.map { it.with(visible = false) }).active)
        assertEquals(false, m.steps[1].with(add = false).add)
    }
}
