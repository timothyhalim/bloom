package dev.bloom.editor

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.bloom.editor.model.ColorEffect
import dev.bloom.editor.model.ColorParam
import dev.bloom.editor.model.Document
import dev.bloom.editor.model.Layer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProjectsTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val ctx get() = rule.activity
    private val projects get() = File(ctx.filesDir, "projects")
    private val edited = Document(listOf(Layer(1, effect = ColorEffect().with(ColorParam.Exposure, 30f))))

    /** [testPhoto] as a JPEG file named [name], to open. */
    private fun photoFile(name: String) = Uri.fromFile(File(ctx.cacheDir, name).apply { outputStream().use { testPhoto().compress(Bitmap.CompressFormat.JPEG, 90, it) } })

    /** A project for [name] saved with [doc], as an earlier visit leaves it. */
    private fun saved(name: String, doc: Document = edited): File {
        Thread.sleep(2) // project folders are named by the millisecond
        val p = Project.create(ctx.contentResolver, ctx.filesDir, photoFile(name))
        p.save(doc, testPhoto(), emptyMap(), null)
        return p.dir
    }

    private fun waitForPhoto(s: dev.bloom.editor.ui.EditorState) = rule.waitUntil(10_000) { s.source != null }

    @Test
    fun homeListsSavedProjectsAndATapOpensOne() {
        var dir: File? = null
        val s = rule.showEditor(photo = false) { saved("lake.jpg"); dir = saved("beach.jpg") }
        rule.onNodeWithContentDescription("lake.jpg").assertIsDisplayed()
        rule.onNodeWithText("Edit in layers. Everything runs on your phone.").assertDoesNotExist()
        rule.onNodeWithContentDescription("beach.jpg").performClick()
        waitForPhoto(s)
        rule.runOnIdle {
            assertEquals(edited, s.doc)
            assertEquals(edited, s.opened)
            assertEquals(dir, s.project?.dir)
            assertFalse("opening isn't an edit", s.history.canUndo)
        }
    }

    @Test
    fun longPressAsksBeforeDeletingAProject() {
        var dir: File? = null
        rule.showEditor(photo = false) { dir = saved("beach.jpg") }
        val question = "Delete beach.jpg? Its edits can't be brought back. The photo in your gallery stays."
        rule.onNodeWithContentDescription("beach.jpg").performTouchInput { longClick() }
        rule.onNodeWithText(question).assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        assertTrue(dir!!.exists())
        rule.onNodeWithContentDescription("beach.jpg").performTouchInput { longClick() }
        rule.onNodeWithText("Delete").performClick()
        rule.waitUntil(5_000) { !dir!!.exists() }
        rule.onNodeWithContentDescription("beach.jpg").assertDoesNotExist()
        rule.onNodeWithText("Edit in layers. Everything runs on your phone.").assertIsDisplayed()
    }

    @Test
    fun firstRunHomeHasTheTaglineAndOpenButtonsAndNoEditing() {
        rule.showEditor(photo = false)
        rule.onNodeWithText("Bloom").assertIsDisplayed()
        rule.onNodeWithText("Edit in layers. Everything runs on your phone.").assertIsDisplayed()
        for (d in listOf("Close photo", "Undo", "Redo")) rule.onNodeWithContentDescription(d).assertIsNotEnabled()
        rule.onNodeWithText("Export").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Color").assertDoesNotExist()
        rule.onNodeWithContentDescription("Photo layer").assertDoesNotExist()
    }

    @Test
    fun openPhotoAsksThePickerForImagesAndOpensACopyAsANewProject() {
        val pickers = FakePickers().apply { result = photoFile("sea.jpg") }
        val s = rule.showEditor(photo = false, pickers = pickers)
        rule.onNodeWithText("Open photo").performClick()
        waitForPhoto(s)
        val request = pickers.launched.single() as PickVisualMediaRequest
        assertEquals(ActivityResultContracts.PickVisualMedia.ImageOnly, request.mediaType)
        rule.runOnIdle {
            val p = s.project!!
            assertEquals("sea.jpg", p.name)
            assertEquals(File(p.dir, "original.jpg"), p.original)
            assertTrue(s.doc.layers.isEmpty())
            assertNotNull(s.preview)
        }
    }

    @Test
    fun openFileAsksForImagesAndRawsAndCancelDoesNothing() {
        val pickers = FakePickers() // the picker returns nothing: cancelled
        val s = rule.showEditor(photo = false, pickers = pickers)
        rule.onNodeWithText("Open file (RAW: CR3, NEF, ARW, DNG…)").performClick()
        rule.runOnIdle {
            assertArrayEquals(arrayOf("image/*", "application/octet-stream"), pickers.launched.single() as Array<*>)
            assertNull(s.source)
        }
    }

    @Test
    fun aFileThatIsNoImageShowsWhyAndLeavesNoProject() {
        val bad = Uri.fromFile(File(ctx.cacheDir, "notes.jpg").apply { writeText("not a photo") })
        val s = rule.showEditor(photo = false, pickers = FakePickers().apply { result = bad })
        rule.onNodeWithText("Open photo").performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Can't open image", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.runOnIdle { assertNull(s.source) }
        assertTrue(projects.listFiles().orEmpty().isEmpty())
    }

    /** Opens [name] through the picker and applies an Exposure layer. */
    private fun openAndEdit(name: String, setup: () -> Unit = {}): dev.bloom.editor.ui.EditorState {
        val s = rule.showEditor(photo = false, pickers = FakePickers().apply { result = photoFile(name) }, setup = setup)
        rule.onNodeWithText("Open photo").performClick()
        waitForPhoto(s)
        rule.onNodeWithContentDescription("Color").performClick()
        rule.onNodeWithContentDescription("Exposure").setSlider(30f)
        rule.onNodeWithContentDescription("Apply as Color layer").performClick()
        return s
    }

    @Test
    fun keepSavesANewProjectToHomeAndDiscardOfAReopenedOneRestoresIt() {
        val s = openAndEdit("sea.jpg")
        val dir = rule.runOnIdle { s.project!!.dir }
        rule.onNodeWithContentDescription("Close photo").performClick()
        rule.onNodeWithText("Keep project").performClick()
        rule.onNodeWithContentDescription("sea.jpg").assertIsDisplayed() // Home lists it once saved
        val kept = Project.open(dir).second
        assertEquals(1, kept.layers.size)

        rule.onNodeWithContentDescription("sea.jpg").performClick()
        waitForPhoto(s)
        rule.onNodeWithContentDescription("Color layer").performClick()
        rule.onNodeWithContentDescription("Opacity").setSlider(50f)
        rule.back() // closes the bubble
        rule.onNodeWithContentDescription("Close photo").performClick()
        rule.onNodeWithText("Discard").performClick()
        rule.onNodeWithContentDescription("sea.jpg").assertIsDisplayed()
        assertEquals(kept, Project.open(dir).second)
    }

    @Test
    fun aReopenedProjectEmptiedOfLayersIsKeptNotDeleted() {
        var dir: File? = null
        val s = rule.showEditor(photo = false) { dir = saved("beach.jpg") }
        rule.onNodeWithContentDescription("beach.jpg").performClick()
        waitForPhoto(s)
        rule.onNodeWithContentDescription("Color layer").performClick()
        rule.onNodeWithText("Delete").performClick()
        rule.runOnIdle { assertTrue(s.doc.layers.isEmpty()) }
        rule.onNodeWithContentDescription("Close photo").performClick()
        rule.onNodeWithText("Keep project").performClick() // asks even though nothing is left
        rule.onNodeWithContentDescription("beach.jpg").assertIsDisplayed()
        assertTrue(dir!!.exists())
        assertTrue(Project.open(dir!!).second.layers.isEmpty())
    }

    @Test
    fun discardDeletesANewProject() {
        val s = openAndEdit("sea.jpg")
        val dir = rule.runOnIdle { s.project!!.dir }
        rule.onNodeWithContentDescription("Close photo").performClick()
        rule.onNodeWithText("Discard").performClick()
        rule.waitUntil(5_000) { !dir.exists() }
        rule.onNodeWithText("Edit in layers. Everything runs on your phone.").assertIsDisplayed()
    }

    @Test
    fun aPhotoClosedWithoutEditsLeavesNoProject() {
        val s = rule.showEditor(photo = false, pickers = FakePickers().apply { result = photoFile("sea.jpg") })
        rule.onNodeWithText("Open photo").performClick()
        waitForPhoto(s)
        val dir = rule.runOnIdle { s.project!!.dir }
        rule.onNodeWithContentDescription("Close photo").performClick() // nothing to lose: no question
        rule.waitUntil(5_000) { !dir.exists() }
        rule.runOnIdle { assertNull(s.source) }
    }

    @Test
    fun autosaveIsOffByDefaultAndSaveWritesTheProjectThenHides() {
        val s = openAndEdit("sea.jpg")
        val doc = File(rule.runOnIdle { s.project!!.dir }, "doc.json")
        Thread.sleep(3_000)
        assertFalse(doc.exists())
        rule.onNodeWithContentDescription("Save").performClick()
        rule.waitUntil(5_000) { doc.exists() }
        assertEquals(1, Project.open(doc.parentFile!!).second.layers.size)
        rule.waitUntil(5_000) { rule.onAllNodes(hasContentDescription("Save")).fetchSemanticsNodes().isEmpty() } // nothing left to save
        rule.onNodeWithContentDescription("Close photo").performClick() // saved: no question
        rule.onNodeWithContentDescription("sea.jpg").assertIsDisplayed()
    }

    @Test
    fun noSaveButtonWithoutEdits() {
        val s = rule.showEditor(photo = false, pickers = FakePickers().apply { result = photoFile("sea.jpg") })
        rule.onNodeWithText("Open photo").performClick()
        waitForPhoto(s)
        rule.onNodeWithContentDescription("Save").assertDoesNotExist()
    }

    @Test
    fun autosaveWhenOnSavesEditsAtItsInterval() {
        val s = openAndEdit("sea.jpg") { ctx.getSharedPreferences("settings", 0).edit().putInt("autosave_s", 2).commit() }
        val doc = File(rule.runOnIdle { s.project!!.dir }, "doc.json")
        rule.waitUntil(8_000) { doc.exists() }
        assertEquals(1, Project.open(doc.parentFile!!).second.layers.size)
        rule.onNodeWithContentDescription("Save").assertIsDisplayed() // autosave isn't the user's save
    }
}
