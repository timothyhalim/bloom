package dev.bloom.editor

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest

class ModelStoreTest {
    private val dir: File = Files.createTempDirectory("models").toFile()
    private val parts = mapOf("/a" to ByteArray(100_000) { it.toByte() }, "/b" to ByteArray(5_000) { (it * 7).toByte() })
    private var requests = 0
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            requests++
            val body = parts[ex.requestURI.path]
            if (body == null) ex.sendResponseHeaders(404, -1)
            else {
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            ex.close()
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val whole = parts.getValue("/a") + parts.getValue("/b")

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @After
    fun stop() {
        server.stop(0)
        dir.deleteRecursively()
    }

    @Test
    fun partsAreJoinedInOrderAndCheckedOnce() {
        val progress = mutableListOf<Float>()
        val f = ModelStore.get(File(dir, "m.mnn"), sha(whole), listOf("$base/a", "$base/b"), whole.size.toLong()) { progress += it }
        assertArrayEquals(whole, f.readBytes())
        assertFalse(File(dir, "m.mnn.part").exists())
        assertEquals(progress.sorted(), progress)
        assertEquals(1f, progress.last())
        // verified: the next call doesn't download again
        ModelStore.get(File(dir, "m.mnn"), sha(whole), listOf("$base/a", "$base/b"), whole.size.toLong())
        assertEquals(2, requests)
    }

    @Test
    fun aFileAlreadyThereIsUsedOnlyIfItsHashMatches() {
        val good = File(dir, "good.mnn").apply { writeBytes(whole) }
        ModelStore.get(good, sha(whole), listOf("$base/missing"), whole.size.toLong())
        assertEquals("no download", 0, requests)
        val stale = File(dir, "stale.mnn").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        ModelStore.get(stale, sha(whole), listOf("$base/a", "$base/b"), whole.size.toLong())
        assertArrayEquals(whole, stale.readBytes())
    }

    @Test
    fun aDamagedDownloadIsDeletedAndReported() {
        val f = File(dir, "bad.mnn")
        try {
            ModelStore.get(f, sha(whole), listOf("$base/a"), whole.size.toLong())
            fail()
        } catch (e: IllegalStateException) {
            assertEquals("Downloaded model is damaged, try again", e.message)
        }
        assertFalse(f.exists())
        assertFalse(File(dir, "bad.mnn.part").exists())
    }

    @Test
    fun anHttpErrorIsReportedWithItsCode() {
        try {
            ModelStore.get(File(dir, "gone.mnn"), sha(whole), listOf("$base/missing"), 1)
            fail()
        } catch (e: IllegalStateException) {
            assertEquals("Model download failed (HTTP 404)", e.message)
        }
        assertFalse(File(dir, "gone.mnn").exists())
    }

    @Test
    fun everyListedModelHasAFileUrlHashAndSize() {
        val models = Json.parseToJsonElement(File("src/main/assets/models.json").readText()).jsonObject
        assertEquals(setOf("subject", "sam_encoder", "sam_decoder", "depth", "eraser", "upscale", "matting"), models.keys)
        for ((name, m) in models) {
            val o = m.jsonObject
            assertTrue(name, o.getValue("file").jsonPrimitive.content.isNotEmpty())
            assertTrue(name, o.getValue("url").jsonPrimitive.content.split(' ').all { it.startsWith("https://") })
            assertTrue(name, Regex("[0-9a-f]{64}").matches(o.getValue("sha256").jsonPrimitive.content))
            assertTrue(name, o.getValue("size").jsonPrimitive.long > 0)
            // the copy in the repo is the one listed
            File("../models", o.getValue("file").jsonPrimitive.content).takeIf { it.exists() }?.let {
                assertEquals(name, o.getValue("sha256").jsonPrimitive.content, sha(it.readBytes()))
                assertEquals(name, o.getValue("size").jsonPrimitive.long, it.length())
            }
        }
        assertEquals("LaMa is split in two", 2, models.getValue("eraser").jsonObject.getValue("url").jsonPrimitive.content.split(' ').size)
    }
}
