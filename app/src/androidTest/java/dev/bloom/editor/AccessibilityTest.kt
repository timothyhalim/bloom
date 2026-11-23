package dev.bloom.editor

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.bloom.editor.model.Document
import dev.bloom.editor.model.GradientStep
import dev.bloom.editor.model.Layer
import dev.bloom.editor.model.Mask
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every control on the main screens has a name a screen reader can say. (Touch target size isn't checked here: Compose
 * widens every pointer target to 48 dp, so layout sizes like M3's 40 dp IconButton say nothing about it.)
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val problems = mutableListOf<String>()

    private fun sweep(screen: String) {
        rule.waitForIdle()
        val controls = hasClickAction() or SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)
        for (n in rule.onAllNodes(controls).fetchSemanticsNodes()) {
            val c = n.config
            val name = (c.getOrNull(SemanticsProperties.ContentDescription).orEmpty() + c.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }).joinToString()
            if (name.isBlank()) problems += "$screen: unnamed control at ${n.boundsInRoot}"
        }
    }

    // a key goes to the focused window, the menu's popup; the activity's back dispatcher would close the photo instead
    private fun closeMenu() = InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)

    @Test
    fun everyControlHasAName() {
        val s = rule.showEditor(photo = false)
        sweep("Home")
        rule.runOnIdle { s.load(android.net.Uri.parse("file:///test.jpg"), testPhoto(), saved = Document(listOf(Layer(1, mask = Mask(listOf(GradientStep(false, 0f, 0f, 1f, 1f))))))) }
        sweep("Editor")
        rule.onNodeWithText("Export").performClick()
        sweep("Export menu")
        closeMenu()
        rule.onNodeWithContentDescription("Color layer, has mask").performClick()
        sweep("Layer bubble")
        rule.onNodeWithText("Normal ▾").performClick()
        sweep("Blend menu")
        closeMenu()
        rule.back()
        rule.onNodeWithContentDescription("Photo layer").performClick()
        sweep("Photo bubble")
        rule.back()
        rule.onNodeWithContentDescription("Color").performClick()
        sweep("Color")
        rule.onNodeWithContentDescription("Curves").performClick()
        sweep("Curves")
        rule.onNodeWithContentDescription("Mask: none. Add").performClick()
        rule.onNodeWithContentDescription("Add gradient").performClick()
        rule.onNodeWithContentDescription("More settings").performClick()
        sweep("Mask")
        rule.runOnIdle { s.close(); s.maskMore = false } // AI shares the mask's More
        for (tool in listOf("Detail", "Liquify", "Crop", "AI")) {
            rule.onNodeWithContentDescription(tool).performClick()
            rule.onNodeWithContentDescription(if (tool == "Crop") "Rotate and perspective" else "More settings").performClick()
            sweep(tool)
            rule.runOnIdle { s.close() }
        }
        assertEquals(emptyList<String>(), problems)
    }
}
