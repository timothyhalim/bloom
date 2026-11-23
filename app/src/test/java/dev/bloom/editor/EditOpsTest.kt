package dev.bloom.editor

import dev.bloom.editor.ai.AiError
import dev.bloom.editor.ai.EditOp
import dev.bloom.editor.ai.EditOps
import dev.bloom.editor.model.BlurType
import dev.bloom.editor.model.ColorEffect
import dev.bloom.editor.model.ColorParam
import dev.bloom.editor.model.Detail
import dev.bloom.editor.model.DetailEffect
import dev.bloom.editor.model.Document
import dev.bloom.editor.model.GradientStep
import dev.bloom.editor.model.Layer
import dev.bloom.editor.model.LuminanceStep
import dev.bloom.editor.model.Mask
import dev.bloom.editor.model.SubjectStep
import dev.bloom.editor.ui.EditorState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EditOpsTest {
    private fun fixture(name: String) = javaClass.getResource("/ai/$name")!!.readText()

    private val warm = Layer(1, "Sky", ColorEffect().with(ColorParam.Temp, 12f), mask = Mask(listOf(GradientStep(false, 0.5f, 0.2f, 0.5f, 0.6f))))
    private val blur = Layer(2, effect = DetailEffect(detail = Detail(amount = 20f)), visible = false)
    private val doc = Document(listOf(warm, blur))

    @Test
    fun cleanJson() {
        val p = EditOps.parse(fixture("ask_clean.json"), doc)
        assertEquals(
            listOf(
                EditOp.AddColorLayer(mapOf(ColorParam.Temp to 12f, ColorParam.Exposure to -8f), listOf(SubjectStep())),
                EditOp.AddBlur(BlurType.Lens, 30f, listOf(SubjectStep(background = true))),
            ),
            p.ops,
        )
        assertTrue(p.notes.isEmpty())
    }

    @Test
    fun jsonInProseACodeFenceAndAfterThinking() {
        val p = EditOps.parse(fixture("ask_prose.txt"), doc)
        assertEquals(
            listOf(EditOp.AddColorLayer(mapOf(ColorParam.Temp to 25f, ColorParam.Saturation to 10f), listOf(GradientStep(false, 0.5f, 0.2f, 0.5f, 0.6f), SubjectStep(add = false)))),
            p.ops,
        )
        // a bare array is read too
        assertEquals(1, EditOps.parse("""Here: [{"op":"add_blur","amount":5}] done""", doc).ops.size)
    }

    @Test
    fun unknownOpsParamsLayersAndMasksAreDroppedWithANote() {
        val p = EditOps.parse(fixture("ask_unknown.json"), doc)
        assertEquals(
            listOf(EditOp.AddColorLayer(mapOf(ColorParam.Contrast to 15f), emptyList()), EditOp.AddMaskStep(1, LuminanceStep.Highlights)),
            p.ops,
        )
        assertEquals(
            listOf(
                "Skipped an unknown change: rotate",
                "Skipped an unknown setting: Warmth",
                "Skipped an unknown mask: sky",
                "Skipped a change to a layer that doesn't exist",
                "Skipped an unknown setting: Hue",
                "Skipped a change that isn't an object",
            ),
            p.notes,
        )
    }

    @Test
    fun valuesAreClampedToTheirRange() {
        val p = EditOps.parse(fixture("ask_out_of_range.json"), doc)
        assertEquals(
            listOf(
                EditOp.AddColorLayer(mapOf(ColorParam.Temp to 100f, ColorParam.Exposure to -100f, ColorParam.Tint to 13f), emptyList()),
                EditOp.AddBlur(BlurType.Motion, 100f, emptyList()),
                EditOp.SetParam(2, EditOp.AMOUNT, 0f),
            ),
            p.ops,
        )
    }

    @Test
    fun atMostTenOps() {
        val p = EditOps.parse(fixture("ask_many.json"), doc)
        assertEquals(EditOps.MAX_OPS, p.ops.size)
        assertEquals(listOf("Only the first 10 changes are used"), p.notes)
    }

    @Test
    fun noJsonOrNoOpsIsAnError() {
        for (reply in listOf("I can't do that.", "{\"answer\": 42}", "{broken")) try {
            EditOps.parse(reply, doc)
            fail(reply)
        } catch (e: AiError) {
        }
    }

    @Test
    fun aRemoveFirstMaskOrARemoveFromNothingIsRefused() {
        val p = EditOps.parse(
            """{"ops":[{"op":"add_color_layer","params":{"Temp":5},"mask":[{"type":"subject","add":false}]},
               {"op":"add_mask_step","layer":2,"step":{"type":"subject","add":false}},
               {"op":"add_mask_step","layer":1,"step":{"type":"subject","add":false}}]}""",
            doc,
        )
        assertEquals(listOf(EditOp.AddMaskStep(1, SubjectStep(add = false))), p.ops)
        assertEquals(2, p.notes.size)
    }

    @Test
    fun refiningALayerKeepsOnlyChangesToIt() {
        val reply = """{"ops":[
            {"op":"set_param","layer":1,"param":"temp","value":6},
            {"op":"set_param","layer":2,"param":"Amount","value":50},
            {"op":"add_color_layer","params":{"Temp":5}},
            {"op":"add_blur","amount":10},
            {"op":"add_mask_step","layer":1,"step":{"type":"subject"}}]}"""
        val p = EditOps.parse(reply, doc, target = 1)
        assertEquals(listOf(EditOp.SetParam(1, "Temp", 6f), EditOp.AddMaskStep(1, SubjectStep())), p.ops)
        assertEquals(
            listOf("Skipped a change to another layer", "Skipped a new layer: only this layer changes here", "Skipped a new layer: only this layer changes here"),
            p.notes,
        )
        // the message names only that layer
        val m = EditOps.message("a bit less orange", doc, target = 1)
        assertTrue(m.contains("Change only layer 1") && m.contains("\"Temp\":12") && !m.contains("\"id\":2"))
    }

    @Test
    fun layerSummary() {
        assertEquals(
            """[{"id":1,"kind":"Color","name":"Sky","params":{"Temp":12},"mask":["gradient"]},{"id":2,"kind":"Blur","hidden":true,"type":"gaussian","Amount":20}]""",
            EditOps.summary(doc.layers),
        )
        val m = EditOps.message(" make it warmer ", doc)
        assertTrue(m.startsWith("Request: make it warmer\nLayers, bottom to top: [{\"id\":1"))
    }

    @Test
    fun applyAddsLayersOnTopAndChangesOthersAndLabelsReadWell() {
        val ops = EditOps.parse(
            """{"ops":[{"op":"add_color_layer","params":{"Temp":12},"mask":[{"type":"subject"}]},
               {"op":"set_param","layer":1,"param":"Temp","value":-5},
               {"op":"add_mask_step","layer":2,"step":{"type":"background"}},
               {"op":"add_blur","type":"gaussian","amount":30,"mask":[{"type":"background"},{"type":"luminance","range":"shadows","add":false}]}]}""",
            doc,
        ).ops
        val d = EditOps.apply(doc, ops, firstId = 7)
        assertEquals(listOf(1L, 2L, 7L, 8L), d.layers.map { it.id })
        assertEquals(-5f, (d.layers[0].effect as ColorEffect)[ColorParam.Temp])
        assertEquals(listOf(SubjectStep(background = true)), d.layers[1].mask.steps)
        assertEquals(12f, (d.layers[2].effect as ColorEffect)[ColorParam.Temp])
        assertEquals(30f, (d.layers[3].effect as DetailEffect).detail.amount)
        assertEquals(
            listOf("Color layer · Temp +12 · mask: Subject", "Sky · Temp → −5", "Blur · mask: + Background", "Blur layer · Gaussian 30 · mask: Background, not Shadows"),
            ops.map { EditOps.label(it, doc) },
        )
    }

    @Test
    fun askPreviewsInTheDraftAndAppliesAsOneUndoStep() {
        val s = EditorState()
        s.load(null, null, saved = doc)
        s.openTool(dev.bloom.editor.model.RetouchEffect())
        s.asked = EditOps.parse(fixture("ask_clean.json"), s.doc)
        assertTrue(s.dirty)
        assertEquals(listOf(1L, 2L, 4L, 5L), s.rendered().map { it.id }.filter { it != s.draft!!.id })
        s.askOff = setOf(1) // untick the blur
        assertEquals(listOf(1L, 2L, 4L), s.rendered().map { it.id }.filter { it != s.draft!!.id })
        s.applyAsk()
        assertEquals(3, s.doc.layers.size)
        assertEquals(null, s.draft)
        assertEquals(null, s.asked)
        s.history = s.history.undo()
        assertEquals(doc, s.doc)
        assertFalse(s.history.canUndo)
        // new ids don't clash with the applied ones
        assertTrue(s.newId() > 5)
    }
}
