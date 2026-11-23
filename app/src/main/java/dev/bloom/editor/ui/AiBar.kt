package dev.bloom.editor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bloom.editor.ai.Capability
import dev.bloom.editor.ai.Choice
import dev.bloom.editor.model.Layer

/** The AI tool's modes in bar order; each shows only when some provider can run it. Background joins with M8.5. */
val AI_MODES = listOf(Capability.ERASE)

private val Capability.icon: ImageVector get() = when (this) {
    Capability.ERASE -> Icons.Eraser
    else -> Icons.Sparkle
}

/** One box above the AI bar with the mode's settings; while a job runs, what it is doing (and Cancel when it runs off the phone). */
@Composable
fun AiRows(state: EditorState, mode: Capability, box: Modifier) {
    val busy = state.running
    if (busy != null) Row(box.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusRow(busy, null, start = 16.dp)
        state.runCancel?.let { TextButton(it) { Text("Cancel", color = Bloom.Pollen) } }
    }
    else when (mode) {
        else -> ToolBox(state.maskMore, { state.maskMore = !state.maskMore }, box, moreRows = { BrushRows(state) }) {
            MainSlider("Erase", "Size", state.brushSize, 4f..150f) { state.brushSize = it; state.showRing(it, state.softness) }
        }
    }
}

/**
 * The AI bar: back, the modes, the mask (modes that paint one), and the split run button: tap runs [mode] with
 * [choice]; ▾ lists every provider · model in [options] that can run it, plus AI settings.
 */
@Composable
fun RowScope.AiBar(
    state: EditorState, d: Layer, modes: List<Capability>, mode: Capability, choice: Choice?, options: List<Choice>,
    onDiscard: () -> Unit, onRun: () -> Unit, onPick: (Choice) -> Unit, onSettings: () -> Unit,
) {
    val idle = state.running == null
    ToolIcon(Icons.Sparkle, "AI. Back to tools", onDiscard)
    Row(Modifier.weight(1f).scrollFade(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        for (m in modes) ParamChip(m.icon, m.label, false, m == mode) { if (idle) state.aiMode = m }
    }
    MaskButton(d.mask) { if (idle) state.maskMode = true }
    val ready = idle && choice != null && d.mask.active
    val run = "Erase the painted area"
    Column(
        Modifier.width(48.dp).fillMaxHeight().clip(RoundedCornerShape(10.dp)).then(
            if (idle) Modifier.clickable(enabled = ready, role = Role.Button, onClick = onRun).semantics { contentDescription = run } else Modifier,
        ),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        if (idle) Icon(mode.icon, null, tint = if (ready) Bloom.Pollen else Bloom.Outline)
        else CircularProgressIndicator(Modifier.size(22.dp), color = Bloom.Pollen, strokeWidth = 2.dp)
        // where it runs, under the icon
        Text(choice?.let { state.ai.name(it.provider) }.orEmpty(), fontSize = 9.sp, lineHeight = 11.sp, color = Bloom.OnVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    var menu by remember { mutableStateOf(false) }
    Box(
        Modifier.width(24.dp).fillMaxHeight().clickable(enabled = idle, role = Role.Button) { menu = true }
            .semantics { contentDescription = "Choose where ${mode.label} runs" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Down, null, Modifier.size(18.dp), tint = if (idle) Bloom.On else Bloom.Outline)
        DropdownMenu(menu, { menu = false }, containerColor = Bloom.SurfaceHigh) {
            for (o in options) DropdownMenuItem(
                text = { Text(state.ai.label(o), color = Bloom.On) },
                onClick = { menu = false; onPick(o) },
                trailingIcon = if (o == choice) ({ Icon(Icons.Check, "Current", tint = Bloom.Pollen) }) else null,
            )
            DropdownMenuItem(text = { Text("AI settings…", color = Bloom.On) }, onClick = { menu = false; onSettings() })
        }
    }
}
