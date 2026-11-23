package dev.bloom.editor

import android.graphics.Bitmap
import java.io.File

/** A subject mask over the whole upright photo: [w]×[h] bytes, row 0 at the top. */
class SubjectMask(val w: Int, val h: Int, val alpha: ByteArray)

/**
 * On-device subject masks through MNN (native, app/src/main/cpp/segment.cpp): IS-Net finds the main subject by itself,
 * MobileSAM selects whatever the pins point at.
 */
object Segment {
    const val SIZE = 1024
    private val loaded = runCatching { System.loadLibrary("bloom") }.isSuccess

    // ponytail: the emulator's translated Vulkan takes 20-35 s to set up IS-Net, its CPU ~11 s in all; real phones use the GPU
    private val gpu = android.os.Build.HARDWARE.let { it != "ranchu" && it != "goldfish" }

    /** IS-Net on [photo] scaled to [SIZE]² (aspect ignored, as the model was trained). */
    fun subject(model: File, photo: Bitmap): SubjectMask? {
        if (!loaded) return null
        val square = Bitmap.createScaledBitmap(photo.copy(Bitmap.Config.ARGB_8888, false), SIZE, SIZE, true)
        val backend = IntArray(1)
        // compiled GPU programs, reused on the next app start (MNN cache file); a stale one is rebuilt
        return run(model.path, cache(model), square, gpu, backend)?.let { SubjectMask(SIZE, SIZE, it) }.also { square.recycle() }
    }

    private var encoded: Bitmap? = null // the photo the MobileSAM embedding belongs to

    /** MobileSAM: the object at [pins] (x, y in 0..1 over the upright photo, label 1 = include / 0 = exclude). */
    fun pinned(encoder: File, decoder: File, photo: Bitmap, pins: List<Float>): SubjectMask? {
        if (!loaded) return null
        val s = SIZE.toFloat() / maxOf(photo.width, photo.height)
        val w = (photo.width * s).toInt()
        val h = (photo.height * s).toInt()
        if (encoded !== photo) {
            val small = Bitmap.createScaledBitmap(photo.copy(Bitmap.Config.ARGB_8888, false), w, h, true)
            val ok = samEncode(encoder.path, cache(encoder), small, gpu)
            small.recycle()
            if (!ok) return null
            encoded = photo
        }
        // SAM reads all pins in one call as one object, so each include pin is its own object (with every exclude pin),
        // and the masks are merged: pins on a monkey and a globe select both, whole.
        val (include, exclude) = pins.chunked(3).partition { it[2] > 0.5f }
        if (include.isEmpty()) return SubjectMask(w, h, ByteArray(w * h))
        val merged = ByteArray(w * h)
        for (p in include) {
            val m = samDecode(decoder.path, (p + exclude.flatten()).toFloatArray()) ?: return null
            for (i in merged.indices) if ((m[i].toInt() and 0xFF) > (merged[i].toInt() and 0xFF)) merged[i] = m[i]
        }
        return SubjectMask(w, h, merged)
    }

    private fun cache(model: File) = File(model.parentFile, model.name + ".cache").path

    @JvmStatic
    private external fun run(model: String, cache: String, square: Bitmap, gpu: Boolean, backend: IntArray): ByteArray?

    @JvmStatic
    private external fun samEncode(encoder: String, cache: String, photo: Bitmap, gpu: Boolean): Boolean

    @JvmStatic
    private external fun samDecode(decoder: String, pins: FloatArray): ByteArray?
}
