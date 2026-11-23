package dev.bloom.editor.ai

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * IOPaint (github.com/Sanster/IOPaint) on the user's own computer: erase with its inpaint models (LaMa, MAT…), fill
 * with its diffusion models, upscale with its RealESRGAN plugin. The server loads one model at a time; running another
 * switches it first. IOPaint has no auth; a key, if given, goes as a Bearer token for a proxy in front of it.
 */
class IoPaint(url: String, private val key: String?, private val found: Map<Capability, List<String>>) : AiProvider {
    private val base = Http.base(url)
    private val auth get() = key?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty()

    override fun models(cap: Capability) = found[cap].orEmpty()

    override suspend fun test() = models(Http.call(base + CONFIG, headers = auth).json())

    override suspend fun run(cap: Capability, model: String, req: AiRequest, status: (String) -> Unit): AiResult {
        val image = b64(png(req.image))
        val (path, body) = when (cap) {
            Capability.UPSCALE -> PLUGIN to buildJsonObject { put("name", model); put("image", image); put("scale", 2.0) }
            Capability.ERASE, Capability.GEN_FILL -> {
                val current = Http.call(base + MODEL, headers = auth).json().jsonObject["name"]?.jsonPrimitive?.contentOrNull
                if (current != model) {
                    status("Loading $model on the server…")
                    Http.call(base + MODEL, "POST", buildJsonObject { put("name", model) }.toString().toByteArray(), headers = auth, timeout = 600_000)
                }
                INPAINT to inpaint(image, b64(maskPng(req.mask!!, req.image.width, req.image.height)), req.prompt)
            }
            else -> throw AiError("IOPaint can't ${cap.label.lowercase()}")
        }
        // IOPaint answers when the image is done and reports no progress, so the status counts the seconds
        val r = coroutineScope {
            val tick = launch { var s = 0; while (true) { if (s > 0) status("Running on the server, $s s"); delay(1000); s++ } }
            try {
                Http.call(base + path, "POST", body.toString().toByteArray(), headers = auth, timeout = 600_000, status = status)
            } finally {
                tick.cancel()
            }
        }
        status("Reading the result…")
        return AiResult.Image(decodeImage(r.body))
    }

    companion object {
        const val CONFIG = "/api/v1/server-config"
        const val MODEL = "/api/v1/model"
        const val INPAINT = "/api/v1/inpaint"
        const val PLUGIN = "/api/v1/run_plugin_gen_image"

        private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

        /** The inpaint request: whole-crop processing (the crop is already the area around the hole), a random seed. */
        fun inpaint(image: String, mask: String, prompt: String): JsonObject = buildJsonObject {
            put("image", image)
            put("mask", mask)
            put("prompt", prompt)
            put("hd_strategy", "Original")
            put("sd_seed", -1)
        }

        /** What a server can do, from its server-config: inpaint models erase, prompted ones fill, RealESRGAN upscales. */
        fun models(config: JsonElement): Map<Capability, List<String>> {
            val o = config as? JsonObject ?: throw AiError("This doesn't look like an IOPaint server")
            val infos = o["modelInfos"]?.jsonArray ?: throw AiError("This doesn't look like an IOPaint server")
            val (fill, erase) = infos.map { it.jsonObject }.partition { it["need_prompt"]?.jsonPrimitive?.booleanOrNull == true }
            val names = { l: List<JsonObject> -> l.mapNotNull { it["name"]?.jsonPrimitive?.contentOrNull } }
            val upscale = o["plugins"]?.jsonArray.orEmpty().map { it.jsonObject }
                .filter { it["name"]?.jsonPrimitive?.contentOrNull == "RealESRGAN" && it["support_gen_image"]?.jsonPrimitive?.booleanOrNull == true }
            return mapOf(
                // other non-prompt models (Paint-by-Example) need an example image, not an erase
                Capability.ERASE to names(erase.filter { it["model_type"]?.jsonPrimitive?.contentOrNull == "inpaint" }),
                Capability.GEN_FILL to names(fill),
                Capability.UPSCALE to upscale.map { "RealESRGAN" },
            ).filterValues { it.isNotEmpty() }
        }
    }
}
