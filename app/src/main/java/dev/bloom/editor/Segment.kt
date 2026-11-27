package dev.bloom.editor

import dev.bloom.editor.model.Eraser
import dev.bloom.editor.model.Upscale
import android.graphics.Bitmap
import java.io.File
import kotlin.math.roundToInt

/** A subject mask over the whole upright photo: [w]×[h] bytes, row 0 at the top. */
class SubjectMask(val w: Int, val h: Int, val alpha: ByteArray)

/**
 * What an eraser works on: [crop], the photo around the hole (a square of 2× its long side, min 512, see [Eraser.context]),
 * and [fill] (crop-sized, 1 = fill): the hole widened a little. [at] is the crop's box in the photo (px).
 */
class EraseArea(private val photo: Bitmap, val crop: Bitmap, val fill: ByteArray, private val at: IntArray, private val box: IntArray, private val grow: Int) {
    /**
     * [filled] (the crop with the hole filled, any size) back on the photo: the filled part (the hole, widened a little,
     * plus a margin) and where it sits in the photo (px). Only the hole is taken from [filled]; the rest is the photo's own.
     */
    fun paste(filled: Bitmap): Pair<IntArray, Bitmap> {
        val (cl, ct, cr, cb) = at
        val cw = cr - cl
        val big = if (filled.width == cw && filled.height == cb - ct) filled else Bitmap.createScaledBitmap(filled, cw, cb - ct, true)
        val out = Eraser.inflate(box, grow, photo.width, photo.height)
        val ow = out[2] - out[0]
        val oh = out[3] - out[1]
        val orig = IntArray(ow * oh).also { crop.getPixels(it, 0, ow, out[0] - cl, out[1] - ct, ow, oh) }
        val gen = IntArray(ow * oh).also { big.getPixels(it, 0, ow, out[0] - cl, out[1] - ct, ow, oh) }
        val px = IntArray(ow * oh) { i -> if (fill[(out[1] - ct + i / ow) * cw + out[0] - cl + i % ow].toInt() != 0) gen[i] else orig[i] }
        if (big !== filled) big.recycle()
        return out to Bitmap.createBitmap(px, ow, oh, Bitmap.Config.ARGB_8888)
    }

    companion object {
        /** The area to erase [hole] (photo.width × photo.height bytes, non-zero = remove) in [photo]; null if nothing is marked. */
        fun of(photo: Bitmap, hole: ByteArray): EraseArea? {
            val w = photo.width
            val h = photo.height
            // widen the hole a little: brush strokes rarely cover an object's soft edge and shadow fringe
            val grow = maxOf(3, h / 300)
            val box = Eraser.bounds(hole, w, h)?.let { Eraser.inflate(it, grow, w, h) } ?: return null
            val at = Eraser.context(box, w, h)
            val (cl, ct, cr, cb) = at
            val cw = cr - cl
            val ch = cb - ct
            val keep = ByteArray(cw * ch) { i -> if (hole[(ct + i / cw) * w + cl + i % cw].toInt() != 0) 0 else -1 }
            val fill = Eraser.erode(keep, cw, ch, grow).map { if (it.toInt() == 0) 1.toByte() else 0.toByte() }.toByteArray()
            return EraseArea(photo, Bitmap.createBitmap(photo, cl, ct, cw, ch), fill, at, box, grow)
        }
    }
}

