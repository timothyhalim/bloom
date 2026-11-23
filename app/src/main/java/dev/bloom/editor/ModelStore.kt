package dev.bloom.editor

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestOutputStream
import java.security.MessageDigest

/**
 * On-demand model files listed in assets/models.json (file, url, sha256, size). Kept in the app's external files
 * dir under models/, so a developer can also `adb push` them there. A file is used only if its SHA-256 matches.
 * [url] may list several space-separated parts (files over GitHub's 100 MB limit); they are joined in order.
 */
object ModelStore {
    private val verified = mutableSetOf<String>()

    /** The model's file, downloading it first if needed; [progress] gets 0..1. Throws if the download or check fails. */
    fun get(context: Context, name: String, progress: (Float) -> Unit = {}): File {
        val spec = JSONObject(context.assets.open("models.json").bufferedReader().use { it.readText() }).getJSONObject(name)
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "models").apply { mkdirs() }
        return get(File(dir, spec.getString("file")), spec.getString("sha256"), spec.getString("url").split(' '), spec.getLong("size"), progress)
    }

    /** [file] if its SHA-256 is [sha], else downloaded first from [urls] (joined in order), [size] bytes in all. */
    internal fun get(file: File, sha: String, urls: List<String>, size: Long, progress: (Float) -> Unit = {}): File {
        synchronized(this) {
            if (file.path in verified) return file
            if (file.exists() && sha256(file) == sha) return file.also { verified += it.path }
            val part = File(file.parentFile, file.name + ".part")
            if (download(urls, part, size, progress) != sha) {
                part.delete()
                error("Downloaded model is damaged, try again")
            }
            check(part.renameTo(file)) { "Can't save the model" }
            verified += file.path
            return file
        }
    }

    /** Joins [urls] into [to]; returns the SHA-256 of what was written. */
    private fun download(urls: List<String>, to: File, size: Long, progress: (Float) -> Unit): String {
        var done = 0L
        val md = MessageDigest.getInstance("SHA-256")
        DigestOutputStream(to.outputStream(), md).use { o ->
            for (url in urls) {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                try {
                    check(c.responseCode == 200) { "Model download failed (HTTP ${c.responseCode})" }
                    c.inputStream.use { i ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = i.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            done += n
                            progress((done.toFloat() / size).coerceAtMost(1f))
                        }
                    }
                } finally {
                    c.disconnect()
                }
            }
        }
        return hex(md.digest())
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.forEachBlock(1 shl 16) { buf, n -> md.update(buf, 0, n) }
        return hex(md.digest())
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
