package dev.bloom.editor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.bloom.editor.ai.AiConfig
import dev.bloom.editor.ai.Capability
import dev.bloom.editor.ai.Choice
import dev.bloom.editor.ai.IoPaint
import dev.bloom.editor.ai.ProviderConfig
import dev.bloom.editor.ai.ProviderType
import dev.bloom.editor.ai.png
import dev.bloom.editor.model.BrushStep
import dev.bloom.editor.model.LayerKind
import dev.bloom.editor.model.Mask
import dev.bloom.editor.model.Stroke
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The AI tool's modes against fake servers: which show, Fill, Upscale, Ask and Refine. */
@RunWith(AndroidJUnit4::class)
class AiModesTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var server: FakeServer? = null

    @After
    fun stop() {
        server?.close()
    }

    private fun gone(text: String) = rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty()

    /** A fake IOPaint that fills with green and records what it got. */
    private fun ioPaint() = FakeServer { r ->
        when (r.path) {
            IoPaint.MODEL -> FakeServer.Reply("""{"name":"sd"}""")
            IoPaint.INPAINT -> FakeServer.Reply(200, png(Bitmap.createBitmap(IntArray(16) { Color.GREEN }, 4, 4, Bitmap.Config.ARGB_8888)), "image/png")
            else -> FakeServer.Reply("{}", 404)
        }
    }.also { server = it }

    private fun pc(url: String, models: Map<Capability, List<String>>) = ProviderConfig("pc", ProviderType.IOPaint, "My PC", url, models)

    @Test
    fun theModeBarShowsOnlyModesAProviderCanRun() {
        val state = rule.showEditor()
        rule.onNodeWithContentDescription("AI").performClick()
        rule.onNodeWithContentDescription("Erase").assertExists()
        rule.onNodeWithContentDescription("Fill").assertDoesNotExist()
        rule.runOnIdle { state.ai = AiConfig(listOf(pc("http://127.0.0.1:1", mapOf(Capability.GEN_FILL to listOf("sd"))))) }
        rule.onNodeWithContentDescription("Fill").performClick()
        rule.onNodeWithContentDescription("What to paint there").assertExists()
        // hidden cloud features take the mode away; the tool falls back to Erase
        rule.runOnIdle { state.ai = state.ai.copy(hideCloud = true) }
        rule.onNodeWithContentDescription("Fill").assertDoesNotExist()
        rule.onNodeWithContentDescription("Erase Size").assertExists()
    }

    @Test
    fun fillAsksFirstThenSendsThePromptAndAddsARetouchLayer() {
        val s = ioPaint()
        val state = rule.showEditor()
        rule.runOnIdle { state.ai = AiConfig(listOf(pc(s.url, mapOf(Capability.GEN_FILL to listOf("sd"))))) }
        rule.onNodeWithContentDescription("AI").performClick()
        rule.onNodeWithContentDescription("Fill").performClick()
        rule.runOnIdle { state.setMask(Mask(listOf(BrushStep(listOf(Stroke(listOf(0.5f, 0.5f), 0.1f, 0f, 1f)))))) }
        rule.onNodeWithContentDescription("Fill the painted area").assertIsNotEnabled() // no prompt yet
        rule.onNodeWithContentDescription("What to paint there").performTextInput("a red balloon")
        rule.onNodeWithContentDescription("Fill the painted area").assertIsEnabled().performClick()
        rule.waitUntil(5_000) { !gone("Send to My PC?") }
        rule.onNodeWithText("with your prompt", substring = true).assertExists()
        rule.onNodeWithText("Send").performClick()
        rule.waitUntil(10_000) { state.doc.layers.any { it.kind == LayerKind.Retouch } }
        rule.runOnIdle {
            assertEquals(null, state.draft)
            assertTrue(s.requests.single { it.path == IoPaint.INPAINT }.text.contains("\"prompt\":\"a red balloon\""))
        }
    }

    @Test
    fun upscaleReplacesThePhotoWithABiggerOneAndUndoBringsItBack() {
        val s = FakeServer { r ->
            if (r.path == IoPaint.PLUGIN) FakeServer.Reply(200, png(Bitmap.createBitmap(IntArray(600 * 800) { Color.BLUE }, 600, 800, Bitmap.Config.ARGB_8888)), "image/png")
            else FakeServer.Reply("{}", 404)
        }.also { server = it }
        val state = rule.showEditor(photo = false)
        val file = java.io.File(rule.activity.cacheDir, "upscale_test.jpg").apply { outputStream().use { testPhoto().compress(Bitmap.CompressFormat.JPEG, 95, it) } }
        val p = Project.create(rule.activity.contentResolver, rule.activity.filesDir, android.net.Uri.fromFile(file))
        val original = android.net.Uri.fromFile(p.original)
        rule.runOnIdle {
            state.load(original, ImageIo.loadPreview(rule.activity.contentResolver, original), p)
            state.ai = AiConfig(listOf(pc(s.url, mapOf(Capability.UPSCALE to listOf("RealESRGAN")))), consented = setOf("pc"))
        }
        rule.onNodeWithContentDescription("AI").performClick()
        rule.onNodeWithContentDescription("Upscale").performClick()
        rule.onNodeWithText("300 × 400 → 600 × 800 px").assertExists()
        rule.onNodeWithContentDescription("4 times").performClick()
        rule.onNodeWithText("300 × 400 → 1200 × 1600 px").assertExists()
        rule.onNodeWithContentDescription("2 times").performClick()
        rule.onNodeWithContentDescription("Upscale the photo").performClick()
        rule.waitUntil(10_000) { state.doc.source.isNotEmpty() }
        rule.waitUntil(5_000) { state.preview?.width == 600 }
        rule.runOnIdle {
            assertEquals(null, state.draft)
            assertEquals(600 to 800, state.photoSize)
            assertTrue(state.source.toString().contains("upscaled_"))
            val body = s.requests.single { it.path == IoPaint.PLUGIN }.text
            assertTrue(body.contains("\"name\":\"RealESRGAN\"") && body.contains("\"scale\":2.0"))
            state.history = state.history.undo()
        }
        rule.waitUntil(5_000) { state.preview?.width == 300 }
        rule.runOnIdle {
            assertEquals(original, state.source)
            assertEquals("", state.doc.source)
            state.history = state.history.redo()
        }
        rule.waitUntil(5_000) { state.preview?.width == 600 }
        // the saved project opens on the upscaled photo
        rule.runOnIdle { p.save(state.doc, state.preview!!, emptyMap(), null) }
        val (_, saved) = Project.open(p.dir)
        assertEquals(600 to 800, ImageIo.size(rule.activity.contentResolver, android.net.Uri.fromFile(p.photo(saved))))
    }

    @Test
    fun aDifferentProviderFromTheConsentSheetBecomesTheModesChoice() {
        val s = ioPaint()
        val state = rule.showEditor()
        rule.runOnIdle {
            state.ai = AiConfig(listOf(pc(s.url, mapOf(Capability.GEN_FILL to listOf("sd", "sdxl")))), mapOf(Capability.GEN_FILL to Choice("pc", "sd")))
        }
        rule.onNodeWithContentDescription("AI").performClick()
        rule.onNodeWithContentDescription("Fill").performClick()
        rule.runOnIdle { state.setMask(Mask(listOf(BrushStep(listOf(Stroke(listOf(0.5f, 0.5f), 0.1f, 0f, 1f)))))) }
        rule.onNodeWithContentDescription("What to paint there").performTextInput("grass")
        rule.onNodeWithContentDescription("Fill the painted area").performClick()
        rule.waitUntil(5_000) { !gone("Send to My PC?") }
        rule.onNodeWithText("Use a different provider").performClick()
        rule.onNodeWithText("My PC · sdxl").performClick()
        rule.runOnIdle { assertEquals(Choice("pc", "sdxl"), state.ai.choices[Capability.GEN_FILL]) }
        rule.onNodeWithText("Not now").performClick()
    }
}