/**
 * On-device models through MNN (native, app/src/main/cpp/segment.cpp): IS-Net finds the main subject by itself,
 * MobileSAM selects whatever the pins point at, RVM mattes people; plus depth, the eraser and upscaling.
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

    /** Frees every loaded model and the embedding (low memory); each loads again on its next use. */
    @Synchronized
    fun release() {
        if (!loaded) return
        encoded = null
        release0()
    }

    /** MobileSAM: the object at [pins] (x, y in 0..1 over the upright photo, label 1 = include / 0 = exclude). */
    @Synchronized // with release(), so the embedding isn't freed between encoding and decoding
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
        // ponytail: CPU, Depth Anything gave no usable depth on a real phone's GPU (2026-11-24); retry the GPU with normal precision
        return depth(model.path, cache(model), small, false).also { small.recycle() }?.let { SubjectMask(w, h, it) }
    }

    /** RVM: the person matte of [photo] at 1024 px on the long side, 255 = person. */
    fun person(model: File, photo: Bitmap): SubjectMask? {
        if (!loaded) return null
        val s = SIZE.toFloat() / maxOf(photo.width, photo.height)
        val w = maxOf(1, (photo.width * s).roundToInt())
        val h = maxOf(1, (photo.height * s).roundToInt())
        val small = argb(photo, w, h)
        return matte(model.path, cache(model), small, gpu).also { small.recycle() }?.let { SubjectMask(w, h, it) }
    }

    /** LaMa eraser: [crop] with [fill] (crop-sized, non-zero = fill) filled in, at the crop's size. */
    fun erase(model: File, crop: Bitmap, fill: ByteArray): Bitmap? {
        if (!loaded) return null
        val cw = crop.width
        val ch = crop.height
        // the model works at 512 x 512
        val n = SIZE_ERASE
        val small = argb(crop, n, n, mutable = true)
        val m = ByteArray(n * n) { i -> fill[(i / n * ch / n) * cw + i % n * cw / n] }
        // ponytail: CPU, LaMa crashed the app on a real phone's GPU (2026-11-24); retry the GPU when a phone log shows why
        val ok = erase(model.path, cache(model), small, m, false)
        val big = if (ok) Bitmap.createScaledBitmap(small, cw, ch, true) else null
        small.recycle()
        return big
    }

    /**
     * Real-ESRGAN: [photo] made [by] (2 or 4) times bigger, in overlapping tiles blended at their seams. [tile] gets
     * (tile number from 1, tiles) before each; throwing from it stops the run. Throws OutOfMemoryError when the result's
     * bitmap doesn't fit. The model is freed afterwards.
     */
    fun upscale(model: File, photo: Bitmap, by: Int, tile: (Int, Int) -> Unit): Bitmap? {
        if (!loaded) return null
        val src = if (photo.config == Bitmap.Config.ARGB_8888) photo else photo.copy(Bitmap.Config.ARGB_8888, false)
        var out: Bitmap? = null
        try {
            out = Bitmap.createBitmap(photo.width * by, photo.height * by, Bitmap.Config.ARGB_8888)
            val (tw, xs) = Upscale.tiles(photo.width)
            val (th, ys) = Upscale.tiles(photo.height)
            val wx = xs.indices.map { Upscale.weights(xs, tw, it, by) }
            for ((j, y) in ys.withIndex()) {
                val wy = Upscale.weights(ys, th, j, by)
                for ((i, x) in xs.withIndex()) {
                    tile(j * xs.size + i + 1, xs.size * ys.size)
                    if (!upscaleTile(model.path, cache(model), src, x, y, tw, th, out, by, wx[i], wy, gpu)) return null.also { out.recycle() }
                }
            }
            return out
        } catch (e: Throwable) {
            out?.recycle()
            throw e
        } finally {
            if (src !== photo) src.recycle()
            upscaleEnd()
        }
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
    private external fun release0()

    @JvmStatic
    private external fun run(model: String, cache: String, square: Bitmap, gpu: Boolean): ByteArray?

    @JvmStatic
    private external fun samEncode(encoder: String, cache: String, photo: Bitmap, gpu: Boolean): Boolean

    @JvmStatic
    private external fun samDecode(decoder: String, pins: FloatArray): ByteArray?

    @JvmStatic
    private external fun depth(model: String, cache: String, photo: Bitmap, gpu: Boolean): ByteArray?

    @JvmStatic
    private external fun upscaleTile(
        model: String, cache: String, src: Bitmap, x: Int, y: Int, w: Int, h: Int, dst: Bitmap, by: Int, wx: FloatArray, wy: FloatArray, gpu: Boolean,
    ): Boolean

    @JvmStatic
    private external fun upscaleEnd()

    @JvmStatic
    private external fun matte(model: String, cache: String, photo: Bitmap, gpu: Boolean): ByteArray?

    @JvmStatic
    private external fun erase(model: String, cache: String, crop: Bitmap, mask: ByteArray, gpu: Boolean): Boolean
}
