package dev.bloom.editor

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.rules.ActivityScenarioRule
import dev.bloom.editor.model.Document
import dev.bloom.editor.ui.BloomTheme
import dev.bloom.editor.ui.EditorScreen
import dev.bloom.editor.ui.EditorState
import java.io.File

typealias EditorRule = AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>

/** A 300×400 photo, dark at the top and light at the bottom. */
fun testPhoto(): Bitmap = Bitmap.createBitmap(IntArray(300 * 400) { Color.rgb(it / 300 * 255 / 400, 128, 64) }, 300, 400, Bitmap.Config.ARGB_8888)

/** Stands in for the system pickers: records what each launch asked for and answers with [result]. */
class FakePickers : ActivityResultRegistry() {
    val launched = mutableListOf<Any?>()
    var result: Any? = null

    override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
        launched += input
        @Suppress("UNCHECKED_CAST")
        dispatchResult(requestCode, result as O)
    }
}

/**
 * Shows the editor on a real PhotoView with [testPhoto] open (as a project opened with [doc], when given), or Home
 * when [photo] is false. Saved tool settings, AI settings, keys and projects are cleared first, so tests don't see each other's; [setup]
 * runs after that, before the editor starts. [pickers] stands in for the system pickers.
 */
fun EditorRule.showEditor(doc: Document? = null, photo: Boolean = true, pickers: FakePickers = FakePickers(), setup: () -> Unit = {}): EditorState {
    for (prefs in listOf("tools", "ai", "ai_keys", "settings")) activity.getSharedPreferences(prefs, 0).edit().clear().commit()
    File(activity.filesDir, "projects").deleteRecursively()
    setup()
    val state = EditorState()
    val view = runOnUiThread { PhotoView(activity) }
    val owner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = pickers
    }
    setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { BloomTheme { EditorScreen(view, state) } } }
    if (photo) runOnIdle {
        val bmp = testPhoto()
        state.load(Uri.parse("file:///test.jpg"), bmp, saved = doc)
        view.setImage(bmp)
    }
    waitForIdle()
    return state
}

/** System back, as the gesture or button sends it. */
fun EditorRule.back() = runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }

/** Sets a slider to [value] as a screen reader would. */
fun SemanticsNodeInteraction.setSlider(value: Float) = performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
