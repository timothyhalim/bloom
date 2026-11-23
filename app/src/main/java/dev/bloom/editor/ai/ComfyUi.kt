package dev.bloom.editor.ai

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.util.UUID

/**
 * ComfyUI on the user's own computer, running the user's workflows (exported with "Save (API format)"), one per
 * capability. Bloom finds its nodes by title: `bloom_image` (Load Image, gets the crop), `bloom_mask` (Load Image,
 * gets the mask, white = change), `bloom_prompt` (a node with a `text` input) and `bloom_output` (Save Image).
 * Samples in docs/comfyui/.
 */
class ComfyUi(url: String, private val key: String?, private val workflows: Map<Capability, String>) : AiProvider {
    private val base = Http.base(url)
    private val auth get() = key?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty()

    override fun models(cap: Capability) = if (cap in workflows) listOf(WORKFLOW) else emptyList()

    override suspend fun test(): Map<Capability, List<String>> {
        val stats = Http.call(base + STATS, headers = auth).json()
        if ((stats as? JsonObject)?.get("system") == null) throw AiError("This doesn't look like a ComfyUI server")
        return workflows.keys.associateWith { listOf(WORKFLOW) }
    }

    override suspend fun run(cap: Capability, model: String, req: AiRequest, status: (String) -> Unit): AiResult {
        val workflow = workflows[cap] ?: throw AiError("No ComfyUI workflow for ${cap.label}")
        val image = upload(png(req.image), status)
        val mask = req.mask?.let { upload(maskPng(it, req.image.width, req.image.height), status) }
        val prompt = fill(Json.parseToJsonElement(workflow).jsonObject, image, mask, req.prompt)
        val queued = Http.call(base + PROMPT, "POST", buildJsonObject { put("prompt", prompt); put("client_id", UUID.randomUUID().toString()) }.toString().toByteArray(), headers = auth).json()
        val id = queued.jsonObject["prompt_id"]?.jsonPrimitive?.contentOrNull ?: throw AiError("ComfyUI didn't queue the workflow")
        try {
            var seconds = 0
            while (true) {
                val history = Http.call("$base$HISTORY/$id", headers = auth).json()
                output(history, id, node(prompt, OUTPUT)!!)?.let { (file, sub, type) ->
                    status("Downloading result…")
                    val q = "filename=${enc(file)}&subfolder=${enc(sub)}&type=${enc(type)}"
                    return AiResult.Image(decodeImage(Http.call("$base$VIEW?$q", headers = auth).body))
                }
                val ahead = ahead(Http.call(base + QUEUE, headers = auth).json(), id)
                status(if (ahead == null || ahead == 0) "Running, $seconds s" else "Queued, $ahead ahead")
                delay(1000)
                seconds++
            }
        } catch (e: Throwable) {
            // cancelled or failed: take the job off the server's queue, or stop it if it is running
            withContext(NonCancellable) {
                runCatching { Http.call(base + QUEUE, "POST", """{"delete":["$id"]}""".toByteArray(), headers = auth, retry = false) }
                if (e is kotlinx.coroutines.CancellationException) runCatching { Http.call(base + INTERRUPT, "POST", ByteArray(0), headers = auth, retry = false) }
            }
            throw e
        }
    }

