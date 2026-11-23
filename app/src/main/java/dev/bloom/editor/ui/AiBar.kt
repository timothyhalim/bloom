package dev.bloom.editor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
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
import dev.bloom.editor.ai.AiError
import dev.bloom.editor.ai.Capability
import dev.bloom.editor.ai.EditOps
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import dev.bloom.editor.ai.Http
import dev.bloom.editor.ai.Choice
import dev.bloom.editor.model.Layer
import kotlin.math.abs

/** The AI tool's modes in bar order; each shows only when some provider can run it. Background joins with M8.5. */
val AI_MODES = listOf(Capability.ERASE, Capability.GEN_FILL, Capability.UPSCALE, Capability.EDIT_COMMAND)

/** Modes that run on the area painted into the layer's mask. */
val PAINTED = setOf(Capability.ERASE, Capability.GEN_FILL)

private val Capability.icon: ImageVector get() = when (this) {
    Capability.ERASE -> Icons.Eraser
    Capability.GEN_FILL -> Icons.Fill
    Capability.UPSCALE -> Icons.Upscale
    Capability.EDIT_COMMAND -> Icons.Ask
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
        Capability.GEN_FILL -> ToolBox(state.maskMore, { state.maskMore = !state.maskMore }, box, moreRows = {
            SliderRow("Size", state.brushSize, 4f..150f) { state.brushSize = it; state.showRing(it, state.softness) }
            BrushRows(state)
        }) {
            TwoLineLabel("Fill", "Prompt")
            PromptField(state.fillPrompt, "What to paint there", Modifier.weight(1f)) { state.fillPrompt = it }
        }
        Capability.UPSCALE -> Row(box.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            TwoLineLabel("Upscale", "Factor")
            for (f in listOf(2, 4)) Chip("$f×", state.upscaleBy == f, "$f times") { state.upscaleBy = f }
            val size = state.photoSize
            Text(
                if (size == null) "Works on JPEG, PNG, WebP and HEIC photos" else upscaleInfo(size.first, size.second, state.upscaleBy).first,
                Modifier.weight(1f).padding(horizontal = 8.dp), fontSize = 12.sp, color = Bloom.OnVariant,
            )
        }
        Capability.EDIT_COMMAND -> Column(box) {
            val a = state.asked
            if (a != null) {
                for (n in a.notes) Text(n, Modifier.padding(horizontal = 16.dp, vertical = 2.dp), fontSize = 11.sp, color = Bloom.OnVariant)
                a.ops.forEachIndexed { i, op ->
                    val on = i !in state.askOff
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp).toggleable(on, role = Role.Checkbox) { state.askOff = if (it) state.askOff - i else state.askOff + i }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(on, null)
                        Text(EditOps.label(op, state.doc), fontSize = 13.sp, color = Bloom.On)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                val target = state.askTarget?.let { id -> state.doc.layers.firstOrNull { it.id == id } }
                TwoLineLabel("Ask", target?.let { it.name.ifEmpty { it.kind.label } } ?: "New layers")
                PromptField(state.askText, if (target != null) "How to change this layer" else "Describe the edit", Modifier.weight(1f)) { state.askText = it }
                if (a != null) TextButton({ state.asked = null }) { Text("Cancel", color = Bloom.Pollen) }
            }
        }
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
        for (m in modes) ParamChip(m.icon, m.label, false, m == mode) {
            if (idle && m != mode) {
                state.aiMode = m
                state.asked = null
                state.askTarget = null
            }
        }
    }
    if (mode in PAINTED) MaskButton(d.mask) { if (idle) state.maskMode = true }
    val ready = idle && choice != null && when (mode) {
        Capability.GEN_FILL -> d.mask.active && state.fillPrompt.isNotBlank()
        Capability.UPSCALE -> state.project != null && state.photoSize?.let { upscaleInfo(it.first, it.second, state.upscaleBy).second } == true
        Capability.EDIT_COMMAND -> state.asked?.let { it.ops.size > state.askOff.size } ?: state.askText.isNotBlank()
        else -> d.mask.active
    }
    val run = when (mode) {
        Capability.GEN_FILL -> "Fill the painted area"
        Capability.UPSCALE -> "Upscale the photo"
        Capability.EDIT_COMMAND -> state.asked?.let { "Apply ${it.ops.size - state.askOff.size} changes" } ?: "Send the request"
        else -> "Erase the painted area"
    }
    Column(
        Modifier.width(48.dp).fillMaxHeight().clip(RoundedCornerShape(10.dp)).then(
            if (idle) Modifier.clickable(enabled = ready, role = Role.Button, onClick = onRun).semantics { contentDescription = run } else Modifier,
        ),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        val apply = state.asked?.takeIf { mode == Capability.EDIT_COMMAND }
        if (!idle) CircularProgressIndicator(Modifier.size(22.dp), color = Bloom.Pollen, strokeWidth = 2.dp)
        // the reply is waiting: the run button applies it
        else if (apply != null) Text("Apply ${apply.ops.size - state.askOff.size}", fontSize = 12.sp, color = if (ready) Bloom.Pollen else Bloom.Outline, maxLines = 1)
        else Icon(mode.icon, null, tint = if (ready) Bloom.Pollen else Bloom.Outline)
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

/** A one-line text field in a settings box, with [hint] while empty. */
@Composable
private fun PromptField(text: String, hint: String, modifier: Modifier, onChange: (String) -> Unit) {
    BasicTextField(
        text, onChange, modifier.padding(horizontal = 8.dp).semantics { contentDescription = hint },
        singleLine = true, textStyle = TextStyle(color = Bloom.On, fontSize = 14.sp), cursorBrush = SolidColor(Bloom.Pollen),
        decorationBox = { field -> Box { if (text.isEmpty()) Text(hint, color = Bloom.Outline, fontSize = 14.sp); field() } },
    )
}

/** What Upscale makes of a [w] × [h] photo [by] times: "3000 × 4000 → 6000 × 8000 px", and whether that is allowed. */
internal fun upscaleInfo(w: Int, h: Int, by: Int): Pair<String, Boolean> {
    val ok = w.toLong() * h * by * by <= Http.MAX_PIXELS
    return "$w × $h → ${w * by} × ${h * by} px" + (if (ok) "" else ", over ${Http.MAX_PIXELS / 1_000_000} MP") to ok
}

/** Refuses a provider's upscaled image that isn't the [w] × [h] photo made bigger: smaller, or another shape (1 % slack). */
internal fun checkUpscaled(w: Int, h: Int, rw: Int, rh: Int) {
    if (rw <= w || rh <= h || abs(rw.toFloat() / rh * h / w - 1f) > 0.01f) throw AiError("The server's image isn't this photo made bigger ($rw × $rh px)")
}
