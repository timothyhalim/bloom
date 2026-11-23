package dev.bloom.editor

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import dev.bloom.editor.gl.DEBAND_RADIUS
import dev.bloom.editor.gl.Offscreen
import dev.bloom.editor.gl.Pipeline
import dev.bloom.editor.model.Layer
import java.io.File

object ImageIo {
    const val PREVIEW_MAX = 2048
    const val TILE = 1024

    /** Smallest power-of-two sample size that keeps the long side <= maxSide. */
    fun sampleSize(w: Int, h: Int, maxSide: Int): Int {
        var s = 1
        while (maxOf(w, h) / s > maxSide) s *= 2
        return s
    }

    /** The pipeline works in sRGB; wide-gamut photos are converted on decode. Sources deeper than 8 bits still decode to F16. */
    private val SRGB by lazy { ColorSpace.get(ColorSpace.Named.SRGB) } // lazy: JVM unit tests touch this object

    /** Downsampled, upright bitmap for on-screen preview. */
    fun loadPreview(cr: ContentResolver, uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, PREVIEW_MAX)
            inPreferredColorSpace = SRGB
        }
        val bmp = cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("unsupported format")
        val exif = cr.openInputStream(uri)!!.use { ExifInterface(it) }
        val m = Matrix().apply {
            postRotate(exif.rotationDegrees.toFloat())
            if (exif.isFlipped) postScale(-1f, 1f)
        }
        return if (m.isIdentity) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    /**
     * Full-resolution export with [layers] applied tile by tile on the GPU.
     * Pixels stay in sensor orientation; the copied EXIF orientation tag keeps it upright.
     * Must run on a thread with no GL context of its own (it creates one).
     */
    fun export(cr: ContentResolver, src: Uri, dst: Uri, cacheDir: File, layers: List<Layer>, deband: Boolean) {
        @Suppress("DEPRECATION") // non-deprecated overload is API 31+
        val decoder = cr.openInputStream(src)!!.use { BitmapRegionDecoder.newInstance(it, false) }
            ?: error("unsupported format")
        val w = decoder.width
        val h = decoder.height
        // Masks are drawn in upright coordinates; this maps the sensor-oriented tiles onto them.
        val exif = cr.openInputStream(src)!!.use { ExifInterface(it) }
        val orient = Matrix().apply {
            postRotate(exif.rotationDegrees.toFloat(), 0.5f, 0.5f)
            if (exif.isFlipped) postScale(-1f, 1f, 0.5f, 0.5f)
        }
        val aspect = if (exif.rotationDegrees % 180 == 0) w.toFloat() / h else h.toFloat() / w
        // Feather and deband read neighbours across tile edges, so each tile is rendered with that much overlap and cropped back.
        val uprightH = if (exif.rotationDegrees % 180 == 0) h else w
        val feather = layers.filter { it.visible }.maxOfOrNull { it.mask.maxFeather } ?: 0f
        val reach = maxOf(feather * 0.03f, if (deband) 2 * DEBAND_RADIUS else 0f)
        val pad = if (reach > 0f) (reach * uprightH).toInt() + 2 else 0
        val region = BitmapFactory.Options().apply { inPreferredColorSpace = SRGB }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val gpu = if (layers.any { it.visible && it.opacity > 0f }) Offscreen() else null
        val pipeline = gpu?.let { Pipeline() }
        try {
            for (y in 0 until h step TILE) for (x in 0 until w step TILE) {
                val r = Rect(maxOf(x - pad, 0), maxOf(y - pad, 0), minOf(x + TILE + pad, w), minOf(y + TILE + pad, h))
                var tile = decoder.decodeRegion(r, region)
                if (pipeline != null) {
                    pipeline.setSource(tile, r.left, r.top, w, h, orient, aspect)
                    pipeline.deband = deband
                    tile.recycle()
                    val padded = pipeline.renderToBitmap(layers)
                    tile = if (pad == 0) padded
                    else Bitmap.createBitmap(padded, x - r.left, y - r.top, minOf(TILE, w - x), minOf(TILE, h - y)).also { padded.recycle() }
                } else if (pad > 0) {
                    tile = Bitmap.createBitmap(tile, x - r.left, y - r.top, minOf(TILE, w - x), minOf(TILE, h - y))
                }
                canvas.drawBitmap(tile, x.toFloat(), y.toFloat(), null)
                tile.recycle()
            }
        } finally {
            pipeline?.release()
            gpu?.close()
            decoder.recycle()
        }

        val tmp = File(cacheDir, "export.jpg")
        try {
            tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            out.recycle()
            copyExif(cr, src, tmp)
            cr.openOutputStream(dst)!!.use { o -> tmp.inputStream().use { it.copyTo(o) } }
        } finally {
            tmp.delete()
        }
    }

    // Thumbnail pointers belong to the source file's layout, not ours.
    private val SKIP_TAGS = setOf(
        ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT,
        ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT_LENGTH,
        ExifInterface.TAG_THUMBNAIL_IMAGE_LENGTH,
        ExifInterface.TAG_THUMBNAIL_IMAGE_WIDTH,
    )

    private fun copyExif(cr: ContentResolver, src: Uri, file: File) {
        val from = cr.openInputStream(src)!!.use { ExifInterface(it) }
        val to = ExifInterface(file)
        ExifInterface::class.java.fields
            .filter { it.name.startsWith("TAG_") && it.type == String::class.java }
            .map { it.get(null) as String }
            .filterNot { it in SKIP_TAGS }
            .forEach { tag -> from.getAttribute(tag)?.let { to.setAttribute(tag, it) } }
        to.saveAttributes()
    }
}