    /** Uploads [png] to ComfyUI's input folder; returns the name its Load Image node reads. */
    private suspend fun upload(png: ByteArray, status: (String) -> Unit): String {
        val (body, type) = Http.multipart(listOf(Triple("image", "bloom_${UUID.randomUUID()}.png", png)), mapOf("overwrite" to "true"))
        val r = Http.call(base + UPLOAD, "POST", body, type, auth, status = status).json().jsonObject
        val name = r["name"]?.jsonPrimitive?.contentOrNull ?: throw AiError("ComfyUI didn't take the image")
        val sub = r["subfolder"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return if (sub.isEmpty()) name else "$sub/$name"
    }

    companion object {
        const val WORKFLOW = "Workflow"
        const val STATS = "/system_stats"
        const val UPLOAD = "/upload/image"
        const val PROMPT = "/prompt"
        const val HISTORY = "/history"
        const val QUEUE = "/queue"
        const val INTERRUPT = "/interrupt"
        const val VIEW = "/view"
        const val IMAGE = "bloom_image"
        const val MASK = "bloom_mask"
        const val TEXT = "bloom_prompt"
        const val OUTPUT = "bloom_output"

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        /** The id of the node titled [title] in an API-format workflow. */
        fun node(workflow: JsonObject, title: String) = workflow.entries.firstOrNull { (_, n) ->
            ((n as? JsonObject)?.get("_meta") as? JsonObject)?.get("title")?.jsonPrimitive?.contentOrNull == title
        }?.key

        /** Why [text] can't be a workflow for [cap], or null if it can. */
        fun check(text: String, cap: Capability): String? {
            val w = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
                ?: return "Not a workflow file. Export it from ComfyUI with Save (API format)"
            if (w.values.any { (it as? JsonObject)?.get("class_type") == null }) return "Not in API format. Export it with Save (API format)"
            val need = listOf(IMAGE, OUTPUT) + (if (cap == Capability.UPSCALE) emptyList() else listOf(MASK)) + (if (cap == Capability.GEN_FILL) listOf(TEXT) else emptyList())
            val missing = need.filter { node(w, it) == null }
            return if (missing.isEmpty()) null else "No node titled ${missing.joinToString(", ")}"
        }

        /** [workflow] with the uploaded [image] and [mask] and the [prompt] put into its marked nodes. */
        fun fill(workflow: JsonObject, image: String, mask: String?, prompt: String): JsonObject {
            val set = mapOf(IMAGE to ("image" to image), MASK to ("image" to mask), TEXT to ("text" to prompt))
                .mapNotNull { (title, v) -> node(workflow, title)?.let { it to v } }.toMap()
            return JsonObject(workflow.mapValues { (id, n) ->
                val (input, value) = set[id] ?: return@mapValues n
                if (value == null) return@mapValues n
                val o = n.jsonObject
                JsonObject(o + ("inputs" to JsonObject((o["inputs"] as? JsonObject).orEmpty() + (input to JsonPrimitive(value)))))
            })
        }

        /**
         * From /history/<id>: the first image [node] saved (file name, subfolder, type), or null while the job hasn't
         * finished. Throws with ComfyUI's message if it failed.
         */
        fun output(history: JsonElement, id: String, node: String): Triple<String, String, String>? {
            val job = (history as? JsonObject)?.get(id) as? JsonObject ?: return null
            val st = job["status"] as? JsonObject
            if (st?.get("status_str")?.jsonPrimitive?.contentOrNull == "error") {
                val msg = st["messages"]?.jsonArray.orEmpty().firstNotNullOfOrNull { m ->
                    m.jsonArray.takeIf { it.firstOrNull()?.jsonPrimitive?.contentOrNull == "execution_error" }
                        ?.getOrNull(1)?.jsonObject?.get("exception_message")?.jsonPrimitive?.contentOrNull
                }
                throw AiError("ComfyUI: ${msg?.filter { it >= ' ' }?.take(200)?.trim() ?: "the workflow failed"}")
            }
            val img = job["outputs"]?.jsonObject?.get(node)?.jsonObject?.get("images")?.jsonArray?.firstOrNull()?.jsonObject
                ?: if (st?.get("completed")?.jsonPrimitive?.contentOrNull == "true") throw AiError("The workflow finished without an image at bloom_output") else return null
            val get = { k: String -> img[k]?.jsonPrimitive?.contentOrNull }
            val file = get("filename") ?: throw AiError("ComfyUI sent no file name")
            return Triple(file, get("subfolder").orEmpty(), get("type") ?: "output")
        }

        /** From /queue: how many jobs wait before [id] (0 = running now), or null if it isn't queued. */
        fun ahead(queue: JsonElement, id: String): Int? {
            val q = queue as? JsonObject ?: return null
            val entries = { k: String -> q[k]?.jsonArray.orEmpty().mapNotNull { it as? kotlinx.serialization.json.JsonArray } }
            if (entries("queue_running").any { it.getOrNull(1)?.jsonPrimitive?.contentOrNull == id }) return 0
            val pending = entries("queue_pending")
            val mine = pending.firstOrNull { it.getOrNull(1)?.jsonPrimitive?.contentOrNull == id } ?: return null
            val n = mine[0].jsonPrimitive.intOrNull ?: return null
            return entries("queue_running").size + pending.count { (it[0].jsonPrimitive.intOrNull ?: Int.MAX_VALUE) < n }
        }
    }
}
