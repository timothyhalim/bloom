package dev.bloom.editor.gl

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.opengl.GLES30.*
import android.opengl.GLUtils
import android.util.Half
import dev.bloom.editor.model.BrushStep
import dev.bloom.editor.model.ColorParam
import dev.bloom.editor.model.ColorStep
import dev.bloom.editor.model.GradientStep
import dev.bloom.editor.model.Layer
import dev.bloom.editor.model.LuminanceStep
import dev.bloom.editor.model.Mask
import dev.bloom.editor.model.MaskStep
import dev.bloom.editor.model.SubjectStep
import dev.bloom.editor.SubjectMask
import kotlin.math.sqrt
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Runs the layer stack on the GPU: source texture -> one pass per visible layer -> result texture.
 * Shared by the preview and the export. Create and use it on one thread with a current GLES 3 context.
 */
class Pipeline {
    var width = 0
        private set
    var height = 0
        private set

    private var raw = 0 // the decoded pixels as uploaded
    private var rawLinear = false // F16 decodes on API 26-27 come back in linear light
    private var rawDeep = false // more than 8 bits per channel: nothing to deband
    private var source = 0 // raw, sRGB-encoded and debanded: what the layers read
    private var sourceFbo = 0
    private var prepared = false

    // Ultra HDR: the gain map turns the photo back into its full highlight range (hdrSource). The layers then run on both
    // versions and each pixel blends to the full range as far as the edits darken it, so the photo opens exactly as in the
    // gallery and lowering Exposure brings back real highlight detail.
    private var gain: HdrGain? = null
    private var gainTex = 0
    private val subjects = mutableMapOf<List<Float>, Triple<Int, Int, Int>>() // pins -> R8 texture, w, h
    private var hdrSource = 0
    private var hdrFbo = 0
    private var sdrKeep = 0 // the edited normal photo, kept while the full-range chain runs
    private var sdrFbo = 0

    /** Smooth 8-bit banding in the source before the layers run. No effect on sources with more than 8 bits. */
    var deband = true
        set(v) {
            if (field != v) { field = v; prepared = false; rendered = null }
        }
    private val tex = IntArray(2)
    private val fbo = IntArray(2)
    private val read = IntArray(2) // texture + framebuffer for glReadPixels, made on first export
    private var readW = 0
    private var readH = 0
    private var readFormat = 0
    private var rendered: List<Layer>? = null
    private var result = 0

    // Mask geometry: maps a pipeline pixel to upright image coordinates (0..1, top-left), so masks line up in export tiles too.
    private val geo = FloatArray(5) // origin x, y, full width, full height, upright aspect
    private var uprightHeight = 1f // in source pixels; feather radii are relative to it
    private val toUpright = FloatArray(9)
    private val fromUpright = FloatArray(9)
    private class MaskTex(val tex: Int, val fbo: Int, var mask: Mask? = null, var below: List<Layer>? = null)
    private val masks = HashMap<Long, MaskTex>()
    private var scratch: MaskTex? = null // ping-pong partner while combining steps
    private var brush: MaskTex? = null // one step's value when it comes from a texture (brush dabs, or feathered)
    private var soft: MaskTex? = null // feather blur intermediate

