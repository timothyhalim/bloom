package dev.bloom.editor

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExportMenuTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val labels = mapOf(
        ImageIo.Format.JPEG to "JPEG", ImageIo.Format.WEBP to "WebP", ImageIo.Format.HEIC to "HEIC", ImageIo.Format.AVIF to "AVIF",
        ImageIo.Format.PNG to "16-bit PNG (larger, for further editing)",
    )

    @Test
    fun theMenuListsTheFormatsThisPhoneCanWrite() {
        rule.showEditor()
        rule.onNodeWithText("Export").performClick()
        for ((f, label) in labels) {
            if (f.available) rule.onNodeWithText(label).assertIsDisplayed() else rule.onNodeWithText(label).assertDoesNotExist()
        }
    }

    @Test
    fun sizeCyclesInPlaceAndTheMenuStaysOpen() {
        rule.showEditor()
        rule.onNodeWithText("Export").performClick()
        rule.onNodeWithText("Size: 100%").performClick()
        rule.onNodeWithText("Size: 50%").performClick()
        rule.onNodeWithText("Size: 25%").performClick()
        rule.onNodeWithText("Size: 100%").assertIsDisplayed()
        rule.onNodeWithText("JPEG").assertIsDisplayed()
    }

    @Test
    fun aFormatAsksWhereToSaveWithATimestampedName() {
        val pickers = FakePickers() // Save-as cancelled: nothing is written
        rule.showEditor(pickers = pickers)
        rule.onNodeWithText("Export").performClick()
        rule.onNodeWithText("Size: 100%").performClick() // kept for the session
        rule.onNodeWithText("16-bit PNG (larger, for further editing)").performClick()
        rule.onNodeWithText("JPEG").assertDoesNotExist()
        rule.runOnIdle { assertTrue(pickers.launched.single() as String, Regex("bloom_\\d+\\.png").matches(pickers.launched.single() as String)) }
        rule.onNodeWithText("Export").performClick()
        rule.onNodeWithText("Size: 50%").assertIsDisplayed()
        rule.onNodeWithText("JPEG").performClick()
        rule.runOnIdle { assertTrue(Regex("bloom_\\d+\\.jpg").matches(pickers.launched.last() as String)) }
    }

    @Test
    fun exportIsOffWhileAToolIsOpen() {
        rule.showEditor()
        rule.onNodeWithText("Export").assertIsEnabled()
        rule.onNodeWithContentDescription("Crop").performClick()
        rule.onNodeWithText("Export").assertIsNotEnabled()
    }

    @Test
    fun exportWritesTheFileAndSaysSaved() {
        val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val src = java.io.File(ctx.cacheDir, "export_src.jpg").apply { outputStream().use { testPhoto().compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) } }
        val dst = java.io.File(ctx.cacheDir, "export_dst.jpg").apply { delete() }
        val s = rule.showEditor(pickers = FakePickers().apply { result = android.net.Uri.fromFile(dst) })
        rule.runOnIdle { s.source = android.net.Uri.fromFile(src) }
        rule.onNodeWithText("Export").performClick()
        rule.onNodeWithText("JPEG").performClick()
        rule.waitUntil(15_000) { runCatching { rule.onNodeWithText("Saved").assertExists() }.isSuccess }
        assertTrue(dst.length() > 0)
    }
}
