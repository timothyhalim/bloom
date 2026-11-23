package dev.bloom.editor.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class Response(val code: Int, val body: ByteArray, val type: String?) {
    fun json(): JsonElement = runCatching { Json.parseToJsonElement(String(body)) }.getOrElse { throw AiError("The server sent something that isn't JSON") }
}

/** Calls to providers: timeouts, a size cap on replies, one retry on a network error, cancellation closes the connection. */
object Http {
    const val MAX_REPLY = 64 shl 20

    /**
     * [method] [url] with [body]; a reply of 400 or more throws an [AiError] with the server's message. [status] gets
     * "Uploading n %" for a large body. [retry] = false for requests that must not run twice (a paid job).
     */
    suspend fun call(
        url: String, method: String = "GET", body: ByteArray? = null, type: String = "application/json",
        headers: Map<String, String> = emptyMap(), timeout: Int = 30_000, retry: Boolean = true, status: (String) -> Unit = {},
    ): Response {
        try {
            return once(url, method, body, type, headers, timeout, status)
        } catch (e: IOException) {
            if (!retry || !currentCoroutineContext().isActive) throw e
        }
        return once(url, method, body, type, headers, timeout, status)
    }

    private suspend fun once(url: String, method: String, body: ByteArray?, type: String, headers: Map<String, String>, timeout: Int, status: (String) -> Unit): Response {
        val u = URL(url)
        if (u.protocol != "http" && u.protocol != "https") throw AiError("Only http and https addresses work")
        val c = u.openConnection() as HttpURLConnection
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { c.disconnect() }
            Dispatchers.IO.asExecutor().execute {
                cont.resumeWith(runCatching {
                    try {
                        c.connectTimeout = 15_000
                        c.readTimeout = timeout
                        c.requestMethod = method
                        c.instanceFollowRedirects = false // a redirect could carry the key to another host
                        for ((k, v) in headers) c.setRequestProperty(k, v)
                        if (body != null) {
                            c.doOutput = true
                            c.setRequestProperty("Content-Type", type)
                            c.setFixedLengthStreamingMode(body.size)
                            c.outputStream.use { o ->
                                var done = 0
                                while (done < body.size) {
                                    val n = minOf(1 shl 16, body.size - done)
                                    o.write(body, done, n)
                                    done += n
                                    if (body.size > 1 shl 18) status("Uploading ${done * 100L / body.size} %")
                                }
                            }
                        }
                        val code = c.responseCode
                        if (c.contentLengthLong > MAX_REPLY) throw AiError("The server's reply is too large")
                        val bytes = (if (code >= 400) c.errorStream else c.inputStream)?.use { i ->
                            val out = ByteArrayOutputStream()
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                val n = i.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                if (out.size() > MAX_REPLY) throw AiError("The server's reply is too large")
                            }
                            out.toByteArray()
                        } ?: ByteArray(0)
                        val r = Response(code, bytes, c.contentType)
                        if (code >= 400) throw AiError(errorText(r))
                        if (code >= 300) throw AiError("The server redirected to another address; enter that address instead")
                        r
                    } finally {
                        c.disconnect()
                    }
                })
            }
        }
    }

    /** The server's own message for a failed request, short and printable: FastAPI's detail, OpenAI's error.message… */
    fun errorText(r: Response): String {
        fun text(e: JsonElement?): String? = when (e) {
            is JsonPrimitive -> e.content.takeIf { e.isString && it.isNotBlank() }
            is JsonArray -> text(e.firstOrNull())
            is JsonObject -> listOf("detail", "message", "msg", "error", "errors").firstNotNullOfOrNull { text(e[it]) }
            else -> null
        }
        val msg = runCatching { text(Json.parseToJsonElement(String(r.body))) }.getOrNull()
            ?: String(r.body).takeIf { r.type?.startsWith("text/plain") == true }
        val clean = msg?.filter { it >= ' ' }?.take(200)?.trim()
        return if (clean.isNullOrEmpty()) "HTTP ${r.code}" else "HTTP ${r.code}: $clean"
    }

    /** A multipart/form-data body of [files] (field name, file name, PNG bytes) and [fields]; returns it with its content type. */
    fun multipart(files: List<Triple<String, String, ByteArray>>, fields: Map<String, String> = emptyMap()): Pair<ByteArray, String> {
        val boundary = "bloom" + System.nanoTime()
        val out = ByteArrayOutputStream()
        for ((k, v) in fields) out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n".toByteArray())
        for ((field, file, bytes) in files) {
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$field\"; filename=\"$file\"\r\nContent-Type: image/png\r\n\r\n".toByteArray())
            out.write(bytes)
            out.write("\r\n".toByteArray())
        }
        out.write("--$boundary--\r\n".toByteArray())
        return out.toByteArray() to "multipart/form-data; boundary=$boundary"
    }

    /** [url] without a trailing slash, after checking it is an http(s) address. */
    fun base(url: String): String {
        val u = url.trim().trimEnd('/')
        if (!u.startsWith("http://") && !u.startsWith("https://")) throw AiError("The address must start with http:// or https://")
        return u
    }
}

fun png(b: Bitmap): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

/** [mask] ([w]×[h], non-zero = change here) as an opaque PNG, white where to change. */
fun maskPng(mask: ByteArray, w: Int, h: Int): ByteArray {
    val b = Bitmap.createBitmap(IntArray(w * h) { if (mask[it].toInt() != 0) -1 else -0x1000000 }, w, h, Bitmap.Config.ARGB_8888)
    return png(b).also { b.recycle() }
}

/** A provider's image reply, refused if it isn't an image or is over 64 MP (it would run the phone out of memory). */
fun decodeImage(bytes: ByteArray): Bitmap {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    if (o.outWidth <= 0 || o.outHeight <= 0) throw AiError("The server didn't send an image")
    if (o.outWidth.toLong() * o.outHeight > 64_000_000) throw AiError("The server's image is too large")
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw AiError("The server didn't send an image")
}