    private val colorProgram = link(PASS_VERTEX, COLOR_FRAGMENT)
    private val copyProgram = link(PASS_VERTEX, COPY_FRAGMENT)
    private val maskProgram = link(PASS_VERTEX, MASK_FRAGMENT)
    private val dabProgram = link(DAB_VERTEX, DAB_FRAGMENT)
    private val blurProgram = link(PASS_VERTEX, BLUR_FRAGMENT)
    private val prepareProgram = link(PASS_VERTEX, PREPARE_FRAGMENT)
    private val combineProgram = link(PASS_VERTEX, COMBINE_FRAGMENT)
    private val params = FloatArray(ColorParam.entries.size)
    private val curveTex = genTexture().also {
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA16F, CURVE_SIZE, 1) // half floats: an 8-bit table would add banding
    }
    private val curveData = ByteBuffer.allocateDirect(CURVE_SIZE * 16).order(ByteOrder.nativeOrder()).asFloatBuffer()

    // Half-float intermediates keep values above 1 between passes; plain RGBA8 is the fallback.
    private val half = glGetString(GL_EXTENSIONS).orEmpty()
        .let { "GL_EXT_color_buffer_half_float" in it || "GL_EXT_color_buffer_float" in it }

    // Some GPUs truncate when writing half floats, so every pass would darken by up to one half-float step
    // (~0.12 level near white). Measured once; the passes that write RGBA16F then add half a step first.
    private val truncates = half && writesTruncate()

    init {
        for (prog in listOf(colorProgram, prepareProgram, combineProgram)) {
            glUseProgram(prog)
            glUniform1i(glGetUniformLocation(prog, "uTrunc"), if (truncates) 1 else 0)
        }
        glUseProgram(colorProgram)
        glUniform1i(glGetUniformLocation(colorProgram, "uMask"), 1)
        glUniform1i(glGetUniformLocation(colorProgram, "uCurve"), 3)
        glUseProgram(prepareProgram)
        glUniform1i(glGetUniformLocation(prepareProgram, "uGain"), 1)
        glUseProgram(combineProgram)
        glUniform1i(glGetUniformLocation(combineProgram, "uSdr"), 1)
        glUniform1i(glGetUniformLocation(combineProgram, "uSrc"), 2)
        glUseProgram(maskProgram)
        glUniform1i(glGetUniformLocation(maskProgram, "uBrush"), 1)
        glUniform1i(glGetUniformLocation(maskProgram, "uBelow"), 2)
        glUniform1i(glGetUniformLocation(maskProgram, "uSubject"), 3)
    }

    /**
     * [bmp] is the whole image (preview) or one export tile at ([originX], [originY]) of a [fullW]×[fullH] image.
     * [orient] maps normalized source coordinates to upright ones (EXIF rotation); [aspect] is the upright width / height.
     */
    fun setSource(
        bmp: Bitmap, originX: Int = 0, originY: Int = 0, fullW: Int = bmp.width, fullH: Int = bmp.height,
        orient: Matrix = Matrix(), aspect: Float = fullW.toFloat() / fullH,
    ) {
        if (raw == 0) raw = genTexture().also { glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST) }
        glBindTexture(GL_TEXTURE_2D, raw)
        rawDeep = bmp.config == Bitmap.Config.RGBA_F16
        rawLinear = rawDeep && bmp.colorSpace == ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB)
        if (rawDeep) {
            // GLUtils can't upload F16 before API 28; raw half floats work everywhere
            val buf = ByteBuffer.allocateDirect(bmp.byteCount).order(ByteOrder.nativeOrder())
            bmp.copyPixelsToBuffer(buf)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, bmp.width, bmp.height, 0, GL_RGBA, GL_HALF_FLOAT, buf.rewind())
        } else {
            GLUtils.texImage2D(GL_TEXTURE_2D, 0, bmp, 0)
        }
        prepared = false
        if (bmp.width != width || bmp.height != height) {
            width = bmp.width
            height = bmp.height
            allocTargets()
        }
        geo[0] = originX.toFloat(); geo[1] = originY.toFloat(); geo[2] = fullW.toFloat(); geo[3] = fullH.toFloat(); geo[4] = aspect
        uprightHeight = sqrt(fullW.toFloat() * fullH / aspect) // upright w × h = full w × h, and w / h = aspect
        columnMajor(orient, toUpright)
        columnMajor(Matrix().also { orient.invert(it) }, fromUpright)
        masks.values.forEach { it.mask = null }
        rendered = null
    }

    /** The whole photo's Ultra HDR gain map, in the same orientation as [setSource]'s tiles; null for an ordinary photo. */
    fun setGainmap(g: HdrGain?) {
        glDeleteTextures(1, intArrayOf(gainTex), 0)
        gainTex = 0
        gain = g
        if (g != null) {
            gainTex = genTexture()
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            // single-channel maps go up as RGBA (value in alpha): GL_ALPHA textures stall some drivers (seen on the emulator)
            val m = if (g.map.config == Bitmap.Config.ALPHA_8) g.map.copy(Bitmap.Config.ARGB_8888, false) else g.map
            GLUtils.texImage2D(GL_TEXTURE_2D, 0, m, 0)
            if (m !== g.map) m.recycle()
        }
        prepared = false
        rendered = null
    }

    /** Masks for SubjectSteps, by their pins (empty = the automatic subject). Replaces the previous set. */
    fun setSubjects(all: Map<List<Float>, SubjectMask>) {
        glDeleteTextures(subjects.size, subjects.values.map { it.first }.toIntArray(), 0)
        subjects.clear()
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        for ((pins, m) in all) {
            val t = genTexture()
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, m.w, m.h, 0, GL_RED, GL_UNSIGNED_BYTE, ByteBuffer.wrap(m.alpha))
            subjects[pins] = Triple(t, m.w, m.h)
        }
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4)
        masks.values.forEach { it.mask = null } // cached masks may contain old subjects
        rendered = null
    }

    /** Renders [layers] (bottom to top) and returns the texture holding the result. Cached until layers change. */
    fun render(layers: List<Layer>): Int {
        if (!prepared) prepare()
        val active = layers.filter { it.visible && it.opacity > 0f }
        if (active == rendered) return result
        masks.keys.filter { id -> layers.none { it.id == id } }.forEach { id -> masks.remove(id)?.let(::delete) }
        var input = source
        if (gain != null && active.isNotEmpty()) {
            val sdr = chain(source, active, sdrFbo to sdrKeep)
            val hdr = chain(hdrSource, active, null) // masks come from the cache filled by the normal chain
            val out = if (hdr == tex[0]) 1 else 0
            glBindFramebuffer(GL_FRAMEBUFFER, fbo[out])
            glViewport(0, 0, width, height)
            glUseProgram(combineProgram)
            glActiveTexture(GL_TEXTURE1)
            glBindTexture(GL_TEXTURE_2D, sdr)
            glActiveTexture(GL_TEXTURE2)
            glBindTexture(GL_TEXTURE_2D, source)
            drawQuad(hdr)
            input = tex[out]
        } else if (active.isNotEmpty()) {
            input = chain(source, active, null)
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        if (active.isNotEmpty()) {
            glBindTexture(GL_TEXTURE_2D, input)
            glGenerateMipmap(GL_TEXTURE_2D) // the preview minifies the result
        }
        rendered = active
        result = input
        return input
    }

    /** Runs [active] over [src] through the ping-pong targets, the last pass into [last] (fbo, texture) if given. */
    private fun chain(src: Int, active: List<Layer>, last: Pair<Int, Int>?): Int {
        var input = src
        active.forEachIndexed { i, l ->
            val m = if (l.mask.active) maskFor(l, input, active.subList(0, i)) else 0
            val out = i % 2
            val toLast = last != null && i == active.lastIndex
            glBindFramebuffer(GL_FRAMEBUFFER, if (toLast) last!!.first else fbo[out])
            glViewport(0, 0, width, height)
            glUseProgram(colorProgram)
            for (p in params.indices) params[p] = l.values[p] / 100f
            glUniform1fv(glGetUniformLocation(colorProgram, "uP"), params.size, params, 0)
            glUniform1f(glGetUniformLocation(colorProgram, "uOpacity"), l.opacity)
            glUniform1i(glGetUniformLocation(colorProgram, "uBlend"), l.blend.ordinal)
            glUniform1i(glGetUniformLocation(colorProgram, "uMasked"), if (m != 0) 1 else 0)
            glUniform1i(glGetUniformLocation(colorProgram, "uInvert"), if (l.mask.invert) 1 else 0)
            glUniform1i(glGetUniformLocation(colorProgram, "uCurves"), if (l.curves.active) 1 else 0)
            if (l.curves.active) {
                // one shared table, refilled per layer; 16 KB, and only when the layers change
                glActiveTexture(GL_TEXTURE3)
                glBindTexture(GL_TEXTURE_2D, curveTex)
                curveData.position(0)
                curveData.put(l.curves.lut(CURVE_SIZE)).position(0)
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, CURVE_SIZE, 1, GL_RGBA, GL_FLOAT, curveData)
            }
            glActiveTexture(GL_TEXTURE1)
            glBindTexture(GL_TEXTURE_2D, m)
            drawQuad(input)
            input = if (toLast) last!!.second else tex[out]
        }
        return input
    }

    /** The mask texture of layer [id] from the last [render], or 0. Same pixel grid as the result. */
    fun maskTexture(id: Long): Int = masks[id]?.takeIf { it.mask != null }?.tex ?: 0

    /** Renders [layers] and reads the result back (export): 8-bit, or with [deep] a 16-bit float sRGB bitmap, opaque. */
    fun renderToBitmap(layers: List<Layer>, deep: Boolean = false): Bitmap {
        val t = render(layers)
        if (!deep) {
            copyToRead(t)
            val buf = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
            glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, buf)
            glBindFramebuffer(GL_FRAMEBUFFER, 0)
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(buf.rewind()) }
        }
        // Float buffers only promise GL_FLOAT readback; halve on the CPU, clamped like the PNG encoder would.
        copyToRead(t, GL_RGBA16F)
        val f = ByteBuffer.allocateDirect(width * height * 16).order(ByteOrder.nativeOrder())
        glReadPixels(0, 0, width, height, GL_RGBA, GL_FLOAT, f)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        val fl = f.asFloatBuffer()
        val h = ByteBuffer.allocateDirect(width * height * 8).order(ByteOrder.nativeOrder())
        val hs = h.asShortBuffer()
        val one = Half.toHalf(1f)
        for (i in 0 until width * height) {
            for (c in 0..2) hs.put(Half.toHalf(fl.get(i * 4 + c).coerceIn(0f, 1f)))
            hs.put(one)
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.RGBA_F16, false, ColorSpace.get(ColorSpace.Named.SRGB))
            .apply { copyPixelsFromBuffer(h) }
    }

    /** Eyedropper: the color of [layers] at upright (u, v), averaged over 3×3 pixels, as sRGB 0..1. Preview only (no tile offset). */
    fun sample(layers: List<Layer>, u: Float, v: Float): FloatArray {
        copyToRead(render(layers))
        val x = (u * width).toInt().coerceIn(1, width - 2)
        val y = (v * height).toInt().coerceIn(1, height - 2)
        val buf = ByteBuffer.allocateDirect(9 * 4).order(ByteOrder.nativeOrder())
        glReadPixels(x - 1, y - 1, 3, 3, GL_RGBA, GL_UNSIGNED_BYTE, buf)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        return FloatArray(3) { c -> (0 until 9).sumOf { buf.get(it * 4 + c).toInt() and 0xFF } / 9f / 255f }
    }

    /** Copies [t] into the read target of [format] and leaves that framebuffer bound for glReadPixels. */
    private fun copyToRead(t: Int, format: Int = GL_RGBA8) {
        if (readW != width || readH != height || readFormat != format) {
            glDeleteTextures(1, read, 0)
            glDeleteFramebuffers(1, read, 1)
            read[0] = genTexture()
            glTexStorage2D(GL_TEXTURE_2D, 1, format, width, height)
            glGenFramebuffers(1, read, 1)
            glBindFramebuffer(GL_FRAMEBUFFER, read[1])
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, read[0], 0)
            checkFramebuffer()
            readW = width
            readH = height
            readFormat = format
        }
        glBindFramebuffer(GL_FRAMEBUFFER, read[1])
        glViewport(0, 0, width, height)
        glUseProgram(copyProgram)
        drawQuad(t)
    }

    fun release() {
        glDeleteTextures(4, intArrayOf(raw, source, curveTex, gainTex), 0)
        setSubjects(emptyMap())
        freeHdrTargets()
        glDeleteFramebuffers(1, intArrayOf(sourceFbo), 0)
        glDeleteTextures(2, tex, 0)
        glDeleteFramebuffers(2, fbo, 0)
        glDeleteTextures(1, read, 0)
        glDeleteFramebuffers(1, read, 1)
        freeMasks()
        listOf(colorProgram, copyProgram, maskProgram, dabProgram, blurProgram, prepareProgram, combineProgram).forEach(::glDeleteProgram)
    }

    /** Writes 0.5 + 3/4 of a half-float step into an RGBA16F target and reads it back: 0.5 means the GPU truncates. */
    private fun writesTruncate(): Boolean {
        val t = genTexture()
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA16F, 1, 1)
        val f = IntArray(1).also { glGenFramebuffers(1, it, 0) }[0]
        glBindFramebuffer(GL_FRAMEBUFFER, f)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, t, 0)
        val prog = link(PASS_VERTEX, "#version 300 es\nprecision highp float;\nout vec4 o;\nvoid main() { o = vec4(0.5 + 0.75 / 2048.0); }")
        glViewport(0, 0, 1, 1)
        glUseProgram(prog)
        drawFullQuad()
        val out = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder())
        glReadPixels(0, 0, 1, 1, GL_RGBA, GL_FLOAT, out)
        val ok = glGetError() == GL_NO_ERROR
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glDeleteProgram(prog)
        glDeleteFramebuffers(1, intArrayOf(f), 0)
        glDeleteTextures(1, intArrayOf(t), 0)
        return ok && out.getFloat(0) < 0.5f + 0.5f / 2048f
    }

    /** raw -> source: sRGB-encode linear decodes and deband 8-bit ones, then mipmap for the preview. */
    private fun prepare() {
        drawPrepare(sourceFbo, false)
        glBindTexture(GL_TEXTURE_2D, source)
        glGenerateMipmap(GL_TEXTURE_2D)
        val g = gain
        if (g != null) {
            if (hdrSource == 0) {
                target().let { (t, f) -> hdrSource = t; hdrFbo = f }
                target().let { (t, f) -> sdrKeep = t; sdrFbo = f }
            }
            glUseProgram(prepareProgram)
            glUniform3fv(glGetUniformLocation(prepareProgram, "uRatioMin"), 1, g.ratioMin, 0)
            glUniform3fv(glGetUniformLocation(prepareProgram, "uRatioMax"), 1, g.ratioMax, 0)
            glUniform3fv(glGetUniformLocation(prepareProgram, "uGamma"), 1, g.gamma, 0)
            glUniform3fv(glGetUniformLocation(prepareProgram, "uEpsSdr"), 1, g.epsSdr, 0)
            glUniform3fv(glGetUniformLocation(prepareProgram, "uEpsHdr"), 1, g.epsHdr, 0)
            glUniform1i(glGetUniformLocation(prepareProgram, "uGainAlpha"), if (g.map.config == Bitmap.Config.ALPHA_8) 1 else 0)
            glUniform2f(glGetUniformLocation(prepareProgram, "uFull"), geo[2], geo[3])
            glActiveTexture(GL_TEXTURE1)
            glBindTexture(GL_TEXTURE_2D, gainTex)
            drawPrepare(hdrFbo, true)
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        prepared = true
        rendered = null
    }

    private fun drawPrepare(target: Int, hdr: Boolean) {
        glBindFramebuffer(GL_FRAMEBUFFER, target)
        glViewport(0, 0, width, height)
        glUseProgram(prepareProgram)
        glUniform1i(glGetUniformLocation(prepareProgram, "uHdr"), if (hdr) 1 else 0)
        glUniform1i(glGetUniformLocation(prepareProgram, "uLinear"), if (rawLinear) 1 else 0)
        glUniform1i(glGetUniformLocation(prepareProgram, "uDeband"), if (deband && !rawDeep) 1 else 0)
        glUniform1f(glGetUniformLocation(prepareProgram, "uRadius"), DEBAND_RADIUS * uprightHeight)
        glUniform2f(glGetUniformLocation(prepareProgram, "uOrigin"), geo[0], geo[1])
        drawQuad(raw)
    }

    /** A single-level render target of the pipeline's size: (texture, framebuffer). */
    private fun target(): Pair<Int, Int> {
        val t = genTexture()
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexStorage2D(GL_TEXTURE_2D, 1, if (half) GL_RGBA16F else GL_RGBA8, width, height)
        val f = IntArray(1).also { glGenFramebuffers(1, it, 0) }[0]
        glBindFramebuffer(GL_FRAMEBUFFER, f)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, t, 0)
        checkFramebuffer()
        return t to f
    }

    private fun freeHdrTargets() {
        glDeleteTextures(2, intArrayOf(hdrSource, sdrKeep), 0)
        glDeleteFramebuffers(2, intArrayOf(hdrFbo, sdrFbo), 0)
        hdrSource = 0; hdrFbo = 0; sdrKeep = 0; sdrFbo = 0
    }

    /**
     * Builds the layer's mask texture, or reuses it if neither the mask nor (for Color/Luminance steps) the layers below
     * have changed since this source was set. [below] is the composite under the layer, [belowLayers] what made it.
     */
    private fun maskFor(l: Layer, below: Int, belowLayers: List<Layer>): Int {
        val e = masks.getOrPut(l.id) { newMask() }
        val key = if (l.mask.readsBelow) belowLayers else null
        if (e.mask == l.mask && e.below == key) return e.tex
        e.mask = l.mask
        e.below = key
        val steps = l.mask.steps.filter { it.visible }
        val other = scratch!!
        val b = brush!!
        steps.forEachIndexed { i, s ->
            // A step whose value comes from a texture: brush dabs, or any feathered step drawn and blurred first.
            val fromTex = s is BrushStep || s.feather > 0f
            if (s is BrushStep) drawDabs(s) else if (fromTex) drawStep(s, b.fbo, 0, below, first = true, valueOnly = true)
            if (s.feather > 0f) feather(s.feather)
            // alternate targets so the last step lands in the layer's own texture
            val (dst, prev) = if ((steps.size - 1 - i) % 2 == 0) e to other else other to e
            drawStep(if (fromTex) null else s, dst.fbo, prev.tex, below, first = i == 0, valueOnly = false, add = s.add)
        }
        return e.tex
    }

    /**
     * One pass of the mask program into [fbo]. [s] = null takes the step value from the brush texture.
     * valueOnly writes the step value itself; otherwise it is combined with [prev] (the mask so far).
     */
    private fun drawStep(s: MaskStep?, fbo: Int, prev: Int, below: Int, first: Boolean, valueOnly: Boolean, add: Boolean = true) {
        val p = maskProgram
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glViewport(0, 0, width, height)
        glUseProgram(p)
        setGeometry(p, toUpright)
        glUniform1i(glGetUniformLocation(p, "uFirst"), if (first) 1 else 0)
        glUniform1i(glGetUniformLocation(p, "uValueOnly"), if (valueOnly) 1 else 0)
        glUniform1i(glGetUniformLocation(p, "uAdd"), if (add) 1 else 0)
        val kind = when (s) {
            null, is BrushStep -> 0
            is GradientStep -> if (s.radial) 2 else 1
            is ColorStep -> 3
            is LuminanceStep -> 4
            is SubjectStep -> 5
        }
        glUniform1i(glGetUniformLocation(p, "uKind"), kind)
        when (s) {
            is GradientStep -> glUniform4f(glGetUniformLocation(p, "uG"), s.x0, s.y0, s.x1, s.y1)
            is LuminanceStep -> glUniform4f(glGetUniformLocation(p, "uG"), s.a, s.b, s.c, s.d)
            is SubjectStep -> {
                val (_, w, h) = subjects[s.pins] ?: Triple(0, 1, 1)
                glUniform4f(glGetUniformLocation(p, "uG"), if (s.background) 1f else 0f, w.toFloat(), h.toFloat(), 0f)
                glUniformMatrix3fv(glGetUniformLocation(p, "uUnmap"), 1, false, fromUpright, 0)
            }
            is ColorStep -> {
                val n = minOf(s.samples.size / 3, ColorStep.MAX_SAMPLES)
                glUniform1i(glGetUniformLocation(p, "uN"), n)
                if (n > 0) glUniform3fv(glGetUniformLocation(p, "uS"), n, s.samples.toFloatArray(), 0)
                glUniform4f(glGetUniformLocation(p, "uG"), s.range, s.smooth, 0f, 0f)
            }
            else -> Unit
        }
        glActiveTexture(GL_TEXTURE1)
        glBindTexture(GL_TEXTURE_2D, brush!!.tex)
        glActiveTexture(GL_TEXTURE2)
        glBindTexture(GL_TEXTURE_2D, below)
        glActiveTexture(GL_TEXTURE3)
        glBindTexture(GL_TEXTURE_2D, (s as? SubjectStep)?.let { subjects[it.pins]?.first } ?: 0)
        drawQuad(prev)
    }

    /** Blurs the brush texture in place: horizontal into [soft], vertical back. Radius = [amount] × 3% of the upright image height. */
    private fun feather(amount: Float) {
        val taps = 12 // per side; the kernel spans ±2σ
        val stride = amount * 0.03f * uprightHeight / taps // source pixels per tap, the same on both axes
        val b = brush!!
        val t = soft!!
        glUseProgram(blurProgram)
        for ((src, dst, dir) in listOf(Triple(b, t, 0), Triple(t, b, 1))) {
            glBindFramebuffer(GL_FRAMEBUFFER, dst.fbo)
            glViewport(0, 0, width, height)
            glUniform2f(glGetUniformLocation(blurProgram, "uStep"), if (dir == 0) stride / width else 0f, if (dir == 1) stride / height else 0f)
            drawQuad(src.tex)
        }
    }

    private fun drawDabs(s: BrushStep) {
        glBindFramebuffer(GL_FRAMEBUFFER, brush!!.fbo)
        glViewport(0, 0, width, height)
        glClearBufferfv(GL_COLOR, 0, floatArrayOf(0f, 0f, 0f, 0f), 0)
        glEnable(GL_BLEND)
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_COLOR) // dst = a + dst × (1 − a): flow builds up where dabs overlap
        glUseProgram(dabProgram)
        setGeometry(dabProgram, fromUpright)
        glUniform2f(glGetUniformLocation(dabProgram, "uSize"), width.toFloat(), height.toFloat())
        glEnableVertexAttribArray(1)
        glVertexAttribDivisor(1, 1)
        for (st in s.strokes) {
            if (st.points.isEmpty()) continue
            glUniform1f(glGetUniformLocation(dabProgram, "uRadius"), st.radius)
            glUniform1f(glGetUniformLocation(dabProgram, "uSoft"), st.softness)
            glUniform1f(glGetUniformLocation(dabProgram, "uFlow"), st.flow)
            // ponytail: dab buffers are rebuilt on every mask change; keep them per stroke if long strokes get slow.
            val centres = ByteBuffer.allocateDirect(st.points.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                .put(st.points.toFloatArray()).apply { position(0) }
            glVertexAttribPointer(1, 2, GL_FLOAT, false, 0, centres)
            glEnableVertexAttribArray(0)
            glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, QUAD)
            glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, st.points.size / 2)
        }
        glVertexAttribDivisor(1, 0)
        glDisableVertexAttribArray(1)
        glDisable(GL_BLEND)
    }

    private fun setGeometry(program: Int, m: FloatArray) {
        glUniformMatrix3fv(glGetUniformLocation(program, "uMap"), 1, false, m, 0)
        glUniform2f(glGetUniformLocation(program, "uOrigin"), geo[0], geo[1])
        glUniform2f(glGetUniformLocation(program, "uFull"), geo[2], geo[3])
        glUniform1f(glGetUniformLocation(program, "uAspect"), geo[4])
    }

    private fun newMask(): MaskTex {
        val t = genTexture()
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR) // single level; the overlay samples it
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_R8, width, height)
        val f = IntArray(1).also { glGenFramebuffers(1, it, 0) }[0]
        glBindFramebuffer(GL_FRAMEBUFFER, f)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, t, 0)
        checkFramebuffer()
        return MaskTex(t, f)
    }

    private fun delete(m: MaskTex) {
        glDeleteTextures(1, intArrayOf(m.tex), 0)
        glDeleteFramebuffers(1, intArrayOf(m.fbo), 0)
    }

    private fun freeMasks() {
        (masks.values + listOfNotNull(scratch, brush, soft)).forEach(::delete)
        masks.clear()
        scratch = null
        brush = null
        soft = null
    }

    private fun allocTargets() {
        glDeleteTextures(2, tex, 0)
        glDeleteFramebuffers(2, fbo, 0)
        glGenFramebuffers(2, fbo, 0)
        val levels = 32 - Integer.numberOfLeadingZeros(maxOf(width, height))
        glDeleteTextures(1, intArrayOf(source), 0)
        glDeleteFramebuffers(1, intArrayOf(sourceFbo), 0)
        freeHdrTargets()
        source = genTexture()
        glTexStorage2D(GL_TEXTURE_2D, levels, if (half) GL_RGBA16F else GL_RGBA8, width, height)
        sourceFbo = IntArray(1).also { glGenFramebuffers(1, it, 0) }[0]
        glBindFramebuffer(GL_FRAMEBUFFER, sourceFbo)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, source, 0)
        checkFramebuffer()
        for (i in 0..1) {
            tex[i] = genTexture()
            glTexStorage2D(GL_TEXTURE_2D, levels, if (half) GL_RGBA16F else GL_RGBA8, width, height)
            glBindFramebuffer(GL_FRAMEBUFFER, fbo[i])
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex[i], 0)
            checkFramebuffer()
        }
        freeMasks()
        scratch = newMask()
        brush = newMask()
        soft = newMask()
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
    }

    private fun drawQuad(input: Int) {
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, input)
        drawFullQuad()
    }

    private companion object {
        const val CURVE_SIZE = 1024

        // Deband (after libplacebo/f3kdb): average 4 random samples around the pixel and take the average only when
        // it is within 3 levels (JPEG noise included), so 8-bit steps in flat areas melt while edges and texture stay.
        // Random offsets hash the full-image pixel position, so export tiles agree where they overlap.
        const val PREPARE_FRAGMENT = """#version 300 es
precision highp float;
precision highp int;
uniform sampler2D uTex;
uniform bool uLinear;
uniform bool uDeband;
""" + HALF_ROUND + """
uniform float uRadius;
uniform vec2 uOrigin;
uniform bool uHdr;
uniform sampler2D uGain;
uniform bool uGainAlpha;
uniform vec3 uRatioMin;
uniform vec3 uRatioMax;
uniform vec3 uGamma;
uniform vec3 uEpsSdr;
uniform vec3 uEpsHdr;
uniform vec2 uFull;
out vec4 outColor;
vec3 lin(vec3 c) { c = max(c, 0.0); return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c)); }
vec3 srgb(vec3 c) { c = max(c, 0.0); return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c)); }
vec3 enc(vec3 c) { c = max(c, 0.0); return uLinear ? mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c)) : c; }
vec3 fetch(vec2 p) { return enc(texelFetch(uTex, clamp(ivec2(p), ivec2(0), textureSize(uTex, 0) - 1), 0).rgb); }
uvec3 pcg3d(uvec3 v) {
    v = v * 1664525u + 1013904223u;
    v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
    v ^= v >> 16u;
    v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
    return v;
}
void main() {
    vec2 p = gl_FragCoord.xy;
    vec3 c = fetch(p);
    if (uDeband) {
        for (int i = 1; i <= 2; i++) {
            uvec3 h = pcg3d(uvec3(uvec2(p + uOrigin), uint(i)));
            float a = float(h.x >> 8) / 16777216.0 * 6.2831853;
            vec2 o = uRadius * float(i) * float(h.y >> 8) / 16777216.0 * vec2(cos(a), sin(a));
            vec3 avg = 0.25 * (fetch(p + o) + fetch(p - o) + fetch(p + vec2(-o.y, o.x)) + fetch(p - vec2(-o.y, o.x)));
            if (all(lessThan(abs(avg - c), vec3(3.0 / 255.0 / float(i))))) c = avg;
        }
    }
    if (uHdr) {
        // Ultra HDR at full headroom (android.graphics.Gainmap): hdr = (sdr + epsSdr) * exp(mix(ln min, ln max, g^gamma)) - epsHdr
        vec4 t = texture(uGain, (p + uOrigin) / uFull);
        vec3 g = uGainAlpha ? vec3(t.a) : t.rgb;
        c = srgb((lin(c) + uEpsSdr) * exp(mix(log(uRatioMin), log(uRatioMax), pow(g, uGamma))) - uEpsHdr);
    }
    outColor = vec4(store(c), 1.0);
}"""

        // Ultra HDR: blend the edited normal photo (uSdr) toward the edited full-range one (uTex) as far as the edits darkened
        // the pixel: unchanged or brighter keeps the gallery look; a stop darker or more shows the full range.
        const val COMBINE_FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uSdr;
uniform sampler2D uSrc;
out vec4 outColor;
""" + HALF_ROUND + """
float luma(vec3 c) {
    c = max(c, 0.0);
    return dot(mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c)), vec3(0.2126, 0.7152, 0.0722));
}
void main() {
    ivec2 px = ivec2(gl_FragCoord.xy);
    vec3 h = texelFetch(uTex, px, 0).rgb;
    vec3 s = texelFetch(uSdr, px, 0).rgb;
    float r = luma(s) / max(luma(texelFetch(uSrc, px, 0).rgb), 1e-4);
    outColor = vec4(store(mix(s, h, 1.0 - smoothstep(0.5, 1.0, r))), 1.0);
}"""

        /** android.graphics.Matrix is row-major; GLES wants column-major without transpose. */
        fun columnMajor(m: Matrix, out: FloatArray) {
            val v = FloatArray(9).also(m::getValues)
            for (r in 0..2) for (c in 0..2) out[c * 3 + r] = v[r * 3 + c]
        }

        // Passes are 1:1, so fragments fetch their own texel; no coordinates needed.
        const val PASS_VERTEX = """#version 300 es
