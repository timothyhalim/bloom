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
import dev.bloom.editor.model.ColorEffect
import dev.bloom.editor.model.ColorParam
import dev.bloom.editor.model.BlurType
import dev.bloom.editor.model.Crop
import dev.bloom.editor.model.Patch
import dev.bloom.editor.model.RetouchEffect
import dev.bloom.editor.model.Curves
import dev.bloom.editor.model.WarpEffect
import dev.bloom.editor.model.WarpField
import dev.bloom.editor.model.WarpStroke
import dev.bloom.editor.model.WarpTool
import dev.bloom.editor.model.Detail
import dev.bloom.editor.model.DetailEffect
import dev.bloom.editor.model.ColorStep
import dev.bloom.editor.model.LuminanceStep
import dev.bloom.editor.model.GradientStep
import dev.bloom.editor.model.Layer
import dev.bloom.editor.model.Mask
import dev.bloom.editor.model.Stroke
import dev.bloom.editor.model.SubjectStep
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow

@RunWith(AndroidJUnit4::class)
class PipelineTest {
    private fun srgbToLinear(c: Double) = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun linearToSrgb(c: Double) = if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1 / 2.4) - 0.055

    /** Renders [layers] over [src] and returns every pixel (ARGB), row by row. [setup] loads the source (and more). */
    private fun pixels(src: Bitmap, layers: List<Layer>, deband: Boolean = true, setup: Pipeline.(Bitmap) -> Unit = { setSource(it) }): IntArray =
        Offscreen().use {
            val p = Pipeline()
            p.deband = deband
            p.setup(src)
            val out = p.renderToBitmap(layers)
            IntArray(out.width * out.height).also { out.getPixels(it, 0, out.width, 0, 0, out.width, out.height) }
        }

    /** Renders [layers] over [src] and returns the red channel row by row. */
    private fun redsOf(src: Bitmap, layers: List<Layer>, deband: Boolean = true, setup: Pipeline.(Bitmap) -> Unit = { setSource(it) }) =
        pixels(src, layers, deband, setup).map(Color::red).toIntArray()

    private fun run(layers: List<Layer>): Int = Color.red(pixels(gray(8, 8) { _, _ -> 128 }, layers)[5 * 8 + 3])

    @Test
    fun noLayersIsIdentity() = assertEquals(128, run(emptyList()))

    @Test
    fun zeroLayerIsIdentity() = assertEquals(128.0, run(listOf(Layer(1))).toDouble(), 1.0)

    @Test
    fun exposurePlusOneStopDoublesLinearLight() {
        // Exposure spans +-3 stops over +-100, so 100/3 is +1 stop.
        val expected = 255 * linearToSrgb(2 * srgbToLinear(128 / 255.0))
        assertEquals(expected, run(listOf(Layer(1, effect = ColorEffect().with(ColorParam.Exposure, 100f / 3)))).toDouble(), 2.0)
    }

    @Test
    fun halfOpacityIsHalfway() {
        val full = run(listOf(Layer(1, effect = ColorEffect().with(ColorParam.Exposure, 100f / 3))))
        val half = run(listOf(Layer(1, effect = ColorEffect().with(ColorParam.Exposure, 100f / 3)).copy(opacity = 0.5f)))
        assertEquals((128 + full) / 2.0, half.toDouble(), 2.0)
    }

    @Test
    fun ditherKeepsFractionalLevelsOnAverage() {
        val reds = redsOf(gray(8, 8) { _, _ -> 128 }, listOf(Layer(1, effect = ColorEffect().with(ColorParam.Exposure, 100f / 3))))
        val expected = 255 * linearToSrgb(2 * srgbToLinear(128 / 255.0)) // ~177.3, between two 8-bit levels
        assertEquals(expected, reds.average(), 0.3)
        assertEquals(2, reds.distinct().size)
    }

    @Test
    fun hiddenLayerIsSkipped() = assertEquals(128, run(listOf(Layer(1, effect = ColorEffect().with(ColorParam.Exposure, 100f)).copy(visible = false))))

    @Test
    fun curvesMatchTheReferenceTableWithinOneLevel() {
        // M1.5 exit test: an S-curve on RGB plus a red curve, over every 8-bit input level
        val c = Curves(rgb = listOf(0f, 0f, 0.25f, 0.15f, 0.75f, 0.85f, 1f, 1f), r = listOf(0f, 0.1f, 0.5f, 0.6f, 1f, 0.95f))
        // a plain layer on top checks the table is per layer
        val out = pixels(gray(256, 1) { x, _ -> x }, listOf(Layer(1, effect = ColorEffect(curves = c)), Layer(2)))
        val lut = c.lut(4096)
        val bad = (0..255).flatMap { i ->
            val px = out[i]
            val ref = (i / 255f * 4095).roundToInt() * 4
            listOf(Color.red(px) to lut[ref], Color.green(px) to lut[ref + 1]).filter { (o, e) -> abs(o - (255 * e).roundToInt()) > 1 } // dither moves output by up to one level
                .map { (o, e) -> "$i:$o/${"%.2f".format(255 * e)}" }
        }
        assertTrue(bad.joinToString(), bad.isEmpty())
    }

    // --- Recover (M2.5a) ---

    /** Column averages: removes dither and deband noise, keeps the horizontal profile. */
    private fun profile(r: IntArray, w: Int, h: Int) = DoubleArray(w) { x -> (0 until h).sumOf { r[it * w + x] }.toDouble() / h }

    private fun gray(w: Int, h: Int, v: (Int, Int) -> Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        setPixels(IntArray(w * h) { val g = v(it % w, it / w); Color.rgb(g, g, g) }, 0, w, 0, 0, w, h)
    }

    private val contrast = Layer(1, effect = ColorEffect().with(ColorParam.Contrast, 100f)) // doubles every step

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
            val src = f16(32, 32, space, value.toFloat())
            assertEquals("$space", 128.25, redsOf(src, emptyList()).average(), 0.1)
            // eight plain layers: each pass must round its half-float output to nearest, not down
            assertEquals("$space, 8 layers", 128.25, redsOf(src, List(8) { Layer(it.toLong()) }).average(), 0.1)
        }
    }

    /** A w×h half-float photo in [space], every pixel (v, v, v). */
    private fun f16(w: Int, h: Int, space: android.graphics.ColorSpace.Named, v: Float) =
        Bitmap.createBitmap(w, h, Bitmap.Config.RGBA_F16, false, android.graphics.ColorSpace.get(space)).apply {
            val hv = android.util.Half.toHalf(v)
            val one = android.util.Half.toHalf(1f)
            val buf = java.nio.ByteBuffer.allocateDirect(w * h * 8).order(java.nio.ByteOrder.nativeOrder())
            repeat(w * h) { buf.putShort(hv).putShort(hv).putShort(hv).putShort(one) }
            copyPixelsFromBuffer(buf.rewind())
        }

    // --- 16-bit PNG export (M2.5c) ---

    private val ctx get() = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext

    private fun exportPng(src: java.io.File, layers: List<Layer>): Pair<ByteArray, Bitmap> {
        val dst = java.io.File(ctx.cacheDir, "t_out.png")
        val steps = mutableListOf<Float>()
        ImageIo.export(ctx.contentResolver, android.net.Uri.fromFile(src), android.net.Uri.fromFile(dst), ctx.cacheDir, layers, false, ImageIo.Format.PNG) { steps += it }
        assertTrue("progress rises to 1: $steps", steps.zipWithNext().all { (a, b) -> b >= a } && steps.last() == 1f)
        val bytes = dst.readBytes()
        return bytes to android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    @Test
    fun pngExportKeepsLevelsBetweenEightBitSteps() {
        // 128.5: halfway between 8-bit steps; each 16-bit PNG decode to half floats rounds down ~0.12 levels
        val src = f16(64, 48, android.graphics.ColorSpace.Named.SRGB, (128.5 / 255).toFloat())
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

    // --- WebP, HEIC, AVIF export ---

    /** 128x256 sensor pixels, red top half, blue bottom, tagged "rotate 90": upright it is 256x128, blue on the left. */
    private fun exportAs(format: ImageIo.Format): Bitmap {
        org.junit.Assume.assumeTrue("no ${format.name} encoder", format.available)
        val src = Bitmap.createBitmap(128, 256, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(200, 40, 40))
            android.graphics.Canvas(this).drawRect(0f, 128f, 128f, 256f, android.graphics.Paint().apply { color = Color.rgb(40, 40, 200) })
        }
        val f = java.io.File(ctx.cacheDir, "t_fmt.jpg").apply { outputStream().use { src.compress(Bitmap.CompressFormat.JPEG, 100, it) } }
        androidx.exifinterface.media.ExifInterface(f).apply {
            setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(androidx.exifinterface.media.ExifInterface.TAG_MAKE, "BloomCam")
            saveAttributes()
        }
        val dst = java.io.File(ctx.cacheDir, "t_fmt_out.${format.ext}")
        ImageIo.export(ctx.contentResolver, android.net.Uri.fromFile(f), android.net.Uri.fromFile(dst), ctx.cacheDir, listOf(Layer(1)), false, format)
        val out = android.graphics.BitmapFactory.decodeFile(dst.path) ?: error("${format.name} output doesn't decode")
        assertEquals(256, out.width)
        assertEquals(128, out.height)
        val blue = out.getPixel(40, 64)
        val red = out.getPixel(216, 64)
        assertTrue("blue on the left: ${Integer.toHexString(blue)}", Color.blue(blue) > 160 && Color.red(blue) < 80)
        assertTrue("red on the right: ${Integer.toHexString(red)}", Color.red(red) > 160 && Color.blue(red) < 80)
        return out
    }

    @Test
    fun webpExport() {
        exportAs(ImageIo.Format.WEBP)
        val dst = java.io.File(ctx.cacheDir, "t_fmt_out.webp")
        assertEquals("RIFF", String(dst.readBytes(), 0, 4))
        val exif = androidx.exifinterface.media.ExifInterface(dst)
        assertEquals("EXIF kept", "BloomCam", exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_MAKE))
        assertEquals("upright", 0, exif.rotationDegrees)
    }

    @Test
    fun heicExport() { exportAs(ImageIo.Format.HEIC) }

    @Test
    fun avifExport() { exportAs(ImageIo.Format.AVIF) }

    // --- Crop (M4.5) ---

    /** Exports [src] (saved as a lossless PNG) as a 16-bit PNG with [crop] and [scale]; returns its pixels, 8-bit. */
    private fun exportCropped(src: Bitmap, crop: Crop, scale: Float = 1f): Bitmap {
        val f = java.io.File(ctx.cacheDir, "t_crop.png").apply { outputStream().use { src.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val dst = java.io.File(ctx.cacheDir, "t_crop_out.png")
        ImageIo.export(ctx.contentResolver, android.net.Uri.fromFile(f), android.net.Uri.fromFile(dst), ctx.cacheDir, emptyList(), false, ImageIo.Format.PNG, crop = crop, scale = scale)
        val out = android.graphics.BitmapFactory.decodeFile(dst.path)
        return out.copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun pattern(w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        setPixels(IntArray(w * h) { val x = it % w; val y = it / w; Color.rgb(x * 255 / w, (x xor y) and 0xFF, y and 0xFF) }, 0, w, 0, 0, w, h)
    }

    private fun smooth(w: Int, h: Int) = gray(w, h) { x, y -> (128 + 100 * Math.sin(x / 37.0) * Math.cos(y / 23.0)).toInt() }

    /** Red of [px] (w × h) sampled bilinearly at (sx, sy) in pixels, pixel centres on integers, edges clamped. */
    private fun bilinearRed(px: IntArray, w: Int, h: Int, sx: Double, sy: Double): Double {
        val at = { x: Int, y: Int -> Color.red(px[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]).toDouble() }
        val x0 = floor(sx).toInt()
        val y0 = floor(sy).toInt()
        val fx = sx - x0
        val fy = sy - y0
        return (at(x0, y0) * (1 - fx) + at(x0 + 1, y0) * fx) * (1 - fy) + (at(x0, y0 + 1) * (1 - fx) + at(x0 + 1, y0 + 1) * fx) * fy
    }

    @Test
    fun cropExportIsThatRegionPixelForPixel() {
        // wider than two tiles, so a tile edge falls inside the crop
        val w = 2500
        val h = 1300
        val src = pattern(w, h)
        val out = exportCropped(src, Crop(left = 0.2f, top = 0.1f, right = 0.9f, bottom = 0.8f))
        assertEquals(1750, out.width)
        assertEquals(910, out.height)
        for (y in 0 until out.height step 7) for (x in 0 until out.width step 3) assertRgbNear("($x,$y)", src.getPixel(500 + x, 130 + y), out.getPixel(x, y), 1)
    }

    @Test
    fun quarterTurnExportIsTheRotatedPhotoAndFourTurnsAreTheOriginal() {
        val w = 300
        val h = 200
        val src = pattern(w, h)
        val out = exportCropped(src, Crop().turned())
        assertEquals(h, out.width)
        assertEquals(w, out.height)
        // clockwise: output (x, y) shows photo (y, h - 1 - x)
        for (y in 0 until w step 5) for (x in 0 until h step 5) assertNear("($x,$y)", src.getPixel(y, h - 1 - x) and 0xFF, out.getPixel(x, y) and 0xFF, 1)
        // round trip: turned back to the start, the export is the whole photo at full size
        val back = exportCropped(src, Crop().turned().turned().turned().turned())
        assertEquals(w, back.width)
        assertEquals(h, back.height)
        assertNear("pixel", src.getPixel(123, 45), back.getPixel(123, 45), 0)
    }

    @Test
    fun straightenedExportMatchesBilinearSamplingAcrossTiles() {
        val w = 2200
        val h = 1400
        val src = smooth(w, h)
        val aspect = w.toFloat() / h
        val crop = Crop(angle = 7f, vertical = 20f).fit(aspect)
        val out = exportCropped(src, crop)
        val m = crop.matrix(aspect)
        val px = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
        var worst = 0.0
        for (y in 0 until out.height step 5) for (x in 0 until out.width step 5) {
            val (u, v) = Crop.map(m, (x + 0.5f) / out.width, (y + 0.5f) / out.height)!!
            val e = bilinearRed(px, w, h, u * w - 0.5, v * h - 0.5)
            worst = maxOf(worst, abs(e - Color.red(out.getPixel(x, y))))
        }
        assertTrue("worst difference $worst levels", worst <= 3.0)
    }

    @Test
    fun halfSizeExportAveragesPixels() {
        val w = 2000
        val h = 1000
        val src = smooth(w, h)
        val out = exportCropped(src, Crop(), scale = 0.5f)
        assertEquals(1000, out.width)
        assertEquals(500, out.height)
        for (y in 0 until 500 step 9) for (x in 0 until 1000 step 7) {
            val e = listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1).sumOf { (dx, dy) -> Color.red(src.getPixel(2 * x + dx, 2 * y + dy)) } / 4.0
            assertEquals("($x,$y)", e, Color.red(out.getPixel(x, y)).toDouble(), 2.0)
        }
    }

    // --- Liquify (M5) ---

    private fun liquify(vararg strokes: WarpStroke) = Layer(1, effect = WarpEffect(strokes.toList()))

    private fun push(aspect: Float, vararg xy: Float): WarpStroke {
        var st = WarpStroke(WarpTool.Push, listOf(xy[0], xy[1]), 0.15f, 1f)
        for (k in 1 until xy.size / 2) st = st.lineTo(xy[2 * k], xy[2 * k + 1], aspect)
        return st
    }

    @Test
    fun liquifyWithoutOffsetsAndAfterFullRestoreKeepsExactPixels() {
        val w = 300
        val h = 200
        val src = pattern(w, h)
        val inp = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
        val sweep = mutableListOf<Float>()
        for (y in 0..20) for (x in 0..20) { sweep += x / 20f; sweep += y / 20f }
        val restore = WarpStroke(WarpTool.Restore, sweep, 0.2f, 1f)
        val pushed = push(1.5f, 0.3f, 0.5f, 0.6f, 0.4f)
        // no offsets: the input (within the 8-bit readback's dither)
        val none = pixels(src, listOf(liquify()), deband = false)
        for (i in inp.indices step 7) assertRgbNear("$i", inp[i], none[i], 1)
        // pushed, then fully restored: exactly the same pixels as no offsets
        val restored = pixels(src, listOf(liquify(pushed, restore, restore, restore)), deband = false)
        assertArrayEquals(none, restored)
        // and the push alone does move pixels
        val moved = pixels(src, listOf(liquify(pushed)), deband = false)
        assertTrue(!moved.contentEquals(none))
    }

    @Test
    fun liquifyReadsWhereTheGridPoints() {
        val w = 400
        val h = 300
        val aspect = w.toFloat() / h
        val src = smooth(w, h)
        val st = push(aspect, 0.3f, 0.5f, 0.55f, 0.5f)
        val out = pixels(src, listOf(liquify(st)), deband = false)
        val f = WarpField(aspect).apply { update(listOf(st)) }
        val px = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
        fun bilinear(g: FloatArray, gw: Int, gh: Int, x: Double, y: Double, c: Int): Double {
            val gx = (x - 0.5).coerceIn(0.0, gw - 1.0)
            val gy = (y - 0.5).coerceIn(0.0, gh - 1.0)
            val x0 = minOf(gx.toInt(), gw - 2)
            val y0 = minOf(gy.toInt(), gh - 2)
            val fx = gx - x0
            val fy = gy - y0
            val v = { i: Int, j: Int -> g[((y0 + j) * gw + x0 + i) * 2 + c].toDouble() }
            return (v(0, 0) * (1 - fx) + v(1, 0) * fx) * (1 - fy) + (v(0, 1) * (1 - fx) + v(1, 1) * fx) * fy
        }
        var worst = 0.0
        for (y in 0 until h step 3) for (x in 0 until w step 3) {
            val u = (x + 0.5) / w
            val v = (y + 0.5) / h
            val ox = bilinear(f.d, f.w, f.h, u * f.w, v * f.h, 0)
            val oy = bilinear(f.d, f.w, f.h, u * f.w, v * f.h, 1)
            val e = bilinearRed(px, w, h, (u + ox / aspect) * w - 0.5, (v + oy) * h - 0.5)
            worst = maxOf(worst, abs(e - Color.red(out[y * w + x])))
        }
        // half-float offsets (~1e-3 relative) on a smooth photo
        assertTrue("worst difference $worst levels", worst <= 3.0)
    }

    @Test
    fun liquifiedExportTileMatchesTheFullRender() {
        val n = 400
        val src = pattern(n, n)
        val st = push(1f, 0.25f, 0.5f, 0.45f, 0.5f)
        // a tile right of the push, padded by the largest offset as ImageIo does
        val pad = ((WarpField(1f).apply { update(listOf(st)) }.maxOffset() + 0.002f) * n).toInt() + 2
        assertTileMatchesFull(src, listOf(liquify(st)), 192, 128, 128, pad, 1)
    }

    /**
     * Renders [layers] over all of [src] and over a [size] px tile of it at (x0, y0), padded by [pad] as export does;
     * the tile's pixels match within [tol]. [extra] sets more inputs after the source.
     */
    private fun assertTileMatchesFull(
        src: Bitmap, layers: List<Layer>, x0: Int, y0: Int, size: Int, pad: Int, tol: Int,
        deband: Boolean = false, label: String = "", extra: Pipeline.() -> Unit = {},
    ) {
        val full = pixels(src, layers, deband) { setSource(it); this.extra() }
        val tile = Bitmap.createBitmap(src, x0 - pad, y0 - pad, size + 2 * pad, size + 2 * pad)
        val part = pixels(tile, layers, deband) { setSource(it, x0 - pad, y0 - pad, src.width, src.height); this.extra() }
        val tw = size + 2 * pad
        for (y in y0 until y0 + size) for (x in x0 until x0 + size) {
            assertRgbNear("$label($x,$y)", full[y * src.width + x], part[(y - y0 + pad) * tw + (x - x0 + pad)], tol)
        }
    }

    // --- camera raw (M2.5c) ---

    @Test
    fun rawHighlightsAboveWhiteComeBackWithExposure() {
        // Raw decodes are linear F16 with the brightest 1% above 1.0; one stop down must bring 2.0 back to white, not grey.
        val src = f16(32, 32, android.graphics.ColorSpace.Named.LINEAR_EXTENDED_SRGB, 2f)
        assertEquals(255.0, redsOf(src, listOf(Layer(1, effect = ColorEffect().with(ColorParam.Exposure, -100f / 3)))).average(), 1.0)
        // a Curves layer on top of the raw must not cut that range off
        val curved = Layer(1, effect = ColorEffect(curves = Curves(rgb = listOf(0f, 0f, 0.5f, 0.6f, 1f, 1f))))
        assertEquals(255.0, redsOf(src, listOf(curved, Layer(2, effect = ColorEffect().with(ColorParam.Exposure, -100f / 3)))).average(), 1.0)
    }

    // --- Detail: Blur and Sharpen (M4) ---

    private fun blur(amount: Float) = Layer(1, effect = DetailEffect(detail = Detail(amount = amount)))

    /** An n×n subject or depth mask: full on the left half, none on the right. */
    private fun leftHalf(n: Int) = SubjectMask(n, n, ByteArray(n * n) { if (it % n < n / 2) -1 else 0 })

    /** The step edge (lo | hi at x = edge) blurred by a Gaussian of [sigma] px, sampled at pixel centre x. */
    private fun bell(x: Int, edge: Int, lo: Int, hi: Int, sigma: Double): Double {
        var sum = 0.0
        var wsum = 0.0
        for (k in -200..200) {
            val w = Math.exp(-0.5 * (k / sigma) * (k / sigma))
            sum += w * (if (x + k < edge) lo else hi)
            wsum += w
        }
        return sum / wsum
    }

    @Test
    fun gaussianBlurFollowsTheBellCurve() {
        // amount 100 = sigma 2% of the height: 4 px on a 200 px photo (full-res taps), 12 px on 600 (reads mipmaps)
        for (h in listOf(200, 600)) {
            val sigma = 0.02 * h
            val w = h
            val out = profile(redsOf(gray(w, h) { x, _ -> if (x < w / 2) 40 else 220 }, listOf(blur(100f)), deband = false), w, h)
            for (x in w / 2 - (3 * sigma).toInt() until w / 2 + (3 * sigma).toInt()) {
                assertEquals("h=$h x=$x", bell(x, w / 2, 40, 220, sigma), out[x], 5.0)
            }
        }
    }

    @Test
    fun maskedBlurDoesntSmearTheSubjectIntoTheBackground() {
        // red subject on the left half, blue background on the right; blur only the background
        val w = 300
        val h = 400
        val src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(IntArray(w * h) { if (it % w < w / 2) Color.rgb(220, 30, 30) else Color.rgb(30, 30, 220) }, 0, w, 0, 0, w, h)
        }
        val bg = blur(100f).copy(mask = Mask(listOf(SubjectStep(background = true))))
        val out = pixels(src, listOf(bg), deband = false) { setSource(it); setSubjects(mapOf(emptyList<Float>() to leftHalf(Segment.SIZE))) }
        val reds = (w / 2 + 1 until w / 2 + 12).map { x -> Color.red(out[h / 2 * w + x]) }
        assertTrue("background next to the subject stays blue: $reds", reds.all { it < 45 })
        // the subject itself is untouched
        assertEquals(220.0, Color.red(out[h / 2 * w + w / 2 - 3]).toDouble(), 2.0)
    }

    @Test
    fun sharpenRaisesEdgeContrastAndLeavesFlatAreas() {
        val w = 200
        val sharpen = Layer(1, effect = DetailEffect(sharpen = true, detail = Detail(amount = 100f, radius = 100f)))
        val out = profile(redsOf(gray(w, 200) { x, _ -> if (x < w / 2) 100 else 150 }, listOf(sharpen), deband = false), w, 200)
        assertTrue("dark side darker at the edge: ${out[w / 2 - 1]}", out[w / 2 - 1] < 95)
        assertTrue("bright side brighter at the edge: ${out[w / 2]}", out[w / 2] > 155)
        assertEquals(100.0, out[10], 1.0)
        assertEquals(150.0, out[w - 10], 1.0)
    }

    @Test
    fun lensBlurTurnsAHighlightIntoADisc() {
        // a small bright spot on black, no depth map: every pixel blurs by the full radius (4% of 400 = 16 px)
        val n = 400
        val src = gray(n, n) { x, y -> if (hypot((x - n / 2).toDouble(), (y - n / 2).toDouble()) <= 3.0) 255 else 0 }
        val lens = Layer(1, effect = DetailEffect(detail = Detail(BlurType.Lens, amount = 100f)))
        val out = pixels(src, listOf(lens), deband = false)
        val lit = { x: Int, y: Int -> Color.red(out[(n / 2 + y) * n + n / 2 + x]) > 3 }
        assertTrue("lit inside the disc", lit(0, 8) && lit(8, 0) && lit(-8, 0) && lit(0, -8))
        assertTrue("dark outside it", !lit(0, 26) && !lit(26, 0))
        val alongX = (0 until 40).last { lit(it, 0) }
        val alongY = (0 until 40).last { lit(0, it) }
        assertNear("round: same reach across ($alongX) and up ($alongY)", alongX, alongY, 1)
    }

    @Test
    fun lensBlurKeepsTheFocusedNearHalfSharp() {
        // left half near (depth 255) with fine stripes, right half far (depth 0); focus on the left
        val w = 300
        val h = 300
        val src = gray(w, h) { x, y -> if (x < w / 2) (if (y % 4 < 2) 40 else 200) else (if (x % 6 < 3) 60 else 220) }
        val lens = Layer(1, effect = DetailEffect(detail = Detail(BlurType.Lens, amount = 100f, bloom = 0f, focusX = 0.25f, focusY = 0.5f, range = 0f)))
        val out = pixels(src, listOf(lens), deband = false) { setSource(it); setDepth(leftHalf(64)) }
        val inp = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
        // the near half (up to a few px from the edge, where the depth map is smoothed) is untouched
        for (y in 100 until 200) for (x in 20 until w / 2 - 8) assertNear("($x,$y)", Color.red(inp[y * w + x]), Color.red(out[y * w + x]), 1)
        // the far half's stripes are blurred away
        val far = (w / 2 + 30 until w - 30).map { Color.red(out[150 * w + it]) }
        assertTrue("far half blurred: ${far.minOrNull()}..${far.maxOrNull()}", far.max() - far.min() < 40)
    }

    @Test
    fun depthRangeStepSelectsTheNearHalf() {
        // depth map: left half near (255), right half far (0); a Near step brightens only the left
        val layer = Layer(1, effect = ColorEffect().with(ColorParam.Exposure, 50f), mask = Mask(listOf(LuminanceStep.Near)))
        val out = pixels(gray(64, 32) { _, _ -> 100 }, listOf(layer), deband = false) { setSource(it); setDepth(leftHalf(64)) }
        assertTrue("near half brightened (${Color.red(out[16 * 64 + 8])})", Color.red(out[16 * 64 + 8]) > 140)
        assertEquals("far half untouched", 100.0, Color.red(out[16 * 64 + 56]).toDouble(), 1.0)
    }

    @Test
    fun motionBlurStreaksAlongItsAngleAndZoomKeepsItsCentre() {
        // a bright dot on black: Motion at 100 draws a 40 px streak (10% of 400) along the angle
        val n = 400
        val src = gray(n, n) { x, y -> if (x in 199..200 && y in 199..200) 255 else 0 }
        for (angle in listOf(0f, 90f)) {
            val out = pixels(src, listOf(Layer(1, effect = DetailEffect(detail = Detail(BlurType.Motion, amount = 100f, angle = angle)))), deband = false)
            val lit = { dx: Int, dy: Int -> Color.red(out[(200 + dy) * n + 200 + dx]) > 0 }
            val along = if (angle == 0f) lit(15, 0) && lit(-15, 0) else lit(0, 15) && lit(0, -15)
            val across = if (angle == 0f) lit(0, 8) || lit(0, -8) else lit(8, 0) || lit(-8, 0)
            assertTrue("angle $angle: streak along", along)
            assertTrue("angle $angle: nothing across", !across)
            assertTrue("angle $angle: ends at half the streak", !lit(if (angle == 0f) 26 else 0, if (angle == 0f) 0 else 26))
        }
        // Zoom: stripes everywhere; the centre (on pixel 200's middle) keeps its pixel, far away the stripes smear
        val stripes = gray(n, n) { x, _ -> if (x % 8 < 4) 40 else 220 }
        val z = pixels(stripes, listOf(Layer(1, effect = DetailEffect(detail = Detail(BlurType.Zoom, amount = 100f, focusX = 200.5f / n, focusY = 200.5f / n)))), deband = false)
        val sp = IntArray(n * n).also { stripes.getPixels(it, 0, n, 0, 0, n, n) }
        assertNear("centre", Color.red(sp[200 * n + 200]), Color.red(z[200 * n + 200]), 1)
        val edge = (20 until 60).map { Color.red(z[200 * n + it]) }
        assertTrue("edge smeared: ${edge.min()}..${edge.max()}", edge.max() - edge.min() < 60)
    }

    @Test
    fun blurredExportTileMatchesTheFullRender() {
        val n = 400
        val src = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until n) for (x in 0 until n) setPixel(x, y, Color.rgb(x * 255 / n, (x xor y) and 0xFF, 128))
        }
        for ((layers, pad) in listOf(
            listOf(blur(100f)) to 64,
            // lens with depth: radius 16 px, reach 1.3 x 16 + 2 rounded up to 64, as ImageIo pads
            listOf(Layer(1, effect = DetailEffect(detail = Detail(BlurType.Lens, amount = 100f, range = 0f)))) to 64,
            // motion: half a 40 px streak plus a mip texel; zoom at 30: 0.18 of the height (72 px), both rounded up to 64
            listOf(Layer(1, effect = DetailEffect(detail = Detail(BlurType.Motion, amount = 100f, angle = 30f)))) to 64,
            listOf(Layer(1, effect = DetailEffect(detail = Detail(BlurType.Zoom, amount = 30f, focusX = 0.2f, focusY = 0.3f)))) to 128,
        )) {
            val depth = SubjectMask(64, 64, ByteArray(64 * 64) { ((it % 64) * 4).toByte() })
            assertTileMatchesFull(src, layers, 128, 128, 128, pad, 2, label = "${(layers[0].effect as DetailEffect).detail.type} ") { setDepth(depth) }
        }
    }

    // --- AI eraser (M6) ---

    private fun redPatch(w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(220, 20, 20)) }

    @Test
    fun retouchShowsItsPatchOnlyInsideItsRectAndMask() {
        val n = 400
        val src = gray(n, n) { _, _ -> 100 }
        val patch = Patch(0.25f, 0.25f, 0.75f, 0.75f, redPatch(50, 50))
        // no mask: the whole rectangle
        val plain = pixels(src, listOf(Layer(1, effect = RetouchEffect(patch))), deband = false)
        assertNear("inside", 220, Color.red(plain[200 * n + 200]), 2)
        assertNear("outside", 100, Color.red(plain[50 * n + 50]), 1)
        // painted mask: only under the stroke
        val stroke = Stroke(listOf(0.5f, 0.5f), 0.08f, 0f, 1f)
        val masked = Layer(1, effect = RetouchEffect(patch), mask = Mask(listOf(BrushStep(listOf(stroke)))))
        val out = pixels(src, listOf(masked), deband = false)
        assertNear("under the stroke", 220, Color.red(out[200 * n + 200]), 2)
        assertNear("in the rectangle, off the stroke", 100, Color.red(out[120 * n + 120]), 1)
    }

    @Test
    fun retouchedExportTileMatchesTheFullRender() {
        val n = 400
        val src = pattern(n, n)
        val layers = listOf(Layer(1, effect = RetouchEffect(Patch(0.3f, 0.3f, 0.6f, 0.6f, pattern(37, 37))),
            mask = Mask(listOf(BrushStep(listOf(Stroke(listOf(0.45f, 0.45f), 0.1f, 0.5f, 1f)))))))
        assertTileMatchesFull(src, layers, 128, 128, 128, 16, 2)
    }

    @Test
    fun eraserContinuesStripesAcrossTheHole() {
        val model = java.io.File(ctx.getExternalFilesDir("models"), "lama_f16.mnn")
        org.junit.Assume.assumeTrue("push lama_f16.mnn (models/lama_f16.mnn.part1 + part2 joined) first", model.exists())
        // vertical stripes 16 px wide, with a solid block in the middle to remove
        val w = 640
        val h = 480
        val photo = gray(w, h) { x, y -> if (x in 280 until 360 && y in 200 until 280) 30 else if (x / 16 % 2 == 0) 60 else 200 }
        val hole = ByteArray(w * h) { i -> if (i % w in 276 until 364 && i / w in 196 until 284) 1 else 0 }
        val t = System.nanoTime()
        val (box, px) = Segment.erase(model, photo, hole) ?: error("erase failed")
        android.util.Log.d("Bloom", "eraser test: ${(System.nanoTime() - t) / 1_000_000} ms")
        assertTrue("box covers the hole: ${box.toList()}", box[0] <= 276 && box[1] <= 196 && box[2] >= 364 && box[3] >= 284)
        // inside the old block, columns that were dark stripes stay darker than the light ones
        val col = { x: Int -> (210 until 270).map { y -> Color.red(px.getPixel(x - box[0], y - box[1])) }.average() }
        val dark = (290 until 350).filter { it / 16 % 2 == 0 }.map(col).average()
        val light = (290 until 350).filter { it / 16 % 2 == 1 }.map(col).average()
        assertTrue("stripes continue: dark $dark, light $light", light - dark > 40)
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
        val mask = leftHalf(Segment.SIZE)
        for (background in listOf(false, true)) {
            val layer = Layer(1, effect = ColorEffect().with(ColorParam.Exposure, 50f), mask = Mask(listOf(SubjectStep(background = background))))
            val out = redsOf(gray(64, 32) { _, _ -> 100 }, listOf(layer)) { setSource(it); setSubjects(mapOf(emptyList<Float>() to mask)) }
            val a = out[16 * 64 + 8]
            val b = out[16 * 64 + 56]
            val (left, right) = if (background) b to a else a to b
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

    private fun hdrReds(layers: List<Layer>, gain: HdrGain?, level: Int = 250) =
        redsOf(gray(32, 32) { _, _ -> level }, layers) { setSource(it); setGainmap(gain) }

    private val minusTwo = Layer(1, effect = ColorEffect().with(ColorParam.Exposure, -200f / 3))

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
        val up = Layer(1, effect = ColorEffect().with(ColorParam.Exposure, 20f))
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

    private val plusOne = Layer(1, effect = ColorEffect().with(ColorParam.Exposure, 100f / 3))
    private val plusOneValue = 255 * linearToSrgb(2 * srgbToLinear(128 / 255.0))
    private fun dab(x: Float, y: Float, r: Float, add: Boolean = true) =
        BrushStep(listOf(Stroke(listOf(x, y), r, softness = 0f, flow = 1f)), add)

    /** Renders onto a gray w×h image and returns the red channel of every pixel, row by row. */
    private fun reds(layers: List<Layer>, w: Int = 16, h: Int = 16, setup: Pipeline.(Bitmap) -> Unit = { setSource(it) }) =
        redsOf(gray(w, h) { _, _ -> 128 }, layers, setup = setup)

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
        val tiles = (0..1).map { ty -> reds(layers, 8, 8) { setSource(it, 0, ty * 8, 8, 16, orient, 2f) } }
        for (sy in 0 until 16) for (sx in 0 until 8) {
            // rotating 90° clockwise moves sensor pixel (sx, sy) to upright (15 - sy, sx)
            val sensor = tiles[sy / 8][(sy % 8) * 8 + sx]
            assertNear("sensor $sx,$sy", upright[sx * 16 + (15 - sy)], sensor, 2)
        }
    }

    private fun assertNear(msg: String, expected: Int, actual: Int, tol: Int) =
        assertTrue("$msg: expected $expected, got $actual", abs(expected - actual) <= tol)

    /** Each of R, G and B of ARGB [actual] is within [tol] of [expected]'s. */
    private fun assertRgbNear(msg: String, expected: Int, actual: Int, tol: Int) {
        for (sh in listOf(16, 8, 0)) assertNear(msg, (expected shr sh) and 0xFF, (actual shr sh) and 0xFF, tol)
    }

    // --- color / luminance ranges, feather, eyedropper (M2b) ---

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
        val out = pixels(gray(200, 200) { _, _ -> 128 }, listOf(plusOne.copy(mask = Mask(listOf(dab(0.5f, 0.5f, 0.25f).copy(feather = 1f))))))
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
        // tile covering x, y in 40..140 plus 8 px of overlap, as export does
        assertTileMatchesFull(src, layers, 40, 40, 100, 8, 2, deband = true)
    }

    @Test
    fun eyedropperReadsTheLayersBelow() = Offscreen().use {
        val p = Pipeline()
        p.setSource(gray(16, 16) { _, _ -> 128 })
        p.sample(listOf(plusOne), 0.5f, 0.5f).forEach { assertEquals(plusOneValue / 255, it.toDouble(), 2.0 / 255) }
    }

    @Test
    fun luminanceFollowsEarlierLayers() {
        // gray 128 (luma 0.5) is outside 0.6..0.8 until the layer below brightens it to ~0.69
        val b = Layer(2, effect = ColorEffect().with(ColorParam.Exposure, 100f / 3)).copy(mask = Mask(listOf(LuminanceStep(0.6f, 0.6f, 0.8f, 0.8f))))
        assertEquals(128, reds(listOf(b), 8, 8)[0])
        val twice = 255 * linearToSrgb(4 * srgbToLinear(128 / 255.0))
        assertEquals(twice, reds(listOf(plusOne, b), 8, 8)[0].toDouble(), 2.0)
    }
}
