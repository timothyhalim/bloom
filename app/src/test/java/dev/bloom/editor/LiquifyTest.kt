package dev.bloom.editor

import dev.bloom.editor.model.WarpField
import dev.bloom.editor.model.WarpStroke
import dev.bloom.editor.model.WarpTool
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class LiquifyTest {
    private val aspect = 1.5f

    private fun field(vararg s: WarpStroke) = WarpField(aspect).apply { update(s.toList()) }

    /** Offset (photo heights) at the cell nearest upright (x, y). */
    private fun WarpField.at(x: Float, y: Float): Pair<Float, Float> {
        val i = ((y * h).toInt() * w + (x * w).toInt()) * 2
        return d[i] to d[i + 1]
    }

    private fun stroke(tool: WarpTool, vararg xy: Float, strength: Float = 1f): WarpStroke {
        var s = WarpStroke(tool, listOf(xy[0], xy[1]), 0.1f, strength)
        for (k in 1 until xy.size / 2) s = s.lineTo(xy[2 * k], xy[2 * k + 1], aspect)
        return s
    }

    @Test
    fun emptyFieldChangesNothing() {
        assertTrue(field().d.all { it == 0f })
        assertEquals(0f, field().maxOffset())
    }

    @Test
    fun pushDragsPixelsAlongTheStroke() {
        // dragging right: a pixel just right of the end now shows what was to its left, so it reads leftward (offset x < 0)
        val f = field(stroke(WarpTool.Push, 0.4f, 0.5f, 0.5f, 0.5f))
        val (dx, dy) = f.at(0.5f, 0.5f)
        assertTrue("dx = $dx", dx < -0.01f)
        assertEquals(0f, dy, 1e-3f)
        // outside the brush nothing moves
        assertEquals(0f to 0f, f.at(0.9f, 0.1f))
    }

    @Test
    fun bloatReadsTowardTheCentreAndPinchAway() {
        val b = field(stroke(WarpTool.Bloat, 0.5f, 0.5f, 0.501f, 0.5f, 0.5f, 0.5f))
        val p = field(stroke(WarpTool.Pinch, 0.5f, 0.5f, 0.501f, 0.5f, 0.5f, 0.5f))
        // right of the centre: Bloat reads from nearer the centre (offset left), Pinch from farther out (offset right)
        assertTrue(b.at(0.53f, 0.5f).first < 0f)
        assertTrue(p.at(0.53f, 0.5f).first > 0f)
    }

    @Test
    fun twirlKeepsDistanceFromTheCentre() {
        val f = field(WarpStroke(WarpTool.Twirl, List(20) { 0.5f }, 0.1f, 1f))
        val x = 0.53f
        val (dx, dy) = f.at(x, 0.5f)
        val px = ((x * f.w).toInt() + 0.5f) / f.w * aspect
        val py = ((0.5f * f.h).toInt() + 0.5f) / f.h
        val cx = 0.5f * aspect
        assertTrue("it turns: dy = $dy", kotlin.math.abs(dy) > 1e-3f)
        assertEquals(hypot(px - cx, py - 0.5f), hypot(px + dx - cx, py + dy - 0.5f), 2e-3f)
        // clockwise on screen (y down): right of the centre now shows what was above it
        assertTrue("reads from above: dy = $dy", dy < 0f)
        val ccw = field(WarpStroke(WarpTool.Twirl, List(20) { 0.5f }, 0.1f, 1f, reverse = true))
        assertTrue("reversed reads from below", ccw.at(x, 0.5f).second > 0f)
    }

    @Test
    fun restoreAtFullStrengthReturnsExactlyToZero() {
        val push = stroke(WarpTool.Push, 0.3f, 0.5f, 0.6f, 0.4f, 0.4f, 0.6f)
        val sweep = mutableListOf<Float>()
        for (y in 0..20) for (x in 0..20) { sweep += x / 20f; sweep += y / 20f }
        val restore = WarpStroke(WarpTool.Restore, sweep, 0.15f, 1f)
        val f = field(push, restore, restore, restore)
        assertTrue("max ${f.maxOffset()}", f.d.all { it == 0f })
    }

    @Test
    fun growingAStrokeMatchesReplayingIt() {
        val full = stroke(WarpTool.Push, 0.3f, 0.5f, 0.5f, 0.45f, 0.6f, 0.6f)
        val a = WarpField(aspect)
        // the stroke as it was mid-drag, then the finished one, then a second stroke
        val half = full.copy(points = full.points.subList(0, full.points.size / 2 / 2 * 2))
        a.update(listOf(half))
        a.update(listOf(full))
        val twirl = WarpStroke(WarpTool.Twirl, List(10) { 0.55f }, 0.08f, 0.5f)
        a.update(listOf(full, twirl))
        assertArrayEquals(field(full, twirl).d, a.d, 1e-6f)
        // undo back to one stroke replays from zero
        a.update(listOf(full))
        assertArrayEquals(field(full).d, a.d, 1e-6f)
    }
}