layout(location = 0) in vec2 aPos;
void main() { gl_Position = vec4(aPos, 0.0, 1.0); }"""

        const val COPY_FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D uTex;
out vec4 outColor;
""" + DITHER + """
void main() { outColor = vec4(dither(texelFetch(uTex, ivec2(gl_FragCoord.xy), 0).rgb), 1.0); }"""

        // uP: Exposure, Contrast, Highlights, Shadows, Temp, Tint, Saturation, Vibrance, each -1..1.
        // Exposure and white balance work in linear light; tone and saturation on the sRGB-encoded value.
        // uBlend follows the Blend enum: Normal, Multiply, Screen, Overlay, Soft light (base = layers below).
        const val COLOR_FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uMask;
uniform float uP[8];
uniform float uOpacity;
uniform int uBlend;
uniform bool uMasked;
uniform bool uInvert;
uniform bool uCurves;
""" + HALF_ROUND + """
uniform sampler2D uCurve; // CURVE_SIZE x 1 tone table, rgb = output per channel
out vec4 outColor;
const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
vec3 toLinear(vec3 c) { c = max(c, 0.0); return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c)); }
vec3 toSrgb(vec3 c) { c = max(c, 0.0); return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c)); }
vec3 blend(vec3 b, vec3 s) {
    b = clamp(b, 0.0, 1.0);
    s = clamp(s, 0.0, 1.0);
    if (uBlend == 1) return b * s;
    if (uBlend == 2) return 1.0 - (1.0 - b) * (1.0 - s);
    if (uBlend == 3) return mix(2.0 * b * s, 1.0 - 2.0 * (1.0 - b) * (1.0 - s), step(0.5, b));
    return (1.0 - 2.0 * s) * b * b + 2.0 * s * b;
}
void main() {
    ivec2 px = ivec2(gl_FragCoord.xy);
    vec4 src = texelFetch(uTex, px, 0);
    vec3 lin = toLinear(src.rgb) * exp2(uP[0] * 3.0);
    lin *= vec3(1.0 + 0.2 * uP[4], 1.0 - 0.2 * uP[5], 1.0 - 0.2 * uP[4]);
    vec3 p = toSrgb(lin);
    float l = dot(p, LUMA);
    float nl = l + uP[3] * 0.35 * (1.0 - smoothstep(0.0, 0.5, l)) + uP[2] * 0.35 * smoothstep(0.5, 1.0, l);
    p *= nl / max(l, 1e-4);
    p = (p - 0.5) * (1.0 + uP[1]) + 0.5;
    l = dot(p, LUMA);
    float sat = clamp(max(p.r, max(p.g, p.b)) - min(p.r, min(p.g, p.b)), 0.0, 1.0);
    p = mix(vec3(l), p, 1.0 + uP[6] + uP[7] * (1.0 - sat));
    if (uCurves) {
        // The table covers 0..1; brighter values (raw, 16-bit) ride on top so later layers can still pull them back.
        vec3 over = max(p - 1.0, 0.0);
        vec3 q = clamp(p, 0.0, 1.0) * (1023.0 / 1024.0) + 0.5 / 1024.0; // texel centres
        p = vec3(texture(uCurve, vec2(q.r, 0.5)).r, texture(uCurve, vec2(q.g, 0.5)).g, texture(uCurve, vec2(q.b, 0.5)).b) + over;
    }
    if (uBlend != 0) p = blend(src.rgb, p);
    float m = 1.0;
    if (uMasked) {
        m = texelFetch(uMask, px, 0).r;
        if (uInvert) m = 1.0 - m;
    }
    outColor = vec4(store(mix(src.rgb, p, uOpacity * m)), 1.0);
}"""

        // Combines one mask step into the mask so far (uTex), or with uValueOnly writes the step value alone.
        // uKind: 0 from texture (uBrush), 1 linear, 2 radial gradient, 3 color range, 4 luminance range, 5 subject (uG.x = 1: background).
        // Gradient math runs in upright image coordinates scaled by aspect, so circles stay round.
        // Color and luminance read the composite of the layers below (uBelow).
        const val MASK_FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uBrush;
uniform sampler2D uBelow;
uniform sampler2D uSubject;
uniform mat3 uMap;
uniform mat3 uUnmap; // upright -> full image 0..1 (subject only)
uniform vec2 uOrigin;
uniform vec2 uFull;
uniform float uAspect;
uniform bool uFirst;
uniform bool uAdd;
uniform bool uValueOnly;
uniform int uKind;
uniform vec4 uG;
uniform vec3 uS[4];
uniform int uN;
out vec4 outColor;
vec3 toLinear(vec3 c) { return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c)); }
vec3 oklab(vec3 c) {
    c = toLinear(clamp(c, 0.0, 1.0));
    vec3 lms = pow(vec3(
        0.4122214708 * c.r + 0.5363325363 * c.g + 0.0514459929 * c.b,
        0.2119034982 * c.r + 0.6806995451 * c.g + 0.1073969566 * c.b,
        0.0883024619 * c.r + 0.2817188376 * c.g + 0.6299787005 * c.b), vec3(1.0 / 3.0));
    return vec3(
        0.2104542553 * lms.x + 0.7936177850 * lms.y - 0.0040720468 * lms.z,
        1.9779984951 * lms.x - 2.4285922050 * lms.y + 0.4505937099 * lms.z,
        0.0259040371 * lms.x + 0.7827717662 * lms.y - 0.8086757660 * lms.z);
}
// 0 below a, 1 from b; a hard step when a == b
float rise(float a, float b, float x) { return b <= a ? step(a, x) : smoothstep(a, b, x); }
void main() {
    ivec2 px = ivec2(gl_FragCoord.xy);
    float s;
    if (uKind == 0) s = texelFetch(uBrush, px, 0).r;
    else if (uKind <= 2) {
        vec2 a = vec2(uAspect, 1.0);
        vec2 q = (uMap * vec3((gl_FragCoord.xy + uOrigin) / uFull, 1.0)).xy * a;
        vec2 q0 = uG.xy * a;
        vec2 d = uG.zw * a - q0;
        if (uKind == 1) s = 1.0 - smoothstep(0.0, 1.0, dot(q - q0, d) / max(dot(d, d), 1e-8));
        else s = 1.0 - smoothstep(0.5, 1.0, length(q - q0) / max(length(d), 1e-6));
    } else if (uKind == 5) {
        // Joint bilateral upsampling of the low-res model mask: of the 4x4 nearest mask texels, those whose spot in the
        // photo looks like this pixel count most, so the edge snaps to the photo's edge instead of a blurry halo.
        vec2 n = uG.yz;
        vec2 t = (uMap * vec3((gl_FragCoord.xy + uOrigin) / uFull, 1.0)).xy * n - 0.5;
        vec2 base = floor(t);
        vec3 g0 = texelFetch(uBelow, px, 0).rgb;
        ivec2 last = textureSize(uBelow, 0) - 1;
        float sum = 0.0, wsum = 0.0;
        for (int j = -1; j <= 2; j++) for (int i = -1; i <= 2; i++) {
            vec2 c = base + vec2(i, j);
            vec2 d = t - c;
            vec2 at = (uUnmap * vec3((c + 0.5) / n, 1.0)).xy * uFull - uOrigin;
            vec3 g = texelFetch(uBelow, clamp(ivec2(at), ivec2(0), last), 0).rgb - g0;
            float w = exp(-0.5 * dot(d, d) - dot(g, g) * 200.0); // spatial sigma 1 texel, color sigma 0.05
            sum += w * texelFetch(uSubject, clamp(ivec2(c), ivec2(0), ivec2(n) - 1), 0).r;
            wsum += w;
        }
        s = sum / max(wsum, 1e-6);
        if (uG.x > 0.5) s = 1.0 - s;
    } else {
        vec3 c = texelFetch(uBelow, px, 0).rgb;
        if (uKind == 3) {
            // lightness counts half, so a sampled blue still matches its lighter and darker shades
            vec3 w = vec3(0.5, 1.0, 1.0);
            vec3 lab = oklab(c);
            float d = 10.0;
            for (int i = 0; i < 4; i++) if (i < uN) d = min(d, length((lab - oklab(uS[i])) * w));
            float r = uG.x * 0.25;
            s = 1.0 - smoothstep(r, r + 0.01 + uG.y * 0.15, d);
            if (uN == 0) s = 0.0;
        } else {
            float l = dot(clamp(c, 0.0, 1.0), vec3(0.2126, 0.7152, 0.0722));
            s = rise(uG.x, uG.y, l) * (uG.w <= uG.z ? step(l, uG.z) : 1.0 - smoothstep(uG.z, uG.w, l));
        }
    }
    float prev = uFirst ? 0.0 : texelFetch(uTex, px, 0).r;
    outColor = vec4(uValueOnly ? s : uAdd ? max(prev, s) : prev * (1.0 - s), 0.0, 0.0, 1.0);
}"""

        // Separable Gaussian, 25 taps spanning ±2σ, uStep apart (uv units, one axis at a time).
        const val BLUR_FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform vec2 uStep;
