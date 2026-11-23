package dev.bloom.editor

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.bloom.editor.model.BrushStep
import dev.bloom.editor.model.DetailEffect
import dev.bloom.editor.model.Document
import dev.bloom.editor.model.Group
import dev.bloom.editor.model.Layer
import dev.bloom.editor.model.Mask
import dev.bloom.editor.model.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class) // performCustomAccessibilityActionWithLabel
@RunWith(AndroidJUnit4::class)
class LayerRailTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    // bottom to top: Color, Blur
    private val twoLayers = Document(listOf(Layer(1), Layer(2, effect = DetailEffect())))
    private val grouped = twoLayers.groupWithBelow(2, 9).replace(Group(9, name = "Sky"))

    private fun top(desc: String) = rule.onNodeWithContentDescription(desc).getUnclippedBoundsInRoot().top

    /** Hold past the long-press timeout, then drag up one 48 dp row. */
    private fun TouchInjectionScope.holdAndDragUpOneRow() {
        down(center)
        advanceEventTime(1000)
        val row = 48.dp.toPx()
        repeat(10) { moveBy(Offset(0f, -row / 10)) }
        up()
    }

    @Test
    fun railListsTheTopLayerFirstAndPhotoLast() {
        rule.showEditor(twoLayers)
        assertTrue(top("Blur layer") < top("Color layer"))
        assertTrue(top("Color layer") < top("Photo layer"))
        rule.onNodeWithText("2").assertIsDisplayed() // the count in the layers pill
    }

    @Test
    fun tappingThePillCollapsesTheRailAndTheTabExpandsIt() {
        rule.showEditor(twoLayers)
        rule.onNodeWithContentDescription("Hide layers panel").performClick()
        rule.onNodeWithContentDescription("Color layer").assertDoesNotExist()
        rule.onNodeWithContentDescription("Show layers").performClick()
        rule.onNodeWithContentDescription("Color layer").assertIsDisplayed()
    }

    @Test
    fun tappingAThumbnailTogglesItsBubble() {
        val s = rule.showEditor(twoLayers)
        rule.onNodeWithContentDescription("Color layer").performClick()
        rule.onNodeWithContentDescription("Opacity").assertIsDisplayed()
        rule.runOnIdle { assertEquals(1L, s.bubble?.first) }
        rule.onNodeWithContentDescription("Color layer").performClick()
        rule.onNodeWithContentDescription("Opacity").assertDoesNotExist()
    }

    @Test
    fun tappingThePhotoTogglesThePhotoBubble() {
        rule.showEditor(twoLayers)
        rule.onNodeWithContentDescription("Photo layer").performClick()
        rule.onNodeWithText("Smooth banding").assertIsDisplayed()
        rule.onNodeWithContentDescription("Photo layer").performClick()
        rule.onNodeWithText("Smooth banding").assertDoesNotExist()
    }

    @Test
    fun swipingAThumbnailHidesTheLayerAsOneStepAndTheSnackbarUndoesIt() {
        val s = rule.showEditor(twoLayers)
        rule.onNodeWithContentDescription("Color layer").performTouchInput { swipeLeft() }
        rule.runOnIdle {
            assertFalse(s.doc.layers[0].visible)
            assertEquals(1, s.history.past.size)
        }
        rule.onNodeWithContentDescription("Color layer, hidden").assertIsDisplayed()
        rule.onNodeWithText("Color layer hidden").assertIsDisplayed()
        // a later edit stays when the snackbar's Undo restores only the visibility
        rule.runOnIdle { s.edit { it.replace(it.layers[1].copy(opacity = 0.5f)) } }
        rule.onNodeWithText("Undo").performClick()
        rule.runOnIdle {
            assertTrue(s.doc.layers[0].visible)
            assertEquals(0.5f, s.doc.layers[1].opacity)
        }
        rule.onNodeWithContentDescription("Color layer").performTouchInput { swipeRight() }
        rule.runOnIdle { assertFalse(s.doc.layers[0].visible) }
    }

    @Test
    fun holdAndDragMovesALayerUpOneRowAsOneStep() {
        val s = rule.showEditor(twoLayers)
        rule.onNodeWithContentDescription("Color layer").performTouchInput { holdAndDragUpOneRow() }
        rule.runOnIdle {
            assertEquals(listOf(2L, 1L), s.doc.layers.map { it.id })
            assertEquals(1, s.history.past.size)
            assertNull(s.bubble)
        }
    }

    @Test
    fun swipingThePillHidesAllLayersAsAViewSetting() {
        val s = rule.showEditor(twoLayers)
        rule.onNodeWithContentDescription("Hide layers panel").performTouchInput { swipe(center, center - Offset(100.dp.toPx(), 0f)) }
        rule.runOnIdle {
            assertTrue(s.layersHidden)
            assertTrue(s.rendered().isEmpty())
            assertFalse("not undoable", s.history.canUndo)
        }
        rule.onNodeWithContentDescription("Color layer").assertIsDisplayed() // the rail still lists them, and the tap didn't collapse it
        rule.onNodeWithContentDescription("Hide layers panel. All layers hidden").performTouchInput { swipe(center, center - Offset(100.dp.toPx(), 0f)) }
        rule.runOnIdle { assertFalse(s.layersHidden) }
        rule.onNodeWithContentDescription("Hide layers panel").performCustomAccessibilityActionWithLabel("Hide all layers")
        rule.runOnIdle { assertTrue(s.layersHidden) }
    }

    @Test
    fun theHideActionTogglesALayerForScreenReaders() {
        val s = rule.showEditor(twoLayers)
        rule.onNodeWithContentDescription("Blur layer").performCustomAccessibilityActionWithLabel("Hide layer")
        rule.runOnIdle { assertFalse(s.doc.layers[1].visible) }
        rule.onNodeWithContentDescription("Blur layer, hidden").performCustomAccessibilityActionWithLabel("Show layer")
        rule.runOnIdle { assertTrue(s.doc.layers[1].visible) }
    }

    @Test
    fun thumbnailsReadNameKindGroupMaskAndHidden() {
        val masked = Layer(3, name = "Face", mask = Mask(listOf(BrushStep(listOf(Stroke(listOf(0.5f, 0.5f), 0.1f, 0f, 1f))))), visible = false)
        rule.showEditor(grouped.copy(layers = grouped.layers + masked))
        rule.onNodeWithContentDescription("Face, Color layer, has mask, hidden").assertExists()
        rule.onNodeWithContentDescription("Sky, group of 2 layers").assertExists()
        rule.onNodeWithContentDescription("Color layer in Sky").assertExists()
        rule.onNodeWithContentDescription("Blur layer in Sky").assertExists()
    }

    @Test
    fun aDraftLayerShowsAsPendingAtTheTop() {
        rule.showEditor(twoLayers)
        rule.onNodeWithContentDescription("Color").performClick()
        assertTrue(top("New Color layer, not applied yet") < top("Blur layer"))
        rule.onNodeWithContentDescription("Color. Back to tools").performClick()
        rule.onNodeWithContentDescription("New Color layer, not applied yet").assertDoesNotExist()
    }

    @Test
    fun groupHeaderSwipesHideHoldDragsMoveAndTapsOpenTheBubble() {
        val s = rule.showEditor(grouped.copy(layers = grouped.layers + Layer(3)))
        rule.onNodeWithContentDescription("Sky, group of 2 layers").performTouchInput { swipeLeft() }
        rule.runOnIdle {
            assertFalse(s.doc.groups.single().visible)
            assertEquals(1, s.history.past.size)
        }
        rule.onNodeWithContentDescription("Sky, group of 2 layers, hidden").performTouchInput { holdAndDragUpOneRow() }
        rule.runOnIdle { assertEquals(listOf(3L, 1L, 2L), s.doc.layers.map { it.id }) }
        rule.onNodeWithContentDescription("Sky, group of 2 layers, hidden").performClick()
        rule.onNodeWithContentDescription("Collapse group").assertIsDisplayed()
    }

    @Test
    fun aCollapsedGroupShowsOnlyItsHeader() {
        rule.showEditor(grouped.replace(Group(9, name = "Sky", collapsed = true)))
        rule.onNodeWithContentDescription("Sky, group of 2 layers, collapsed").assertIsDisplayed()
        rule.onNodeWithContentDescription("Color layer in Sky").assertDoesNotExist()
    }

    @Test
    fun scrollArrowsShowOnlyWhenThereIsMoreThatWay() {
        rule.showEditor(Document(List(30) { Layer(it + 1L) }))
        rule.onNodeWithContentDescription("Scroll layers up").assertDoesNotExist()
        rule.onNodeWithContentDescription("Scroll layers down").performClick()
        rule.onNodeWithContentDescription("Scroll layers up").assertIsDisplayed()
    }
}
