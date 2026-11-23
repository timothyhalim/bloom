package dev.bloom.editor.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Any OpenAI-compatible chat endpoint (llama.cpp, Ollama, LiteLLM, OpenRouter…) for Ask: text only, never the photo.
 * The URL may end in /v1 or not.
 */
class OpenAiCompatible(url: String, private val key: String?, private val found: Map<Capability, List<String>>) : AiProvider {
    private val base = Http.base(url).removeSuffix("/v1") + "/v1"
    private val auth get() = key?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty()

    override fun models(cap: Capability) = found[cap].orEmpty()

    override suspend fun test() = mapOf(Capability.EDIT_COMMAND to models(Http.call("$base/models", headers = auth).json())).filterValues { it.isNotEmpty() }

    override suspend fun run(cap: Capability, model: String, req: AiRequest, status: (String) -> Unit): AiResult {
        if (cap != Capability.EDIT_COMMAND) throw AiError("An OpenAI-compatible server only answers Ask")
        status("Asking $model…")
        suspend fun post(schema: Boolean) = Http.call("$base/chat/completions", "POST", chat(model, req.prompt, schema).toString().toByteArray(), headers = auth, timeout = 180_000)
        val r = try {
            post(true)
        } catch (e: AiError) {
            // servers without JSON schema support refuse the request; the system prompt asks for JSON anyway
            if (e.message?.startsWith("HTTP 400") != true && e.message?.startsWith("HTTP 422") != true) throw e
            post(false)
        }
        return AiResult.Text(content(r.json()))
    }

    companion object {
        /** Model ids from a /v1/models reply. */
        fun models(r: JsonElement): List<String> {
            val data = (r as? JsonObject)?.get("data") ?: throw AiError("This doesn't look like an OpenAI-compatible server")
            return data.jsonArray.mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull }
        }

        /** The chat request: Bloom's system prompt and [user]; with [schema] the reply is held to [EditOps.SCHEMA]. */
        fun chat(model: String, user: String, schema: Boolean): JsonObject = buildJsonObject {
            put("model", model)
            put("temperature", 0.2)
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "system"); put("content", EditOps.SYSTEM) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            }
            if (schema) putJsonObject("response_format") {
                put("type", "json_schema")
                putJsonObject("json_schema") { put("name", "bloom_edits"); put("schema", EditOps.SCHEMA) }
            }
        }

        /** The reply's text from a chat completion: choices[0].message.content. */
        fun content(r: JsonElement): String {
            val msg = ((r as? JsonObject)?.get("choices") as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.get("message") } as? JsonObject
            return (msg?.get("content") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: throw AiError("The server sent no answer")
        }
    }
}
