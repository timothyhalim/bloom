package dev.bloom.editor.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * fal.ai with the user's own key, through its queue API: submit, poll the status (queue position), fetch the result.
 * The key goes only to fal's own hosts: URLs from replies are checked before the key is sent to them, and result images
 * are downloaded without it.
 */
class Fal(private val key: String, private val found: Map<Capability, List<String>>, private val queue: String = QUEUE, private val api: String = API) : AiProvider {
    private val auth get() = mapOf("Authorization" to "Key $key")

    override fun models(cap: Capability) = found[cap].orEmpty()

    override suspend fun test(): Map<Capability, List<String>> {
        // free and needs a valid key, unlike running a model
        Http.call("$api/v1/models/pricing?endpoint_id=${MODELS.keys.first()}", headers = auth)
        return MODELS.entries.groupBy({ it.value.first }, { it.key })
    }

    override suspend fun run(cap: Capability, model: String, req: AiRequest, status: (String) -> Unit): AiResult {
        val (c, input) = MODELS[model] ?: throw AiError("Unknown fal model $model")
        if (c != cap) throw AiError("$model can't ${cap.label.lowercase()}")
        val image = uri(png(req.image!!))
        val mask = req.mask?.let { uri(maskPng(it, req.image!!.width, req.image.height)) }
        // not retried: a lost reply may still have started a paid job
        val sub = Http.call("$queue/$model", "POST", input(image, mask, req).toString().toByteArray(), headers = auth, retry = false, status = status).json()
        val (statusUrl, resultUrl, cancelUrl) = submitted(sub, queue)
        try {
            var seconds = 0
            while (true) {
                val s = Http.call(statusUrl, headers = auth).json().jsonObject
                when (s["status"]?.jsonPrimitive?.contentOrNull) {
                    "IN_QUEUE" -> status("Queued, ${s["queue_position"]?.jsonPrimitive?.intOrNull ?: 0} ahead")
                    "IN_PROGRESS" -> status("Running on fal, $seconds s")
                    "COMPLETED" -> {
                        s["error"]?.takeIf { it !is JsonNull }?.let { throw AiError("fal: " + ((it as? JsonPrimitive)?.contentOrNull ?: it.toString()).filter { ch -> ch >= ' ' }.take(200)) }
                        status("Downloading result…")
                        val url = imageUrl(Http.call(resultUrl, headers = auth).json())
                        return AiResult.Image(decodeImage(if (url.startsWith("data:")) Base64.getDecoder().decode(url.substringAfter(",")) else Http.call(url).body))
                    }
                    else -> throw AiError("fal sent an unknown status")
                }
                delay(1000)
                seconds++
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { runCatching { Http.call(cancelUrl, "PUT", ByteArray(0), headers = auth, retry = false) } }
            throw e
        }
    }

    companion object {
        const val QUEUE = "https://queue.fal.run"
        const val API = "https://api.fal.ai"

        private fun uri(png: ByteArray) = "data:image/png;base64," + Base64.getEncoder().encodeToString(png)

        /** Model ids (fal.ai/models, checked 2026-11) with what they do and their input from the image, mask (white = change) and request. */
        val MODELS: Map<String, Pair<Capability, (String, String?, AiRequest) -> JsonObject>> = linkedMapOf(
            "fal-ai/object-removal/mask" to (Capability.ERASE to { i, m, _ -> buildJsonObject { put("image_url", i); put("mask_url", m); put("mask_expansion", 0) } }),
            "fal-ai/bria/eraser" to (Capability.ERASE to { i, m, _ -> buildJsonObject { put("image_url", i); put("mask_url", m) } }),
            "fal-ai/flux-pro/v1/fill" to (Capability.GEN_FILL to { i, m, r -> buildJsonObject { put("image_url", i); put("mask_url", m); put("prompt", r.prompt); put("output_format", "png") } }),
            "fal-ai/flux-lora/inpainting" to (Capability.GEN_FILL to { i, m, r -> buildJsonObject { put("image_url", i); put("mask_url", m); put("prompt", r.prompt) } }),
            "fal-ai/esrgan" to (Capability.UPSCALE to { i, _, r -> buildJsonObject { put("image_url", i); put("scale", r.scale) } }),
            "fal-ai/clarity-upscaler" to (Capability.UPSCALE to { i, _, r -> buildJsonObject { put("image_url", i); put("upscale_factor", r.scale) } }),
        )

        /** The status, result and cancel URLs from a submit reply; each must be on [queue], since the key goes there. */
        fun submitted(r: JsonElement, queue: String): Triple<String, String, String> {
            val o = r as? JsonObject ?: throw AiError("fal didn't queue the job")
            val get = { k: String ->
                val u = o[k]?.jsonPrimitive?.contentOrNull ?: throw AiError("fal didn't queue the job")
                if (!u.startsWith("$queue/")) throw AiError("fal sent an address on another host")
                u
            }
            return Triple(get("status_url"), get("response_url"), get("cancel_url"))
        }

        /** The result image's URL: `image.url` or `images[0].url` (models differ); https or a data URI. */
        fun imageUrl(r: JsonElement): String {
            val o = r as? JsonObject
            val img = (o?.get("image") as? JsonObject) ?: (o?.get("images")?.jsonArray?.firstOrNull() as? JsonObject)
            val url = img?.get("url")?.jsonPrimitive?.contentOrNull ?: throw AiError("fal sent no image")
            if (!url.startsWith("https://") && !url.startsWith("data:image/")) throw AiError("fal sent an image address that isn't https")
            return url
        }
    }
}
