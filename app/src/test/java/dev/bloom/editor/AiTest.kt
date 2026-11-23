package dev.bloom.editor

import com.sun.net.httpserver.HttpServer
import dev.bloom.editor.ai.AiConfig
import dev.bloom.editor.ai.AiError
import dev.bloom.editor.ai.AiProvider
import dev.bloom.editor.ai.AiRequest
import dev.bloom.editor.ai.Capability
import dev.bloom.editor.ai.Choice
import dev.bloom.editor.ai.ComfyUi
import dev.bloom.editor.ai.Fal
import dev.bloom.editor.ai.Http
import dev.bloom.editor.ai.IoPaint
import dev.bloom.editor.ai.OnDevice
import dev.bloom.editor.ai.OpenAiCompatible
import dev.bloom.editor.ai.ProviderConfig
import dev.bloom.editor.ai.ProviderType
import dev.bloom.editor.ai.Response
import dev.bloom.editor.ui.checkUpscaled
import dev.bloom.editor.ui.upscaleInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress

class AiTest {
    private fun fixture(name: String) = javaClass.getResource("/ai/$name")!!.readText()
    private fun json(name: String): JsonElement = Json.parseToJsonElement(fixture(name))

    private val phone = object : AiProvider {
        override fun models(cap: Capability) = if (cap == Capability.ERASE) listOf("LaMa") else emptyList()
        override suspend fun test() = emptyMap<Capability, List<String>>()
        override suspend fun run(cap: Capability, model: String, req: AiRequest, status: (String) -> Unit) = error("not here")
    }
    private val server = ProviderConfig("s1", ProviderType.IOPaint, "My server", "http://pc:8080", mapOf(Capability.ERASE to listOf("lama", "mat"), Capability.GEN_FILL to listOf("sd")))

    @Test
    fun choiceDefaultsToThePhoneAndFollowsTheUsersPick() {
        val c = AiConfig(providers = listOf(server))
        assertEquals(listOf(Choice(OnDevice.ID, "LaMa"), Choice("s1", "lama"), Choice("s1", "mat")), c.options(Capability.ERASE, phone))
        assertEquals(Choice(OnDevice.ID, "LaMa"), c.choice(Capability.ERASE, phone))
        assertEquals(Choice("s1", "sd"), c.choice(Capability.GEN_FILL, phone))
        assertNull(c.choice(Capability.UPSCALE, phone))
        val picked = c.copy(choices = mapOf(Capability.ERASE to Choice("s1", "mat")))
        assertEquals(Choice("s1", "mat"), picked.choice(Capability.ERASE, phone))
        // a model the server no longer has falls back
        assertEquals(Choice(OnDevice.ID, "LaMa"), picked.copy(choices = mapOf(Capability.ERASE to Choice("s1", "big-lama"))).choice(Capability.ERASE, phone))
    }

    @Test
    fun hidingCloudFeaturesLeavesOnlyThePhone() {
        val c = AiConfig(providers = listOf(server), choices = mapOf(Capability.ERASE to Choice("s1", "mat")), hideCloud = true)
        assertEquals(listOf(Choice(OnDevice.ID, "LaMa")), c.options(Capability.ERASE, phone))
        assertEquals(Choice(OnDevice.ID, "LaMa"), c.choice(Capability.ERASE, phone))
        assertNull(c.choice(Capability.GEN_FILL, phone))
    }

    @Test
    fun consentIsPerProviderAndGoesWithIt() {
        val c = AiConfig(providers = listOf(server), choices = mapOf(Capability.ERASE to Choice("s1", "mat")))
        assertFalse(c.needsConsent(OnDevice.ID))
        assertTrue(c.needsConsent("s1"))
        val ok = c.copy(consented = setOf("s1"))
        assertFalse(ok.needsConsent("s1"))
        val removed = ok.remove("s1")
        assertTrue(removed.providers.isEmpty() && removed.choices.isEmpty() && removed.consented.isEmpty())
        assertEquals("My server", c.name("s1"))
        assertEquals(2, c.put(server.copy(id = "s2")).put(server.copy(name = "Renamed")).providers.size)
    }

    @Test
    fun configSurvivesJsonWithoutKeys() {
        val c = AiConfig(providers = listOf(server), choices = mapOf(Capability.ERASE to Choice("s1", "mat")), consented = setOf("s1"))
        val text = Json.encodeToString(c)
        assertEquals(c, Json.decodeFromString<AiConfig>(text))
        assertFalse("no key field", text.contains("key", ignoreCase = true))
    }

    @Test
    fun ioPaintModelsFromServerConfig() {
        val m = IoPaint.models(json("iopaint_server_config.json"))
        assertEquals(listOf("lama", "mat"), m[Capability.ERASE]) // Paint-by-Example needs no prompt but isn't an eraser
        assertEquals(listOf("runwayml/stable-diffusion-inpainting"), m[Capability.GEN_FILL])
        assertEquals(listOf("RealESRGAN"), m[Capability.UPSCALE])
        try {
            IoPaint.models(json("openai_models.json"))
            fail()
        } catch (e: AiError) {
            assertTrue(e.message!!.contains("IOPaint"))
        }
    }

