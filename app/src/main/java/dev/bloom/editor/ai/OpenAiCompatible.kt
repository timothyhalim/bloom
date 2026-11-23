package dev.bloom.editor.ai

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Any OpenAI-compatible chat endpoint (llama.cpp, Ollama, LiteLLM, OpenRouter…) for Ask: text only, never the photo.
 * The URL may end in /v1 or not.
 */
class OpenAiCompatible(url: String, private val key: String?, private val found: Map<Capability, List<String>>) : AiProvider {
    private val base = Http.base(url).removeSuffix("/v1") + "/v1"
    private val auth get() = key?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty()

    override fun models(cap: Capability) = found[cap].orEmpty()

    override suspend fun test() = mapOf(Capability.EDIT_COMMAND to models(Http.call("$base/models", headers = auth).json())).filterValues { it.isNotEmpty() }

    override suspend fun run(cap: Capability, model: String, req: AiRequest, status: (String) -> Unit): AiResult =
        throw AiError("Ask isn't available yet")

    companion object {
        /** Model ids from a /v1/models reply. */
        fun models(r: JsonElement): List<String> {
            val data = (r as? JsonObject)?.get("data") ?: throw AiError("This doesn't look like an OpenAI-compatible server")
            return data.jsonArray.mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull }
        }
    }
}
