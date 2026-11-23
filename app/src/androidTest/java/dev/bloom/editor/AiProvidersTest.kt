package dev.bloom.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.bloom.editor.ai.AiError
import dev.bloom.editor.ai.AiRequest
import dev.bloom.editor.ai.AiResult
import dev.bloom.editor.ai.Capability
import dev.bloom.editor.ai.ComfyUi
import dev.bloom.editor.ai.Fal
import dev.bloom.editor.ai.IoPaint
import dev.bloom.editor.ai.Keys
import dev.bloom.editor.ai.OpenAiCompatible
import dev.bloom.editor.ai.png
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64

/** Provider adapters against a fake server replaying their APIs (no real IOPaint, ComfyUI or fal here), and key storage. */
@RunWith(AndroidJUnit4::class)
class AiProvidersTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var server: FakeServer? = null
    private fun serve(handle: (FakeServer.Req) -> FakeServer.Reply) = FakeServer(handle).also { server = it }

    @After
    fun stop() {
        server?.close()
    }

    private val crop = Bitmap.createBitmap(IntArray(64 * 48) { Color.rgb(200, 30, 30) }, 64, 48, Bitmap.Config.ARGB_8888)
    private val hole = ByteArray(64 * 48) { if (it % 64 in 20 until 40) 1 else 0 }
    private val green = png(Bitmap.createBitmap(IntArray(64 * 48) { Color.GREEN }, 64, 48, Bitmap.Config.ARGB_8888))
    private fun decode(b64: String) = Base64.getDecoder().decode(b64.substringAfter(",")).let { BitmapFactory.decodeByteArray(it, 0, it.size) }

    @Test
    fun keysAreEncryptedAtRestAndComeBack() {
        Keys.put(context, "t1", "sk-secret-123")
        val stored = context.getSharedPreferences("ai_keys", 0).getString("t1", null)!!
        assertFalse(stored.contains("secret"))
        assertEquals("sk-secret-123", Keys.get(context, "t1"))
        Keys.put(context, "t1", "sk-other")
        assertEquals("sk-other", Keys.get(context, "t1"))
        Keys.forget(context, "t1")
        assertEquals(null, Keys.get(context, "t1"))
        assertFalse(Keys.has(context, "t1"))
    }

    @Test
    fun ioPaintSwitchesModelAndErases() = runBlocking {
        val s = serve { r ->
            when (r.path) {
                IoPaint.MODEL -> FakeServer.Reply("""{"name":"lama","model_type":"inpaint"}""")
                IoPaint.INPAINT -> FakeServer.Reply(200, green, "image/png", delayMs = 1500)
                else -> FakeServer.Reply("{}", 404)
            }
        }
        val status = mutableListOf<String>()
        val r = IoPaint(s.url, null, emptyMap()).run(Capability.ERASE, "mat", AiRequest(crop, hole)) { status += it } as AiResult.Image
        assertEquals(Color.GREEN, r.bitmap.getPixel(10, 10))
        assertEquals(listOf("GET " + IoPaint.MODEL, "POST " + IoPaint.MODEL, "POST " + IoPaint.INPAINT), s.requests.map { "${it.method} ${it.path}" })
        assertEquals("mat", Json.parseToJsonElement(s.requests[1].text).jsonObject["name"]!!.jsonPrimitive.content)
        val body = Json.parseToJsonElement(s.requests[2].text).jsonObject
        val mask = decode(body["mask"]!!.jsonPrimitive.content)
        assertEquals(64, mask.width)
        assertEquals(Color.WHITE, mask.getPixel(30, 10)) // white = fill
        assertEquals(Color.BLACK, mask.getPixel(5, 10))
        assertEquals(Color.rgb(200, 30, 30), decode(body["image"]!!.jsonPrimitive.content).getPixel(5, 5))
        assertTrue(status.toString(), status.contains("Running on the server, 1 s"))
    }

    @Test
    fun ioPaintTestListsModelsAndErrorsShowTheServersMessage() = runBlocking {
        val s = serve { r ->
            when (r.path) {
                IoPaint.CONFIG -> FakeServer.Reply("""{"plugins":[],"modelInfos":[{"name":"lama","model_type":"inpaint","need_prompt":false}]}""")
                IoPaint.MODEL -> FakeServer.Reply("""{"name":"lama"}""")
                else -> FakeServer.Reply("""{"error":"HTTPException","detail":"Image size(64,48) and mask size(1,1) not match."}""", 400)
            }
        }
        val p = IoPaint(s.url, "proxy-key", emptyMap())
        assertEquals(mapOf(Capability.ERASE to listOf("lama")), p.test())
        assertEquals("Bearer proxy-key", s.requests[0].headers["authorization"])
        try {
            p.run(Capability.ERASE, "lama", AiRequest(crop, hole)) {}
            fail()
        } catch (e: AiError) {
            assertEquals("HTTP 400: Image size(64,48) and mask size(1,1) not match.", e.message)
        }
    }

    @Test
    fun cancellingClosesTheConnectionQuickly() = runBlocking {
        val s = serve { FakeServer.Reply(200, green, "image/png", delayMs = 20_000) }
        val job = async { IoPaint(s.url, null, emptyMap()).run(Capability.UPSCALE, "RealESRGAN", AiRequest(crop)) {} }
        delay(500)
        val t = System.nanoTime()
        job.cancel()
        runCatching { job.await() }
        assertTrue(System.nanoTime() - t < 2_000_000_000L)
    }

    @Test
    fun comfyUiUploadsQueuesReportsProgressAndDownloads() = runBlocking {
        val workflow = """{"2":{"class_type":"LoadImage","inputs":{"image":"x"},"_meta":{"title":"bloom_image"}},
            "3":{"class_type":"LoadImage","inputs":{"image":"x"},"_meta":{"title":"bloom_mask"}},
            "5":{"class_type":"CLIPTextEncode","inputs":{"text":""},"_meta":{"title":"bloom_prompt"}},
            "10":{"class_type":"SaveImage","inputs":{"images":["2",0]},"_meta":{"title":"bloom_output"}}}"""
        var polls = 0
        val s = serve { r ->
            when {
                r.path == ComfyUi.UPLOAD -> FakeServer.Reply("""{"name":"up${r.body.size}.png","subfolder":"","type":"input"}""")
                r.path == ComfyUi.PROMPT -> FakeServer.Reply("""{"prompt_id":"p1","number":5,"node_errors":{}}""")
                r.path == "${ComfyUi.HISTORY}/p1" -> FakeServer.Reply(if (++polls < 3) "{}" else """{"p1":{"outputs":{"10":{"images":[{"filename":"out 1.png","subfolder":"","type":"output"}]}},"status":{"status_str":"success","completed":true,"messages":[]}}}""")
                r.path == ComfyUi.QUEUE -> FakeServer.Reply(if (polls < 2) """{"queue_running":[[4,"x",{},{},[]]],"queue_pending":[[5,"p1",{},{},[]]]}""" else """{"queue_running":[[5,"p1",{},{},[]]],"queue_pending":[]}""")
                r.path.startsWith(ComfyUi.VIEW) -> FakeServer.Reply(200, green, "image/png")
                else -> FakeServer.Reply("{}", 404)
            }
        }
        val status = mutableListOf<String>()
        val r = ComfyUi(s.url, null, mapOf(Capability.GEN_FILL to workflow)).run(Capability.GEN_FILL, ComfyUi.WORKFLOW, AiRequest(crop, hole, prompt = "grass")) { status += it } as AiResult.Image
        assertEquals(Color.GREEN, r.bitmap.getPixel(1, 1))
        assertEquals(2, s.requests.count { it.path == ComfyUi.UPLOAD })
        assertTrue(s.requests.first { it.path == ComfyUi.UPLOAD }.headers["content-type"]!!.startsWith("multipart/form-data; boundary="))
        val prompt = Json.parseToJsonElement(s.requests.first { it.path == ComfyUi.PROMPT }.text).jsonObject["prompt"]!!.jsonObject
        assertEquals("grass", prompt["5"]!!.jsonObject["inputs"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("/view?filename=out+1.png&subfolder=&type=output", s.requests.last().path)
        assertEquals(listOf("Queued, 1 ahead", "Running, 1 s", "Downloading result…"), status.filter { !it.startsWith("Uploading") })
    }

    @Test
    fun comfyUiCancelTakesTheJobOffTheServer() = runBlocking {
        val s = serve { r ->
            when (r.path) {
                ComfyUi.UPLOAD -> FakeServer.Reply("""{"name":"a.png","subfolder":"","type":"input"}""")
                ComfyUi.PROMPT -> FakeServer.Reply("""{"prompt_id":"p1"}""")
                ComfyUi.QUEUE -> FakeServer.Reply("""{"queue_running":[[5,"p1",{},{},[]]],"queue_pending":[]}""")
                else -> FakeServer.Reply("{}")
            }
        }
        val upscale = """{"1":{"class_type":"LoadImage","inputs":{"image":"x"},"_meta":{"title":"bloom_image"}},
            "5":{"class_type":"SaveImage","inputs":{"images":["1",0]},"_meta":{"title":"bloom_output"}}}"""
        val job = async { ComfyUi(s.url, null, mapOf(Capability.UPSCALE to upscale)).run(Capability.UPSCALE, ComfyUi.WORKFLOW, AiRequest(crop)) {} }
        delay(1500)
        job.cancel()
        runCatching { job.await() }
        assertTrue(s.requests.any { it.method == "POST" && it.path == ComfyUi.QUEUE && it.text.contains("p1") })
        assertTrue(s.requests.any { it.path == ComfyUi.INTERRUPT })
    }

    @Test
    fun falQueuesPollsAndFetchesTheResult() = runBlocking {
        var polls = 0
        lateinit var base: String
        val s = serve { r ->
            when {
                r.path == "/fal-ai/object-removal/mask" -> FakeServer.Reply("""{"request_id":"r1","status_url":"$base/fal-ai/object-removal/requests/r1/status","response_url":"$base/fal-ai/object-removal/requests/r1","cancel_url":"$base/fal-ai/object-removal/requests/r1/cancel"}""")
                r.path.endsWith("/status") -> FakeServer.Reply(
                    when (++polls) {
                        1 -> """{"status":"IN_QUEUE","queue_position":3}"""
                        2 -> """{"status":"IN_PROGRESS","logs":[]}"""
                        else -> """{"status":"COMPLETED","error":null}"""
                    },
                )
                r.path.endsWith("/r1") -> FakeServer.Reply("""{"images":[{"url":"data:image/png;base64,${Base64.getEncoder().encodeToString(green)}"}]}""")
                else -> FakeServer.Reply("{}", 404)
            }
        }
        base = s.url
        val status = mutableListOf<String>()
        val fal = Fal("fal-key", emptyMap(), queue = s.url, api = s.url)
        val r = fal.run(Capability.ERASE, "fal-ai/object-removal/mask", AiRequest(crop, hole)) { status += it } as AiResult.Image
        assertEquals(Color.GREEN, r.bitmap.getPixel(1, 1))
        assertTrue(s.requests.all { it.headers["authorization"] == "Key fal-key" })
        val input = Json.parseToJsonElement(s.requests[0].text).jsonObject
        assertTrue(input["image_url"]!!.jsonPrimitive.content.startsWith("data:image/png;base64,"))
        assertEquals(Color.WHITE, decode(input["mask_url"]!!.jsonPrimitive.content).getPixel(30, 10))
        assertEquals(listOf("Queued, 3 ahead", "Running on fal, 1 s", "Downloading result…"), status.filter { !it.startsWith("Uploading") })
    }

    @Test
    fun falErrorsAndCancel() = runBlocking {
        lateinit var base: String
        var fail = true
        val s = serve { r ->
            when {
                r.path.startsWith("/v1/models/pricing") -> FakeServer.Reply("""{"error":{"type":"authorization_error","message":"Authentication required."}}""", 401)
                r.method == "POST" && fail -> FakeServer.Reply("""{"detail":[{"loc":["body","mask_url"],"msg":"Field required","type":"missing"}]}""", 422)
                r.method == "POST" -> FakeServer.Reply("""{"status_url":"$base/a/requests/r1/status","response_url":"$base/a/requests/r1","cancel_url":"$base/a/requests/r1/cancel"}""")
                r.path.endsWith("/status") -> FakeServer.Reply("""{"status":"IN_QUEUE","queue_position":0}""")
                else -> FakeServer.Reply("""{"status":"OK"}""")
            }
        }
        base = s.url
        val fal = Fal("bad", emptyMap(), queue = s.url, api = s.url)
        try {
            fal.test()
            fail()
        } catch (e: AiError) {
            assertEquals("HTTP 401: Authentication required.", e.message)
        }
        try {
            fal.run(Capability.ERASE, "fal-ai/bria/eraser", AiRequest(crop, hole)) {}
            fail()
        } catch (e: AiError) {
            assertEquals("HTTP 422: Field required", e.message)
        }
        assertEquals("a failed submit isn't retried", 1, s.requests.count { it.method == "POST" })
        fail = false
        val job = async { fal.run(Capability.ERASE, "fal-ai/bria/eraser", AiRequest(crop, hole)) {} }
        delay(1500)
        job.cancel()
        runCatching { job.await() }
        assertTrue(s.requests.any { it.method == "PUT" && it.path == "/a/requests/r1/cancel" })
    }

    @Test
    fun openAiCompatibleListsModels() = runBlocking {
        val s = serve { r -> if (r.path == "/v1/models") FakeServer.Reply("""{"data":[{"id":"qwen"},{"id":"llama"}]}""") else FakeServer.Reply("{}", 404) }
        assertEquals(mapOf(Capability.EDIT_COMMAND to listOf("qwen", "llama")), OpenAiCompatible(s.url + "/v1/", "k", emptyMap()).test())
        assertEquals("Bearer k", s.requests[0].headers["authorization"])
    }
}
