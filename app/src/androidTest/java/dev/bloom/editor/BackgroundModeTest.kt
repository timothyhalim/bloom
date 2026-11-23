package dev.bloom.editor

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.bloom.editor.model.BackgroundEffect
import dev.bloom.editor.model.BackgroundSource
import dev.bloom.editor.model.LayerKind
import dev.bloom.editor.model.Mask
import dev.bloom.editor.model.PersonStep
import dev.bloom.editor.ui.EditorState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The AI tool's Background mode: a Background layer cut along the Person matte, with a colour, blur or picked image. */
@RunWith(AndroidJUnit4::class)
class BackgroundModeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** The editor on [testPhoto] as a project, its person matte (the left half) already found. */
    private fun project(pickers: FakePickers = FakePickers()): Pair<EditorState, Project> {
        val state = rule.showEditor(photo = false, pickers = pickers)
        val file = File(rule.activity.cacheDir, "bg_test.jpg").apply { outputStream().use { testPhoto().compress(Bitmap.CompressFormat.JPEG, 95, it) } }
        val p = Project.create(rule.activity.contentResolver, rule.activity.filesDir, Uri.fromFile(file))
        val original = Uri.fromFile(p.original)
        rule.runOnIdle {
            state.load(original, ImageIo.loadPreview(rule.activity.contentResolver, original), p)
            state.subjects = mapOf(PersonStep.KEY to SubjectMask(64, 64, ByteArray(64 * 64) { if (it % 64 < 32) -1 else 0 }))
        }
        return state to p
    }

    @Test
    fun backgroundModeAddsABackgroundLayerOverThePersonMatte() {
        val (state, _) = project()
        rule.onNodeWithContentDescription("AI").performClick()
        rule.onNodeWithContentDescription("Replace background").performClick()
        rule.onNodeWithContentDescription("Blur background").assertIsSelected()
        rule.onNodeWithContentDescription("Amount").assertExists()
        rule.runOnIdle { assertEquals(Mask(listOf(PersonStep())), state.draft!!.mask) }
        rule.onNodeWithContentDescription("Color background").performClick()
        rule.onNodeWithContentDescription("Color #3A6EA5").performClick()
        rule.onNodeWithContentDescription("More settings").performClick()
        rule.onNodeWithContentDescription("Color match").assertExists()
        rule.onNodeWithContentDescription("Light wrap").assertExists()
        rule.onNodeWithText("Phone", substring = true).assertExists()
        rule.onNodeWithContentDescription("Replace the background").performClick()
        rule.runOnIdle {
            assertEquals(null, state.draft)
            val l = state.doc.layers.single()
            assertEquals(LayerKind.Background, l.kind)
            assertEquals(BackgroundEffect(BackgroundSource.Color, color = 0xFF3A6EA5.toInt()), l.effect)
            assertEquals(Mask(listOf(PersonStep())), l.mask)
            // leaving the mode takes the tool back to a Retouch draft
            state.openAi()
            state.switchAiMode(dev.bloom.editor.ai.Capability.ERASE)
            assertEquals(LayerKind.Retouch, state.draft!!.kind)
        }
    }

    @Test
    fun aPickedImageIsCopiedIntoTheProjectAndReopensWithIt() {
        val img = File(rule.activity.cacheDir, "bg_image.png").apply {
            outputStream().use { Bitmap.createBitmap(IntArray(80 * 60) { Color.rgb(it % 80 * 3, 90, 200) }, 80, 60, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val (state, p) = project(FakePickers().apply { result = Uri.fromFile(img) })
        rule.onNodeWithContentDescription("AI").performClick()
        rule.onNodeWithContentDescription("Replace background").performClick()
        rule.onNodeWithContentDescription("Image background").performClick()
        rule.onNodeWithText("Pick a photo for the background").assertExists()
        rule.onNodeWithContentDescription("Pick a background image").performClick()
        rule.waitUntil(5_000) { (state.draft?.effect as? BackgroundEffect)?.image != null }
        rule.onNodeWithContentDescription("Replace the background").performClick()
        val name = rule.runOnIdle { (state.doc.layers.single().effect as BackgroundEffect).image!!.file }
        assertTrue(name, Regex("image_\\d+\\.jpg").matches(name))
        rule.runOnIdle { p.save(state.doc, state.preview!!, state.subjects, null) }

        val (_, back) = Project.open(p.dir)
        val e = back.layers.single().effect as BackgroundEffect
        assertEquals(BackgroundSource.Image, e.source)
        assertEquals(name, e.image!!.file)
        assertEquals(80, e.image!!.pixels.width)
        // the photo renders with it (export and thumbnail read the same layer)
        assertNotNull(File(p.dir, "thumb.jpg").takeIf { it.exists() })
        // once the layer is gone and the project reopens, its image goes too
        p.save(back.copy(layers = emptyList(), deband = !back.deband), state.preview!!, emptyMap(), null)
        Project.open(p.dir)
        assertTrue(File(p.dir, name).exists().not())
    }
}
