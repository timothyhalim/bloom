package dev.bloom.editor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.bloom.editor.gl.Offscreen
import dev.bloom.editor.gl.HdrGain
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
import dev.bloom.editor.model.SubjectStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt
import org.junit.runner.RunWith
import kotlin.math.abs
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

    // --- Recover (M2.5a) ---

    /** Renders [layers] over [src] and returns the red channel row by row. */
    private fun redsOf(src: Bitmap, layers: List<Layer>, deband: Boolean = true): IntArray = Offscreen().use {
        val p = Pipeline()
        try {
            p.deband = deband
            p.setSource(src)
            val out = p.renderToBitmap(layers)
            IntArray(src.width * src.height).also { out.getPixels(it, 0, src.width, 0, 0, src.width, src.height) }.map { Color.red(it) }.toIntArray()
        } finally {
            p.release()
        }
    }

    /** Column averages: removes dither and deband noise, keeps the horizontal profile. */
    private fun profile(r: IntArray, w: Int, h: Int) = DoubleArray(w) { x -> (0 until h).sumOf { r[it * w + x] }.toDouble() / h }

    private fun gray(w: Int, h: Int, v: (Int, Int) -> Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        setPixels(IntArray(w * h) { val g = v(it % w, it / w); Color.rgb(g, g, g) }, 0, w, 0, 0, w, h)
    }

    private val contrast = Layer(1).with(ColorParam.Contrast, 100f) // doubles every step

    @Test
    fun debandSmoothsEightBitStepsUnderAHardPush() {
        val src = gray(600, 600) { x, _ -> 90 + x / 8 } // one 8-bit level every 8 px
        fun maxJump(deband: Boolean) = profile(redsOf(src, listOf(contrast), deband), 600, 600)
            .toList().subList(20, 580).zipWithNext { a, b -> abs(b - a) }.max()
        val off = maxJump(false)
        val on = maxJump(true)
        assertTrue("banded steps should show without deband: $off", off > 1.5)
        assertTrue("deband should leave no visible steps: $on", on < 0.75)
    }

    @Test
    fun debandKeepsEdgesAndTexture() {
        // a hard edge, and fine texture of +-4 levels on the right
        val rnd = java.util.Random(3)
        val src = gray(600, 600) { x, _ -> if (x < 300) 60 else 128 + if (rnd.nextBoolean()) 4 else -4 }
        val inp = IntArray(600 * 600).also { src.getPixels(it, 0, 600, 0, 0, 600, 600) }.map { Color.red(it) }
        val out = redsOf(src, listOf(Layer(1)))
        assertEquals(60.0, profile(out, 600, 600)[299], 0.3)
        val diff = inp.indices.filter { it % 600 >= 300 }.map { abs(out[it] - inp[it]) }.average()
        assertTrue("texture changed by $diff levels on average", diff < 0.6)
    }

    @Test
    fun sixteenBitSourceKeepsLevelsBetweenEightBitSteps() {
        // 128.25 / 255 in sRGB, stored once sRGB-encoded and once linear (both decode paths exist on real devices).
        val target = 128.25 / 255
        for ((space, value) in listOf(
            android.graphics.ColorSpace.Named.EXTENDED_SRGB to target,
            android.graphics.ColorSpace.Named.LINEAR_EXTENDED_SRGB to srgbToLinear(target),
        )) {
            val src = Bitmap.createBitmap(32, 32, Bitmap.Config.RGBA_F16, false, android.graphics.ColorSpace.get(space))
            val h = android.util.Half.toHalf(value.toFloat())
            val one = android.util.Half.toHalf(1f)
            val buf = java.nio.ByteBuffer.allocateDirect(32 * 32 * 8).order(java.nio.ByteOrder.nativeOrder())
            repeat(32 * 32) { buf.putShort(h).putShort(h).putShort(h).putShort(one) }
            src.copyPixelsFromBuffer(buf.rewind())
            assertEquals("$space", 128.25, redsOf(src, emptyList()).average(), 0.1)
            // eight plain layers: each pass must round its half-float output to nearest, not down
            assertEquals("$space, 8 layers", 128.25, redsOf(src, List(8) { Layer(it.toLong()) }).average(), 0.1)
        }
    }

    // --- 16-bit PNG export (M2.5c) ---

    private val ctx get() = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext

    private fun exportPng(src: java.io.File, layers: List<Layer>): Pair<ByteArray, Bitmap> {
        val dst = java.io.File(ctx.cacheDir, "t_out.png")
        val steps = mutableListOf<Float>()
        ImageIo.export(ctx.contentResolver, android.net.Uri.fromFile(src), android.net.Uri.fromFile(dst), ctx.cacheDir, layers, false, png = true) { steps += it }
        assertTrue("progress rises to 1: $steps", steps.zipWithNext().all { (a, b) -> b >= a } && steps.last() == 1f)
        val bytes = dst.readBytes()
        return bytes to android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    @Test
    fun pngExportKeepsLevelsBetweenEightBitSteps() {
        val src = Bitmap.createBitmap(64, 48, Bitmap.Config.RGBA_F16, false, android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
        // 128.5: halfway between 8-bit steps; each 16-bit PNG decode to half floats rounds down ~0.12 levels
        val h = android.util.Half.toHalf((128.5 / 255).toFloat())
        val one = android.util.Half.toHalf(1f)
        val buf = java.nio.ByteBuffer.allocateDirect(64 * 48 * 8).order(java.nio.ByteOrder.nativeOrder())
        repeat(64 * 48) { buf.putShort(h).putShort(h).putShort(h).putShort(one) }
        src.copyPixelsFromBuffer(buf.rewind())
        val f = java.io.File(ctx.cacheDir, "t_in.png").apply { outputStream().use { src.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        assertEquals("source PNG bit depth", 16, f.readBytes()[24].toInt())
        for (layers in listOf(listOf(Layer(1)), emptyList())) {
            val (bytes, out) = exportPng(f, layers)
            assertEquals("PNG bit depth", 16, bytes[24].toInt())
            val srgb = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
            assertEquals("${layers.size} layers", 128.5, 255.0 * out.getColor(10, 10).convert(srgb).red(), 0.3)
        }
    }

    @Test
    fun pngExportIsUpright() {
        // 8x16 sensor pixels, dark top half, tagged "rotate 90": upright it is 16x8 with the dark half on the right
        val src = gray(8, 16) { _, y -> if (y < 8) 20 else 220 }
        val f = java.io.File(ctx.cacheDir, "t_in.jpg").apply { outputStream().use { src.compress(Bitmap.CompressFormat.JPEG, 100, it) } }
        androidx.exifinterface.media.ExifInterface(f).apply {
            setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val (_, out) = exportPng(f, listOf(Layer(1)))
        assertEquals(16, out.width)
        assertEquals(8, out.height)
        assertTrue("dark half on the right", out.getColor(14, 4).red() < 0.2f && out.getColor(1, 4).red() > 0.7f)
        val dst = java.io.File(ctx.cacheDir, "t_out.png")
        assertEquals(0, androidx.exifinterface.media.ExifInterface(dst).rotationDegrees)
    }

    // --- camera raw (M2.5c) ---

    @Test
    fun rawHighlightsAboveWhiteComeBackWithExposure() {
        // Raw decodes are linear F16 with the brightest 1% above 1.0; one stop down must bring 2.0 back to white, not grey.
        val src = Bitmap.createBitmap(32, 32, Bitmap.Config.RGBA_F16, false, android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.LINEAR_EXTENDED_SRGB))
        val two = android.util.Half.toHalf(2f)
        val one = android.util.Half.toHalf(1f)
        val buf = java.nio.ByteBuffer.allocateDirect(32 * 32 * 8).order(java.nio.ByteOrder.nativeOrder())
        repeat(32 * 32) { buf.putShort(two).putShort(two).putShort(two).putShort(one) }
        src.copyPixelsFromBuffer(buf.rewind())
        assertEquals(255.0, redsOf(src, listOf(Layer(1).with(ColorParam.Exposure, -100f / 3))).average(), 1.0)
        // a Curves layer on top of the raw must not cut that range off
        val curved = Layer(1).copy(curves = Curves(rgb = listOf(0f, 0f, 0.5f, 0.6f, 1f, 1f)))
        assertEquals(255.0, redsOf(src, listOf(curved, Layer(2).with(ColorParam.Exposure, -100f / 3))).average(), 1.0)
    }

    // --- AI subject (M3) ---

    @Test
    fun aPinSelectsOnlyThatObject() {
        val dir = ctx.getExternalFilesDir("models")
        val enc = java.io.File(dir, "mobilesam_encoder_w8.mnn")
        val dec = java.io.File(dir, "mobilesam_decoder_w8.mnn")
        org.junit.Assume.assumeTrue("push models/mobilesam_*.mnn first", enc.exists() && dec.exists())
        // an orange ball (left) and a blue box (right) on a grey wall; one pin on the box
        val photo = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(photo).apply {
            drawColor(Color.rgb(150, 150, 150))
            drawCircle(220f, 300f, 130f, android.graphics.Paint().apply { color = Color.rgb(240, 140, 30); isAntiAlias = true })
            drawRect(480f, 180f, 700f, 420f, android.graphics.Paint().apply { color = Color.rgb(40, 80, 200) })
        }
        val m = Segment.pinned(enc, dec, photo, listOf(590f / 800, 300f / 600, 1f))!!
        val at = { x: Float, y: Float -> m.alpha[(y * m.h).toInt() * m.w + (x * m.w).toInt()].toInt() and 0xFF }
        assertTrue("box ${at(0.74f, 0.5f)}", at(0.74f, 0.5f) > 200)
        assertTrue("ball ${at(0.275f, 0.5f)}", at(0.275f, 0.5f) < 50)
        assertTrue("wall ${at(0.05f, 0.05f)}", at(0.05f, 0.05f) < 50)
    }

    @Test
    fun subjectStepsFollowTheSubjectMask() {
        // subject = left half of the upright image; brighten the subject, then the background
        val mask = SubjectMask(Segment.SIZE, Segment.SIZE, ByteArray(Segment.SIZE * Segment.SIZE) { if (it % Segment.SIZE < Segment.SIZE / 2) -1 else 0 })
        for (background in listOf(false, true)) {
            val layer = Layer(1, mask = Mask(listOf(SubjectStep(background = background)))).with(ColorParam.Exposure, 50f)
            val out = Offscreen().use {
                val p = Pipeline()
                try {
                    p.setSource(gray(64, 32) { _, _ -> 100 })
                    p.setSubjects(mapOf(emptyList<Float>() to mask))
                    val b = p.renderToBitmap(listOf(layer))
                    intArrayOf(Color.red(b.getPixel(8, 16)), Color.red(b.getPixel(56, 16)))
                } finally {
                    p.release()
                }
            }
            val (left, right) = if (background) out[1] to out[0] else out[0] to out[1]
            assertTrue("background=$background: selected side brightened ($left)", left > 140)
            assertEquals("background=$background: other side untouched", 100.0, right.toDouble(), 1.0) // ±1: deband dither
        }
    }

    @Test
    fun subjectMaskFindsTheObject() {
        val model = java.io.File(ctx.getExternalFilesDir("models"), "isnet_w8.mnn")
        org.junit.Assume.assumeTrue("push models/isnet_w8.mnn first", model.exists())
        // an orange ball on a grey-blue wall
        val photo = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(photo).apply {
            drawColor(Color.rgb(120, 130, 150))
            drawCircle(400f, 300f, 150f, android.graphics.Paint().apply { color = Color.rgb(240, 140, 30); isAntiAlias = true })
        }
        val mask = Segment.subject(model, photo)!!
        val at = { x: Int, y: Int -> mask.alpha[y * Segment.SIZE + x].toInt() and 0xFF }
        assertTrue("ball centre ${at(512, 512)}", at(512, 512) > 200)
        assertTrue("wall corner ${at(40, 40)}", at(40, 40) < 50)
    }

    // --- Ultra HDR (M2.5c) ---

    /** A flat gain map: every pixel says "g of the way from ratioMin to ratioMax" (ratios 1 and 4 = up to 2 stops). */
    private fun flatGain(g: Int, single: Boolean = false) = HdrGain(
        if (single) Bitmap.createBitmap(4, 4, Bitmap.Config.ALPHA_8).apply { eraseColor(Color.argb(g, 0, 0, 0)) }
        else Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(g, g, g)) },
        floatArrayOf(1f, 1f, 1f), floatArrayOf(4f, 4f, 4f), floatArrayOf(1f, 1f, 1f), floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f),
    )

    private fun hdrReds(layers: List<Layer>, gain: HdrGain?, level: Int = 250): IntArray = Offscreen().use {
        val p = Pipeline()
        try {
            p.setSource(gray(32, 32) { _, _ -> level })
            p.setGainmap(gain)
            val out = p.renderToBitmap(layers)
            IntArray(32 * 32).also { out.getPixels(it, 0, 32, 0, 0, 32, 32) }.map { Color.red(it) }.toIntArray()
        } finally {
            p.release()
        }
    }

    private val minusTwo = Layer(1).with(ColorParam.Exposure, -200f / 3)

    @Test
    fun ultraHdrBringsBackHighlightsWhenDarkened() {
        // full gain = 4x = 2 stops; two stops down lands back on the original value, where an ordinary photo goes grey
        for (single in listOf(false, true)) {
            assertEquals("single=$single", 250.0, hdrReds(listOf(minusTwo), flatGain(255, single)).average(), 1.5)
        }
        val plain = 255 * linearToSrgb(srgbToLinear(250 / 255.0) / 4)
        assertEquals(plain, hdrReds(listOf(minusTwo), null).average(), 1.5)
        // half the gain map (g = 128/255): 4^0.5 = 2x, so two stops down is one stop below the original
        val half = 255 * linearToSrgb(srgbToLinear(250 / 255.0) * Math.pow(4.0, 128 / 255.0) / 4)
        assertEquals(half, hdrReds(listOf(minusTwo), flatGain(128)).average(), 1.5)
    }

    @Test
    fun ultraHdrOpensLikeTheGalleryPhoto() {
        val g = flatGain(255)
        assertEquals(250.0, hdrReds(emptyList(), g).average(), 0.6)
        assertEquals(250.0, hdrReds(listOf(Layer(1)), g).average(), 0.6)
        // brightening edits use the normal photo too
        val up = Layer(1).with(ColorParam.Exposure, 20f)
        assertEquals(hdrReds(listOf(up), null, 120).average(), hdrReds(listOf(up), g, 120).average(), 0.6)
    }

    @Test
    fun androidKeepsTheGainMapThroughJpegAndRotation() {
        if (android.os.Build.VERSION.SDK_INT < 34) return
        val base = gray(64, 48) { x, _ -> 100 + x }
        val gm = android.graphics.Gainmap(Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) })
        gm.setRatioMax(4f, 4f, 4f)
        gm.setDisplayRatioForFullHdr(4f)
        base.gainmap = gm
        val bytes = java.io.ByteArrayOutputStream().also { base.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
        val back = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val g = ImageIo.gainOf(back)
        assertTrue("decoded Ultra HDR has a gain map", g != null)
        assertEquals(4f, g!!.ratioMax[0], 0.05f)
        val rotated = Bitmap.createBitmap(back, 0, 0, back.width, back.height, Matrix().apply { postRotate(90f) }, true)
        val r = ImageIo.gainOf(rotated)
        assertTrue("rotation keeps the gain map", r != null)
        assertEquals("gain map turns with the photo", r!!.map.width < r.map.height, rotated.width < rotated.height)
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
