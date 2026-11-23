package dev.bloom.editor.ai

import android.content.Context
import dev.bloom.editor.ModelStore
import dev.bloom.editor.Segment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The phone itself: IS-Net / MobileSAM subject masks and the LaMa eraser through [Segment], models from [ModelStore]. */
class OnDevice(private val context: Context) : AiProvider {
    override fun models(cap: Capability) = when (cap) {
        // IS-Net finds the subject by itself, MobileSAM follows pins: which one runs depends on the pins, not a choice
        Capability.SEGMENT -> listOf("IS-Net + MobileSAM")
        Capability.ERASE -> listOf("LaMa")
        else -> emptyList()
    }

    override suspend fun test() = Capability.entries.associateWith(::models).filterValues { it.isNotEmpty() }

    override suspend fun run(cap: Capability, model: String, req: AiRequest, status: (String) -> Unit): AiResult = withContext(Dispatchers.IO) {
        val get = { name: String -> ModelStore.get(context, name) { status("Downloading AI model… ${(it * 100).toInt()}%") } }
        when (cap) {
            Capability.SEGMENT -> {
                val m = if (req.pins.isEmpty()) Segment.subject(get("subject"), req.image)
                else Segment.pinned(get("sam_encoder"), get("sam_decoder"), req.image, req.pins)
                AiResult.Mask(m ?: error("the model couldn't run on this phone"))
            }
            Capability.ERASE -> {
                val file = get("eraser")
                status("Erasing…")
                AiResult.Image(Segment.erase(file, req.image, req.mask!!) ?: error("the model couldn't run on this phone"))
            }
            else -> error("${cap.label} doesn't run on the phone")
        }
    }

    companion object {
        const val ID = "phone"
    }
}
