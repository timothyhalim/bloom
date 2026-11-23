package dev.bloom.editor

import dev.bloom.editor.model.Eraser
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
    private const val SIZE_ERASE = 512 // LaMa's input
    private val loaded = runCatching { System.loadLibrary("bloom") }.isSuccess

    // ponytail: the emulator's translated Vulkan takes 20-35 s to set up IS-Net, its CPU ~11 s in all; real phones use the GPU
    private val gpu = android.os.Build.HARDWARE.let { it != "ranchu" && it != "goldfish" }

    /** IS-Net on [photo] scaled to [SIZE]² (aspect ignored, as the model was trained). */
    fun subject(model: File, photo: Bitmap): SubjectMask? {
        if (!loaded) return null
        val square = argb(photo, SIZE, SIZE)
        return run(model.path, cache(model), square, gpu)?.let { SubjectMask(SIZE, SIZE, it) }.also { square.recycle() }
    }

    private var encoded: Bitmap? = null // the photo the MobileSAM embedding belongs to

    /** MobileSAM: the object at [pins] (x, y in 0..1 over the upright photo, label 1 = include / 0 = exclude). */
    fun pinned(encoder: File, decoder: File, photo: Bitmap, pins: List<Float>): SubjectMask? {
        if (!loaded) return null
        val s = SIZE.toFloat() / maxOf(photo.width, photo.height)
        val w = (photo.width * s).toInt()
        val h = (photo.height * s).toInt()
        if (encoded !== photo) {
            val small = argb(photo, w, h)
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

    /** Depth Anything V2 Small: relative depth of [photo], 255 = nearest, at about 518 px on the long side. */
    fun depth(model: File, photo: Bitmap): SubjectMask? {
        if (!loaded) return null
        val s = 518f / maxOf(photo.width, photo.height)
        val w = maxOf(14, Math.round(photo.width * s / 14) * 14) // the model works in 14 px patches
        val h = maxOf(14, Math.round(photo.height * s / 14) * 14)
        val small = argb(photo, w, h)
        return depth(model.path, cache(model), small, gpu).also { small.recycle() }?.let { SubjectMask(w, h, it) }
    }

    /**
     * LaMa eraser: fills [hole] (photo.width × photo.height bytes, non-zero = remove) in [photo].
     * Returns the filled part (the hole, widened a little, plus a margin) and where it sits in the photo (px).
     */
    fun erase(model: File, photo: Bitmap, hole: ByteArray): Pair<IntArray, Bitmap>? {
        if (!loaded) return null
        val w = photo.width
        val h = photo.height
        // widen the hole a little: brush strokes rarely cover an object's soft edge and shadow fringe
        val grow = maxOf(3, h / 300)
        val box = Eraser.bounds(hole, w, h)?.let { Eraser.inflate(it, grow, w, h) } ?: return null
        val (cl, ct, cr, cb) = Eraser.context(box, w, h)
        val cw = cr - cl
        val ch = cb - ct
        val keep = ByteArray(cw * ch) { i -> if (hole[(ct + i / cw) * w + cl + i % cw].toInt() != 0) 0 else -1 }
        val fill = Eraser.erode(keep, cw, ch, grow).map { if (it.toInt() == 0) 1.toByte() else 0.toByte() }.toByteArray() // 1 = fill
        val crop = Bitmap.createBitmap(photo, cl, ct, cw, ch)
        // the model works at 512 x 512
        val n = SIZE_ERASE
        val small = argb(crop, n, n, mutable = true)
        val m = ByteArray(n * n) { i -> fill[(i / n * ch / n) * cw + i % n * cw / n] }
        val ok = erase(model.path, cache(model), small, m, gpu)
        val big = if (ok) Bitmap.createScaledBitmap(small, cw, ch, true) else null
        small.recycle()
        if (big == null) {
            if (crop !== photo) crop.recycle()
            return null
        }
        // keep the photo's own pixels outside the hole: only the hole is the model's
        val out = Eraser.inflate(box, grow, w, h)
        val ow = out[2] - out[0]
        val oh = out[3] - out[1]
        val orig = IntArray(ow * oh).also { crop.getPixels(it, 0, ow, out[0] - cl, out[1] - ct, ow, oh) }
        val gen = IntArray(ow * oh).also { big.getPixels(it, 0, ow, out[0] - cl, out[1] - ct, ow, oh) }
        val px = IntArray(ow * oh) { i -> if (fill[(out[1] - ct + i / ow) * cw + out[0] - cl + i % ow].toInt() != 0) gen[i] else orig[i] }
        big.recycle()
        if (crop !== photo) crop.recycle()
        return out to Bitmap.createBitmap(px, ow, oh, Bitmap.Config.ARGB_8888)
    }

    /** [src] scaled to [w]×[h] as a new ARGB_8888 bitmap (what the native side reads); [src] is left alone. */
    private fun argb(src: Bitmap, w: Int, h: Int, mutable: Boolean = false): Bitmap {
        val s = Bitmap.createScaledBitmap(src, w, h, true) // may be src itself when the size matches
        if (s !== src && s.config == Bitmap.Config.ARGB_8888 && !mutable) return s
        return s.copy(Bitmap.Config.ARGB_8888, mutable).also { if (s !== src) s.recycle() }
    }

    // MobileSAM, depth and LaMa share one MNN runtime, so one cache file for their compiled GPU programs
    private fun cache(model: File) = File(model.parentFile, "mnn.cache").path

    @JvmStatic
    private external fun run(model: String, cache: String, square: Bitmap, gpu: Boolean): ByteArray?

    @JvmStatic
    private external fun samEncode(encoder: String, cache: String, photo: Bitmap, gpu: Boolean): Boolean

    @JvmStatic
    private external fun samDecode(decoder: String, pins: FloatArray): ByteArray?

    @JvmStatic
    private external fun depth(model: String, cache: String, photo: Bitmap, gpu: Boolean): ByteArray?

    @JvmStatic
    private external fun erase(model: String, cache: String, crop: Bitmap, mask: ByteArray, gpu: Boolean): Boolean
}
