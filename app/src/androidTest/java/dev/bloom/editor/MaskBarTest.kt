package dev.bloom.editor

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.bloom.editor.model.ColorStep
import dev.bloom.editor.model.Document
import dev.bloom.editor.model.GradientStep
import dev.bloom.editor.model.Layer
import dev.bloom.editor.model.LuminanceStep
import dev.bloom.editor.model.Mask
import dev.bloom.editor.model.SubjectStep
import dev.bloom.editor.ui.EditorState
import dev.bloom.editor.ui.MaskTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class) // performCustomAccessibilityActionWithLabel
@RunWith(AndroidJUnit4::class)
class MaskBarTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** The Color tool in mask mode, on [doc]. */
    private fun maskMode(doc: Document? = null): EditorState {
        val s = rule.showEditor(doc)
        rule.onNodeWithContentDescription("Color").performClick()
        rule.onNodeWithContentDescription("Mask: none. Add").performClick()
        return s
    }

    private fun steps(s: EditorState) = rule.runOnIdle { s.draft!!.mask.steps }

    // A step chip reads like the bar button that adds it ("Add gradient"); the steps row comes first.
    private fun chip(desc: String) = rule.onAllNodesWithContentDescription(desc)[0]

    @Test
    fun maskModeHasTheStepTypesInvertAndDone() {
        val s = maskMode()
        rule.onNodeWithContentDescription("Color mask. Back to the tool").assertIsDisplayed()
        for (t in listOf("brush", "gradient", "color range", "luminance range", "depth range", "subject", "background")) {
            rule.onNodeWithContentDescription("Add $t").assertExists() // the bar scrolls
        }
        rule.onNodeWithContentDescription("Add brush").assertIsSelected()
        rule.onNodeWithContentDescription("Invert off").assertIsNotSelected().performClick()
        rule.onNodeWithContentDescription("Invert on").assertIsSelected()
        rule.runOnIdle { assertTrue(s.draft!!.mask.invert) }
        rule.onNodeWithContentDescription("Done with mask").performClick()
        rule.runOnIdle {
            assertFalse(s.maskMode)
            assertTrue("kept", s.draft!!.mask.invert)
        }
        rule.onNodeWithContentDescription("Mask: none. Add").assertIsDisplayed()
    }

    @Test
    fun theLeftSlotGoesBackToTheTool() {
        val s = maskMode()
        rule.onNodeWithContentDescription("Color mask. Back to the tool").performClick()
        rule.runOnIdle { assertFalse(s.maskMode) }
    }

    @Test
    fun typeButtonsAddAStepWithTheSignSelectItAndSwitchTool() {
        val s = rule.showEditor()
        rule.runOnIdle {
            s.subjects = mapOf(emptyList<Float>() to SubjectMask(1, 1, byteArrayOf(0))) // found already: no model download
            s.depth = SubjectMask(1, 1, byteArrayOf(0))
        }
        rule.onNodeWithContentDescription("Color").performClick()
        rule.onNodeWithContentDescription("Mask: none. Add").performClick()
        rule.onNodeWithContentDescription("Add brush").performClick()
        assertTrue("brush only picks the tool", steps(s).isEmpty())
        rule.onNodeWithContentDescription("Adding. Tap to remove instead").performClick()
        rule.onNodeWithContentDescription("Remove luminance range").performClick()
        assertEquals(listOf(LuminanceStep.Shadows.copy(add = false)), steps(s))
        rule.onAllNodesWithContentDescription("Remove luminance range")[1].assertIsSelected()
        rule.onNodeWithContentDescription("Remove depth range").assertIsNotSelected()
        rule.onNodeWithContentDescription("Removing. Tap to add instead").performClick()
        rule.onNodeWithContentDescription("Add depth range").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Add color range").performClick()
        rule.onNodeWithContentDescription("Add gradient").performClick()
        rule.onNodeWithContentDescription("Add subject").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Add background").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(
                listOf(LuminanceStep.Shadows.copy(add = false), LuminanceStep.Near, ColorStep(), GradientStep(false, 0.5f, 0.2f, 0.5f, 0.6f), SubjectStep(), SubjectStep(background = true)),
                s.draft!!.mask.steps,
            )
            assertEquals(5, s.stepIndex)
            assertEquals(MaskTool.Subject, s.maskTool)
        }
        rule.onAllNodesWithContentDescription("Add background")[1].assertIsSelected()
        rule.onAllNodesWithContentDescription("Add subject")[1].assertIsNotSelected()
        rule.onNodeWithContentDescription("Color mask. Back to the tool").performClick()
        rule.onNodeWithContentDescription("Mask: 6 steps. Edit").assertIsDisplayed()
    }

    @Test
    fun aSelectedStepFlipsHidesAndDeletes() {
        val s = maskMode()
        rule.onNodeWithContentDescription("Add gradient").performClick()
        rule.onNodeWithContentDescription("Make this step remove").performClick()
        chip("Remove gradient").assertIsSelected()
        rule.onNodeWithContentDescription("Hide step").performClick()
        chip("Remove gradient, hidden").assertExists()
        rule.runOnIdle { assertFalse(s.draft!!.mask.active) }
        rule.onNodeWithContentDescription("Show step").performClick()
        rule.onNodeWithContentDescription("Delete step").performClick()
        rule.runOnIdle {
            assertTrue(steps(s).isEmpty())
            assertNull(s.stepIndex)
        }
    }

    @Test
    fun tappingAStepChipSelectsItAndAgainDeselects() {
        val s = maskMode()
        rule.onNodeWithContentDescription("Add gradient").performClick()
        rule.onNodeWithContentDescription("Add color range").performClick()
        chip("Add gradient").performClick()
        rule.runOnIdle {
            assertEquals(0, s.stepIndex)
            assertEquals(MaskTool.Gradient, s.maskTool)
        }
        rule.onNodeWithText("Linear").assertIsSelected()
        rule.onNodeWithText("Radial").performClick()
        rule.runOnIdle { assertTrue((steps(s)[0] as GradientStep).radial) }
        chip("Add radial gradient").performClick()
        rule.runOnIdle { assertNull(s.stepIndex) }
        rule.onNodeWithText("Tap the gradient icon to add one").assertIsDisplayed()
        rule.onNodeWithContentDescription("Delete step").assertDoesNotExist()
    }

    @Test
    fun stepChipsReorderByActionAndByHoldAndDrag() {
        val s = maskMode()
        rule.onNodeWithContentDescription("Add gradient").performClick()
        rule.onNodeWithContentDescription("Add color range").performClick()
        chip("Add gradient").performCustomAccessibilityActionWithLabel("Move right")
        rule.runOnIdle {
            assertTrue(steps(s)[1] is GradientStep)
            assertEquals("the moved step stays selected", 1, s.stepIndex)
        }
        chip("Add gradient").performTouchInput {
            down(center)
            advanceEventTime(1000)
            repeat(10) { moveBy(Offset(-54.dp.toPx() / 10, 0f)) }
            up()
        }
        rule.runOnIdle {
            assertTrue(steps(s)[0] is GradientStep)
            assertEquals(0, s.stepIndex)
        }
    }

    @Test
    fun moreHasCopyFromAnotherLayerClearAndFeather() {
        val other = Layer(1, mask = Mask(listOf(GradientStep(true, 0f, 0f, 1f, 1f)), invert = true))
        val s = maskMode(Document(listOf(other)))
        rule.onNodeWithContentDescription("More settings").performClick()
        rule.onNodeWithText("Clear mask").assertIsNotEnabled()
        rule.onNodeWithText("Copy mask from…").performClick()
        rule.onNodeWithText("Color layer 1").performClick()
        rule.runOnIdle {
            assertEquals(other.mask, s.draft!!.mask)
            assertNull(s.stepIndex)
        }
        chip("Add radial gradient").performClick()
        rule.onNodeWithContentDescription("Feather").setSlider(40f)
        rule.runOnIdle { assertEquals(0.4f, steps(s)[0].feather, 1e-4f) }
        rule.onNodeWithText("Clear mask").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(Mask(), s.draft!!.mask) }
        rule.onNodeWithContentDescription("Less").performClick()
        rule.onNodeWithText("Clear mask").assertDoesNotExist()
    }

    @Test
    fun copyFromIsDisabledWithoutAnotherMaskedLayer() {
        maskMode(Document(listOf(Layer(1))))
        rule.onNodeWithContentDescription("More settings").performClick()
        rule.onNodeWithText("Copy mask from…").assertIsNotEnabled()
    }

    @Test
    fun brushRowsHaveSizeSoftnessAndFlow() {
        val s = maskMode()
        rule.onNodeWithContentDescription("Brush size").setSlider(500f)
        rule.runOnIdle { assertEquals(150f, s.brushSize) }
        rule.onNodeWithContentDescription("More settings").performClick()
        rule.onNodeWithContentDescription("Softness").setSlider(20f)
        rule.onNodeWithContentDescription("Flow").setSlider(0f)
        rule.runOnIdle { assertEquals(20f to 1f, s.softness to s.flow) }
    }

    @Test
    fun luminancePresetCyclesAndTheStripReadsItsRange() {
        val s = maskMode()
        rule.onNodeWithContentDescription("Add luminance range").performClick()
        rule.onNodeWithContentDescription("Luminance range 0 to 40 percent").assertIsDisplayed()
        rule.onNodeWithText("Shadows").performClick()
        rule.onNodeWithText("Midtones").performClick()
        rule.onNodeWithText("Highlights").performClick()
        rule.onNodeWithText("Shadows").assertIsDisplayed()
        // a handle drag makes it Custom
        rule.onNodeWithContentDescription("Luminance range", substring = true).performTouchInput { down(Offset(width * 0.9f, centerY)); moveTo(Offset(width * 0.95f, centerY)); up() }
        rule.onNodeWithText("Custom").assertIsDisplayed()
        rule.runOnIdle { val l = steps(s)[0] as LuminanceStep; assertTrue(l.a <= l.b && l.b <= l.c && l.c <= l.d) }
    }

    @Test
    fun colorRangeHintsThenATapOnThePhotoPicksAColor() {
        val s = maskMode()
        rule.onNodeWithContentDescription("Add color range").performClick()
        rule.onNodeWithText("Tap the photo to pick a color").assertIsDisplayed()
        rule.onNodeWithTag("photo").performTouchInput { click(center) }
        rule.waitUntil(5_000) { (steps(s)[0] as ColorStep).samples.size == 3 }
        rule.onNodeWithContentDescription("Sample 1").assertIsDisplayed()
        rule.onNodeWithContentDescription("Add another sample").performClick()
        rule.onNodeWithContentDescription("Next tap adds a sample. Tap to cancel").assertIsSelected()
        rule.onNodeWithTag("photo").performTouchInput { click(Offset(centerX, height * 0.2f)) }
        rule.waitUntil(5_000) { (steps(s)[0] as ColorStep).samples.size == 6 }
        rule.onNodeWithContentDescription("Sample 1. Tap to remove").performClick()
        rule.runOnIdle { assertEquals(3, (steps(s)[0] as ColorStep).samples.size) }
        rule.onNodeWithContentDescription("Color range").setSlider(70f)
        rule.runOnIdle { assertEquals(0.7f, (steps(s)[0] as ColorStep).range, 1e-4f) }
    }

    @Test
    fun subjectStatusAndHintShowInTheMainRow() {
        val s = rule.showEditor()
        rule.runOnIdle { s.subjects = mapOf(emptyList<Float>() to SubjectMask(1, 1, byteArrayOf(0))) }
        rule.onNodeWithContentDescription("Color").performClick()
        rule.onNodeWithContentDescription("Mask: none. Add").performClick()
        rule.onNodeWithContentDescription("Add subject").performScrollTo().performClick()
        rule.onNodeWithText("Tap what to select. Remove mode: tap what to leave out").assertIsDisplayed()
        rule.runOnIdle { s.subjectStatus = "Downloading AI model… 42%" }
        rule.onNodeWithText("Downloading AI model… 42%").assertIsDisplayed()
        rule.runOnIdle { s.subjectStatus = "Couldn't find the subject: test" }
        rule.onNodeWithText("Couldn't find the subject: test. Tap to retry").performClick()
        rule.runOnIdle { assertEquals(1, s.aiRetry) }
    }
}
