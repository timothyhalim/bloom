package dev.bloom.editor

import dev.bloom.editor.model.Crop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CropTest {
    private val aspect = 1.5f

    private fun at(c: Crop, x: Float, y: Float) = Crop.map(c.matrix(aspect), x, y)!!

    private fun near(e: Pair<Float, Float>, a: Pair<Float, Float>) {
        assertEquals(e.first, a.first, 1e-4f)
        assertEquals(e.second, a.second, 1e-4f)
    }

    @Test
    fun identityMapsOutputOntoThePhoto() {
        near(0f to 0f, at(Crop(), 0f, 0f))
        near(1f to 1f, at(Crop(), 1f, 1f))
        assertEquals(aspect to 1f, Crop().size(aspect))
    }

    @Test
    fun cropRectPicksThatPartOfThePhoto() {
        val c = Crop(left = 0.25f, top = 0.5f, right = 0.75f, bottom = 1f)
        near(0.25f to 0.5f, at(c, 0f, 0f))
        near(0.75f to 1f, at(c, 1f, 1f))
    }

    @Test
    fun quarterTurnIsClockwise() {
        val c = Crop().turned()
        assertEquals(1f to aspect, c.size(aspect))
        // output top-left shows the photo's bottom-left; output top-right shows its top-left
        near(0f to 1f, at(c, 0f, 0f))
        near(0f to 0f, at(c, 1f, 0f))
        assertEquals(Crop(), Crop().turned().turned().turned().turned())
    }

    @Test
    fun flipMirrorsAndTwiceIsIdentity() {
        near(1f to 0f, at(Crop().flipped(), 0f, 0f))
        val c = Crop(angle = 5f, turns = 1, horizontal = 20f, left = 0.1f, right = 0.6f).fit(aspect)
        near(at(c, 0.3f, 0.7f), at(c.flipped().flipped(), 0.3f, 0.7f))
        // flipping a turned, straightened crop mirrors the output: its left edge shows what was the right edge
        near(at(c, 1f, 0.3f), at(c.flipped(), 0f, 0.3f))
        near(at(c, 0.2f, 0.9f), at(c.flipped(), 0.8f, 0.9f))
    }

    @Test
    fun turningKeepsTheSameCropOfThePhoto() {
        val c = Crop(angle = 3f, vertical = 30f, left = 0.1f, top = 0.2f, right = 0.7f, bottom = 0.9f).fit(aspect)
        val t = c.turned()
        // output corner (0,0) after a clockwise turn is the old bottom-left
        near(at(c, 0f, 1f), at(t, 0f, 0f))
        near(at(c, 1f, 0f), at(t, 1f, 1f))
    }

    @Test
    fun straightenedCropFitsInsideThePhoto() {
        for (a in listOf(-45f, -10f, 1f, 30f, 45f)) for (v in listOf(-100f, 0f, 100f)) {
            val c = Crop(angle = a, vertical = v).fit(aspect)
            assertTrue("a=$a v=$v", c.inside(aspect))
            assertFalse("a=$a v=$v: not shrunk more than needed", c.copy(left = c.left - 0.01f, right = c.right + 0.01f, top = c.top - 0.01f, bottom = c.bottom + 0.01f).inside(aspect))
        }
        assertFalse(Crop(angle = 10f).inside(aspect))
    }

    @Test
    fun maximizedFillsThePhotoAtTheRatio() {
        val c = Crop().maximized(aspect, 1f)
        val (w, h) = c.size(aspect)
        assertEquals(1f, w / h, 1e-4f)
        assertEquals(1f, h, 1e-3f)
        // straightened and keystoned: fits, and touches the photo (a little bigger doesn't fit)
        for (k in listOf(Crop(angle = 12f), Crop(vertical = 60f), Crop(angle = -20f, horizontal = -40f, turns = 1))) {
            val m = k.maximized(aspect)
            assertTrue("$k", m.inside(aspect))
            assertFalse("$k", m.copy(left = m.left - 0.005f, right = m.right + 0.005f, top = m.top - 0.005f, bottom = m.bottom + 0.005f).inside(aspect))
        }
    }

    @Test
    fun maximizedUsesTheWideEndOfAKeystone() {
        // vertical keystone widens the top: the largest crop sits above the photo's centre and beats a centred one
        val k = Crop(vertical = 60f)
        val m = k.maximized(aspect)
        val centred = k.fit(aspect) // shrunk about the photo's centre at the photo's ratio
        assertTrue("${m.size(aspect)} vs ${centred.size(aspect)}", m.size(aspect).second > centred.size(aspect).second * 1.02f)
        assertTrue("above centre", (m.top + m.bottom) / 2 < 0.5f)
        // no keystone: a square crop of a landscape photo stays in the middle
        val sq = Crop().maximized(aspect, 1f)
        assertEquals(0.5f, (sq.left + sq.right) / 2, 1e-3f)
    }

    @Test
    fun frameShowsTheWholeWarpedPhoto() {
        for (k in listOf(Crop(angle = 30f), Crop(vertical = 100f), Crop(horizontal = -70f, angle = 5f, turns = 3, flip = true))) {
            val inv = Crop.inverse(k.matrix(aspect, full = true))
            val pts = listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f).map { (u, v) -> Crop.map(inv, u, v)!! }
            assertTrue("$k: $pts", pts.all { (x, y) -> x in -1e-3f..1.001f && y in -1e-3f..1.001f })
            assertEquals("$k touches left", 0f, pts.minOf { it.first }, 1e-3f)
            assertEquals("$k touches bottom", 1f, pts.maxOf { it.second }, 1e-3f)
        }
    }

    @Test
    fun moveTowardStopsAtThePhotoEdge() {
        val c = Crop(angle = 10f, left = 0.4f, top = 0.4f, right = 0.6f, bottom = 0.6f)
        val m = c.moveToward(c.copy(left = 0f, top = 0f), aspect)
        assertTrue(m.inside(aspect))
        assertTrue(m.left < c.left)
    }
}
