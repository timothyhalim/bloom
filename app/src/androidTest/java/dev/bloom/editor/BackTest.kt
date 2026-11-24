package dev.bloom.editor

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.bloom.editor.model.Document
import dev.bloom.editor.model.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun backHandled() = rule.runOnIdle { rule.activity.onBackPressedDispatcher.hasEnabledCallbacks() }

    @Test
    fun backIsHandledOnlyWhileAPhotoIsOpen() {
        val s = rule.showEditor(photo = false)
        assertFalse(backHandled())
        rule.runOnIdle { s.load(Uri.parse("file:///test.jpg"), testPhoto()) }
        assertTrue(backHandled())
    }

    @Test
    fun backGoesFromMaskModeToTheToolToToolsToHome() {
        val s = rule.showEditor()
        rule.onNodeWithContentDescription("Color").performClick()
        rule.onNodeWithContentDescription("Mask: none. Add").performClick()
        rule.back()
        rule.runOnIdle {
            assertFalse(s.maskMode)
            assertNotNull(s.draft)
        }
        rule.back() // nothing changed: no question
        rule.runOnIdle { assertNull(s.draft) }
        rule.back() // nothing to lose: straight to Home
        rule.runOnIdle { assertNull(s.source) }
        rule.onNodeWithText("Edit in layers. Everything runs on your phone.").assertIsDisplayed()
    }

    @Test
    fun leavingAChangedToolAsksAndKeepEditingStays() {
        val s = rule.showEditor()
        rule.onNodeWithContentDescription("Color").performClick()
        rule.onNodeWithContentDescription("Exposure").setSlider(30f)
        rule.onNodeWithContentDescription("Color. Back to tools").performClick()
        rule.onNodeWithText("Discard changes?").assertIsDisplayed()
        rule.onNodeWithText("Keep editing").performClick()
        rule.runOnIdle { assertNotNull(s.draft) }
        rule.back()
        rule.onNodeWithText("Discard changes?").assertIsDisplayed()
        rule.back() // back closes the dialog first
        rule.onNodeWithText("Discard changes?").assertDoesNotExist()
        rule.runOnIdle { assertNotNull(s.draft) }
        rule.back()
        rule.onNodeWithText("Discard").performClick()
        rule.runOnIdle {
            assertNull(s.draft)
            assertTrue(s.doc.layers.isEmpty())
        }
    }

    @Test
    fun leavingAChangedCropAsks() {
        val s = rule.showEditor()
        rule.onNodeWithContentDescription("Crop").performClick()
        rule.onNodeWithContentDescription("Flip horizontally").performClick()
        rule.back()
        rule.onNodeWithText("Discard").performClick()
        rule.runOnIdle {
            assertNull(s.crop)
            assertTrue(s.doc.crop.isIdentity)
        }
    }

    @Test
    fun closingAPhotoWithEditsAsksKeepDiscardOrCancel() {
        val s = rule.showEditor()
        rule.onNodeWithContentDescription("Color").performClick()
        rule.onNodeWithContentDescription("Exposure").setSlider(30f)
        rule.onNodeWithContentDescription("Apply as Color layer").performClick()
        rule.onNodeWithContentDescription("Close photo").performClick()
        rule.onNodeWithText("Keep this project? Discard deletes it.").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        rule.runOnIdle { assertEquals(1, s.doc.layers.size) }
        rule.back()
        rule.onNodeWithText("Discard").performClick()
        rule.runOnIdle { assertNull(s.source) }
    }

    @Test
    fun closingWithAChangedToolAsksToDiscardItFirst() {
        val s = rule.showEditor()
        rule.onNodeWithContentDescription("Color").performClick()
        rule.onNodeWithContentDescription("Exposure").setSlider(30f)
        rule.onNodeWithContentDescription("Close photo").performClick()
        rule.onNodeWithText("Discard changes?").assertIsDisplayed()
        rule.onNodeWithText("Discard").performClick()
        // the draft was this visit's only change, so the photo closes without the Keep question
        rule.runOnIdle { assertNull(s.source) }
    }

    @Test
    fun anOpenedProjectAsksAboutItsChanges() {
        rule.showEditor(Document(listOf(Layer(1))))
        rule.onNodeWithContentDescription("Color layer").performClick()
        rule.onNodeWithContentDescription("Opacity").setSlider(50f)
        rule.back() // closes the bubble
        rule.onNodeWithContentDescription("Close photo").performClick()
        rule.onNodeWithText("Keep your changes? Discard takes the project back to its last save.").assertIsDisplayed()
    }
}
