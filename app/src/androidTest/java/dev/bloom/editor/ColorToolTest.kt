package dev.bloom.editor

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.bloom.editor.model.ColorEffect
import dev.bloom.editor.model.ColorParam
import dev.bloom.editor.model.Curves
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class) // performCustomAccessibilityActionWithLabel
@RunWith(AndroidJUnit4::class)
class ColorToolTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun openColor() = rule.showEditor().also { rule.onNodeWithContentDescription("Color").performClick() }

    private fun color(s: dev.bloom.editor.ui.EditorState) = rule.runOnIdle { s.draft!!.effect as ColorEffect }

    private fun left(desc: String) = rule.onNodeWithContentDescription(desc).getUnclippedBoundsInRoot().left

    @Test
    fun theToolListHasEveryTool() {
        rule.showEditor()
        for (t in listOf("Color", "Detail", "Liquify", "Crop", "AI")) rule.onNodeWithContentDescription(t).assertIsDisplayed()
    }

    @Test
    fun theBarListsEveryParameterWithCurvesBeforeTemp() {
        openColor()
        rule.onNodeWithContentDescription("Exposure 0 stops").assertIsSelected()
        for (p in ColorParam.entries.drop(1)) rule.onNodeWithContentDescription("${p.name} 0").assertExists()
        assertTrue(left("Shadows 0") < left("Curves"))
        assertTrue(left("Curves") < left("Temp 0"))
        rule.onNodeWithContentDescription("Color. Back to tools").assertIsDisplayed()
        rule.onNodeWithContentDescription("Mask: none. Add").assertIsDisplayed()
        rule.onNodeWithContentDescription("Apply as Color layer").assertIsDisplayed()
    }

    @Test
    fun exposureReadsInStopsAndSnapsToATenth() {
        val s = openColor()
        rule.onNodeWithContentDescription("Exposure").setSlider(11.5f) // 0.345 stops
        assertEquals(10f, color(s)[ColorParam.Exposure], 1e-4f)
        rule.onNodeWithText("+0.3").assertIsDisplayed()
        rule.onNodeWithContentDescription("Exposure").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "+0.3 EV"))
        rule.onNodeWithContentDescription("Exposure +0.3 stops").assertExists()
        rule.onNodeWithContentDescription("Exposure").setSlider(-100f)
        rule.onNodeWithText("−3.0").assertIsDisplayed()
    }

    @Test
    fun otherParametersSnapToWholeNumbersWithATrueMinus() {
        val s = openColor()
        rule.onNodeWithContentDescription("Contrast 0").performClick()
        rule.onNodeWithContentDescription("Contrast 0").assertIsSelected()
        rule.onNodeWithText("Contrast").assertIsDisplayed()
        rule.onNodeWithContentDescription("Contrast").setSlider(-12.6f)
        assertEquals(-13f, color(s)[ColorParam.Contrast])
        rule.onNodeWithText("−13").assertIsDisplayed()
        rule.onNodeWithContentDescription("Contrast −13").assertExists()
        rule.onNodeWithContentDescription("Reset Contrast").performClick()
        assertEquals(0f, color(s)[ColorParam.Contrast])
        rule.onNodeWithContentDescription("Contrast 0").assertIsSelected()
    }

    @Test
    fun curvesChipSwapsTheSliderForTheGraph() {
        openColor()
        rule.onNodeWithContentDescription("Curves").performClick()
        rule.onNodeWithContentDescription("Exposure").assertDoesNotExist()
        for (c in listOf("RGB", "R", "G", "B")) rule.onNodeWithContentDescription("$c curve").assertIsDisplayed()
        rule.onNodeWithContentDescription("RGB curve").assertIsSelected()
        rule.onNodeWithContentDescription("RGB tone curve, 0% to 0%, 100% to 100%").assertIsDisplayed()
        rule.onNodeWithContentDescription("Exposure 0 stops").performClick()
        rule.onNodeWithContentDescription("Exposure").assertIsDisplayed()
    }

    @Test
    fun tapAddsAPointOnTheCurveAndDragOffTheTopRemovesIt() {
        val s = openColor()
        rule.onNodeWithContentDescription("Curves").performClick()
        val graph = rule.onNodeWithContentDescription("RGB tone curve", substring = true)
        graph.performTouchInput { click(center) }
        assertEquals(6, color(s).curves.rgb.size)
        rule.onNodeWithContentDescription("Curves, edited").assertExists()
        rule.onNodeWithContentDescription("RGB curve, edited").assertExists()
        graph.performTouchInput { down(center); moveTo(Offset(centerX, centerY - height / 4)); up() }
        assertTrue("dragged up", color(s).curves.rgb[3] > 0.6f)
        graph.performTouchInput { down(Offset(centerX, centerY - height / 4)); moveTo(Offset(centerX, -300f)); up() }
        assertEquals(Curves.LINE, color(s).curves.rgb)
        // end points stay
        graph.performTouchInput { down(Offset(left + 2f, bottom - 2f)); moveTo(Offset(left + 2f, -300f)); up() }
        assertEquals(4, color(s).curves.rgb.size)
    }

    @Test
    fun resetAndEditedMarksAreForTheSelectedChannelOnly() {
        val s = openColor()
        rule.onNodeWithContentDescription("Curves").performClick()
        rule.onNodeWithContentDescription("R curve").performClick()
        rule.onNodeWithContentDescription("R tone curve", substring = true).performCustomAccessibilityActionWithLabel("Raise point at 0%")
        rule.onNodeWithContentDescription("B curve").performClick()
        rule.onNodeWithContentDescription("B tone curve", substring = true).performCustomAccessibilityActionWithLabel("Lower point at 100%")
        rule.onNodeWithContentDescription("R curve, edited").assertExists()
        assertEquals(0.05f, color(s).curves.r[1], 1e-4f)
        assertEquals(0.95f, color(s).curves.b[3], 1e-4f)
        rule.onNodeWithContentDescription("Reset B curve").performClick()
        assertEquals(Curves.LINE, color(s).curves.b)
        assertEquals(0.05f, color(s).curves.r[1], 1e-4f)
    }

    private fun androidx.compose.ui.test.TouchInjectionScope.dp() = 0f // keeps call sites readable: -300f is 100 px above

    private fun Int.dp() = toFloat()
}
