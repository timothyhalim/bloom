package dev.bloom.editor

import dev.bloom.editor.model.Blend
import dev.bloom.editor.model.BrushStep
import dev.bloom.editor.model.ColorParam
import dev.bloom.editor.model.Curves
import dev.bloom.editor.model.GradientStep
import dev.bloom.editor.model.Stroke
import dev.bloom.editor.model.Document
import dev.bloom.editor.model.History
import dev.bloom.editor.model.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.random.Random

class HistoryTest {
    private fun randomEdit(doc: Document, rnd: Random, id: Long): Document = when {
        doc.layers.isEmpty() || rnd.nextInt(4) == 0 -> doc.copy(layers = doc.layers + Layer(id).with(ColorParam.Exposure, 10f))
        rnd.nextBoolean() -> doc.replace(doc.layers.random(rnd).let { it.with(ColorParam.entries.random(rnd), rnd.nextInt(-100, 100).toFloat()) })
        rnd.nextBoolean() -> doc.move(doc.layers.random(rnd).id, rnd.nextInt(doc.layers.size))
        rnd.nextBoolean() -> doc.replace(doc.layers.random(rnd).let { l ->
            val step = if (rnd.nextBoolean()) BrushStep(listOf(Stroke(listOf(rnd.nextFloat(), rnd.nextFloat()), 0.1f, 0.5f, 1f)))
            else GradientStep(rnd.nextBoolean(), 0f, 0f, rnd.nextFloat(), 1f, add = rnd.nextBoolean())
            l.copy(mask = l.mask.copy(steps = l.mask.steps + step, invert = rnd.nextBoolean()), blend = Blend.entries.random(rnd))
        })
        rnd.nextBoolean() -> doc.replace(doc.layers.random(rnd).let { l ->
            l.copy(curves = l.curves.with(rnd.nextInt(4), listOf(0f, 0f, 0.5f, rnd.nextFloat(), 1f, 1f)))
        })
        else -> doc.replace(doc.layers.random(rnd).let { it.copy(visible = !it.visible) })
    }

    @Test
    fun fiftyEditsUndoAndRedoRoundTrip() {
        val rnd = Random(7)
        var h = History()
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
        var h = History()
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
}
