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
import dev.bloom.editor.model.Curves
import dev.bloom.editor.model.ColorStep
import dev.bloom.editor.model.LuminanceStep
import dev.bloom.editor.model.GradientStep
import dev.bloom.editor.model.Layer
import dev.bloom.editor.model.Mask
import dev.bloom.editor.model.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt
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

    @Test
    fun curvesMatchTheReferenceTableWithinOneLevel() = Offscreen().use {
        // M1.5 exit test: an S-curve on RGB plus a red curve, over every 8-bit input level
        val c = Curves(rgb = listOf(0f, 0f, 0.25f, 0.15f, 0.75f, 0.85f, 1f, 1f), r = listOf(0f, 0.1f, 0.5f, 0.6f, 1f, 0.95f))
        val src = Bitmap.createBitmap(256, 1, Bitmap.Config.ARGB_8888).apply { for (i in 0..255) setPixel(i, 0, Color.rgb(i, i, i)) }
        val p = Pipeline()
        try {
            p.setSource(src)
            // a plain layer on top checks the table is per layer
            val out = p.renderToBitmap(listOf(Layer(1).copy(curves = c), Layer(2)))
            val lut = c.lut(4096)
            val bad = (0..255).flatMap { i ->
                val px = out.getPixel(i, 0)
                val ref = (i / 255f * 4095).roundToInt() * 4
                listOf(Color.red(px) to lut[ref], Color.green(px) to lut[ref + 1]).filter { (o, e) -> kotlin.math.abs(o - (255 * e).roundToInt()) > 1 } // dither moves output by up to one level
                    .map { (o, e) -> "$i:$o/${"%.2f".format(255 * e)}" }
            }
            assertTrue(bad.joinToString(), bad.isEmpty())
        } finally {
            p.release()
        }
    }

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

    // --- color / luminance ranges, feather, eyedropper (M2b) ---

    /** Renders [layers] over [src] and returns every pixel (ARGB), row by row. */
    private fun pixels(src: Bitmap, layers: List<Layer>, setup: (Pipeline, Bitmap) -> Unit = { p, b -> p.setSource(b) }): IntArray =
        Offscreen().use {
            val p = Pipeline()
            try {
                setup(p, src)
                val out = p.renderToBitmap(layers)
                IntArray(out.width * out.height).also { out.getPixels(it, 0, out.width, 0, 0, out.width, out.height) }
            } finally {
                p.release()
            }
        }

    @Test
    fun luminanceStepSelectsOnlyItsRange() {
        val ramp = Bitmap.createBitmap(256, 1, Bitmap.Config.ARGB_8888).apply { for (x in 0 until 256) setPixel(x, 0, Color.rgb(x, x, x)) }
        val out = pixels(ramp, listOf(plusOne.copy(mask = Mask(listOf(LuminanceStep(0.4f, 0.4f, 0.6f, 0.6f))))))
        for (x in 0 until 256) {
            val inside = x / 255.0 in 0.41..0.59
            val outside = x / 255.0 !in 0.39..0.61
            if (inside) assertTrue("x=$x should change", Color.red(out[x]) > x + 5)
            if (outside) assertEquals("x=$x should not change", x.toDouble(), Color.red(out[x]).toDouble(), 1.0)
        }
    }

    @Test
    fun colorStepSelectsTheSampledHueOnly() {
        val red = Color.rgb(200, 40, 40)
        val blue = Color.rgb(40, 40, 200)
        val src = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { for (y in 0 until 8) for (x in 0 until 8) setPixel(x, y, if (x < 4) red else blue) }
        val step = ColorStep(listOf(200 / 255f, 40 / 255f, 40 / 255f))
        val out = pixels(src, listOf(plusOne.copy(mask = Mask(listOf(step)))))
        assertTrue("red changed", Color.green(out[0]) > 40 + 10)
        assertEquals("blue unchanged", 40.0, Color.green(out[7]).toDouble(), 1.0)
    }

    @Test
    fun featherSoftensAnEdgeEvenly() {
        // 200 px tall: feather 1 = 6 px radius. Hard dab edge 50 px right of centre.
        val gray = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(128, 128, 128)) }
        val out = pixels(gray, listOf(plusOne.copy(mask = Mask(listOf(dab(0.5f, 0.5f, 0.25f).copy(feather = 1f))))))
        fun f(x: Int) = (Color.red(out[100 * 200 + x]) - 128) / (plusOneValue - 128)
        assertEquals("on the edge", 0.5, f(150), 0.12)
        assertTrue("well inside: ${f(140)}", f(140) > 0.9)
        assertTrue("well outside: ${f(160)}", f(160) < 0.1)
        assertTrue("softened: ${f(147)}", f(147) in 0.55..0.95)
    }

    @Test
    fun paddedTileMatchesFullRenderWithFeather() {
        val src = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until 200) for (x in 0 until 200) setPixel(x, y, Color.rgb(x, y, 128))
        }
        val layers = listOf(plusOne.copy(mask = Mask(listOf(dab(0.45f, 0.5f, 0.2f).copy(feather = 1f), LuminanceStep.Midtones.copy(add = false, feather = 0.5f)))))
        val full = pixels(src, layers)
        // tile covering x, y in 40..140 plus 8 px of overlap, as export does
        val tile = Bitmap.createBitmap(src, 32, 32, 116, 116)
        val part = pixels(tile, layers) { p, b -> p.setSource(b, 32, 32, 200, 200) }
        for (y in 40 until 140) for (x in 40 until 140) {
            val a = full[y * 200 + x]
            val b = part[(y - 32) * 116 + (x - 32)]
            for (sh in listOf(16, 8, 0)) assertNear("($x,$y)", (a shr sh) and 0xFF, (b shr sh) and 0xFF, 2)
        }
    }

    @Test
    fun eyedropperReadsTheLayersBelow() = Offscreen().use {
        val p = Pipeline()
        try {
            p.setSource(Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(128, 128, 128)) })
            val c = p.sample(listOf(plusOne), 0.5f, 0.5f)
            c.forEach { assertEquals(plusOneValue / 255, it.toDouble(), 2.0 / 255) }
        } finally {
            p.release()
        }
    }

    @Test
    fun luminanceFollowsEarlierLayers() {
        // gray 128 (luma 0.5) is outside 0.6..0.8 until the layer below brightens it to ~0.69
        val b = Layer(2).with(ColorParam.Exposure, 100f / 3).copy(mask = Mask(listOf(LuminanceStep(0.6f, 0.6f, 0.8f, 0.8f))))
        assertEquals(128, reds(listOf(b), 8, 8)[0])
        val twice = 255 * linearToSrgb(4 * srgbToLinear(128 / 255.0))
        assertEquals(twice, reds(listOf(plusOne, b), 8, 8)[0].toDouble(), 2.0)
    }
}
