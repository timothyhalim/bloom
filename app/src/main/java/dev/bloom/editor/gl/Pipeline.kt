package dev.bloom.editor.gl

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.opengl.GLES30.*
import android.opengl.GLUtils
import android.util.Half
import dev.bloom.editor.model.BlurType
import dev.bloom.editor.model.BrushStep
import dev.bloom.editor.model.Detail
import dev.bloom.editor.model.ColorParam
import dev.bloom.editor.model.ColorStep
import dev.bloom.editor.model.GradientStep
import dev.bloom.editor.model.Layer
import dev.bloom.editor.model.LayerKind
import dev.bloom.editor.model.WarpField
import dev.bloom.editor.model.LuminanceStep
import dev.bloom.editor.model.Mask
import dev.bloom.editor.model.MaskStep
import dev.bloom.editor.model.Patch
import dev.bloom.editor.model.SubjectStep
import dev.bloom.editor.SubjectMask
import kotlin.math.ln
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
    private val warpProgram = link(PASS_VERTEX, WARP_FRAGMENT)
    private val maskProgram = link(PASS_VERTEX, MASK_FRAGMENT)
    private val dabProgram = link(DAB_VERTEX, DAB_FRAGMENT)
    private val blurProgram = link(PASS_VERTEX, BLUR_FRAGMENT)
    private val prepareProgram = link(PASS_VERTEX, PREPARE_FRAGMENT)
    private val combineProgram = link(PASS_VERTEX, COMBINE_FRAGMENT)
    private val gaussProgram = link(PASS_VERTEX, GAUSS_FRAGMENT)
    private val lensProgram = link(PASS_VERTEX, LENS_FRAGMENT)
    private val streakProgram = link(PASS_VERTEX, STREAK_FRAGMENT)
    private var depth: SubjectMask? = null // the whole photo's depth map (255 = near), for lens blur
    private var depthTex = 0
    // Blur and Sharpen scratch: [0] half-blurred (with mipmaps), [1] small blur, [2] large blur. Texture + framebuffer.
    private var detail: List<Pair<Int, Int>>? = null
    // Liquify: each layer's offset grid (kept between frames so a growing stroke only adds its new dabs) and its texture
    private class WarpTex(val field: WarpField, val tex: Int) {
        val buf: java.nio.FloatBuffer = ByteBuffer.allocateDirect(field.d.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    }
    private val warps = HashMap<Long, WarpTex>()
    private val patches = HashMap<Long, Pair<Patch, Int>>() // Retouch: each layer's patch and its texture
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
    private val truncates = half && runCatching { writesTruncate() }.getOrDefault(false) // a 16F target that won't render: no probe

    init {
        for (prog in listOf(colorProgram, prepareProgram, combineProgram)) {
            glUseProgram(prog)
            glUniform1i(glGetUniformLocation(prog, "uTrunc"), if (truncates) 1 else 0)
        }
        glUseProgram(colorProgram)
        glUniform1i(glGetUniformLocation(colorProgram, "uMask"), 1)
        glUniform1i(glGetUniformLocation(colorProgram, "uCurve"), 3)
        glUniform1i(glGetUniformLocation(colorProgram, "uF1"), 4)
        glUniform1i(glGetUniformLocation(colorProgram, "uF2"), 5)
        glUniform1i(glGetUniformLocation(colorProgram, "uWarp"), 6)
        glUniform1i(glGetUniformLocation(colorProgram, "uPatch"), 7)
        glUseProgram(gaussProgram)
        glUniform1i(glGetUniformLocation(gaussProgram, "uMask"), 1)
        glUseProgram(lensProgram)
        glUniform1i(glGetUniformLocation(lensProgram, "uMask"), 1)
        glUniform1i(glGetUniformLocation(lensProgram, "uDepth"), 2)
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
        for ((pins, m) in all) subjects[pins] = Triple(r8Texture(m), m.w, m.h)
        masks.values.forEach { it.mask = null } // cached masks may contain old subjects
        rendered = null
    }

    /** The photo's depth map for lens blur (upright, 255 = nearest), or null to blur without depth. */
    fun setDepth(d: SubjectMask?) {
        glDeleteTextures(1, intArrayOf(depthTex), 0)
        depthTex = 0
        depth = d
        masks.values.forEach { it.mask = null } // depth range steps read it
        if (d != null) depthTex = r8Texture(d)
        rendered = null
    }

    private fun r8Texture(m: SubjectMask): Int = genTexture().also {
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, m.w, m.h, 0, GL_RED, GL_UNSIGNED_BYTE, ByteBuffer.wrap(m.alpha))
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4)
    }

    /** Renders [layers] (bottom to top) and returns the texture holding the result. Cached until layers change. */
    fun render(layers: List<Layer>): Int {
        if (!prepared) prepare()
        val active = layers.filter { it.visible && it.opacity > 0f }
        if (active == rendered) return result
        masks.keys.filter { id -> layers.none { it.id == id } }.forEach { id -> masks.remove(id)?.let(::delete) }
        warps.keys.filter { id -> layers.none { it.id == id && it.kind == LayerKind.Liquify } }.forEach { id -> glDeleteTextures(1, intArrayOf(warps.remove(id)!!.tex), 0) }
        patches.keys.filter { id -> layers.none { it.id == id && it.patch === patches[id]!!.first } }.forEach { id -> glDeleteTextures(1, intArrayOf(patches.remove(id)!!.second), 0) }
        rendered = active
        result = source
        if (active.isEmpty()) return result
        result = if (gain != null) {
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
            tex[out]
        } else chain(source, active, null)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glBindTexture(GL_TEXTURE_2D, result)
        glGenerateMipmap(GL_TEXTURE_2D) // the preview minifies the result
        return result
    }

    /** Runs [active] over [src] through the ping-pong targets, the last pass into [last] (fbo, texture) if given. */
    private fun chain(src: Int, active: List<Layer>, last: Pair<Int, Int>?): Int {
        var input = src
        active.forEachIndexed { i, l ->
            val m = if (l.mask.active) maskFor(l, input, active.subList(0, i)) else 0
            val detailed = l.kind == LayerKind.Blur || l.kind == LayerKind.Sharpen
            val curves = l.kind == LayerKind.Color && l.curves.active
            if (detailed) detailPasses(l, input, m)
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
            glUniform1i(glGetUniformLocation(colorProgram, "uCurves"), if (curves) 1 else 0)
            glUniform1i(glGetUniformLocation(colorProgram, "uKind"), l.kind.ordinal)
            glUniform2f(glGetUniformLocation(colorProgram, "uDetail"), l.detail.amount / 50f, l.detail.clarity / 100f * 1.5f)
            setGeometry(colorProgram, toUpright)
            glUniformMatrix3fv(glGetUniformLocation(colorProgram, "uUnmap"), 1, false, fromUpright, 0)
            if (l.kind == LayerKind.Retouch) {
                val pt = l.patch
                glActiveTexture(GL_TEXTURE7)
                glBindTexture(GL_TEXTURE_2D, if (pt != null) patchTexture(l.id, pt) else 0)
                glUniform4f(glGetUniformLocation(colorProgram, "uRect"), pt?.left ?: 0f, pt?.top ?: 0f, pt?.right ?: 0f, pt?.bottom ?: 0f)
            }
            if (l.kind == LayerKind.Liquify) {
                glActiveTexture(GL_TEXTURE6)
                glBindTexture(GL_TEXTURE_2D, warpTexture(l))
            }
            if (detailed) {
                val d = detail!!
                glActiveTexture(GL_TEXTURE4)
                glBindTexture(GL_TEXTURE_2D, d[1].first)
                glActiveTexture(GL_TEXTURE5)
                glBindTexture(GL_TEXTURE_2D, d[2].first)
            }
            if (curves) {
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

    /** [p]'s pixels as a texture, uploaded once per patch. */
    private fun patchTexture(id: Long, p: Patch): Int {
        patches[id]?.let { (old, tex) -> if (old === p) return tex; glDeleteTextures(1, intArrayOf(tex), 0) }
        val t = genTexture()
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        GLUtils.texImage2D(GL_TEXTURE_2D, 0, p.pixels, 0)
        patches[id] = p to t
        return t
    }

    /** Layer [id]'s mask from the last [render], as width × height bytes (row 0 at the top), or null if it has none. */
    fun maskPixels(id: Long): ByteArray? {
        val m = masks[id]?.takeIf { it.mask != null } ?: return null
        glBindFramebuffer(GL_FRAMEBUFFER, m.fbo)
        val buf = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, buf) // RGBA is the readback every GPU supports
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        return ByteArray(width * height) { buf.get(it * 4) }
    }

    /** Layer [l]'s offset grid as an RG16F texture, brought up to date (only the changed cells are uploaded). */
    private fun warpTexture(l: Layer): Int {
        val aspect = geo[4]
        val wt = warps[l.id]?.takeIf { it.field.aspect == aspect } ?: run {
            warps.remove(l.id)?.let { glDeleteTextures(1, intArrayOf(it.tex), 0) }
            val f = WarpField(aspect)
            val t = genTexture()
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_RG16F, f.w, f.h)
            WarpTex(f, t).also { warps[l.id] = it }
        }
        wt.field.update(l.warp)
        wt.field.takeDirty()?.let { (x0, y0, x1, y1) ->
            val f = wt.field
            wt.buf.position(0)
            wt.buf.put(f.d, y0 * f.w * 2, (y1 - y0) * f.w * 2).position(0)
            glBindTexture(GL_TEXTURE_2D, wt.tex)
            glPixelStorei(GL_UNPACK_ROW_LENGTH, f.w)
            glPixelStorei(GL_UNPACK_SKIP_PIXELS, x0)
            glTexSubImage2D(GL_TEXTURE_2D, 0, x0, y0, x1 - x0, y1 - y0, GL_RG, GL_FLOAT, wt.buf)
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 0)
            glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0)
        }
        return wt.tex
    }

    /**
     * Blur: [src] blurred into detail[1]. Sharpen: a small blur into detail[1] and, for clarity, a large one into detail[2].
     * A masked Blur only averages pixels inside its mask, so a blurred background doesn't pick up the subject's colors.
     */
    private fun detailPasses(l: Layer, src: Int, mask: Int) {
        val d = detail ?: List(3) { i -> target(levels = if (i == 0) mipLevels() else 1) }.also { detail = it }
        when {
            l.kind == LayerKind.Sharpen -> {
                gauss(src, (0.5f + 2.5f * l.detail.radius / 100f) / SHARPEN_SCALE * uprightHeight, 0, false, d[1].second)
                if (l.detail.clarity != 0f) gauss(src, CLARITY_RADIUS * uprightHeight, 0, false, d[2].second)
            }
            l.detail.lens -> lens(src, l, mask, d[1].second)
            l.detail.type == BlurType.Motion || l.detail.type == BlurType.Zoom -> streak(src, l.detail, d[1].second)
            else -> gauss(src, l.detail.amount / 100f * BLUR_MAX * uprightHeight, mask, l.mask.invert, d[1].second)
        }
    }

    /** Motion or Zoom blur of [src] into framebuffer [out]: averages along a line (see STREAK_FRAGMENT). */
    private fun streak(src: Int, det: Detail, out: Int) {
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, src)
        glGenerateMipmap(GL_TEXTURE_2D)
        glBindFramebuffer(GL_FRAMEBUFFER, out)
        glViewport(0, 0, width, height)
        val p = streakProgram
        glUseProgram(p)
        setGeometry(p, toUpright)
        glUniformMatrix3fv(glGetUniformLocation(p, "uUnmap"), 1, false, fromUpright, 0)
        glUniform1f(glGetUniformLocation(p, "uHeight"), uprightHeight)
        val zoom = det.type == BlurType.Zoom
        glUniform1i(glGetUniformLocation(p, "uZoom"), if (zoom) 1 else 0)
        // Motion: half the streak in upright 0..1 (y down, so a positive angle tilts up to the right)
        val half = det.amount / 100f * MOTION_MAX / 2
        val a = Math.toRadians(det.angle.toDouble())
        glUniform2f(glGetUniformLocation(p, "uDir"), (half * kotlin.math.cos(a)).toFloat() / geo[4], -(half * kotlin.math.sin(a)).toFloat())
        glUniform2f(glGetUniformLocation(p, "uCentre"), det.focusX, det.focusY)
        glUniform1f(glGetUniformLocation(p, "uAmount"), det.amount / 100f * ZOOM_MAX)
        drawQuad(src)
    }

    /** Depth-aware lens blur of [src] into framebuffer [out] (see LENS_FRAGMENT). */
    private fun lens(src: Int, l: Layer, mask: Int, out: Int) {
        val det = l.detail
        val radius = det.amount / 100f * LENS_MAX * uprightHeight
        val dm = depth
        val focus = dm?.let { (it.alpha[(det.focusY * (it.h - 1)).toInt() * it.w + (det.focusX * (it.w - 1)).toInt()].toInt() and 0xFF) / 255f } ?: 0f
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, src)
        glGenerateMipmap(GL_TEXTURE_2D)
        glBindFramebuffer(GL_FRAMEBUFFER, out)
        glViewport(0, 0, width, height)
        val p = lensProgram
        glUseProgram(p)
        setGeometry(p, toUpright)
        glUniform1i(glGetUniformLocation(p, "uHasDepth"), if (dm != null) 1 else 0)
        glUniform1i(glGetUniformLocation(p, "uWeighted"), if (mask != 0) 1 else 0)
        glUniform1i(glGetUniformLocation(p, "uInvert"), if (l.mask.invert) 1 else 0)
        glUniform1f(glGetUniformLocation(p, "uRadius"), radius)
        glUniform1f(glGetUniformLocation(p, "uFocus"), focus)
        glUniform1f(glGetUniformLocation(p, "uRange"), det.range / 100f * 0.5f)
        glUniform1f(glGetUniformLocation(p, "uBloom"), det.bloom / 100f)
        // the samples are this far apart, so read a mip level that averages that much
        glUniform1f(glGetUniformLocation(p, "uLod"), maxOf(0f, ln(radius * 0.22f) / ln(2f) - 0.5f))
        glActiveTexture(GL_TEXTURE1)
        glBindTexture(GL_TEXTURE_2D, mask)
        glActiveTexture(GL_TEXTURE2)
        glBindTexture(GL_TEXTURE_2D, depthTex)
        drawQuad(src)
    }

    /** Separable Gaussian of [src] (sigma in pixels) into framebuffer [out]; wide ones read [src]'s mipmaps. */
    private fun gauss(src: Int, sigma: Float, mask: Int, invert: Boolean, out: Int) {
        val d = detail!!
        val stride = maxOf(sigma, 0.01f) * 3f / GAUSS_TAPS // ±GAUSS_TAPS taps span ±3 sigma
        val lod = maxOf(0f, ln(stride) / ln(2f))
        glUseProgram(gaussProgram)
        glUniform1i(glGetUniformLocation(gaussProgram, "uWeighted"), if (mask != 0) 1 else 0)
        glUniform1i(glGetUniformLocation(gaussProgram, "uInvert"), if (invert) 1 else 0)
        glUniform1f(glGetUniformLocation(gaussProgram, "uLod"), lod)
        for ((from, to, first) in listOf(Triple(src, d[0].second, true), Triple(d[0].first, out, false))) {
            if (lod > 0f) {
                glActiveTexture(GL_TEXTURE0)
                glBindTexture(GL_TEXTURE_2D, from)
                glGenerateMipmap(GL_TEXTURE_2D)
            }
            glActiveTexture(GL_TEXTURE1)
            glBindTexture(GL_TEXTURE_2D, mask)
            glBindFramebuffer(GL_FRAMEBUFFER, to)
            glViewport(0, 0, width, height)
            glUniform1i(glGetUniformLocation(gaussProgram, "uFirst"), if (first) 1 else 0)
            glUniform2f(glGetUniformLocation(gaussProgram, "uDir"), if (first) stride / width else 0f, if (first) 0f else stride / height)
            drawQuad(from)
        }
    }

    /** The mask texture of layer [id] from the last [render], or 0. Same pixel grid as the result. */
    fun maskTexture(id: Long): Int = masks[id]?.takeIf { it.mask != null }?.tex ?: 0

    /**
     * Renders [layers] and reads the result back (export): 8-bit, or with [deep] a 16-bit float sRGB bitmap, opaque.
     * With [warp] (row-major 3×3, output 0..1 -> this source 0..1, projective) the result is resampled into [width]×[height]
     * (crop, rotation, scale), averaging where it shrinks.
     */
    fun renderToBitmap(layers: List<Layer>, deep: Boolean = false, warp: FloatArray? = null, width: Int = this.width, height: Int = this.height): Bitmap {
        val t = render(layers)
        if (!deep) {
            copyToRead(t, GL_RGBA8, warp, width, height)
            val buf = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
            glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, buf)
            glBindFramebuffer(GL_FRAMEBUFFER, 0)
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(buf.rewind()) }
        }
        // Float buffers only promise GL_FLOAT readback; halve on the CPU, clamped like the PNG encoder would.
        copyToRead(t, GL_RGBA16F, warp, width, height)
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

    /** Copies [t] (or resamples it through [warp]) into the read target of [format], [width]×[height], and leaves that framebuffer bound for glReadPixels. */
    private fun copyToRead(t: Int, format: Int = GL_RGBA8, warp: FloatArray? = null, width: Int = this.width, height: Int = this.height) {
        if (readW != width || readH != height || readFormat != format) {
            glDeleteTextures(1, read, 0)
            glDeleteFramebuffers(1, read, 1)
            target(format, w = width, h = height).let { (rt, rf) -> read[0] = rt; read[1] = rf }
            readW = width
            readH = height
            readFormat = format
        }
        glBindFramebuffer(GL_FRAMEBUFFER, read[1])
        glViewport(0, 0, width, height)
        if (warp == null) glUseProgram(copyProgram)
        else {
            glUseProgram(warpProgram)
            glUniformMatrix3fv(glGetUniformLocation(warpProgram, "uWarp"), 1, true, warp, 0)
            glUniform2f(glGetUniformLocation(warpProgram, "uOut"), width.toFloat(), height.toFloat())
        }
        drawQuad(t)
    }

    /** Writes 0.5 + 3/4 of a half-float step into an RGBA16F target and reads it back: 0.5 means the GPU truncates. */
    private fun writesTruncate(): Boolean {
        val (t, f) = target(GL_RGBA16F, w = 1, h = 1)
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

    /** A render target, the pipeline's size unless given: (texture, framebuffer). Single-level ones sample without mipmaps. */
    private fun target(format: Int = if (half) GL_RGBA16F else GL_RGBA8, levels: Int = 1, w: Int = width, h: Int = height): Pair<Int, Int> {
        val t = genTexture()
        if (levels == 1) glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexStorage2D(GL_TEXTURE_2D, levels, format, w, h)
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
            is LuminanceStep -> if (s.depth) 6 else 4
            is SubjectStep -> 5
        }
        glUniform1i(glGetUniformLocation(p, "uKind"), kind)
        when (s) {
            is GradientStep -> glUniform4f(glGetUniformLocation(p, "uG"), s.x0, s.y0, s.x1, s.y1)
            is LuminanceStep -> {
                glUniform4f(glGetUniformLocation(p, "uG"), s.a, s.b, s.c, s.d)
                if (s.depth) {
                    glUniform2f(glGetUniformLocation(p, "uSize"), (depth?.w ?: 1).toFloat(), (depth?.h ?: 1).toFloat())
                    glUniformMatrix3fv(glGetUniformLocation(p, "uUnmap"), 1, false, fromUpright, 0)
                }
            }
            is SubjectStep -> {
                val (_, w, h) = subjects[s.pins] ?: Triple(0, 1, 1)
                glUniform4f(glGetUniformLocation(p, "uG"), if (s.background) 1f else 0f, 0f, 0f, 0f)
                glUniform2f(glGetUniformLocation(p, "uSize"), w.toFloat(), h.toFloat())
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
        // the low-res map that kinds 5 and 6 upsample: a subject mask, or the depth map
        glBindTexture(GL_TEXTURE_2D, (s as? SubjectStep)?.let { subjects[it.pins]?.first } ?: if ((s as? LuminanceStep)?.depth == true) depthTex else 0)
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

    /** Sets the GEOMETRY snippet's uniforms, with [m] as uMap. */
    private fun setGeometry(program: Int, m: FloatArray) {
        glUniformMatrix3fv(glGetUniformLocation(program, "uMap"), 1, false, m, 0)
        glUniform2f(glGetUniformLocation(program, "uOrigin"), geo[0], geo[1])
        glUniform2f(glGetUniformLocation(program, "uFull"), geo[2], geo[3])
        glUniform1f(glGetUniformLocation(program, "uAspect"), geo[4])
    }

    private fun newMask(): MaskTex = target(GL_R8).let { (t, f) -> MaskTex(t, f) }

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
        detail?.let { d -> glDeleteTextures(3, d.map { it.first }.toIntArray(), 0); glDeleteFramebuffers(3, d.map { it.second }.toIntArray(), 0) }
        detail = null
        glDeleteTextures(2, tex, 0)
        glDeleteFramebuffers(2, fbo, 0)
        glDeleteTextures(1, intArrayOf(source), 0)
        glDeleteFramebuffers(1, intArrayOf(sourceFbo), 0)
        freeHdrTargets()
        target(levels = mipLevels()).let { (t, f) -> source = t; sourceFbo = f }
        for (i in 0..1) target(levels = mipLevels()).let { (t, f) -> tex[i] = t; fbo[i] = f }
        freeMasks()
        scratch = newMask()
        brush = newMask()
        soft = newMask()
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
    }

    private fun mipLevels() = 32 - Integer.numberOfLeadingZeros(maxOf(width, height))

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
""" + SRGB + """
vec3 enc(vec3 c) { return uLinear ? toSrgb(c) : max(c, 0.0); }
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
        c = toSrgb((toLinear(c) + uEpsSdr) * exp(mix(log(uRatioMin), log(uRatioMax), pow(g, uGamma))) - uEpsHdr);
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
""" + HALF_ROUND + SRGB + """
float luma(vec3 c) { return dot(toLinear(c), LUMA); }
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

        // Export resampling (crop, rotation, scale): bilinear taps on an n×n grid inside each output pixel, n = source px
        // per output px (rounded), so shrinking averages every source pixel. Not mipmaps: their texels only line up with
        // the output when the tile's size is a multiple of the shrink.
        const val WARP_FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform mat3 uWarp; // output 0..1 -> source 0..1
uniform vec2 uOut;
out vec4 outColor;
""" + DITHER + """
vec2 at(vec2 o) { vec3 h = uWarp * vec3(o / uOut, 1.0); return h.xy / h.z; }
void main() {
    vec2 size = vec2(textureSize(uTex, 0));
    vec2 p = at(gl_FragCoord.xy) * size;
    int n = int(clamp(floor(max(length(dFdx(p)), length(dFdy(p))) + 0.5), 1.0, 8.0));
    vec3 sum = vec3(0.0);
    for (int j = 0; j < 8; j++) {
        if (j >= n) break;
        for (int i = 0; i < 8; i++) {
            if (i >= n) break;
            sum += textureLod(uTex, at(gl_FragCoord.xy + (vec2(i, j) + 0.5) / float(n) - 0.5), 0.0).rgb;
        }
    }
    outColor = vec4(dither(sum / float(n * n)), 1.0);
}"""

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
uniform int uKind; // LayerKind.ordinal: 0 Color, 1 Blur, 2 Sharpen, 3 Liquify, 4 Retouch; must follow the enum's order
uniform sampler2D uPatch; // Retouch: the filled pixels
uniform vec4 uRect; // Retouch: where they go, upright 0..1
uniform sampler2D uF1; // Blur: the blurred layer input. Sharpen: its small blur
uniform sampler2D uF2; // Sharpen: the large blur, for clarity
uniform vec2 uDetail; // Sharpen: amount, clarity
uniform sampler2D uWarp; // Liquify: where each upright point reads from, as an offset in photo heights
uniform mat3 uUnmap; // Liquify: upright 0..1 -> pipeline pixel (0..1 of the full image)
""" + GEOMETRY + HALF_ROUND + SRGB + """
uniform sampler2D uCurve; // CURVE_SIZE x 1 tone table, rgb = output per channel
out vec4 outColor;
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
    vec3 p;
    if (uKind == 1) p = texelFetch(uF1, px, 0).rgb;
    else if (uKind == 3) {
        vec2 uv = upright(gl_FragCoord.xy);
        vec2 o = texture(uWarp, uv).rg;
        // untouched (or fully restored) places keep their exact pixel
        if (o == vec2(0.0)) p = src.rgb;
        else {
            vec2 q = (uUnmap * vec3(uv + o / vec2(uAspect, 1.0), 1.0)).xy * uFull - uOrigin;
            p = textureLod(uTex, q / vec2(textureSize(uTex, 0)), 0.0).rgb;
        }
    } else if (uKind == 4) {
        // the patch inside its rectangle; the layer mask (painted over the object) blends it in
        vec2 uv = upright(gl_FragCoord.xy);
        vec2 t = (uv - uRect.xy) / max(uRect.zw - uRect.xy, vec2(1e-6));
        p = all(greaterThanEqual(t, vec2(0.0))) && all(lessThanEqual(t, vec2(1.0))) ? texture(uPatch, t).rgb : src.rgb;
    } else if (uKind == 2) {
        // unsharp mask, then clarity: midtone-weighted local contrast on brightness only
        p = src.rgb + uDetail.x * (src.rgb - texelFetch(uF1, px, 0).rgb);
        float l = dot(clamp(src.rgb, 0.0, 1.0), LUMA);
        float local = l - dot(clamp(texelFetch(uF2, px, 0).rgb, 0.0, 1.0), LUMA);
        p += uDetail.y * local * 4.0 * l * (1.0 - l);
    } else {
    vec3 lin = toLinear(src.rgb) * exp2(uP[0] * 3.0);
    lin *= vec3(1.0 + 0.2 * uP[4], 1.0 - 0.2 * uP[5], 1.0 - 0.2 * uP[4]);
    p = toSrgb(lin);
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
        // uKind: 0 from texture (uBrush), 1 linear, 2 radial gradient, 3 color range, 4 luminance range, 5 subject (uG.x = 1:
        // background), 6 depth range (uG as for luminance). 5 and 6 upsample a low-res map (uSubject, uSize texels).
        // Gradient math runs in upright image coordinates scaled by aspect, so circles stay round.
        // Color and luminance read the composite of the layers below (uBelow).
        const val MASK_FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uBrush;
uniform sampler2D uBelow;
uniform sampler2D uSubject;
uniform mat3 uUnmap; // upright -> full image 0..1 (subject and depth only)
uniform vec2 uSize; // texels of the subject or depth map
""" + GEOMETRY + """
uniform bool uFirst;
uniform bool uAdd;
uniform bool uValueOnly;
uniform int uKind;
uniform vec4 uG;
uniform vec3 uS[4];
uniform int uN;
out vec4 outColor;
""" + SRGB + """
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
        vec2 q = upright(gl_FragCoord.xy) * a;
        vec2 q0 = uG.xy * a;
        vec2 d = uG.zw * a - q0;
        if (uKind == 1) s = 1.0 - smoothstep(0.0, 1.0, dot(q - q0, d) / max(dot(d, d), 1e-8));
        else s = 1.0 - smoothstep(0.5, 1.0, length(q - q0) / max(length(d), 1e-6));
    } else if (uKind >= 5) {
        // Joint bilateral upsampling of the low-res model mask: of the 4x4 nearest mask texels, those whose spot in the
        // photo looks like this pixel count most, so the edge snaps to the photo's edge instead of a blurry halo.
        vec2 n = uSize;
        vec2 t = upright(gl_FragCoord.xy) * n - 0.5;
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
        if (uKind == 5) { if (uG.x > 0.5) s = 1.0 - s; }
        else s = rise(uG.x, uG.y, s) * (uG.w <= uG.z ? step(s, uG.z) : 1.0 - smoothstep(uG.z, uG.w, s));
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
            float l = dot(clamp(c, 0.0, 1.0), LUMA);
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

        // One pass of a separable Gaussian: ±16 taps, [uDir] apart (uv), from mip level [uLod] so wide blurs stay smooth.
        // First pass: weights each tap by the layer mask and keeps the weight in alpha (premultiplied).
        // Second pass: sums those and divides by the weight, so only pixels inside the mask are averaged.
        const val GAUSS_FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uMask;
uniform vec2 uDir;
uniform float uLod;
uniform bool uFirst;
uniform bool uWeighted;
uniform bool uInvert;
out vec4 outColor;
void main() {
    vec2 uv = gl_FragCoord.xy / vec2(textureSize(uTex, 0));
    vec4 acc = vec4(0.0);
    for (int i = -16; i <= 16; i++) {
        float x = float(i) * (3.0 / 16.0);
        float w = exp(-0.5 * x * x);
        vec2 q = uv + uDir * float(i);
        vec4 c = textureLod(uTex, q, uLod);
        if (uFirst) {
            float m = 1.0;
            if (uWeighted) { m = texture(uMask, q).r; if (uInvert) m = 1.0 - m; }
            c = vec4(c.rgb * m, m);
        }
        acc += w * c;
    }
    outColor = uFirst ? acc : vec4(acc.rgb / max(acc.a, 1e-5), 1.0);
}"""

        // Motion / Zoom: STREAK_TAPS samples along a line through the pixel (Motion) or toward the centre (Zoom), each
        // from the mip level as wide as the gap between samples, so long streaks stay smooth.
        const val STREAK_FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform mat3 uUnmap;
""" + GEOMETRY + """
uniform float uHeight; // upright height in px
uniform bool uZoom;
uniform vec2 uDir; // Motion: half the streak, upright 0..1
uniform vec2 uCentre; // Zoom: centre, upright 0..1
uniform float uAmount; // Zoom: share of the way to the centre the streak reaches
out vec4 outColor;
const int N = """ + STREAK_TAPS + """;
void main() {
    vec2 uv = upright(gl_FragCoord.xy);
    vec2 span = uZoom ? (uCentre - uv) * uAmount : 2.0 * uDir; // the streak, upright 0..1
    vec2 start = uZoom ? uv : uv - uDir;
    float gap = length(span * vec2(uAspect, 1.0)) * uHeight / float(N);
    float lod = log2(max(gap, 1.0));
    vec2 size = vec2(textureSize(uTex, 0));
    vec3 acc = vec3(0.0);
    for (int i = 0; i < N; i++) {
        vec2 q = start + span * (float(i) + 0.5) / float(N);
        vec2 px = (uUnmap * vec3(q, 1.0)).xy * uFull - uOrigin;
        acc += textureLod(uTex, px / size, lod).rgb;
    }
    outColor = vec4(acc / float(N), 1.0);
}"""

        // Lens blur as a gather: 64 taps on a golden-angle spiral fill a disc of uRadius. A tap counts if its own blur
        // disc reaches this pixel, so sharp areas stay sharp and blur grows with depth. A tap
        // farther away than this pixel can blur no more than this pixel does, so the background never smears over a
        // sharp foreground. Bright taps weigh more (uBloom): highlights turn into discs like a real lens.
        const val LENS_FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uMask;
uniform sampler2D uDepth;
""" + GEOMETRY + """
uniform bool uHasDepth;
uniform bool uWeighted;
uniform bool uInvert;
uniform float uRadius;
uniform float uFocus;
uniform float uRange;
uniform float uBloom;
uniform float uLod;
out vec4 outColor;
""" + SRGB + """
float depthAt(vec2 frag) { return texture(uDepth, upright(frag)).r; }
// blur radius in pixels for a depth: none within uRange of the focus depth, full uRadius half the depth scale beyond
float coc(float d) { return uHasDepth ? uRadius * clamp((abs(d - uFocus) - uRange) * 2.0, 0.0, 1.0) : uRadius; }
void main() {
    vec2 frag = gl_FragCoord.xy;
    vec2 size = vec2(textureSize(uTex, 0));
    float d0 = uHasDepth ? depthAt(frag) : 0.0;
    float c0 = coc(d0);
    vec4 acc = vec4(texelFetch(uTex, ivec2(frag), 0).rgb, 1.0); // the pixel itself: in focus it is all there is
    for (int k = 0; k < 64; k++) {
        float r = sqrt((float(k) + 0.5) / 64.0);
        float a = float(k) * 2.39996323;
        vec2 q = frag + vec2(cos(a), sin(a)) * r * uRadius;
        vec3 c = textureLod(uTex, q / size, uLod).rgb;
        float ds = uHasDepth ? depthAt(q) : d0;
        float cs = coc(ds);
        if (ds < d0) cs = min(cs, c0);
        float w = clamp(cs - r * uRadius + 1.0, 0.0, 1.0);
        w *= 1.0 + uBloom * 8.0 * smoothstep(0.7, 1.0, dot(clamp(c, 0.0, 1.0), LUMA));
        if (uWeighted) { float m = texture(uMask, q / size).r; w *= uInvert ? 1.0 - m : m; }
        acc += vec4(c * w, w);
    }
    outColor = vec4(acc.rgb / acc.a, 1.0);
}"""

        // One instanced quad per dab: centre in upright coordinates -> source pixel -> this tile's clip space.
        const val DAB_VERTEX = """#version 300 es
layout(location = 0) in vec2 aCorner;
layout(location = 1) in vec2 aCentre;
""" + GEOMETRY + """
uniform vec2 uSize;
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

private const val GAUSS_TAPS = 16
private const val BLUR_MAX = 0.02f // Blur at 100: sigma = 2% of the upright image height
private const val LENS_MAX = 0.04f // Lens blur at 100: a disc with a radius of 4% of the upright image height
private const val MOTION_MAX = 0.1f // Motion blur at 100: a streak 10% of the upright image height long
private const val ZOOM_MAX = 0.3f // Zoom blur at 100: streaks reach 30% of the way to the centre
private const val STREAK_TAPS = 48
private const val SHARPEN_SCALE = 3000f // Sharpen radius 0..100 = sigma 0.5..3 px on a 3000 px tall photo
private const val CLARITY_RADIUS = 0.015f // of the image height

/** How far (fraction of the upright height) a layer reads around a pixel; export tiles overlap by this. */
fun detailReach(l: Layer): Float = when {
    !l.visible -> 0f
    // taps reach the radius, and each reads a mip texel about a quarter of it wide
    l.kind == LayerKind.Blur && l.detail.lens -> 1.3f * LENS_MAX * l.detail.amount / 100f
    // half the streak plus a mip texel; Zoom from the farthest corner (at most ~1.8 heights away on a 16:9 photo)
    l.kind == LayerKind.Blur && l.detail.type == BlurType.Motion -> 0.6f * MOTION_MAX * l.detail.amount / 100f
    l.kind == LayerKind.Blur && l.detail.type == BlurType.Zoom -> 2f * ZOOM_MAX * l.detail.amount / 100f
    l.kind == LayerKind.Blur -> 3f * BLUR_MAX * l.detail.amount / 100f
    l.kind == LayerKind.Sharpen -> 3f * if (l.detail.clarity != 0f) CLARITY_RADIUS else 3f / SHARPEN_SCALE
    else -> 0f
}

/** For passes that write RGBA16F: with uTrunc, add half a half-float step so the GPU's truncation rounds to nearest. */
internal const val HALF_ROUND = """
uniform bool uTrunc;
vec3 store(vec3 c) { return uTrunc ? c + sign(c) * exp2(floor(log2(max(abs(c), vec3(6.1e-5)))) - 11.0) : c; }"""

/** GLSL: adds +-0.5 of an 8-bit step of fixed noise before the 8-bit write, so smooth gradients don't band. Exact 8-bit values pass through unchanged. */
internal const val DITHER = """
vec3 dither(vec3 c) {
    float n = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    return c + (n - 0.5) / 255.0;
}"""

/** GLSL: sRGB <-> linear light (negatives clamp to 0) and Rec. 709 luma weights. */
private const val SRGB = """
const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
vec3 toLinear(vec3 c) { c = max(c, 0.0); return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c)); }
vec3 toSrgb(vec3 c) { c = max(c, 0.0); return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c)); }"""

/** GLSL: the uniforms [Pipeline.setGeometry] sets, and upright() for a pipeline pixel's upright 0..1 position. */
private const val GEOMETRY = """
uniform mat3 uMap; // pipeline pixel (0..1 of the full image) -> upright 0..1; for brush dabs the reverse
uniform vec2 uOrigin; // this tile's offset in the full image, px
uniform vec2 uFull; // the full image's size, px
uniform float uAspect; // upright width / height
vec2 upright(vec2 px) { return (uMap * vec3((px + uOrigin) / uFull, 1.0)).xy; }"""

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
    val v = compile(GL_VERTEX_SHADER, vs)
    val f = compile(GL_FRAGMENT_SHADER, fs)
    glAttachShader(it, v)
    glAttachShader(it, f)
    glLinkProgram(it)
    glDeleteShader(v) // freed with the program
    glDeleteShader(f)
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