    @Test
    fun ioPaintInpaintRequest() {
        val r = IoPaint.inpaint("AAA", "BBB", "a lake")
        assertEquals("AAA", r["image"]!!.jsonPrimitive.content)
        assertEquals("BBB", r["mask"]!!.jsonPrimitive.content)
        assertEquals("a lake", r["prompt"]!!.jsonPrimitive.content)
        assertEquals("Original", r["hd_strategy"]!!.jsonPrimitive.content)
    }

    @Test
    fun serverErrorMessagesAreShortAndReadable() {
        val r = { name: String, code: Int -> Http.errorText(Response(code, fixture(name).toByteArray(), "application/json")) }
        assertEquals("HTTP 400: Image size(512,512) and mask size(256,256) not match.", r("iopaint_error.json", 400))
        assertEquals("HTTP 422: Field required", r("fal_validation_error.json", 422))
        assertEquals("HTTP 401: Incorrect API key provided: sk-...abcd.", r("openai_error.json", 401))
        assertEquals("HTTP 502", Http.errorText(Response(502, "<html>bad gateway</html>".toByteArray(), "text/html")))
        assertEquals("HTTP 500: line one", Http.errorText(Response(500, "line one\n".toByteArray(), "text/plain")))
        assertEquals(200 + "HTTP 500: ".length, Http.errorText(Response(500, ("x".repeat(5000)).toByteArray(), "text/plain")).length)
    }

    @Test
    fun comfyUiSampleWorkflowsAreValidAndGetFilledIn() {
        val inpaint = File("../docs/comfyui/inpaint.json").readText()
        val upscale = File("../docs/comfyui/upscale.json").readText()
        assertNull(ComfyUi.check(inpaint, Capability.ERASE))
        assertNull(ComfyUi.check(inpaint, Capability.GEN_FILL))
        assertNull(ComfyUi.check(upscale, Capability.UPSCALE))
        assertEquals("No node titled bloom_mask", ComfyUi.check(upscale, Capability.ERASE))
        assertTrue(ComfyUi.check("{\"1\": {\"inputs\": {}}}", Capability.UPSCALE)!!.contains("API format"))
        assertTrue(ComfyUi.check("not json", Capability.UPSCALE)!!.contains("Not a workflow"))
        val w = ComfyUi.fill(Json.parseToJsonElement(inpaint).jsonObject, "in/a.png", "in/m.png", "grass")
        val input = { id: String, k: String -> w[id]!!.jsonObject["inputs"]!!.jsonObject[k]!!.jsonPrimitive.content }
        assertEquals("in/a.png", input("2", "image"))
        assertEquals("in/m.png", input("3", "image"))
        assertEquals("grass", input("5", "text"))
        assertEquals("blurry, text, watermark, person, object", input("6", "text")) // other nodes untouched
        assertEquals("10", ComfyUi.node(w, ComfyUi.OUTPUT))
    }

    @Test
    fun comfyUiHistoryAndQueue() {
        assertEquals(Triple("bloom_00001_.png", "", "output"), ComfyUi.output(json("comfyui_history_done.json"), "8b3c-41", "10"))
        assertNull("not finished", ComfyUi.output(Json.parseToJsonElement("{}"), "8b3c-41", "10"))
        try {
            ComfyUi.output(json("comfyui_history_error.json"), "8b3c-41", "10")
            fail()
        } catch (e: AiError) {
            assertTrue(e.message!!, e.message!!.startsWith("ComfyUI: Value not in list"))
        }
        // one running, one pending with a lower number
        assertEquals(2, ComfyUi.ahead(json("comfyui_queue.json"), "8b3c-41"))
        assertEquals(0, ComfyUi.ahead(json("comfyui_queue.json"), "aaaa"))
        assertNull(ComfyUi.ahead(json("comfyui_queue.json"), "gone"))
    }

    @Test
    fun falReplies() {
        val (status, result, cancel) = Fal.submitted(json("fal_submit.json"), Fal.QUEUE)
        assertTrue(status.endsWith("/status") && cancel.endsWith("/cancel") && !result.endsWith("/status"))
        try {
            Fal.submitted(Json.parseToJsonElement(fixture("fal_submit.json").replace("queue.fal.run", "evil.example")), Fal.QUEUE)
            fail("the key must not go to another host")
        } catch (e: AiError) {
        }
        assertEquals("https://v3.fal.media/files/penguin/abc123.png", Fal.imageUrl(json("fal_result_images.json")))
        assertEquals("https://v3.fal.media/files/koala/def456.png", Fal.imageUrl(json("fal_result_image.json")))
        try {
            Fal.imageUrl(Json.parseToJsonElement("""{"image":{"url":"file:///data/x"}}"""))
            fail()
        } catch (e: AiError) {
        }
        // every model builds its input; fill sends the prompt, upscale no mask but the factor
        for ((id, m) in Fal.MODELS) {
            val input = m.second("data:image/png;base64,AA", "data:image/png;base64,BB", AiRequest(prompt = "sky", scale = 4))
            assertTrue(id, input["image_url"] != null)
            assertEquals(id, m.first == Capability.UPSCALE, input["mask_url"] == null)
            assertEquals(id, m.first == Capability.GEN_FILL, input["prompt"] != null)
            if (m.first == Capability.UPSCALE) assertTrue(id, input.values.contains(JsonPrimitive(4)))
        }
    }

