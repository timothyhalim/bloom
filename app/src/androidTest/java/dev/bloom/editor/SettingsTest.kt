package dev.bloom.editor

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.bloom.editor.model.WarpTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val prefs get() = rule.activity.getSharedPreferences("tools", 0)

    @Test
    fun savedToolSettingsLoadOnStart() {
        val s = rule.showEditor {
            prefs.edit().putFloat("brushSize", 77f).putFloat("softness", 12f).putFloat("flow", 34f).putFloat("warpSize", 99f)
                .putFloat("warpStrength", 66f).putFloat("warpSoftness", 5f).putBoolean("warpReverse", true).putBoolean("maskMore", true)
                .putBoolean("warpMore", true).putBoolean("detailMore", true).putBoolean("cropMore", true).putString("warpTool", "Twirl").commit()
        }
        rule.runOnIdle {
            assertEquals(listOf(77f, 12f, 34f, 99f, 66f, 5f), listOf(s.brushSize, s.softness, s.flow, s.warpSize, s.warpStrength, s.warpSoftness))
            assertTrue(s.warpReverse && s.maskMore && s.warpMore && s.detailMore && s.cropMore)
            assertEquals(WarpTool.Twirl, s.warpTool)
        }
    }

    @Test
    fun changesAreSavedAsTheyHappen() {
        rule.showEditor()
        rule.onNodeWithContentDescription("Liquify").performClick()
        rule.onNodeWithContentDescription("Bloat").performClick()
        rule.onNodeWithContentDescription("More settings").performClick()
        rule.onNodeWithContentDescription("Strength").setSlider(80f)
        rule.waitUntil(5_000) { prefs.getString("warpTool", null) == "Bloat" && prefs.getFloat("warpStrength", 0f) == 80f && prefs.getBoolean("warpMore", false) }
    }

    @Test
    fun anUnknownSavedLiquifyBrushIsIgnored() {
        val s = rule.showEditor { prefs.edit().putString("warpTool", "Smudge").commit() }
        rule.runOnIdle { assertEquals(WarpTool.Push, s.warpTool) }
    }
}
