package dev.bloom.editor

import dev.bloom.editor.model.BackgroundEffect
import dev.bloom.editor.model.BackgroundSource
import dev.bloom.editor.model.Document
import dev.bloom.editor.model.Layer
import dev.bloom.editor.model.LayerKind
import dev.bloom.editor.model.Mask
import dev.bloom.editor.model.PersonStep
import dev.bloom.editor.model.matchGain
import dev.bloom.editor.model.matchStats
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackgroundTest {
    /** [n] RGBA samples: the first [subject] in the matte with colour [fg] / [bg] from [v]. */
    private fun samples(n: Int, subject: Int, v: (Int) -> Int) = ByteArray(n * 4) { i ->
        val p = i / 4
        if (i % 4 == 3) (if (p < subject) 255 else 0).toByte() else v(p).toByte()
    }

    @Test
    fun statsAreTheSubjectsAndTheBackgroundsMeanAndDeviation() {
        // subject: 50 and 150 (mean 100, deviation 50); background: 200 everywhere
        val fg = samples(100, 50) { if (it < 25) 50 else 150 }
        val bg = samples(100, 50) { 200 }
        val s = matchStats(fg, bg)!!
        assertEquals(100 / 255f, s[0], 1e-4f)
        assertEquals(50 / 255f, s[3], 1e-4f)
        assertEquals(200 / 255f, s[6], 1e-4f)
        assertEquals(0f, s[9], 1e-4f)
        // no subject, or no background: nothing to match
        assertNull(matchStats(samples(10, 0) { 9 }, samples(10, 0) { 9 }))
        assertNull(matchStats(samples(10, 10) { 9 }, samples(10, 10) { 9 }))
    }

    @Test
    fun gainMovesTheSubjectMeanPartWayAndCapsContrast() {
        // subject mean 0.4, deviation 0.1; background mean 0.8, deviation 0.4 (4x: capped at 1.4x)
        val s = floatArrayOf(0.4f, 0.4f, 0.4f, 0.1f, 0.1f, 0.1f, 0.8f, 0.8f, 0.8f, 0.4f, 0.4f, 0.4f)
        for (k in listOf(0f, 0.5f, 1f)) {
            val g = matchGain(s, k)
            assertEquals("k=$k mean", 0.4f + k * 0.4f, 0.4f * g[0] + g[3], 1e-5f)
            assertEquals("k=$k contrast", 1f + k * 0.4f, g[0], 1e-5f)
        }
        // a flat background lowers contrast at most to 1 / 1.4
        assertEquals(1f / 1.4f, matchGain(s.copyOf().also { it[9] = 0f; it[10] = 0f; it[11] = 0f }, 1f)[1], 1e-5f)
        assertArrayEquals(floatArrayOf(1f, 1f, 1f, 0f, 0f, 0f), matchGain(null, 1f), 0f)
    }

    @Test
    fun backgroundLayerSurvivesJsonAndIsTheLastKind() {
        val l = Layer(3, effect = BackgroundEffect(BackgroundSource.Color, blur = 20f, color = 0xFF3A6EA5.toInt(), colorMatch = 0.6f, lightWrap = 0.1f), mask = Mask(listOf(PersonStep())))
        val json = Project(File("unused")).json
        val text = json.encodeToString(SavedProject(Project.VERSION, "a.jpg", Document(listOf(l))))
        assertTrue(text, text.contains("\"type\":\"background\"") && text.contains("\"type\":\"person\""))
        assertEquals(l, json.decodeFromString<SavedProject>(text).doc.layers.single())
        // the shader reads the ordinal
        assertEquals(5, LayerKind.Background.ordinal)
        assertEquals(LayerKind.Background, l.kind)
        assertTrue(l.edited)
        assertFalse("an image background with no image yet changes nothing", l.copy(effect = BackgroundEffect(BackgroundSource.Image)).edited)
    }
}
