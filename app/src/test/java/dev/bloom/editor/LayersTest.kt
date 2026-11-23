package dev.bloom.editor

import dev.bloom.editor.model.Document
import dev.bloom.editor.model.Group
import dev.bloom.editor.model.History
import dev.bloom.editor.model.Layer
import dev.bloom.editor.ui.EditorState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LayersTest {
    private val ids = { d: Document -> d.layers.map { it.id } }
    private val groupOf = { d: Document -> d.layers.map { it.group } }

    /** Every group's members are next to each other, and every group has members. */
    private fun assertContiguous(d: Document) {
        for (g in d.groups) {
            val at = d.layers.indices.filter { d.layers[it].group == g.id }
            assertTrue("group ${g.id} is empty in $d", at.isNotEmpty())
            assertEquals("group ${g.id} is split in ${groupOf(d)}", at.last() - at.first() + 1, at.size)
        }
        assertTrue(d.layers.all { l -> l.group == null || d.groups.any { it.id == l.group } })
    }

    @Test
    fun renameIsOneUndoStep() {
        var h = History(current = Document(listOf(Layer(1), Layer(2))))
        h = h.push(h.current.replace(h.current.layers[0].copy(name = "Sky")))
        assertEquals("Sky", h.current.layers[0].name)
        assertEquals(1, h.past.size)
        assertEquals("", h.undo().current.layers[0].name)
    }

    @Test
    fun stepSwapsWithTheNeighbourAndStopsAtTheEnds() {
        val d = Document(listOf(Layer(1), Layer(2), Layer(3)))
        assertEquals(listOf(2L, 1L, 3L), ids(d.step(1, up = true)))
        assertEquals(listOf(1L, 3L, 2L), ids(d.step(3, up = false)))
        assertSame(d, d.step(3, up = true))
        assertSame(d, d.step(1, up = false))
    }

    @Test
    fun groupWithBelowMakesAGroupThenJoinsIt() {
        val d = Document(listOf(Layer(1), Layer(2), Layer(3))).groupWithBelow(2, newId = 9)
        assertEquals(listOf(9L, 9L, null), groupOf(d))
        assertEquals(listOf(Group(9)), d.groups)
        val joined = d.groupWithBelow(3, newId = 10)
        assertEquals(listOf(9L, 9L, 9L), groupOf(joined))
        assertEquals(listOf(Group(9)), joined.groups)
        assertSame(joined, joined.groupWithBelow(2, newId = 11)) // both already grouped
        assertSame(d, d.groupWithBelow(1, newId = 11)) // nothing below
    }

    @Test
    fun stepLeavesAGroupAtItsEdgeAndJoinsOneNextToIt() {
        val d = Document(listOf(Layer(1), Layer(2), Layer(3))).groupWithBelow(2, 9) // [1 2] 3
        val left = d.step(2, up = true)
        assertEquals(listOf(1L, 2L, 3L), ids(left))
        assertEquals(listOf(9L, null, null), groupOf(left))
        val back = left.step(2, up = false) // next to the group again: joins it, still in place
        assertEquals(d, back)
        assertEquals(listOf(9L, 9L, 9L), groupOf(d.step(3, up = false)))
        assertEquals(listOf(2L, 1L, 3L), ids(d.step(1, up = true))) // within the group: a swap
    }

    @Test
    fun stepSkipsACollapsedGroup() {
        val d = Document(listOf(Layer(1), Layer(2), Layer(3))).groupWithBelow(2, 9).let { it.replace(it.groups[0].copy(collapsed = true)) }
        val down = d.step(3, up = false)
        assertEquals(listOf(3L, 1L, 2L), ids(down))
        assertEquals(listOf(null, 9L, 9L), groupOf(down))
        assertEquals(d, down.step(3, up = true))
    }

    @Test
    fun stepGroupMovesItsMembersAsABlock() {
        // 1 [2 3] 4 [5 6]
        val d = Document((1L..6L).map { Layer(it) }).groupWithBelow(3, 8).groupWithBelow(6, 9)
        assertEquals(listOf(1L, 4L, 2L, 3L, 5L, 6L), ids(d.stepGroup(8, up = true)))
        assertEquals(listOf(2L, 3L, 1L, 4L, 5L, 6L), ids(d.stepGroup(8, up = false)))
        assertEquals(listOf(1L, 5L, 6L, 2L, 3L, 4L), ids(d.stepGroup(9, up = false).stepGroup(9, up = false)))
        assertSame(d, d.stepGroup(9, up = true))
    }

    @Test
    fun moveIntoAGroupJoinsItAndOutOfItLeaves() {
        val d = Document((1L..4L).map { Layer(it) }).groupWithBelow(3, 9) // 1 [2 3] 4
        val into = d.move(4, 2)
        assertEquals(listOf(1L, 2L, 4L, 3L), ids(into))
        assertEquals(listOf(null, 9L, 9L, 9L), groupOf(into))
        val out = d.move(2, 0)
        assertEquals(listOf(2L, 1L, 3L, 4L), ids(out))
        assertEquals(listOf(null, null, 9L, null), groupOf(out))
    }

    @Test
    fun ungroupKeepsTheLayersInPlace() {
        val d = Document((1L..3L).map { Layer(it) }).groupWithBelow(2, 9).ungroup(9)
        assertEquals(listOf(1L, 2L, 3L), ids(d))
        assertEquals(listOf(null, null, null), groupOf(d))
        assertTrue(d.groups.isEmpty())
    }

    @Test
    fun deletingInAGroupKeepsItUntilItsLastMemberGoes() {
        val d = Document((1L..3L).map { Layer(it) }).groupWithBelow(2, 9)
        val one = d.remove(1)
        assertEquals(listOf(Group(9)), one.groups)
        assertTrue(one.remove(2).groups.isEmpty())
        assertTrue(d.step(2, up = true).step(1, up = true).groups.isEmpty()) // both stepped out
    }

    @Test
    fun randomOperationsKeepGroupsContiguous() {
        val rnd = Random(7)
        var d = Document((1L..8L).map { Layer(it) })
        var nextId = 100L
        repeat(2000) {
            val id = d.layers.random(rnd).id
            d = when (rnd.nextInt(7)) {
                0 -> d.move(id, rnd.nextInt(d.layers.size))
                1, 2 -> d.step(id, rnd.nextBoolean())
                3 -> d.groupWithBelow(id, nextId++)
                4 -> d.groups.randomOrNull(rnd)?.let { g -> if (rnd.nextInt(4) == 0) d.ungroup(g.id) else d.stepGroup(g.id, rnd.nextBoolean()) } ?: d
                5 -> d.groups.randomOrNull(rnd)?.let { d.replace(it.copy(collapsed = !it.collapsed)) } ?: d
                else -> if (d.layers.size > 4) d.remove(id) else d.copy(layers = d.layers + Layer(nextId++))
            }
            assertContiguous(d)
        }
    }

    @Test
    fun renderedAppliesGroupVisibilityAndOpacity() {
        val d = Document(listOf(Layer(1, opacity = 0.5f), Layer(2, visible = false), Layer(3))).groupWithBelow(2, 9)
        assertSame(d.layers[0], d.rendered()[0]) // a plain group changes nothing
        val faded = d.replace(d.groups[0].copy(opacity = 0.5f)).rendered()
        assertEquals(listOf(0.25f, 0.5f, 1f), faded.map { it.opacity })
        val hidden = d.replace(d.groups[0].copy(visible = false)).rendered()
        assertEquals(listOf(false, false, true), hidden.map { it.visible })
    }

    @Test
    fun hidingAllLayersIsAViewSettingThatKeepsTheDraft() {
        val s = EditorState()
        s.load(null, null, saved = Document(listOf(Layer(1), Layer(2))).groupWithBelow(2, 9))
        s.opacityPreview = 9L to 0.5f // a group opacity drag previews on its members
        assertEquals(listOf(0.5f, 0.5f), s.rendered().map { it.opacity })
        s.opacityPreview = null
        s.layersHidden = true
        assertEquals(emptyList<Layer>(), s.rendered())
        s.openTool()
        assertEquals(listOf(s.draft), s.rendered())
        assertTrue(s.draft!!.id > 9) // new ids don't reuse a group's
        assertTrue(!s.history.canUndo)
    }
}
