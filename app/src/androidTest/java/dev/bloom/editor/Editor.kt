package dev.bloom.editor

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.rules.ActivityScenarioRule
import dev.bloom.editor.model.Document
import dev.bloom.editor.ui.BloomTheme
import dev.bloom.editor.ui.EditorScreen
import dev.bloom.editor.ui.EditorState
import java.io.File

typealias EditorRule = AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>

/** A 300×400 photo, dark at the top and light at the bottom. */
fun testPhoto(): Bitmap = Bitmap.createBitmap(IntArray(300 * 400) { Color.rgb(it / 300 * 255 / 400, 128, 64) }, 300, 400, Bitmap.Config.ARGB_8888)

/**
 * Shows the editor on a real PhotoView with [testPhoto] open (as a project opened with [doc], when given), or Home
 * when [photo] is false. Saved tool settings and projects are cleared first, so tests don't see each other's.
 */
fun EditorRule.showEditor(doc: Document? = null, photo: Boolean = true): EditorState {
    activity.getSharedPreferences("tools", 0).edit().clear().commit()
    File(activity.filesDir, "projects").deleteRecursively()
    val state = EditorState()
    val view = runOnUiThread { PhotoView(activity) }
    setContent { BloomTheme { EditorScreen(view, state) } }
    if (photo) runOnIdle {
        val bmp = testPhoto()
        state.load(Uri.parse("file:///test.jpg"), bmp, saved = doc)
        view.setImage(bmp)
    }
    waitForIdle()
    return state
}

/** Sets a slider to [value] as a screen reader would. */
fun SemanticsNodeInteraction.setSlider(value: Float) = performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
