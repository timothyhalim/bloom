package dev.bloom.editor

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import dev.bloom.editor.gl.DEBAND_RADIUS
import dev.bloom.editor.gl.HdrGain
import dev.bloom.editor.gl.Offscreen
import dev.bloom.editor.gl.Pipeline
import dev.bloom.editor.model.Layer
import dev.bloom.editor.model.SubjectStep
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

    /** The photo's Ultra HDR gain map (Android 14+), or null. */
    fun gainOf(bmp: Bitmap): HdrGain? {
        if (Build.VERSION.SDK_INT < 34 || !bmp.hasGainmap()) return null
        val g = bmp.gainmap ?: return null
        return HdrGain(g.gainmapContents, g.ratioMin, g.ratioMax, g.gamma, g.epsilonSdr, g.epsilonHdr)
    }

    /** Downsampled, upright bitmap for on-screen preview. Ultra HDR photos keep their gain map (rotated with them). */
    fun loadPreview(cr: ContentResolver, uri: Uri): Bitmap {
        if (Raw.isRaw(cr, uri)) Raw.decode(cr, uri, half = true)?.let { raw ->
            val s = PREVIEW_MAX.toFloat() / maxOf(raw.width, raw.height)
            if (s >= 1f) return raw
            return Bitmap.createScaledBitmap(raw, (raw.width * s).toInt(), (raw.height * s).toInt(), true).also { raw.recycle() }
        }
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
     * JPEG keeps sensor orientation and the copied EXIF orientation tag; 16-bit [png] is drawn upright,
     * since many viewers ignore orientation in PNG.
     * Must run on a thread with no GL context of its own (it creates one). [progress] gets 0..1 on this thread.
     */
    fun export(
        cr: ContentResolver, src: Uri, dst: Uri, cacheDir: File, layers: List<Layer>, deband: Boolean, png: Boolean = false,
        subjects: Map<List<Float>, SubjectMask> = emptyMap(), progress: (Float) -> Unit = {},
    ) {
        // Raw files decode whole (already upright); everything else is read tile by tile in sensor orientation.
        // ponytail: whole raw in memory (~14 B/px peak in LibRaw, then 8 B/px), fine to ~50 MP on 8 GB phones
        // Share of the bar: raw decode (when there is one), then tiles, then the last 5% for encoding.
        val rawShare = 0.35f
        val raw = if (Raw.isRaw(cr, src)) Raw.decode(cr, src, half = false) { progress(it * rawShare) } else null
        val tilesFrom = if (raw != null) rawShare else 0f
        @Suppress("DEPRECATION") // non-deprecated overload is API 31+
        val decoder = if (raw != null) null else cr.openInputStream(src)!!.use { BitmapRegionDecoder.newInstance(it, false) }
            ?: error("unsupported format")
        val w = raw?.width ?: decoder!!.width
        val h = raw?.height ?: decoder!!.height
        // Masks are drawn in upright coordinates; this maps the sensor-oriented tiles onto them.
        val exif = if (raw != null) null else cr.openInputStream(src)!!.use { ExifInterface(it) }
        val rotation = exif?.rotationDegrees ?: 0
        val flipped = exif?.isFlipped == true
        val orient = Matrix().apply {
            postRotate(rotation.toFloat(), 0.5f, 0.5f)
            if (flipped) postScale(-1f, 1f, 0.5f, 0.5f)
        }
        val aspect = if (rotation % 180 == 0) w.toFloat() / h else h.toFloat() / w
        // Feather and deband read neighbours across tile edges, so each tile is rendered with that much overlap and cropped back.
        val uprightH = if (rotation % 180 == 0) h else w
        val feather = layers.filter { it.visible }.maxOfOrNull { it.mask.maxFeather } ?: 0f
        // Subject edges look up to 3 mask texels away (joint bilateral upsampling).
        val subjectReach = if (subjects.isNotEmpty() && layers.any { l -> l.visible && l.mask.steps.any { it.visible && it is SubjectStep } }) 3f / Segment.SIZE else 0f
        val reach = maxOf(feather * 0.03f, if (deband) 2 * DEBAND_RADIUS else 0f, subjectReach)
        val pad = if (reach > 0f) (reach * uprightH).toInt() + 2 else 0
        val region = BitmapFactory.Options().apply { inPreferredColorSpace = SRGB }
        // ponytail: whole image in memory (8 bytes/px for PNG, ~400 MB at 50 MP); stream rows to the encoder if phones run out
        val upright = Matrix()
        if (png) {
            upright.postRotate(rotation.toFloat())
            if (flipped) upright.postScale(-1f, 1f)
            val b = RectF(0f, 0f, w.toFloat(), h.toFloat()).also(upright::mapRect)
            upright.postTranslate(-b.left, -b.top)
        }
        val out = if (png) Bitmap.createBitmap(if (rotation % 180 == 0) w else h, uprightH, Bitmap.Config.RGBA_F16, false, SRGB)
        else Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out).apply { concat(upright) }
        val gpu = if (layers.any { it.visible && it.opacity > 0f }) Offscreen() else null
        val pipeline = gpu?.let { Pipeline() }
        pipeline?.setSubjects(subjects)
        if (pipeline != null && raw == null && Build.VERSION.SDK_INT >= 34) {
            // The whole photo's gain map, at the preview's size so export matches it; sensor orientation like the tiles.
            cr.openInputStream(src)!!.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                    inSampleSize = sampleSize(w, h, PREVIEW_MAX)
                    inPreferredColorSpace = SRGB
                })
            }?.let { whole -> gainOf(whole)?.let(pipeline::setGainmap); whole.recycle() }
        }
        val tiles = ((w + TILE - 1) / TILE) * ((h + TILE - 1) / TILE)
        var done = 0
        try {
            for (y in 0 until h step TILE) for (x in 0 until w step TILE) {
                val r = Rect(maxOf(x - pad, 0), maxOf(y - pad, 0), minOf(x + TILE + pad, w), minOf(y + TILE + pad, h))
                var tile = if (raw != null) Bitmap.createBitmap(raw, r.left, r.top, r.width(), r.height()) else decoder!!.decodeRegion(r, region)
                if (pipeline != null) {
                    pipeline.setSource(tile, r.left, r.top, w, h, orient, aspect)
                    pipeline.deband = deband
                    tile.recycle()
                    val padded = pipeline.renderToBitmap(layers, png)
                    tile = if (pad == 0) padded
                    else Bitmap.createBitmap(padded, x - r.left, y - r.top, minOf(TILE, w - x), minOf(TILE, h - y)).also { padded.recycle() }
                } else if (pad > 0) {
                    tile = Bitmap.createBitmap(tile, x - r.left, y - r.top, minOf(TILE, w - x), minOf(TILE, h - y))
                }
                canvas.drawBitmap(tile, x.toFloat(), y.toFloat(), null)
                tile.recycle()
                progress(tilesFrom + (0.95f - tilesFrom) * ++done / tiles)
            }
        } finally {
            pipeline?.release()
            gpu?.close()
            decoder?.recycle()
            raw?.recycle()
        }

        val tmp = File(cacheDir, if (png) "export.png" else "export.jpg")
        try {
            tmp.outputStream().use { out.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, it) }
            out.recycle()
            copyExif(cr, src, tmp, upright = png || raw != null)
            cr.openOutputStream(dst)!!.use { o -> tmp.inputStream().use { it.copyTo(o) } }
            progress(1f)
        } finally {
            tmp.delete()
        }
    }

    // Pixel layout and thumbnail pointers belong to the source file (raw files describe sensor data here), not ours.
    private val SKIP_TAGS = setOf(
        ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT,
        ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT_LENGTH,
        ExifInterface.TAG_THUMBNAIL_IMAGE_LENGTH,
        ExifInterface.TAG_THUMBNAIL_IMAGE_WIDTH,
        ExifInterface.TAG_IMAGE_WIDTH,
        ExifInterface.TAG_IMAGE_LENGTH,
        ExifInterface.TAG_PIXEL_X_DIMENSION,
        ExifInterface.TAG_PIXEL_Y_DIMENSION,
        ExifInterface.TAG_BITS_PER_SAMPLE,
        ExifInterface.TAG_COMPRESSION,
        ExifInterface.TAG_PHOTOMETRIC_INTERPRETATION,
        ExifInterface.TAG_SAMPLES_PER_PIXEL,
        ExifInterface.TAG_PLANAR_CONFIGURATION,
        ExifInterface.TAG_STRIP_OFFSETS,
        ExifInterface.TAG_STRIP_BYTE_COUNTS,
        ExifInterface.TAG_ROWS_PER_STRIP,
        ExifInterface.TAG_NEW_SUBFILE_TYPE,
        ExifInterface.TAG_SUBFILE_TYPE,
        ExifInterface.TAG_DNG_VERSION,
        ExifInterface.TAG_DEFAULT_CROP_SIZE,
    )

    private fun copyExif(cr: ContentResolver, src: Uri, file: File, upright: Boolean) {
        // Some raw formats (CR3) have no EXIF ExifInterface can read; the photo matters more than its tags.
        val from = runCatching { cr.openInputStream(src)!!.use { ExifInterface(it) } }.getOrNull() ?: return
        val to = ExifInterface(file)
        ExifInterface::class.java.fields
            .filter { it.name.startsWith("TAG_") && it.type == String::class.java }
            .map { it.get(null) as String }
            .filterNot { it in SKIP_TAGS }
            .forEach { tag -> from.getAttribute(tag)?.let { to.setAttribute(tag, it) } }
        if (upright) to.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        to.saveAttributes()
    }
}
