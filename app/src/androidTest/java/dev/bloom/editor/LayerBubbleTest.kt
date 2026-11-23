package dev.bloom.editor

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.bloom.editor.model.Blend
import dev.bloom.editor.model.DetailEffect
import dev.bloom.editor.model.Document
import dev.bloom.editor.model.Group
import dev.bloom.editor.model.Layer
import dev.bloom.editor.ui.EditorState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LayerBubbleTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val twoLayers = Document(listOf(Layer(1), Layer(2, effect = DetailEffect())))

    /** Shows [doc] and opens the bubble of the thumbnail read as [desc]. */
    private fun bubble(doc: Document, desc: String): EditorState {
        val s = rule.showEditor(doc)
        rule.onNodeWithContentDescription(desc).performClick()
        return s
    }

    @Test
    fun opacitySliderSetsOpacityAsOneStep() {
        val s = bubble(twoLayers, "Color layer")
        rule.onNodeWithContentDescription("Opacity").setSlider(40f)
        rule.runOnIdle {
            assertEquals(0.4f, s.doc.layers[0].opacity, 1e-4f)
            assertEquals(1, s.history.past.size)
        }
        rule.onNodeWithText("40%").assertIsDisplayed()
    }

    @Test
    fun opacityDragPreviewsLiveAndCommitsOnRelease() {
        val s = bubble(twoLayers, "Color layer")
        val slider = rule.onNodeWithContentDescription("Opacity")
        slider.performTouchInput { down(center); repeat(10) { moveBy(Offset(-width / 40f, 0f)) } }
        rule.runOnIdle {
            assertEquals(1L, s.opacityPreview?.first)
            assertEquals(1f, s.doc.layers[0].opacity)
            assertTrue(s.rendered()[0].opacity < 1f)
        }
        slider.performTouchInput { up() }
        rule.runOnIdle {
            assertNull(s.opacityPreview)
            assertTrue(s.doc.layers[0].opacity < 1f)
            assertEquals(1, s.history.past.size)
        }
    }

    @Test
    fun blendMenuListsTheModesAndSetsOne() {
        val s = bubble(twoLayers, "Color layer")
        rule.onNodeWithText("Normal ▾").performClick()
        for (b in Blend.entries) rule.onNodeWithText(b.label).assertIsDisplayed()
        rule.onNodeWithContentDescription("Current").assertIsDisplayed()
        rule.onNodeWithText("Soft light").performClick()
        rule.runOnIdle {
            assertEquals(Blend.SoftLight, s.doc.layers[0].blend)
            assertEquals(1, s.history.past.size)
        }
        rule.onNodeWithText("Soft light ▾").assertIsDisplayed()
    }

    @Test
    fun renameTrimsAndEmptyFallsBackToTheKind() {
        val s = bubble(twoLayers, "Color layer")
        rule.onNodeWithText("Color").performClick()
        rule.onNodeWithContentDescription("layer name").performTextReplacement("  Sky  ")
        rule.onNodeWithContentDescription("layer name").performImeAction()
        rule.runOnIdle {
            assertEquals("Sky", s.doc.layers[0].name)
            assertEquals(1, s.history.past.size)
        }
        rule.onNodeWithContentDescription("Sky, Color layer").assertExists()
        rule.onNodeWithText("Sky").performClick()
        rule.onNodeWithContentDescription("layer name").performTextReplacement("   ")
        rule.onNodeWithContentDescription("layer name").performImeAction()
        rule.runOnIdle { assertEquals("", s.doc.layers[0].name) }
        rule.onNodeWithText("Color").assertIsDisplayed()
    }

    @Test
    fun eyeButtonHidesTheLayerWithASnackbar() {
        val s = bubble(twoLayers, "Blur layer")
        rule.onNodeWithContentDescription("Hide layer").performClick()
        rule.runOnIdle { assertFalse(s.doc.layers[1].visible) }
        rule.onNodeWithText("Blur layer hidden").assertIsDisplayed()
        rule.onNodeWithContentDescription("Show layer").assertIsDisplayed()
    }

    @Test
    fun moveButtonsStepTheLayerAndDisableAtTheEnds() {
        val s = bubble(twoLayers, "Blur layer")
        rule.onNodeWithText("Move up").assertIsNotEnabled()
        rule.onNodeWithText("Move down").assertIsEnabled().performClick()
        rule.runOnIdle {
            assertEquals(listOf(2L, 1L), s.doc.layers.map { it.id })
            assertEquals(2L, s.bubble?.first) // the bubble stays with its layer
        }
        rule.onNodeWithText("Move down").assertIsNotEnabled()
        rule.onNodeWithText("Move up").assertIsEnabled()
    }

    @Test
    fun groupBelowMakesAGroupAndUngroupUndoesIt() {
        val s = bubble(twoLayers, "Blur layer")
        rule.onNodeWithText("Group below").performClick()
        rule.runOnIdle {
            val g = s.doc.groups.single().id
            assertEquals(listOf(g, g), s.doc.layers.map { it.group })
        }
        rule.onNodeWithText("Ungroup").performClick()
        rule.runOnIdle {
            assertTrue(s.doc.groups.isEmpty())
            assertEquals(listOf(null, null), s.doc.layers.map { it.group })
            assertEquals(2, s.history.past.size)
        }
    }

    @Test
    fun groupBelowIsDisabledForTheBottomLayer() {
        bubble(twoLayers, "Color layer")
        rule.onNodeWithText("Group below").assertIsNotEnabled()
    }

    @Test
    fun deleteRemovesTheLayerAsOneStepAndClosesTheBubble() {
        val s = bubble(twoLayers, "Color layer")
        rule.onNodeWithText("Delete").performClick()
        rule.runOnIdle {
            assertEquals(listOf(2L), s.doc.layers.map { it.id })
            assertNull(s.bubble)
        }
        rule.onNodeWithContentDescription("Undo").performClick()
        rule.runOnIdle { assertEquals(listOf(1L, 2L), s.doc.layers.map { it.id }) }
        rule.onNodeWithContentDescription("Opacity").assertDoesNotExist()
    }

    @Test
    fun editOpensTheLayersToolAndApplyReplacesIt() {
        val s = bubble(twoLayers, "Color layer")
        rule.onNodeWithText("Edit").performClick()
        rule.runOnIdle {
            assertEquals(1L, s.editingId)
            assertNull("opening a tool closes the bubble", s.bubble)
        }
        rule.onNodeWithContentDescription("Exposure").setSlider(30f)
        rule.onNodeWithContentDescription("Apply changes").performClick()
        rule.runOnIdle {
            assertEquals(listOf(1L, 2L), s.doc.layers.map { it.id })
            assertTrue(s.doc.layers[0].edited)
        }
    }

    @Test
    fun editMaskOpensTheToolInMaskMode() {
        val s = bubble(twoLayers, "Blur layer")
        rule.onNodeWithText("Edit mask").performClick()
        rule.runOnIdle { assertTrue(s.maskMode) }
        rule.onNodeWithContentDescription("Blur mask. Back to the tool").assertIsDisplayed()
    }

    @Test
    fun tapOutsideClosesTheBubbleAndDoesNothingElse() {
        val s = bubble(twoLayers, "Color layer")
        rule.onRoot().performTouchInput { click(Offset(width * 0.2f, height * 0.4f)) }
        rule.runOnIdle {
            assertNull(s.bubble)
            assertFalse(s.history.canUndo)
        }
    }

    @Test
    fun touchesOnTheBubblesOwnBackgroundKeepItOpen() {
        val s = bubble(twoLayers, "Color layer")
        rule.onNodeWithText("Blend").performClick()
        // the slider's very end, past its track: it was a gap where the tap-outside layer took the touch
        rule.onNodeWithContentDescription("Opacity").performTouchInput { click(Offset(right - 2f, centerY)) }
        rule.runOnIdle { assertEquals(1L, s.bubble?.first) }
    }

    @Test
    fun theBubbleClosesWhenItsLayerGoes() {
        val s = bubble(twoLayers, "Color layer")
        rule.runOnIdle { s.edit { it.remove(1) } }
        rule.onNodeWithContentDescription("Opacity").assertDoesNotExist()
    }

    @Test
    fun backClosesTheBubbleFirst() {
        val s = bubble(twoLayers, "Color layer")
        rule.back()
        rule.runOnIdle {
            assertNull(s.bubble)
            assertNotNull("photo still open", s.source)
        }
    }

    @Test
    fun groupBubbleRenamesCollapsesHidesFadesMovesAndUngroups() {
        val doc = Document(listOf(Layer(1), Layer(2), Layer(3))).groupWithBelow(2, 9)
        val s = bubble(doc, "Group of 2 layers")
        rule.onNodeWithText("Group").performClick()
        rule.onNodeWithContentDescription("group name").performTextReplacement("Sky")
        rule.onNodeWithContentDescription("group name").performImeAction()
        rule.onNodeWithContentDescription("Collapse group").performClick()
        rule.onNodeWithContentDescription("Hide group").performClick()
        rule.onNodeWithContentDescription("Opacity").setSlider(50f)
        rule.runOnIdle {
            assertEquals(Group(9, "Sky", opacity = 0.5f, visible = false, collapsed = true), s.doc.groups.single())
            assertEquals(4, s.history.past.size)
        }
        rule.onNodeWithText("Move down").assertIsNotEnabled()
        rule.onNodeWithText("Move up").performClick()
        rule.runOnIdle { assertEquals(listOf(3L, 1L, 2L), s.doc.layers.map { it.id }) }
        rule.onNodeWithText("Ungroup").performClick()
        rule.runOnIdle {
            assertTrue(s.doc.groups.isEmpty())
            assertEquals(listOf(3L, 1L, 2L), s.doc.layers.map { it.id })
            assertNull(s.bubble)
        }
    }

    @Test
    fun photoBubbleTogglesSmoothBandingAsOneStep() {
        val s = bubble(twoLayers, "Photo layer")
        rule.onNode(isToggleable()).assertIsOn().performClick()
        rule.runOnIdle {
            assertFalse(s.doc.deband)
            assertEquals(1, s.history.past.size)
        }
        rule.onNode(isToggleable()).assertIsOff()
    }

    @Test
    fun photoBubbleSwitchIsOffAndDisabledForDeepPhotos() {
        val s = rule.showEditor(twoLayers)
        rule.runOnIdle { s.preview = testPhoto().copy(Bitmap.Config.RGBA_F16, false) }
        rule.onNodeWithContentDescription("Photo layer").performClick()
        rule.onNodeWithText("Not needed: this photo has more than 8 bits").assertIsDisplayed()
        rule.onNode(isToggleable()).assertIsOff().assertIsNotEnabled()
    }
}
