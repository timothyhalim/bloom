package dev.bloom.editor

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.bloom.editor.model.BrushStep
import dev.bloom.editor.model.ColorEffect
import dev.bloom.editor.model.ColorParam
import dev.bloom.editor.model.Document
import dev.bloom.editor.model.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UndoTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun applyingAToolIsOneUndoStep() {
        val s = rule.showEditor()
        rule.onNodeWithContentDescription("Undo").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Color").performClick()
        rule.onNodeWithContentDescription("Exposure").setSlider(50f)
        rule.onNodeWithContentDescription("Contrast 0").performClick()
        rule.onNodeWithContentDescription("Contrast").setSlider(20f)
        rule.onNodeWithContentDescription("Apply as Color layer").performClick()
        rule.runOnIdle {
            assertEquals(1, s.doc.layers.size)
            assertEquals(20f, (s.doc.layers[0].effect as ColorEffect)[ColorParam.Contrast])
            assertEquals(1, s.history.past.size)
        }
        rule.onNodeWithContentDescription("Undo").performClick()
        rule.runOnIdle { assertEquals(0, s.doc.layers.size) }
        rule.onNodeWithContentDescription("Undo").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Redo").performClick()
        rule.runOnIdle { assertEquals(1, s.doc.layers.size) }
    }

    @Test
    fun applyingCropIsOneUndoStep() {
        val s = rule.showEditor()
        rule.onNodeWithContentDescription("Crop").performClick()
        rule.onNodeWithText("1:1").performClick()
        rule.onNodeWithContentDescription("Rotate 90° clockwise").performClick()
        rule.onNodeWithContentDescription("Apply crop").performClick()
        rule.runOnIdle {
            assertFalse(s.doc.crop.isIdentity)
            assertEquals(1, s.history.past.size)
            assertTrue("rail unchanged", s.doc.layers.isEmpty())
        }
        rule.onNodeWithContentDescription("Undo").performClick()
        rule.runOnIdle { assertTrue(s.doc.crop.isIdentity) }
    }

    @Test
    fun undoInsideAToolStepsBackOneBrushStrokeAtATime() {
        val s = rule.showEditor(Document(listOf(Layer(1, effect = ColorEffect().with(ColorParam.Exposure, 10f)))))
        rule.onNodeWithContentDescription("Color").performClick()
        // a fresh tool has no steps of its own yet; the document's history isn't reachable from inside
        rule.onNodeWithContentDescription("Undo").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Mask: none. Add").performClick()
        val photo = rule.onNodeWithTag("photo")
        photo.performTouchInput { swipe(Offset(width * 0.3f, height * 0.3f), Offset(width * 0.6f, height * 0.35f)) }
        photo.performTouchInput { swipe(Offset(width * 0.3f, height * 0.5f), Offset(width * 0.6f, height * 0.55f)) }
        rule.runOnIdle { assertEquals(2, (s.draft!!.mask.steps.single() as BrushStep).strokes.size) }
        rule.onNodeWithContentDescription("Undo").performClick()
        rule.runOnIdle { assertEquals(1, (s.draft!!.mask.steps.single() as BrushStep).strokes.size) }
        rule.onNodeWithContentDescription("Undo").performClick()
        rule.runOnIdle { assertTrue(s.draft!!.mask.steps.isEmpty()) }
        rule.onNodeWithContentDescription("Undo").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Redo").performClick()
        rule.runOnIdle { assertEquals(1, (s.draft!!.mask.steps.single() as BrushStep).strokes.size) }
        rule.onNodeWithContentDescription("Redo").assertIsEnabled()
    }

    @Test
    fun sliderDragInsideAToolIsOneStep() {
        val s = rule.showEditor()
        rule.onNodeWithContentDescription("Color").performClick()
        rule.onNodeWithContentDescription("Exposure").performTouchInput { swipe(center, Offset(right - 4f, centerY)) }
        rule.runOnIdle { assertTrue((s.draft!!.effect as ColorEffect)[ColorParam.Exposure] > 0f) }
        rule.onNodeWithContentDescription("Undo").performClick()
        rule.runOnIdle { assertEquals(0f, (s.draft!!.effect as ColorEffect)[ColorParam.Exposure]) }
    }

    @Test
    fun openingAnotherPhotoClearsHistory() {
        val s = rule.showEditor()
        rule.onNodeWithContentDescription("Color").performClick()
        rule.onNodeWithContentDescription("Exposure").setSlider(30f)
        rule.onNodeWithContentDescription("Apply as Color layer").performClick()
        rule.onNodeWithContentDescription("Undo").assertIsEnabled()
        rule.runOnIdle { s.load(s.source, testPhoto()) }
        rule.onNodeWithContentDescription("Undo").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Redo").assertIsNotEnabled()
        rule.runOnIdle { assertTrue(s.doc.layers.isEmpty()) }
    }
}
