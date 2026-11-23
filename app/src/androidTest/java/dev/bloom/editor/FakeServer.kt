package dev.bloom.editor

import java.io.BufferedInputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** A tiny HTTP server on 127.0.0.1 standing in for an AI provider: [handle] answers each request; [requests] records them. */
class FakeServer(val handle: (Req) -> Reply) : Closeable {
    class Req(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray) {
        val text get() = String(body)
    }

    class Reply(val code: Int = 200, val body: ByteArray = ByteArray(0), val type: String = "application/json", val delayMs: Long = 0) {
        constructor(json: String, code: Int = 200, delayMs: Long = 0) : this(code, json.toByteArray(), delayMs = delayMs)
    }

    val requests = CopyOnWriteArrayList<Req>()
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val url = "http://127.0.0.1:${socket.localPort}"

    init {
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val s = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { serve(s) }; s.close() }
            }
        }
    }

    private fun serve(s: Socket) {
        val input = BufferedInputStream(s.getInputStream())
        fun line(): String {
            val b = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0 || c == '\n'.code) return b.toString().trimEnd('\r')
                b.append(c.toChar())
            }
        }
        val (method, path) = line().split(" ").let { it[0] to it[1] }
        val headers = generateSequence { line().takeIf { it.isNotEmpty() } }.associate { it.substringBefore(":").lowercase() to it.substringAfter(":").trim() }
        val n = headers["content-length"]?.toInt() ?: 0
        val body = ByteArray(n)
        var read = 0
        while (read < n) read += input.read(body, read, n - read).takeIf { it > 0 } ?: break
        val req = Req(method, path, headers, body)
        requests += req
        val r = handle(req)
        if (r.delayMs > 0) Thread.sleep(r.delayMs)
        val out = s.getOutputStream()
        out.write("HTTP/1.1 ${r.code} X\r\nContent-Type: ${r.type}\r\nContent-Length: ${r.body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(r.body)
        out.flush()
    }

    override fun close() = socket.close()
}
