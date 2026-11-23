package dev.bloom.editor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.bloom.editor.ai.AiConfig
import dev.bloom.editor.ai.Capability
import dev.bloom.editor.ai.Choice
import dev.bloom.editor.ai.IoPaint
import dev.bloom.editor.ai.Keys
import dev.bloom.editor.ai.OnDevice
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

/** Settings › AI providers and the consent sheet, against a fake IOPaint server (no real one here). */
@RunWith(AndroidJUnit4::class)
class AiSettingsTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var server: FakeServer? = null

    @After
    fun stop() {
        server?.close()
    }

    private fun ioPaint(inpaintDelay: Long = 0) = FakeServer { r ->
        when (r.path) {
            IoPaint.CONFIG -> FakeServer.Reply("""{"plugins":[],"modelInfos":[{"name":"lama","model_type":"inpaint","need_prompt":false}]}""")
            IoPaint.MODEL -> FakeServer.Reply("""{"name":"lama"}""")
            IoPaint.INPAINT -> FakeServer.Reply(200, png(Bitmap.createBitmap(IntArray(16) { Color.GREEN }, 4, 4, Bitmap.Config.ARGB_8888)), "image/png", inpaintDelay)
            else -> FakeServer.Reply("{}", 404)
        }
    }.also { server = it }

    @Test
    fun addAProviderTestItSaveItAndPickIt() {
        val s = ioPaint()
        val state = rule.showEditor(photo = false)
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText("Phone · LaMa").assertIsSelected()
        rule.onNodeWithText("+ Add provider").performScrollTo().performClick()
        rule.onNode(hasSetTextAction() and hasText("Name")).performTextInput("My PC")
        rule.onNode(hasSetTextAction() and hasText("Address")).performTextInput(s.url)
        rule.onNode(hasSetTextAction() and hasText("Key")).performTextInput("proxy-secret")
        rule.onNodeWithText("This address isn't encrypted", substring = true).assertExists()
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onNodeWithText("Test connection").performScrollTo().performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Found: Erase (lama)")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Save").assertIsEnabled().performClick()
        rule.onNodeWithText("My PC · lama").performScrollTo().performClick()
        rule.runOnIdle {
            val p = state.ai.providers.single()
            assertEquals(ProviderConfig(p.id, ProviderType.IOPaint, "My PC", s.url, mapOf(Capability.ERASE to listOf("lama"))), p)
            assertEquals(Choice(p.id, "lama"), state.ai.choices[Capability.ERASE])
            assertEquals("proxy-secret", Keys.get(rule.activity, p.id))
            assertTrue("the key isn't in the config", !rule.activity.getSharedPreferences("ai", 0).all.toString().contains("proxy-secret"))
        }
        // hiding cloud features takes it out of the picker
        rule.onNodeWithText("Hide cloud features").performScrollTo().performClick()
        rule.onNodeWithText("My PC · lama").assertDoesNotExist()
        rule.onNodeWithContentDescription("Close settings").performClick()
        rule.onNodeWithText("Open photo").assertIsDisplayed()
    }

    @Test
    fun theAiBarOpensAiSettings() {
        rule.showEditor()
        rule.onNodeWithContentDescription("AI").performClick()
        rule.onNodeWithContentDescription("Choose where Erase runs").performClick()
        rule.onNodeWithText("AI settings…").performClick()
        rule.onNodeWithText("Settings").assertIsDisplayed()
        rule.onNodeWithText("On-device models").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theRunMenuListsEveryProviderAndModelAndSwitches() {
        val state = rule.showEditor()
        rule.runOnIdle {
            val p = ProviderConfig("pc", ProviderType.IOPaint, "My PC", "http://127.0.0.1:1", mapOf(Capability.ERASE to listOf("lama", "mat")))
            state.ai = AiConfig(listOf(p))
        }
        rule.onNodeWithContentDescription("AI").performClick()
        rule.onNodeWithText("Phone").assertIsDisplayed() // where it runs, under the run icon
        rule.onNodeWithContentDescription("Choose where Erase runs").performClick()
        rule.onNodeWithText("Phone · LaMa").assertIsDisplayed()
        rule.onNodeWithText("My PC · lama").assertIsDisplayed()
        rule.onNodeWithText("My PC · mat").performClick()
        rule.runOnIdle { assertEquals(Choice("pc", "mat"), state.ai.choices[Capability.ERASE]) }
        rule.onNodeWithText("My PC").assertIsDisplayed()
        rule.onNodeWithContentDescription("Choose where Erase runs").performClick()
        rule.onNode(hasText("My PC · mat") and hasContentDescription("Current")).assertIsDisplayed()
    }

    /** The editor with a remote IOPaint chosen for Erase and a painted mask in the AI tool. */
    private fun remoteErase(s: FakeServer, consented: Boolean = false) = rule.showEditor().also { state ->
        rule.runOnIdle {
            val p = ProviderConfig("pc", ProviderType.IOPaint, "My PC", s.url, mapOf(Capability.ERASE to listOf("lama")))
            state.ai = AiConfig(listOf(p), mapOf(Capability.ERASE to Choice("pc", "lama")), if (consented) setOf("pc") else emptySet())
        }
        rule.onNodeWithContentDescription("AI").performClick()
        rule.runOnIdle { state.setMask(Mask(listOf(BrushStep(listOf(Stroke(listOf(0.5f, 0.5f), 0.1f, 0f, 1f)))))) }
        rule.onNodeWithContentDescription("Erase the painted area").performClick()
    }

    @Test
    fun theFirstSendAsksAndSendOnlyWhenAgreed() {
        val s = ioPaint()
        val state = remoteErase(s)
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Send to My PC?")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("127.0.0.1", substring = true).assertIsDisplayed()
        rule.onNodeWithText("px crop of the photo", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Use a different provider").performClick()
        rule.onNodeWithText("Phone · LaMa").assertIsDisplayed()
        rule.onNodeWithText("Not now").performClick()
        rule.waitForIdle()
        assertTrue("nothing sent", s.requests.isEmpty())
        rule.onNodeWithContentDescription("Erase the painted area").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Send to My PC?")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Don't ask again for My PC").performClick()
        rule.onNodeWithText("Send").performClick()
        rule.waitUntil(10_000) { state.doc.layers.any { it.kind == LayerKind.Retouch } }
        rule.runOnIdle {
            assertEquals(setOf("pc"), state.ai.consented)
            assertEquals(null, state.draft)
            assertTrue(s.requests.any { it.path == IoPaint.INPAINT })
        }
        // reset in Settings asks again
        rule.runOnIdle { state.ai = state.ai.copy(consented = emptySet()) }
        rule.runOnIdle { assertTrue(state.ai.needsConsent("pc") && !state.ai.needsConsent(OnDevice.ID)) }
    }

    @Test
    fun aRemoteEraseShowsItsStatusAndCancels() {
        val s = ioPaint(inpaintDelay = 30_000)
        val state = remoteErase(s, consented = true)
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Running on the server", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Erase the painted area").assertDoesNotExist() // locked while it runs
        rule.onNodeWithText("Cancel").performClick()
        rule.waitUntil(5_000) { state.running == null }
        rule.runOnIdle {
            assertTrue(state.doc.layers.none { it.kind == LayerKind.Retouch })
            assertEquals(LayerKind.Retouch, state.draft?.kind) // still in the tool, mask kept
        }
    }

    @Test
    fun aProviderErrorIsShown() {
        val s = FakeServer { r ->
            if (r.path == IoPaint.MODEL) FakeServer.Reply("""{"name":"lama"}""") else FakeServer.Reply("""{"detail":"CUDA out of memory"}""", 500)
        }.also { server = it }
        remoteErase(s, consented = true)
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Couldn't erase: HTTP 500: CUDA out of memory")).fetchSemanticsNodes().isNotEmpty() }
    }
}
