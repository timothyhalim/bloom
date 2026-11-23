package dev.bloom.editor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.bloom.editor.model.Layer

/** One box above the AI bar, the mask brush's: Size, and More for Softness and Flow; while running, what it is doing. */
@Composable
fun EraseRows(state: EditorState, box: Modifier) {
    ToolBox(state.maskMore, { state.maskMore = !state.maskMore }, box, moreRows = { BrushRows(state) }) {
        val busy = state.erasing
        if (busy != null) StatusRow(busy, null, start = 16.dp)
        else MainSlider("Erase", "Size", state.brushSize, 4f..150f) { state.brushSize = it; state.showRing(it, state.softness) }
    }
}

/** The AI bar in Erase mode: back, mask, and the run button (running is applying: no separate ✓). */
@Composable
fun RowScope.EraseBar(state: EditorState, d: Layer, onDiscard: () -> Unit, onErase: () -> Unit) {
    ToolIcon(Icons.Sparkle, "AI. Back to tools", onDiscard)
    Spacer(Modifier.weight(1f))
    MaskButton(d.mask) { state.maskMode = true }
    val ready = d.mask.active && state.erasing == null
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        if (state.erasing != null) CircularProgressIndicator(Modifier.size(22.dp), color = Bloom.Pollen, strokeWidth = 2.dp)
        else IconButton(onErase, enabled = ready) { Icon(Icons.Eraser, "Erase the painted area", tint = if (ready) Bloom.Pollen else Bloom.Outline) }
    }
}
