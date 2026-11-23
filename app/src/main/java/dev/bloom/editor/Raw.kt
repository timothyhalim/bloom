package dev.bloom.editor

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.net.Uri
import dev.bloom.editor.model.Curves

/** Camera raw files (DNG, CR3, NEF, ARW, ...) through LibRaw. */
object Raw {
    private val loaded = runCatching { System.loadLibrary("bloom") }.isSuccess

    // Formats Android decodes itself; anything else is tried as raw first.
    private val PLAIN = setOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif", "image/avif", "image/gif", "image/bmp")

    fun isRaw(cr: ContentResolver, uri: Uri) = loaded && cr.getType(uri) !in PLAIN

    /** Lightroom's "Medium Contrast" point curve: raws open with roughly a camera JPEG's contrast instead of flat. */
    private val TONE = Curves(rgb = listOf(0, 0, 32, 22, 64, 56, 128, 128, 192, 196, 255, 255).map { it / 255f })
        .lut().filterIndexed { i, _ -> i % 4 == 0 }.toFloatArray()

    /**
     * Upright, white-balanced, linear sRGB RGBA_F16 bitmap, brightened so 1% of pixels reach white
     * (brighter values are kept above 1.0), with [TONE] applied. [half] skips demosaicing at half size, for previews.
     * [progress] gets 0..1 as LibRaw works. Null if LibRaw can't read it.
     */
    fun decode(cr: ContentResolver, uri: Uri, half: Boolean, progress: Progress? = null): Bitmap? =
        if (!loaded) null else cr.openFileDescriptor(uri, "r")?.use { decode(it.fd, half, TONE, progress) }

    fun interface Progress { fun report(f: Float) }

    @JvmStatic
    private external fun decode(fd: Int, half: Boolean, tone: FloatArray, progress: Progress?): Bitmap?

    @JvmStatic
    private fun alloc(w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.RGBA_F16, false, ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB))
}