    @Test
    fun upscaleInfoLineAndSizeCap() {
        assertEquals("3000 × 4000 → 6000 × 8000 px" to true, upscaleInfo(3000, 4000, 2))
        assertEquals("3000 × 4000 → 12000 × 16000 px, over 64 MP" to false, upscaleInfo(3000, 4000, 4))
        assertTrue(upscaleInfo(4000, 4000, 2).second) // exactly 64 MP
        assertFalse(upscaleInfo(4001, 4000, 2).second)
        assertFalse("no Int overflow", upscaleInfo(50_000, 50_000, 4).second)
    }

    @Test
    fun anUpscaledImageMustBeThePhotoMadeBigger() {
        checkUpscaled(300, 400, 600, 800)
        checkUpscaled(300, 400, 1200, 1601) // a pixel off from rounding
        for ((w, h) in listOf(300 to 400, 150 to 200, 800 to 600, 600 to 900)) try {
            checkUpscaled(300, 400, w, h)
            fail("$w × $h")
        } catch (e: AiError) {
        }
    }

    @Test
    fun openAiModels() {
        assertEquals(listOf("qwen2.5-7b-instruct", "llama-3.1-8b"), OpenAiCompatible.models(json("openai_models.json")))
    }

    // Http against a local server

    private var hits = 0
    private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            hits++
            when (ex.requestURI.path) {
                "/ok" -> { val b = "{\"a\":1}".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
                "/echo" -> { val b = ex.requestBody.readBytes(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
                "/bad" -> { val b = fixture("iopaint_error.json").toByteArray(); ex.sendResponseHeaders(400, b.size.toLong()); ex.responseBody.use { it.write(b) } }
                "/flaky" -> if (hits == 1) ex.close() else { ex.sendResponseHeaders(200, 2); ex.responseBody.use { it.write("ok".toByteArray()) } }
                "/slow" -> { Thread.sleep(3000); ex.sendResponseHeaders(200, -1) }
                "/huge" -> { ex.sendResponseHeaders(200, 0); ex.responseBody.use { o -> val b = ByteArray(1 shl 20); repeat(70) { o.write(b) } } }
                "/redirect" -> { ex.responseHeaders.add("Location", "http://elsewhere/"); ex.sendResponseHeaders(302, -1) }
            }
            ex.close()
        }
        executor = java.util.concurrent.Executors.newCachedThreadPool()
        start()
    }
    private val base = "http://127.0.0.1:${http.address.port}"

    @After
    fun stop() = http.stop(0)

    @Test
    fun httpReadsRepliesAndSendsBodies() = runBlocking {
        assertEquals("{\"a\":1}", String(Http.call("$base/ok").body))
        val up = mutableListOf<String>()
        val big = ByteArray(1 shl 19) { it.toByte() }
        assertTrue(big.contentEquals(Http.call("$base/echo", "POST", big, status = { up += it }).body))
        assertEquals("Uploading 100 %", up.last())
    }

    @Test
    fun httpErrorsCarryTheServersMessageAndAreNotRetried() = runBlocking {
        try {
            Http.call("$base/bad")
            fail()
        } catch (e: AiError) {
            assertTrue(e.message!!.contains("not match"))
        }
        assertEquals(1, hits)
    }

    @Test
    fun httpRetriesANetworkErrorOnceUnlessTold() = runBlocking {
        // POST with a fixed-length body: the JDK retries some failed GETs by itself
        assertEquals("ok", String(Http.call("$base/flaky", "POST", "x".toByteArray()).body))
        assertEquals(2, hits)
        hits = 0
        try {
            Http.call("$base/flaky", "POST", "x".toByteArray(), retry = false)
            fail()
        } catch (e: IOException) {
        }
        assertEquals(1, hits)
    }

    @Test
    fun httpTimesOutRefusesHugeRepliesAndRedirects() = runBlocking {
        try {
            Http.call("$base/slow", timeout = 500, retry = false)
            fail()
        } catch (e: java.net.SocketTimeoutException) {
        }
        try {
            Http.call("$base/huge")
            fail()
        } catch (e: AiError) {
            assertTrue(e.message!!.contains("too large"))
        }
        try {
            Http.call("$base/redirect")
            fail()
        } catch (e: AiError) {
            assertTrue(e.message!!.contains("redirected"))
        }
        try {
            Http.call("file:///etc/passwd")
            fail()
        } catch (e: AiError) {
        }
    }

    @Test
    fun cancellingClosesTheConnectionAtOnce() = runBlocking {
        val t = System.nanoTime()
        val job = async { Http.call("$base/slow") }
        delay(300)
        job.cancel()
        try {
            job.await()
            fail()
        } catch (e: kotlinx.coroutines.CancellationException) {
        }
        assertTrue("took ${(System.nanoTime() - t) / 1_000_000} ms", System.nanoTime() - t < 2_000_000_000)
    }
}
