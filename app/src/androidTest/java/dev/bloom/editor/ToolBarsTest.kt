package dev.bloom.editor

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.bloom.editor.model.BlurType
import dev.bloom.editor.model.BrushStep
import dev.bloom.editor.model.Crop
import dev.bloom.editor.model.Detail
import dev.bloom.editor.model.DetailEffect
import dev.bloom.editor.model.Document
import dev.bloom.editor.model.Layer
import dev.bloom.editor.model.Mask
import dev.bloom.editor.model.Stroke
import dev.bloom.editor.model.WarpTool
import dev.bloom.editor.ui.EditorState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ToolBarsTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun open(tool: String, doc: Document? = null) = rule.showEditor(doc).also { rule.onNodeWithContentDescription(tool).performClick() }

    private fun detail(s: EditorState) = rule.runOnIdle { (s.draft!!.effect as DetailEffect) }

    private fun valueOf(desc: String, value: String) = rule.onNodeWithContentDescription(desc).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value))

    @Test
    fun detailOpensABlurLayerAndSwitchesToSharpenWithFreshSettings() {
        val s = open("Detail")
        rule.onNodeWithContentDescription("Blur").assertIsSelected()
        rule.onNodeWithContentDescription("Detail. Back to tools").assertIsDisplayed()
        rule.onNodeWithContentDescription("Gaussian Blur").setSlider(40.4f)
        assertEquals(40f, detail(s).detail.amount)
        rule.onNodeWithContentDescription("Sharpen").performClick()
        assertEquals(DetailEffect(sharpen = true), detail(s))
        rule.onNodeWithContentDescription("Sharpen Amount").assertIsDisplayed()
        rule.onNodeWithContentDescription("Apply as Sharpen layer").assertIsDisplayed()
    }

    @Test
    fun anAppliedBlurLayerKeepsItsKind() {
        val blur = Layer(1, effect = DetailEffect(detail = Detail(amount = 20f)))
        val s = rule.showEditor(Document(listOf(blur)))
        rule.runOnIdle { s.openTool(edit = blur) }
        rule.onNodeWithContentDescription("Sharpen").performClick()
        assertFalse(detail(s).sharpen)
    }

    @Test
    fun blurMoreHasTheTypeRowAndEachTypesSliders() {
        val s = open("Detail")
        rule.runOnIdle { s.depth = SubjectMask(1, 1, byteArrayOf(0)) } // Lens needs depth; this keeps the model from downloading
        rule.onNodeWithContentDescription("More settings").performClick()
        rule.onNodeWithContentDescription("Less").assertIsDisplayed()
        rule.onNodeWithContentDescription("Gaussian blur").assertIsSelected()
        rule.onNodeWithContentDescription("Motion blur").performClick()
        rule.onNodeWithContentDescription("Angle").setSlider(90f)
        assertEquals(Detail(type = BlurType.Motion, angle = 90f), detail(s).detail)
        rule.onNodeWithContentDescription("Motion Blur").assertIsDisplayed()
        rule.onNodeWithContentDescription("Lens blur").performClick()
        valueOf("Range", "10")
        valueOf("Highlights", "30")
        rule.onNodeWithContentDescription("Angle").assertDoesNotExist()
        rule.onNodeWithContentDescription("Focus point. Tap the photo to focus elsewhere").assertExists()
        rule.onNodeWithContentDescription("Zoom blur").performClick()
        rule.onNodeWithContentDescription("Range").assertDoesNotExist()
        rule.onNodeWithContentDescription("Focus point. Tap the photo to focus elsewhere").assertExists()
    }

    @Test
    fun sharpenMoreHasRadiusAndClarity() {
        val s = open("Detail")
        rule.onNodeWithContentDescription("Sharpen").performClick()
        rule.onNodeWithContentDescription("More settings").performClick()
        valueOf("Radius", "30")
        rule.onNodeWithContentDescription("Clarity").setSlider(-40f)
        assertEquals(-40f, detail(s).detail.clarity)
        rule.onNodeWithContentDescription("Gaussian blur").assertDoesNotExist()
    }

    @Test
    fun liquifyHasFiveBrushesSizeAndMore() {
        val s = open("Liquify")
        for (t in WarpTool.entries) rule.onNodeWithContentDescription(t.name).assertIsDisplayed()
        rule.onNodeWithContentDescription("Push").assertIsSelected()
        rule.onNodeWithContentDescription("Push Size").setSlider(200f)
        rule.runOnIdle { assertEquals(150f, s.warpSize) }
        rule.onNodeWithContentDescription("More settings").performClick()
        rule.onNodeWithContentDescription("Strength").setSlider(0f)
        rule.runOnIdle { assertEquals(5f, s.warpStrength) }
        rule.onNodeWithContentDescription("Softness").assertIsDisplayed()
        rule.onNodeWithContentDescription("Clockwise").assertDoesNotExist()
        rule.onNodeWithContentDescription("Twirl").performClick()
        rule.onNodeWithContentDescription("Twirl Size").assertIsDisplayed()
        rule.onNodeWithContentDescription("Clockwise").assertIsSelected()
        rule.onNodeWithContentDescription("Counterclockwise").performClick()
        rule.runOnIdle { assertTrue(s.warpReverse) }
        rule.onNodeWithContentDescription("Apply as Liquify layer").assertIsDisplayed()
    }

    @Test
    fun cropBarHasPresetsTurnFlipResetAndApply() {
        val s = open("Crop")
        for (p in listOf("Free", "Original", "1:1", "4:5", "3:2", "16:9")) rule.onNodeWithText(p).assertIsDisplayed()
        rule.onNodeWithText("Free").assertIsSelected()
        rule.onNodeWithContentDescription("Reset crop").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Flip horizontally").performClick()
        rule.runOnIdle { assertTrue(s.crop!!.flip) }
        rule.onNodeWithContentDescription("Reset crop").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(Crop(), s.crop) }
        rule.onNodeWithContentDescription("Rotate 90° clockwise").performClick()
        rule.runOnIdle { assertEquals(1, s.crop!!.turns) }
        rule.onNodeWithContentDescription("Crop. Back to tools").assertIsDisplayed()
    }

    @Test
    fun aPresetTakesTheCropsOrientationAndTappingItAgainTurnsIt() {
        val s = open("Crop") // the photo is portrait, 3:4
        val ratio = { rule.runOnIdle { s.crop!!.size(0.75f).let { (w, h) -> w / h } } }
        rule.onNodeWithText("4:5").performClick()
        assertEquals(0.8f, ratio(), 1e-3f)
        rule.onNodeWithText("4:5").assertIsSelected() // reads as the crop is: 4 wide, 5 high
        rule.onNodeWithText("4:5").performClick()
        assertEquals(1.25f, ratio(), 1e-3f)
        rule.onNodeWithText("5:4").assertIsSelected()
        rule.onNodeWithText("3:2").performClick() // a new preset follows the current orientation
        assertEquals(1.5f, ratio(), 1e-3f)
        rule.onNodeWithText("Original").performClick()
        assertEquals(0.75f, ratio(), 1e-3f)
        rule.onNodeWithText("Free").performClick()
        rule.runOnIdle { assertNull(s.cropRatio) }
        assertEquals(0.75f, ratio(), 1e-3f)
    }

    @Test
    fun cropMoreHasRotateInTenthsAndKeystones() {
        val s = open("Crop")
        rule.onNodeWithContentDescription("Rotate and perspective").performClick()
        valueOf("Rotate", "0.0°")
        rule.onNodeWithContentDescription("Rotate").setSlider(5.24f)
        rule.runOnIdle { assertEquals(5.2f, s.crop!!.angle, 1e-4f) }
        rule.onNodeWithText("5.2°").assertIsDisplayed()
        rule.onNodeWithContentDescription("Vertical").setSlider(-20.4f)
        rule.onNodeWithContentDescription("Horizontal").setSlider(30f)
        rule.runOnIdle {
            assertEquals(-20f, s.crop!!.vertical)
            assertEquals(30f, s.crop!!.horizontal)
            assertFalse("the crop shrinks to fit", s.crop!!.left == 0f && s.crop!!.right == 1f)
        }
    }

    @Test
    fun aiBarErasesOnlyOnceThereIsAMask() {
        val s = open("AI")
        rule.onNodeWithContentDescription("AI. Back to tools").assertIsDisplayed()
        rule.onNodeWithContentDescription("Erase the painted area").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Erase Size").setSlider(80f)
        rule.runOnIdle { assertEquals(80f, s.brushSize) }
        rule.onNodeWithContentDescription("More settings").performClick()
        rule.onNodeWithContentDescription("Softness").assertIsDisplayed()
        rule.onNodeWithContentDescription("Flow").assertIsDisplayed()
        rule.runOnIdle { s.setMask(Mask(listOf(BrushStep(listOf(Stroke(listOf(0.5f, 0.5f), 0.1f, 0f, 1f)))))) }
        rule.onNodeWithContentDescription("Erase the painted area").assertIsEnabled()
        rule.onNodeWithContentDescription("Mask: 1 steps. Edit").assertIsDisplayed()
        rule.runOnIdle { s.erasing = "Erasing…" }
        rule.onNodeWithText("Erasing…").assertIsDisplayed()
        rule.onNodeWithContentDescription("Erase the painted area").assertDoesNotExist()
    }
}
