package dev.bloom.editor.ai

import android.content.Context
import dev.bloom.editor.ModelStore
import dev.bloom.editor.Segment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * The phone itself: IS-Net / MobileSAM subject masks, the LaMa eraser, Real-ESRGAN upscaling and the RVM person matte
 * through [Segment], models from [ModelStore].
 */
class OnDevice(private val context: Context) : AiProvider {
    override fun models(cap: Capability) = when (cap) {
        // IS-Net finds the subject by itself, MobileSAM follows pins: which one runs depends on the pins, not a choice
        Capability.SEGMENT -> listOf("IS-Net + MobileSAM")
        Capability.ERASE -> listOf("LaMa")
        Capability.UPSCALE -> listOf("Real-ESRGAN")
        // the person matte the Background layer cuts along
        Capability.BG_REPLACE -> listOf(MATTING)
        else -> emptyList()
    }

    override suspend fun test() = Capability.entries.associateWith(::models).filterValues { it.isNotEmpty() }

    override suspend fun run(cap: Capability, model: String, req: AiRequest, status: (String) -> Unit): AiResult = withContext(Dispatchers.IO) {
        val get = { name: String -> ModelStore.get(context, name) { status("Downloading AI model… ${(it * 100).toInt()}%") } }
        when (cap) {
            Capability.SEGMENT -> {
                val m = if (req.pins.isEmpty()) Segment.subject(get("subject"), req.image!!)
                else Segment.pinned(get("sam_encoder"), get("sam_decoder"), req.image!!, req.pins)
                AiResult.Mask(m ?: error("the model couldn't run on this phone"))
            }
            Capability.ERASE -> {
                val file = get("eraser")
                status("Erasing…")
                // LaMa's peak memory grows with its size: about 2 GB at 768, 2.7 GB at 1024
                val ram = android.app.ActivityManager.MemoryInfo().also { context.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it) }.totalMem
                val max = if (ram < 7L shl 30) 768 else 1024
                AiResult.Image(Segment.erase(file, req.image!!, req.mask!!, max) ?: error("the model couldn't run on this phone"))
            }
            Capability.UPSCALE -> {
                val file = get("upscale")
                val img = req.image!!
                val t = System.nanoTime()
                val out = try {
                    Segment.upscale(file, img, req.scale) { i, n -> ensureActive(); status("Tile $i / $n") }
                } catch (e: OutOfMemoryError) {
                    throw AiError("Not enough memory for a ${img.width * req.scale} × ${img.height * req.scale} px photo")
                }
                android.util.Log.d("Bloom", "upscale ${img.width}x${img.height} ×${req.scale}: ${(System.nanoTime() - t) / 1_000_000} ms")
                AiResult.Image(out ?: error("the model couldn't run on this phone"))
            }
            Capability.BG_REPLACE -> AiResult.Mask(Segment.person(get("matting"), req.image!!) ?: error("the model couldn't run on this phone"))
            else -> error("${cap.label} doesn't run on the phone")
        }
    }

    companion object {
        const val ID = "phone"
        const val MATTING = "RVM"
    }
}