out vec4 outColor;
void main() {
    vec2 uv = gl_FragCoord.xy / vec2(textureSize(uTex, 0));
    float sum = 0.0;
    float wsum = 0.0;
    for (int i = -12; i <= 12; i++) {
        float w = exp(-float(i * i) / 72.0);
        sum += w * texture(uTex, uv + uStep * float(i)).r;
        wsum += w;
    }
    outColor = vec4(sum / wsum, 0.0, 0.0, 1.0);
}"""

        // One instanced quad per dab: centre in upright coordinates -> source pixel -> this tile's clip space.
        const val DAB_VERTEX = """#version 300 es
layout(location = 0) in vec2 aCorner;
layout(location = 1) in vec2 aCentre;
uniform mat3 uMap;
uniform vec2 uOrigin;
uniform vec2 uFull;
uniform vec2 uSize;
uniform float uAspect;
uniform float uRadius;
out vec2 vLocal;
void main() {
    vLocal = aCorner;
    vec2 uv = aCentre + aCorner * uRadius / vec2(uAspect, 1.0);
    vec2 px = (uMap * vec3(uv, 1.0)).xy * uFull - uOrigin;
    gl_Position = vec4(px / uSize * 2.0 - 1.0, 0.0, 1.0);
}"""

        const val DAB_FRAGMENT = """#version 300 es
