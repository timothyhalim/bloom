package dev.bloom.editor.gl

import android.graphics.Bitmap
import android.graphics.Matrix
import android.opengl.GLES30.*
import android.opengl.GLUtils
import dev.bloom.editor.model.BrushStep
import dev.bloom.editor.model.ColorParam
import dev.bloom.editor.model.GradientStep
import dev.bloom.editor.model.Layer
import dev.bloom.editor.model.Mask
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

    private var source = 0
    private val tex = IntArray(2)
    private val fbo = IntArray(2)
    private val read = IntArray(2) // RGBA8 texture + framebuffer for glReadPixels, made on first export
    private var readW = 0
    private var readH = 0
    private var rendered: List<Layer>? = null
    private var result = 0

    // Mask geometry: maps a pipeline pixel to upright image coordinates (0..1, top-left), so masks line up in export tiles too.
    private val geo = FloatArray(5) // origin x, y, full width, full height, upright aspect
    private val toUpright = FloatArray(9)
    private val fromUpright = FloatArray(9)
    private class MaskTex(val tex: Int, val fbo: Int, var mask: Mask? = null)
    private val masks = HashMap<Long, MaskTex>()
    private var scratch: MaskTex? = null // ping-pong partner while combining steps
    private var brush: MaskTex? = null // one brush step's dabs

    private val colorProgram = link(PASS_VERTEX, COLOR_FRAGMENT)
    private val copyProgram = link(PASS_VERTEX, COPY_FRAGMENT)
    private val maskProgram = link(PASS_VERTEX, MASK_FRAGMENT)
    private val dabProgram = link(DAB_VERTEX, DAB_FRAGMENT)
    private val params = FloatArray(ColorParam.entries.size)

    // Half-float intermediates keep values above 1 between passes; plain RGBA8 is the fallback.
    private val half = glGetString(GL_EXTENSIONS).orEmpty()
        .let { "GL_EXT_color_buffer_half_float" in it || "GL_EXT_color_buffer_float" in it }

    init {
        glUseProgram(colorProgram)
        glUniform1i(glGetUniformLocation(colorProgram, "uMask"), 1)
        glUseProgram(maskProgram)
        glUniform1i(glGetUniformLocation(maskProgram, "uBrush"), 1)
    }

    /**
     * [bmp] is the whole image (preview) or one export tile at ([originX], [originY]) of a [fullW]×[fullH] image.
     * [orient] maps normalized source coordinates to upright ones (EXIF rotation); [aspect] is the upright width / height.
     */
    fun setSource(
        bmp: Bitmap, originX: Int = 0, originY: Int = 0, fullW: Int = bmp.width, fullH: Int = bmp.height,
        orient: Matrix = Matrix(), aspect: Float = fullW.toFloat() / fullH,
    ) {
        if (source == 0) source = genTexture()
        glBindTexture(GL_TEXTURE_2D, source)
        GLUtils.texImage2D(GL_TEXTURE_2D, 0, bmp, 0)
        glGenerateMipmap(GL_TEXTURE_2D)
        if (bmp.width != width || bmp.height != height) {
            width = bmp.width
            height = bmp.height
            allocTargets()
        }
        geo[0] = originX.toFloat(); geo[1] = originY.toFloat(); geo[2] = fullW.toFloat(); geo[3] = fullH.toFloat(); geo[4] = aspect
        columnMajor(orient, toUpright)
        columnMajor(Matrix().also { orient.invert(it) }, fromUpright)
        masks.values.forEach { it.mask = null }
        rendered = null
    }

    /** Renders [layers] (bottom to top) and returns the texture holding the result. Cached until layers change. */
    fun render(layers: List<Layer>): Int {
        val active = layers.filter { it.visible && it.opacity > 0f }
        if (active == rendered) return result
        masks.keys.filter { id -> layers.none { it.id == id } }.forEach { id -> masks.remove(id)?.let(::delete) }
        var input = source
        active.forEachIndexed { i, l ->
            val m = if (l.mask.active) maskFor(l) else 0
            val out = i % 2
            glBindFramebuffer(GL_FRAMEBUFFER, fbo[out])
            glViewport(0, 0, width, height)
            glUseProgram(colorProgram)
            for (p in params.indices) params[p] = l.values[p] / 100f
            glUniform1fv(glGetUniformLocation(colorProgram, "uP"), params.size, params, 0)
            glUniform1f(glGetUniformLocation(colorProgram, "uOpacity"), l.opacity)
            glUniform1i(glGetUniformLocation(colorProgram, "uBlend"), l.blend.ordinal)
            glUniform1i(glGetUniformLocation(colorProgram, "uMasked"), if (m != 0) 1 else 0)
            glUniform1i(glGetUniformLocation(colorProgram, "uInvert"), if (l.mask.invert) 1 else 0)
            glActiveTexture(GL_TEXTURE1)
            glBindTexture(GL_TEXTURE_2D, m)
            drawQuad(input)
            input = tex[out]
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

    /** The mask texture of layer [id] from the last [render], or 0. Same pixel grid as the result. */
    fun maskTexture(id: Long): Int = masks[id]?.takeIf { it.mask != null }?.tex ?: 0

    /** Renders [layers] and reads the result back as an 8-bit bitmap (export). */
    fun renderToBitmap(layers: List<Layer>): Bitmap {
        val t = render(layers)
        if (readW != width || readH != height) {
            glDeleteTextures(1, read, 0)
            glDeleteFramebuffers(1, read, 1)
            read[0] = genTexture()
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, width, height)
            glGenFramebuffers(1, read, 1)
            glBindFramebuffer(GL_FRAMEBUFFER, read[1])
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, read[0], 0)
            checkFramebuffer()
            readW = width
            readH = height
        }
        glBindFramebuffer(GL_FRAMEBUFFER, read[1])
        glViewport(0, 0, width, height)
        glUseProgram(copyProgram)
        drawQuad(t)
        val buf = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, buf)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(buf.rewind()) }
    }

    fun release() {
        glDeleteTextures(1, intArrayOf(source), 0)
        glDeleteTextures(2, tex, 0)
        glDeleteFramebuffers(2, fbo, 0)
        glDeleteTextures(1, read, 0)
        glDeleteFramebuffers(1, read, 1)
        freeMasks()
        listOf(colorProgram, copyProgram, maskProgram, dabProgram).forEach(::glDeleteProgram)
    }

    /** Builds the layer's mask texture, or reuses it if the mask hasn't changed since this source was set. */
    private fun maskFor(l: Layer): Int {
        val e = masks.getOrPut(l.id) { newMask() }
        if (e.mask == l.mask) return e.tex
        e.mask = l.mask
        val steps = l.mask.steps.filter { it.visible }
        val other = scratch!!
        steps.forEachIndexed { i, s ->
            if (s is BrushStep) drawDabs(s)
            // alternate targets so the last step lands in the layer's own texture
            val (dst, prev) = if ((steps.size - 1 - i) % 2 == 0) e to other else other to e
            glBindFramebuffer(GL_FRAMEBUFFER, dst.fbo)
            glViewport(0, 0, width, height)
            glUseProgram(maskProgram)
            setGeometry(maskProgram, toUpright)
            glUniform1i(glGetUniformLocation(maskProgram, "uFirst"), if (i == 0) 1 else 0)
            glUniform1i(glGetUniformLocation(maskProgram, "uAdd"), if (s.add) 1 else 0)
            val g = s as? GradientStep
            glUniform1i(glGetUniformLocation(maskProgram, "uKind"), if (g == null) 0 else if (g.radial) 2 else 1)
            if (g != null) glUniform4f(glGetUniformLocation(maskProgram, "uG"), g.x0, g.y0, g.x1, g.y1)
            glActiveTexture(GL_TEXTURE1)
            glBindTexture(GL_TEXTURE_2D, brush!!.tex)
            drawQuad(prev.tex)
        }
        return e.tex
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
        (masks.values + listOfNotNull(scratch, brush)).forEach(::delete)
        masks.clear()
        scratch = null
        brush = null
    }

    private fun allocTargets() {
        glDeleteTextures(2, tex, 0)
        glDeleteFramebuffers(2, fbo, 0)
        glGenFramebuffers(2, fbo, 0)
        val levels = 32 - Integer.numberOfLeadingZeros(maxOf(width, height))
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
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
    }

    private fun drawQuad(input: Int) {
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, input)
        drawFullQuad()
    }

    private companion object {
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
    if (uBlend != 0) p = blend(src.rgb, p);
    float m = 1.0;
    if (uMasked) {
        m = texelFetch(uMask, px, 0).r;
        if (uInvert) m = 1.0 - m;
    }
    outColor = vec4(mix(src.rgb, p, uOpacity * m), 1.0);
}"""

        // Combines one mask step into the mask so far (uTex). uKind: 0 brush (uBrush), 1 linear, 2 radial gradient.
        // Gradient math runs in upright image coordinates scaled by aspect, so circles stay round.
        const val MASK_FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uBrush;
uniform mat3 uMap;
uniform vec2 uOrigin;
uniform vec2 uFull;
uniform float uAspect;
uniform bool uFirst;
uniform bool uAdd;
uniform int uKind;
uniform vec4 uG;
out vec4 outColor;
void main() {
    ivec2 px = ivec2(gl_FragCoord.xy);
    float prev = uFirst ? 0.0 : texelFetch(uTex, px, 0).r;
    vec2 a = vec2(uAspect, 1.0);
    vec2 q = (uMap * vec3((gl_FragCoord.xy + uOrigin) / uFull, 1.0)).xy * a;
    vec2 q0 = uG.xy * a;
    vec2 d = uG.zw * a - q0;
    float s;
    if (uKind == 0) s = texelFetch(uBrush, px, 0).r;
    else if (uKind == 1) s = 1.0 - smoothstep(0.0, 1.0, dot(q - q0, d) / max(dot(d, d), 1e-8));
    else s = 1.0 - smoothstep(0.5, 1.0, length(q - q0) / max(length(d), 1e-6));
    outColor = vec4(uAdd ? max(prev, s) : prev * (1.0 - s), 0.0, 0.0, 1.0);
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
