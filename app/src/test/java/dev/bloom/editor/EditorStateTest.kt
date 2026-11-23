package dev.bloom.editor

import dev.bloom.editor.model.ColorEffect
import dev.bloom.editor.model.ColorParam
import dev.bloom.editor.model.ColorStep
import dev.bloom.editor.model.Crop
import dev.bloom.editor.model.Detail
import dev.bloom.editor.model.DetailEffect
import dev.bloom.editor.model.Document
import dev.bloom.editor.model.GradientStep
import dev.bloom.editor.model.Layer
import dev.bloom.editor.model.LuminanceStep
import dev.bloom.editor.model.SubjectStep
import dev.bloom.editor.ui.EditorState
import dev.bloom.editor.ui.MaskTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorStateTest {
    @Test
    fun openingAToolStartsAFreshDraft() {
        val s = EditorState()
        s.openTool()
        s.param = null
        s.curveChannel = 2
        s.maskMode = true
        s.addStep(GradientStep(false, 0f, 0f, 1f, 1f))
        s.bubble = 1L to 0f
        s.openTool(DetailEffect())
        assertEquals(ColorParam.Exposure, s.param)
        assertEquals(0, s.curveChannel)
        assertFalse(s.maskMode)
        assertEquals(MaskTool.Brush, s.maskTool)
        assertNull(s.stepIndex)
        assertNull(s.bubble)
        assertTrue(s.draft!!.effect is DetailEffect)
        assertNull(s.editingId)
    }

    @Test
    fun applyingAnUnchangedNewLayerAddsNothing() {
        val s = EditorState()
        s.openTool()
        assertFalse(s.dirty)
        s.apply()
        assertTrue(s.doc.layers.isEmpty())
        assertFalse(s.history.canUndo)
        assertNull(s.draft)
    }

    @Test
    fun dirtyMeansTheCropOrTheEditedLayerOrANewEditedLayerChanged() {
        val s = EditorState()
        s.load(null, null, saved = Document(listOf(Layer(1))))
        s.openTool(edit = s.doc.layers[0])
        assertFalse(s.dirty)
        s.draft = s.draft!!.copy(opacity = 0.5f) // an edited layer: any change counts
        assertTrue(s.dirty)
        s.close()
        s.openTool()
        s.draft = s.draft!!.copy(opacity = 0.5f) // a new layer: only a change to the photo counts
        assertFalse(s.dirty)
        s.draft = s.draft!!.copy(effect = ColorEffect().with(ColorParam.Tint, 5f))
        assertTrue(s.dirty)
        s.close()
        s.openCrop()
        assertFalse(s.dirty)
        s.crop = Crop(left = 0.1f)
        assertTrue(s.dirty)
    }

    @Test
    fun blurAndSharpenWithNothingToDoAreNotEdits() {
        assertFalse(Layer(1, effect = DetailEffect()).edited)
        assertFalse(Layer(1, effect = DetailEffect(sharpen = true)).edited)
        assertTrue(Layer(1, effect = DetailEffect(detail = Detail(amount = 1f))).edited)
        assertTrue(Layer(1, effect = DetailEffect(sharpen = true, detail = Detail(clarity = -5f))).edited)
        assertFalse("clarity is Sharpen's only", Layer(1, effect = DetailEffect(detail = Detail(clarity = -5f))).edited)
    }

    @Test
    fun addingAStepSelectsItAndSwitchesToItsTool() {
        val s = EditorState()
        s.openTool()
        s.addingSample = true
        s.addStep(ColorStep(add = false))
        assertEquals(0, s.stepIndex)
        assertEquals(MaskTool.Color, s.maskTool)
        assertFalse(s.addingSample)
        s.addStep(LuminanceStep.Near)
        assertEquals(1, s.stepIndex)
        assertEquals(MaskTool.Luminance, s.maskTool)
        s.maskAdd = false
        s.addGradient()
        assertFalse((s.draft!!.mask.steps[2] as GradientStep).add)
        assertEquals(MaskTool.Gradient, s.maskTool)
    }

    @Test
    fun tappingAStepSelectsItAndTappingAgainDeselects() {
        val s = EditorState()
        s.openTool()
        s.addStep(SubjectStep())
        s.addStep(GradientStep(true, 0f, 0f, 1f, 1f))
        s.selectStep(0)
        assertEquals(0, s.stepIndex)
        assertEquals(MaskTool.Subject, s.maskTool)
        s.selectStep(0)
        assertNull(s.stepIndex)
        s.selectStep(1)
        s.deleteStep(1)
        assertNull(s.stepIndex)
        assertEquals(1, s.draft!!.mask.steps.size)
    }

    @Test
    fun luminanceAndDepthPresetsCoverTheirBands() {
        for ((lum, depth) in listOf(LuminanceStep.Shadows to LuminanceStep.Far, LuminanceStep.Midtones to LuminanceStep.Middle, LuminanceStep.Highlights to LuminanceStep.Near)) {
            assertEquals(lum.copy(depth = true), depth)
            assertTrue(lum.a <= lum.b && lum.b <= lum.c && lum.c <= lum.d)
        }
        assertEquals(0f, LuminanceStep.Shadows.a)
        assertEquals(1f, LuminanceStep.Highlights.d)
    }

    @Test
    fun newLayersGoOnTopAndEditedOnesStayInPlace() {
        val s = EditorState()
        s.load(null, null, saved = Document(listOf(Layer(1), Layer(2))))
        s.openTool(edit = s.doc.layers[0])
        s.draft = s.draft!!.copy(opacity = 0.3f)
        s.apply()
        assertEquals(listOf(1L, 2L), s.doc.layers.map { it.id })
        assertEquals(0.3f, s.doc.layers[0].opacity)
        s.openTool()
        s.draft = s.draft!!.copy(effect = ColorEffect().with(ColorParam.Contrast, 10f))
        s.apply()
        assertEquals(3, s.doc.layers.size)
        assertEquals(s.doc.layers.last().id, 3L)
    }
}
