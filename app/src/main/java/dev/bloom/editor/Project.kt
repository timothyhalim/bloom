package dev.bloom.editor

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import dev.bloom.editor.gl.Offscreen
import dev.bloom.editor.gl.Pipeline
import dev.bloom.editor.model.BackgroundEffect
import dev.bloom.editor.model.BackgroundImage
import dev.bloom.editor.model.Document
import dev.bloom.editor.model.Patch
import dev.bloom.editor.model.RetouchEffect
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import java.io.File
import java.io.OutputStream
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/** doc.json: the photo's file name and the document. Bump [version] when old files need converting. */
@Serializable
class SavedProject(val version: Int, val name: String, val doc: Document)

/** A Retouch patch in doc.json: where it goes and the PNG in the project folder holding its pixels. */
@Serializable
private class PatchFile(val left: Float, val top: Float, val right: Float, val bottom: Float, val file: String)

@Serializable
private class Head(val name: String)

/**
 * A saved project: a folder under files/projects with `original.<ext>` (a byte copy of the opened file, so it decodes
 * as before), `doc.json`, `patch_*.png` (Retouch pixels, lossless), `upscaled_*.jpg` (Upscale results; the one
 * [Document.source] names is used), `image_*.jpg` (Background layers' images) and `thumb.jpg` (the result, for Home).
 * doc.json appears with the first edit; a folder without one is a photo opened and never edited.
 */
class Project(val dir: File) {
    var name = ""
    private var files = IdentityHashMap<Bitmap, String>() // each Retouch patch's PNG in [dir]
    private var saved: Document? = null
    private var deleted = false

    val original: File get() = dir.listFiles().orEmpty().first { it.name.startsWith("original") }

    /** The photo [doc] edits: an upscaled copy when it names one that is here, else the original. */
    fun photo(doc: Document): File =
        doc.source.takeIf { it.isNotEmpty() }?.let { File(dir, it) }?.takeIf { it.parentFile == dir && it.isFile } ?: original

    /** Saves [b] as a new `<prefix>_<time>.jpg` here (an upscaled photo for [Document.source], a background image) and returns its name. */
    fun addJpeg(prefix: String, b: Bitmap): String {
        val name = "${prefix}_${System.currentTimeMillis()}.jpg"
        write(File(dir, name)) { check(b.compress(Bitmap.CompressFormat.JPEG, 95, it)) { "can't encode $name" } }
        return name
    }

    val json = Json {
        ignoreUnknownKeys = true // a file from a newer Bloom still opens, minus what this one doesn't know
        encodeDefaults = true // defaults may change later; a saved edit must not
        serializersModule = SerializersModule {
            contextual(Patch::class, PatchSerializer())
            contextual(BackgroundImage::class, ImageSerializer())
        }
    }

    /** A Background layer's image in doc.json: its file name here. */
    private inner class ImageSerializer : KSerializer<BackgroundImage> {
        override val descriptor = PrimitiveSerialDescriptor("BackgroundImage", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: BackgroundImage) = encoder.encodeString(value.file)

        override fun deserialize(decoder: Decoder): BackgroundImage {
            val f = decoder.decodeString()
            return BackgroundImage(f, BitmapFactory.decodeFile(File(dir, f).path) ?: error("$f is missing"))
        }
    }

    private inner class PatchSerializer : KSerializer<Patch> {
        override val descriptor = PatchFile.serializer().descriptor

        override fun serialize(encoder: Encoder, value: Patch) = encoder.encodeSerializableValue(
            PatchFile.serializer(), PatchFile(value.left, value.top, value.right, value.bottom, files.getValue(value.pixels)),
        )

        override fun deserialize(decoder: Decoder): Patch {
            val f = decoder.decodeSerializableValue(PatchFile.serializer())
            val px = BitmapFactory.decodeFile(File(dir, f.file).path) ?: error("${f.file} is missing")
            files[px] = f.file
            return Patch(f.left, f.top, f.right, f.bottom, px)
        }
    }

