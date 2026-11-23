package dev.bloom.editor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.bloom.editor.gl.Offscreen
import dev.bloom.editor.gl.Pipeline
import android.graphics.Matrix
import dev.bloom.editor.model.Blend
import dev.bloom.editor.model.BrushStep
import dev.bloom.editor.model.ColorParam
import dev.bloom.editor.model.GradientStep
import dev.bloom.editor.model.Layer
import dev.bloom.editor.model.Mask
import dev.bloom.editor.model.Stroke
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.pow

@RunWith(AndroidJUnit4::class)
class PipelineTest {
    private fun srgbToLinear(c: Double) = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun linearToSrgb(c: Double) = if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1 / 2.4) - 0.055

    private fun run(layers: List<Layer>, gray: Int = 128): Int = Offscreen().use {
        val p = Pipeline()
        try {
            p.setSource(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(gray, gray, gray)) })
            Color.red(p.renderToBitmap(layers).getPixel(3, 5))
        } finally {
            p.release()
        }
    }

    @Test
    fun noLayersIsIdentity() = assertEquals(128, run(emptyList()))

    @Test
    fun zeroLayerIsIdentity() = assertEquals(128.0, run(listOf(Layer(1))).toDouble(), 1.0)

    @Test
    fun exposurePlusOneStopDoublesLinearLight() {
        // Exposure spans +-3 stops over +-100, so 100/3 is +1 stop.
        val expected = 255 * linearToSrgb(2 * srgbToLinear(128 / 255.0))
        assertEquals(expected, run(listOf(Layer(1).with(ColorParam.Exposure, 100f / 3))).toDouble(), 2.0)
    }

    @Test
    fun halfOpacityIsHalfway() {
        val full = run(listOf(Layer(1).with(ColorParam.Exposure, 100f / 3)))
        val half = run(listOf(Layer(1).with(ColorParam.Exposure, 100f / 3).copy(opacity = 0.5f)))
        assertEquals((128 + full) / 2.0, half.toDouble(), 2.0)
    }

    @Test
    fun ditherKeepsFractionalLevelsOnAverage() = Offscreen().use {
        val p = Pipeline()
        try {
            p.setSource(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(128, 128, 128)) })
            val out = p.renderToBitmap(listOf(Layer(1).with(ColorParam.Exposure, 100f / 3)))
            val reds = IntArray(64).also { out.getPixels(it, 0, 8, 0, 0, 8, 8) }.map { Color.red(it) }
            val expected = 255 * linearToSrgb(2 * srgbToLinear(128 / 255.0)) // ~177.3, between two 8-bit levels
            assertEquals(expected, reds.average(), 0.3)
            assertEquals(2, reds.distinct().size)
        } finally {
            p.release()
        }
    }

    @Test
    fun hiddenLayerIsSkipped() = assertEquals(128, run(listOf(Layer(1).with(ColorParam.Exposure, 100f).copy(visible = false))))

    // --- masks and blend modes (M2) ---

    private val plusOne = Layer(1).with(ColorParam.Exposure, 100f / 3)
    private val plusOneValue = 255 * linearToSrgb(2 * srgbToLinear(128 / 255.0))
    private fun dab(x: Float, y: Float, r: Float, add: Boolean = true) =
        BrushStep(listOf(Stroke(listOf(x, y), r, softness = 0f, flow = 1f)), add)

    /** Renders onto a gray w×h image and returns the red channel of every pixel, row by row. */
    private fun reds(layers: List<Layer>, w: Int = 16, h: Int = 16, setup: (Pipeline, Bitmap) -> Unit = { p, b -> p.setSource(b) }): IntArray =
        Offscreen().use {
            val p = Pipeline()
            try {
                setup(p, Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(128, 128, 128)) })
                val out = p.renderToBitmap(layers)
                IntArray(w * h).also { out.getPixels(it, 0, w, 0, 0, w, h) }.map { Color.red(it) }.toIntArray()
            } finally {
                p.release()
            }
        }

    @Test
    fun maskWithoutVisibleStepsCoversTheWholeLayer() {
        val r = reds(listOf(plusOne.copy(mask = Mask(listOf(dab(0.5f, 0.5f, 0.1f).copy(visible = false)), invert = true))))
        r.forEach { assertEquals(plusOneValue, it.toDouble(), 2.0) }
    }

    @Test
    fun hardDabIsFullInsideAndNoneOutside() {
        val r = reds(listOf(plusOne.copy(mask = Mask(listOf(dab(0.5f, 0.5f, 0.25f))))))
        assertEquals(plusOneValue, r[8 * 16 + 8].toDouble(), 2.0) // centre
        assertEquals(128, r[0]) // corner
    }

    @Test
    fun removeStepCutsOutAndInvertFlips() {
        val steps = listOf(dab(0.5f, 0.5f, 2f), dab(0.5f, 0.5f, 0.25f, add = false)) // everything, minus the centre
        val r = reds(listOf(plusOne.copy(mask = Mask(steps))))
        assertEquals(128, r[8 * 16 + 8])
        assertEquals(plusOneValue, r[0].toDouble(), 2.0)
        val inv = reds(listOf(plusOne.copy(mask = Mask(steps, invert = true))))
        assertEquals(plusOneValue, inv[8 * 16 + 8].toDouble(), 2.0)
        assertEquals(128, inv[0])
    }

    @Test
    fun linearGradientFollowsSmoothstep() {
        val r = reds(listOf(plusOne.copy(mask = Mask(listOf(GradientStep(false, 0f, 0.5f, 1f, 0.5f))))))
        for (x in 0 until 16) {
            val t = (x + 0.5) / 16
            val s = 1 - t * t * (3 - 2 * t)
            assertEquals("x=$x", 128 + (plusOneValue - 128) * s, r[3 * 16 + x].toDouble(), 2.0)
        }
    }

    @Test
    fun blendModesMatchTheirFormulas() {
        val b = 128 / 255.0
        val s = plusOneValue / 255.0
        val expected = mapOf(
            Blend.Multiply to b * s,
            Blend.Screen to 1 - (1 - b) * (1 - s),
            Blend.Overlay to 1 - 2 * (1 - b) * (1 - s), // base is just above 0.5
            Blend.SoftLight to (1 - 2 * s) * b * b + 2 * s * b,
        )
        expected.forEach { (mode, v) -> assertEquals(mode.name, 255 * v, reds(listOf(plusOne.copy(blend = mode)), 8, 8)[0].toDouble(), 2.0) }
    }

    @Test
    fun rotatedExportTilesMatchTheUprightPreview() {
        // Upright 16×8 preview with an off-centre dab, versus the same photo stored as 8×16 sensor pixels (EXIF 90°), exported as two 8×8 tiles.
        val mask = Mask(listOf(dab(0.8f, 0.3f, 0.2f), GradientStep(true, 0.2f, 0.6f, 0.4f, 0.6f)))
        val layers = listOf(plusOne.copy(mask = mask))
        val upright = reds(layers, 16, 8)
        val orient = Matrix().apply { postRotate(90f, 0.5f, 0.5f) }
        val tiles = (0..1).map { ty -> reds(layers, 8, 8) { p, b -> p.setSource(b, 0, ty * 8, 8, 16, orient, 2f) } }
        for (sy in 0 until 16) for (sx in 0 until 8) {
            // rotating 90° clockwise moves sensor pixel (sx, sy) to upright (15 - sy, sx)
            val sensor = tiles[sy / 8][(sy % 8) * 8 + sx]
            assertNear("sensor $sx,$sy", upright[sx * 16 + (15 - sy)], sensor, 2)
        }
    }

    private fun assertNear(msg: String, expected: Int, actual: Int, tol: Int) =
        org.junit.Assert.assertTrue("$msg: expected $expected, got $actual", kotlin.math.abs(expected - actual) <= tol)
}
