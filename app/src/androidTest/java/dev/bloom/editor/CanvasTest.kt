package dev.bloom.editor

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.bloom.editor.model.BlurType
import dev.bloom.editor.model.Crop
import dev.bloom.editor.model.Detail
import dev.bloom.editor.model.DetailEffect
import dev.bloom.editor.model.Document
import dev.bloom.editor.model.GradientStep
import dev.bloom.editor.model.Layer
import dev.bloom.editor.model.WarpEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Touches on the photo: compare, and what each tool does with one finger. */
@RunWith(AndroidJUnit4::class)
class CanvasTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val photo get() = rule.onNodeWithTag("photo")

    @Test
    fun holdingCompareShowsTheOriginalWithTheCropStill() {
        val s = rule.showEditor(Document(listOf(Layer(1)), crop = Crop(left = 0.2f)))
        val compare = rule.onNodeWithContentDescription("Compare. Hold to see the original")
        compare.performTouchInput { down(center) }
        rule.runOnIdle {
            assertTrue(s.comparing)
            assertTrue(s.rendered().isEmpty())
            assertFalse("only layers drop", s.doc.crop.isIdentity)
        }
        compare.performTouchInput { up() }
        rule.runOnIdle {
            assertFalse(s.comparing)
            assertEquals(1, s.rendered().size)
        }
    }

    @Test
    fun compareDoesNothingWithoutAPhoto() {
        val s = rule.showEditor(photo = false)
        rule.onNodeWithContentDescription("Compare. Hold to see the original").performTouchInput { down(center) }
        rule.runOnIdle { assertFalse(s.comparing) }
    }

    @Test
    fun eachLiquifyTouchIsOneStroke() {
        val s = rule.showEditor()
        rule.onNodeWithContentDescription("Liquify").performClick()
        photo.performTouchInput { swipe(Offset(width * 0.3f, height * 0.3f), Offset(width * 0.5f, height * 0.3f)) }
        photo.performTouchInput { swipe(Offset(width * 0.3f, height * 0.5f), Offset(width * 0.5f, height * 0.5f)) }
        rule.runOnIdle {
            val strokes = (s.draft!!.effect as WarpEffect).strokes
            assertEquals(2, strokes.size)
            assertTrue("the drag adds points", strokes[0].points.size > 2)
        }
    }

    @Test
    fun dragInsideACropMovesIt() {
        val s = rule.showEditor()
        rule.onNodeWithContentDescription("Crop").performClick()
        rule.onNodeWithText("1:1").performClick() // a square in a 3:4 photo: room to move up and down
        val before = rule.runOnIdle { s.crop!! }
        photo.performTouchInput { swipe(center, center + Offset(0f, height * 0.1f)) }
        rule.runOnIdle {
            val after = s.crop!!
            assertTrue(after.top > before.top)
            assertEquals(before.bottom - before.top, after.bottom - after.top, 1e-4f)
            assertEquals(before.left, after.left, 1e-4f)
        }
    }

    @Test
    fun aTapMovesTheLensFocus() {
        val s = rule.showEditor()
        rule.onNodeWithContentDescription("Detail").performClick()
        rule.runOnIdle {
            s.depth = SubjectMask(1, 1, byteArrayOf(0)) // keeps the depth model from downloading
            s.draft = s.draft!!.copy(effect = DetailEffect(detail = Detail(type = BlurType.Lens, amount = 50f)))
        }
        photo.performTouchInput { click(Offset(width * 0.5f, height * 0.2f)) }
        rule.runOnIdle {
            val d = (s.draft!!.effect as DetailEffect).detail
            assertTrue("moved up from the middle: ${d.focusY}", d.focusY < 0.45f)
        }
    }

    @Test
    fun touchingAwayFromTheHandlesDrawsANewGradientLine() {
        val s = rule.showEditor()
        rule.onNodeWithContentDescription("Color").performClick()
        rule.onNodeWithContentDescription("Mask: none. Add").performClick()
        rule.onNodeWithContentDescription("Add gradient").performClick()
        val before = rule.runOnIdle { s.draft!!.mask.steps.single() as GradientStep }
        photo.performTouchInput { swipe(Offset(width * 0.2f, height * 0.7f), Offset(width * 0.25f, height * 0.4f)) }
        rule.runOnIdle {
            val g = s.draft!!.mask.steps.single() as GradientStep
            assertNotEquals(before, g)
            assertTrue("starts at the finger, low on the photo", g.y0 > 0.6f)
            assertTrue("ends where it lifted", g.y1 < g.y0)
        }
    }
}