    /**
     * Writes [doc] and a thumbnail of it rendered from [photo] (the preview). Skips a document already saved, and a
     * new project with no edits.
     */
    @Synchronized
    fun save(doc: Document, photo: Bitmap, subjects: Map<List<Float>, SubjectMask>, depth: SubjectMask?) {
        val docFile = File(dir, "doc.json")
        if (deleted || doc == saved || !doc.edited && !docFile.exists()) return
        // a changed patch gets a new file, so the doc.json on disk keeps pointing at whole files until it is replaced
        val kept = IdentityHashMap<Bitmap, String>()
        for (l in doc.layers) {
            val px = (l.effect as? RetouchEffect)?.patch?.pixels ?: continue
            kept[px] = files[px] ?: "patch_${l.id}_${System.currentTimeMillis()}.png".also { f -> write(File(dir, f)) { px.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
        files = kept
        write(docFile) { it.write(json.encodeToString(SavedProject(VERSION, name, doc)).toByteArray()) }
        saved = doc
        val used = kept.values.toSet() // IdentityHashMap's own values() compares by identity
        dir.listFiles { f -> f.name.startsWith("patch_") && f.name !in used }?.forEach { it.delete() }
        runCatching { write(File(dir, "thumb.jpg")) { thumbnail(doc, photo, subjects, depth).compress(Bitmap.CompressFormat.JPEG, 85, it) } }
            .onFailure { android.util.Log.e("Bloom", "thumbnail failed", it) }
    }

    @Synchronized
    fun delete() {
        deleted = true
        dir.deleteRecursively()
    }

    /** One project on Home. */
    class Recent(val dir: File, val name: String, val thumb: Bitmap?)

    companion object {
        const val VERSION = 1
        const val THUMB = 360 // long side, px
        const val IMAGE_MAX = 3072 // a Background layer's image, long side, px

        private val headJson = Json { ignoreUnknownKeys = true }

        private fun root(filesDir: File) = File(filesDir, "projects")

        /** A new project holding a copy of the photo at [uri]. Also removes photos opened before and never edited. */
        fun create(cr: ContentResolver, filesDir: File, uri: Uri): Project {
            root(filesDir).listFiles().orEmpty().filterNot { File(it, "doc.json").exists() }.forEach { it.deleteRecursively() }
            val name = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: uri.lastPathSegment ?: "Photo"
            // the copy has no MIME type, only its extension, which tells ImageIo how to decode it
            val ext = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 5 && it.all(Char::isLetterOrDigit) }
                ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(cr.getType(uri))
            val dir = File(root(filesDir), "${System.currentTimeMillis()}")
            check(dir.mkdirs()) { "can't create the project folder" }
            try {
                write(File(dir, if (ext == null) "original" else "original.${ext.lowercase()}")) { out -> cr.openInputStream(uri)!!.use { it.copyTo(out) } }
            } catch (e: Exception) {
                dir.deleteRecursively()
                throw e
            }
            return Project(dir).also { it.name = name }
        }

        /** The project in [dir] and its document; patches load with it. */
        fun open(dir: File): Pair<Project, Document> {
            val p = Project(dir)
            val s = p.json.decodeFromString<SavedProject>(File(dir, "doc.json").readText())
            check(s.version <= VERSION) { "it was saved by a newer Bloom" }
            p.name = s.name
            p.saved = s.doc
            // copies an undone Upscale and images of removed Background layers left behind; history doesn't outlive the session
            val images = s.doc.layers.mapNotNull { (it.effect as? BackgroundEffect)?.image?.file }
            dir.listFiles { f -> f.name.startsWith("upscaled_") && f.name != s.doc.source || f.name.startsWith("image_") && f.name !in images }?.forEach { it.delete() }
            return p to s.doc
        }

        /** Projects with edits, most recently saved first. */
        fun list(filesDir: File): List<Recent> = root(filesDir).listFiles().orEmpty()
            // a new photo never saved, left by a killed app: gone after a day (not at once: it may be saving right now)
            .onEach { if (!File(it, "doc.json").exists() && it.lastModified() < System.currentTimeMillis() - 86_400_000) it.deleteRecursively() }
            .mapNotNull { dir -> File(dir, "doc.json").takeIf { it.exists() }?.let { dir to it.lastModified() } }
            .sortedByDescending { it.second }
            .mapNotNull { (dir, _) ->
                val name = runCatching { headJson.decodeFromString<Head>(File(dir, "doc.json").readText()).name }.getOrNull() ?: return@mapNotNull null
                Recent(dir, name, BitmapFactory.decodeFile(File(dir, "thumb.jpg").path))
            }

        /** Writes [f] whole or not at all: to a temp file, then renamed over it. */
        private fun write(f: File, body: (OutputStream) -> Unit) {
            val tmp = File(f.path + ".tmp")
            tmp.outputStream().use { body(it); it.fd.sync() }
            check(tmp.renameTo(f)) { "can't write ${f.name}" }
        }

        /** [doc] rendered small, crop included, on a GL context of this thread's own (export's way). */
        private fun thumbnail(doc: Document, photo: Bitmap, subjects: Map<List<Float>, SubjectMask>, depth: SubjectMask?): Bitmap {
            val aspect = photo.width.toFloat() / photo.height
            // blurs, feathers and strokes are in fractions of the photo, so a smaller source looks the same
            val s = minOf(1f, 2f * THUMB / maxOf(photo.width, photo.height))
            val small = Bitmap.createScaledBitmap(photo, (photo.width * s).roundToInt().coerceAtLeast(1), (photo.height * s).roundToInt().coerceAtLeast(1), true)
            val (cw, ch) = doc.crop.size(aspect)
            val k = THUMB / maxOf(cw, ch)
            return try {
                Offscreen().use {
                    Pipeline().run {
                        deband = doc.deband
                        setSource(small)
                        setSubjects(subjects)
                        setDepth(depth)
                        renderToBitmap(doc.layers, warp = doc.crop.matrix(aspect), width = (cw * k).roundToInt().coerceAtLeast(1), height = (ch * k).roundToInt().coerceAtLeast(1))
                    }
                }
            } finally {
                if (small !== photo) small.recycle()
            }
        }
    }
}
