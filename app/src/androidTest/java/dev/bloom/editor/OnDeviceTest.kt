package dev.bloom.editor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** The phone's own M8.5 models: Real-ESRGAN upscale and the RVM person matte. Push the model files first (see models.json). */
@RunWith(AndroidJUnit4::class)
class OnDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun model(name: String) = File(rule.activity.getExternalFilesDir("models"), name).also { assumeTrue("push models/$name first", it.exists()) }

    /** Smooth stripes and a soft disc: something with edges for the model to keep. */
    private fun pattern(w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        val c = android.graphics.Canvas(this)
        c.drawColor(Color.rgb(90, 140, 200))
        val p = android.graphics.Paint().apply { isAntiAlias = true }
        for (i in 0 until w step 40) { p.color = Color.rgb(230, 200, 60); c.drawRect(i.toFloat(), 0f, i + 12f, h.toFloat(), p) }
        p.color = Color.rgb(200, 40, 60)
        c.drawCircle(w * 0.6f, h * 0.5f, h * 0.3f, p)
    }

    @Test
    fun upscaleMakesThePhotoTwiceAndFourTimesBiggerWithoutSeams() {
        val m = model("realesr_x4v3_f16.mnn")
        // 2 × 2 tiles (seams at x 134..166, y 124..156); its top-left 256 px fit one tile, the seamless reference
        val src = pattern(300, 280)
        val one = Bitmap.createBitmap(src, 0, 0, 256, 256)
        for (by in listOf(2, 4)) {
            val tiles = mutableListOf<Int>()
            val t = System.nanoTime()
            val out = Segment.upscale(m, src, by) { i, n -> tiles += i; assertEquals(4, n) }!!
            android.util.Log.d("Bloom", "test upscale ×$by: ${(System.nanoTime() - t) / 1_000_000} ms")
            assertEquals(listOf(1, 2, 3, 4), tiles)
            assertEquals(300 * by, out.width)
            assertEquals(280 * by, out.height)
            assertEquals(255, Color.alpha(out.getPixel(out.width - 1, out.height - 1)))
            val ref = Segment.upscale(m, one, by) { _, n -> assertEquals(1, n) }!!
            // away from the reference's own border (the model sees ~33 px around), tiled and whole agree
            var worst = 0
            var sum = 0L
            val n = 216 * by
            for (y in 0 until n) for (x in 0 until n) {
                val a = out.getPixel(x, y)
                val b = ref.getPixel(x, y)
                val d = maxOf(abs(Color.red(a) - Color.red(b)), abs(Color.green(a) - Color.green(b)), abs(Color.blue(a) - Color.blue(b)))
                worst = maxOf(worst, d)
                sum += d
            }
            assertTrue("×$by worst $worst", worst <= 12)
            assertTrue("×$by mean ${sum.toDouble() / (n * n)}", sum.toDouble() / (n * n) < 0.5)
            // shrunk back, it is the photo
            val back = Bitmap.createScaledBitmap(out, 300, 280, true)
            var diff = 0L
            for (y in 0 until 280) for (x in 0 until 300) {
                val a = src.getPixel(x, y)
                val b = back.getPixel(x, y)
                diff += abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))
            }
            assertTrue("×$by mean diff to the photo", diff / (300 * 280 * 3.0) < 4)
            out.recycle()
            ref.recycle()
        }
    }

    @Test
    fun upscaleStopsWhenToldTo() {
        val m = model("realesr_x4v3_f16.mnn")
        val r = runCatching { Segment.upscale(m, pattern(600, 300), 2) { i, _ -> if (i == 2) throw java.util.concurrent.CancellationException() } }
        assertTrue(r.exceptionOrNull() is java.util.concurrent.CancellationException)
    }

    @Test
    fun onDeviceUpscaleReplacesThePhotoAndUndoBringsItBack() {
        model("realesr_x4v3_f16.mnn")
        val state = rule.showEditor(photo = false)
        val file = File(rule.activity.cacheDir, "upscale_phone.jpg").apply { outputStream().use { testPhoto().compress(Bitmap.CompressFormat.JPEG, 95, it) } }
        val p = Project.create(rule.activity.contentResolver, rule.activity.filesDir, android.net.Uri.fromFile(file))
        val original = android.net.Uri.fromFile(p.original)
        rule.runOnIdle { state.load(original, ImageIo.loadPreview(rule.activity.contentResolver, original), p) }
        rule.onNodeWithContentDescription("AI").performClick()
        rule.onNodeWithContentDescription("Upscale").performClick()
        rule.onNodeWithText("300 × 400 → 600 × 800 px").assertExists()
        rule.onNodeWithContentDescription("Upscale the photo").performClick()
        rule.waitUntil(5_000) { state.running?.startsWith("Tile") == true }
        rule.onNodeWithText("Cancel").assertExists()
        rule.waitUntil(120_000) { state.doc.source.isNotEmpty() }
        rule.waitUntil(5_000) { state.preview?.width == 600 }
        rule.runOnIdle {
            assertEquals(600 to 800, state.photoSize)
            state.history = state.history.undo()
        }
        rule.waitUntil(5_000) { state.preview?.width == 300 }
        rule.runOnIdle { assertEquals(original, state.source) }
    }

    @Test
    fun personMatteCoversAPersonShapeAtTheModelsSize() {
        val m = model("rvm_mobilenetv3_f16.mnn")
        // a head and shoulders in front of a plain wall, 1600 × 1200
        val photo = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(photo).apply {
            drawColor(Color.rgb(170, 190, 200))
            val p = android.graphics.Paint().apply { isAntiAlias = true }
            p.color = Color.rgb(60, 70, 110)
            drawOval(500f, 750f, 1100f, 1500f, p) // shoulders
            p.color = Color.rgb(225, 180, 150)
            drawRect(730f, 600f, 870f, 800f, p) // neck
            drawOval(650f, 280f, 950f, 680f, p) // face
            p.color = Color.rgb(60, 40, 30)
            drawArc(640f, 250f, 960f, 600f, 180f, 180f, true, p) // hair
        }
        val t = System.nanoTime()
        val matte = Segment.person(m, photo)!!
        android.util.Log.d("Bloom", "test matte: ${(System.nanoTime() - t) / 1_000_000} ms")
        assertEquals(1024, matte.w)
        assertEquals(768, matte.h)
        val at = { x: Float, y: Float -> matte.alpha[(y * 768).toInt() * 1024 + (x * 1024).toInt()].toInt() and 0xFF }
        android.util.Log.d("Bloom", "test matte: face ${at(0.5f, 0.4f)} body ${at(0.5f, 0.9f)} corner ${at(0.05f, 0.05f)}")
        assertTrue("wall corner ${at(0.05f, 0.05f)}", at(0.05f, 0.05f) < 50)
        assertTrue("body ${at(0.5f, 0.9f)} face ${at(0.5f, 0.4f)}", maxOf(at(0.5f, 0.9f), at(0.5f, 0.4f)) > 128)
    }
}