precision highp float;
in vec2 vLocal;
uniform float uSoft;
uniform float uFlow;
out vec4 outColor;
void main() {
    float a = uFlow * (1.0 - smoothstep(1.0 - max(uSoft, 0.001), 1.0, length(vLocal)));
    outColor = vec4(a, 0.0, 0.0, a);
}"""
    }
}

/** GLSL: adds +-0.5 of an 8-bit step of fixed noise before the 8-bit write, so smooth gradients don't band. Exact 8-bit values pass through unchanged. */
/** For passes that write RGBA16F: with uTrunc, add half a half-float step so the GPU's truncation rounds to nearest. */
internal const val HALF_ROUND = """
uniform bool uTrunc;
vec3 store(vec3 c) { return uTrunc ? c + sign(c) * exp2(floor(log2(max(abs(c), vec3(6.1e-5)))) - 11.0) : c; }"""

internal const val DITHER = """
vec3 dither(vec3 c) {
    float n = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    return c + (n - 0.5) / 255.0;
}"""

private val QUAD: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    .put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).apply { position(0) }

/** Draws a full-viewport quad with the position at attribute 0. */
internal fun drawFullQuad() {
    glEnableVertexAttribArray(0)
    glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, QUAD)
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
}

/** An Ultra HDR photo's gain map and its metadata (android.graphics.Gainmap), per channel. */
class HdrGain(
    val map: Bitmap, val ratioMin: FloatArray, val ratioMax: FloatArray, val gamma: FloatArray,
    val epsSdr: FloatArray, val epsHdr: FloatArray,
)

/** Deband sample radius as a fraction of the upright image height; the second pass reaches twice as far. */
const val DEBAND_RADIUS = 0.01f

internal fun genTexture(): Int = IntArray(1).also { glGenTextures(1, it, 0) }[0].also {
    glBindTexture(GL_TEXTURE_2D, it)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
}

private fun checkFramebuffer() =
    check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) { "framebuffer incomplete" }

internal fun link(vs: String, fs: String): Int = glCreateProgram().also {
    glAttachShader(it, compile(GL_VERTEX_SHADER, vs))
    glAttachShader(it, compile(GL_FRAGMENT_SHADER, fs))
    glLinkProgram(it)
    val ok = IntArray(1)
    glGetProgramiv(it, GL_LINK_STATUS, ok, 0)
    check(ok[0] != 0) { "program link failed: ${glGetProgramInfoLog(it)}" }
}

private fun compile(type: Int, src: String): Int = glCreateShader(type).also {
    glShaderSource(it, src)
    glCompileShader(it)
    val ok = IntArray(1)
    glGetShaderiv(it, GL_COMPILE_STATUS, ok, 0)
    check(ok[0] != 0) { "shader compile failed: ${glGetShaderInfoLog(it)}" }
}
