package dev.bloom.editor

import dev.bloom.editor.model.Blend
import dev.bloom.editor.model.BrushStep
import dev.bloom.editor.model.ColorEffect
import dev.bloom.editor.model.ColorParam
import dev.bloom.editor.model.Curves
import dev.bloom.editor.model.GradientStep
import dev.bloom.editor.model.Stroke
import dev.bloom.editor.model.Document
import dev.bloom.editor.model.History
import dev.bloom.editor.model.Layer
import dev.bloom.editor.ui.EditorState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.random.Random

class HistoryTest {
    private fun randomEdit(doc: Document, rnd: Random, id: Long): Document = when {
        doc.layers.isEmpty() || rnd.nextInt(4) == 0 -> doc.copy(layers = doc.layers + Layer(id, effect = ColorEffect().with(ColorParam.Exposure, 10f)))
        rnd.nextBoolean() -> doc.replace(doc.layers.random(rnd).let { it.copy(effect = (it.effect as ColorEffect).with(ColorParam.entries.random(rnd), rnd.nextInt(-100, 100).toFloat())) })
        rnd.nextBoolean() -> doc.move(doc.layers.random(rnd).id, rnd.nextInt(doc.layers.size))
        rnd.nextBoolean() -> doc.replace(doc.layers.random(rnd).let { l ->
            val step = if (rnd.nextBoolean()) BrushStep(listOf(Stroke(listOf(rnd.nextFloat(), rnd.nextFloat()), 0.1f, 0.5f, 1f)))
            else GradientStep(rnd.nextBoolean(), 0f, 0f, rnd.nextFloat(), 1f, add = rnd.nextBoolean())
            l.copy(mask = l.mask.copy(steps = l.mask.steps + step, invert = rnd.nextBoolean()), blend = Blend.entries.random(rnd))
        })
        rnd.nextBoolean() -> doc.replace(doc.layers.random(rnd).let { l ->
            val c = l.effect as ColorEffect
            l.copy(effect = c.copy(curves = c.curves.with(rnd.nextInt(4), listOf(0f, 0f, 0.5f, rnd.nextFloat(), 1f, 1f))))
        })
        else -> doc.replace(doc.layers.random(rnd).let { it.copy(visible = !it.visible) })
    }

    @Test
    fun fiftyEditsUndoAndRedoRoundTrip() {
        val rnd = Random(7)
        var h = History(current = Document())
        val states = mutableListOf(h.current)
        repeat(50) { i ->
            var next = randomEdit(h.current, rnd, i.toLong())
            while (next == h.current) next = randomEdit(h.current, rnd, i.toLong() + 1000)
            h = h.push(next)
            states += h.current
        }
        repeat(50) { h = h.undo() }
        assertEquals(states.first(), h.current)
        assertFalse(h.canUndo)
        repeat(50) { h = h.redo() }
        assertEquals(states.last(), h.current)
    }

    @Test
    fun pushClearsRedoAndCapsHistory() {
        var h = History(current = Document())
        repeat(History.LIMIT + 20) { h = h.push(Document(listOf(Layer(it.toLong())))) }
        assertEquals(History.LIMIT, h.past.size)
        h = h.undo().push(Document())
        assertFalse(h.canRedo)
    }

    @Test
    fun moveKeepsAllLayers() {
        val d = Document(listOf(Layer(1), Layer(2), Layer(3))).move(1, 2)
        assertEquals(listOf(2L, 3L, 1L), d.layers.map { it.id })
    }

    @Test
    fun moveClampsToTheEnds() {
        val d = Document(listOf(Layer(1), Layer(2), Layer(3)))
        assertEquals(listOf(3L, 1L, 2L), d.move(3, -5).layers.map { it.id })
        assertEquals(listOf(2L, 3L, 1L), d.move(1, 9).layers.map { it.id })
    }

    @Test
    fun toolStepsOneGestureEach() {
        val s = EditorState()
        s.openTool()
        val start = s.draft!!
        // a slider drag: many changes, one step when the finger lifts
        for (v in 1..20) s.draft = s.draft!!.copy(effect = ColorEffect().with(ColorParam.Exposure, v.toFloat()))
        s.commitStep()
        val afterDrag = s.draft
        s.commitStep() // a tap that changed nothing adds no step
        s.draft = s.draft!!.copy(opacity = 0.5f)
        s.commitStep()
        assertEquals(2, s.steps.past.size)
        s.undoStep()
        assertEquals(afterDrag, s.draft)
        s.undoStep()
        assertEquals(start, s.draft)
        assertFalse(s.steps.canUndo)
        s.redoStep()
        assertEquals(afterDrag, s.draft)
        // undo with a change not yet recorded undoes that change
        s.draft = s.draft!!.copy(visible = false)
        s.undoStep()
        assertEquals(afterDrag, s.draft)
        s.close()
        assertFalse(s.steps.canUndo)
    }
}
