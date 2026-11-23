package dev.bloom.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.bloom.editor.gl.DITHER
import dev.bloom.editor.gl.Pipeline
import dev.bloom.editor.gl.drawFullQuad
import dev.bloom.editor.gl.link
import dev.bloom.editor.model.Layer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Quad scale (NDC) that fits an image inside a view, preserving aspect. */
fun fitScale(imgW: Int, imgH: Int, viewW: Int, viewH: Int): Pair<Float, Float> {
    val img = imgW.toFloat() / imgH
    val view = viewW.toFloat() / viewH
    return if (img > view) 1f to view / img else img / view to 1f
}

/** One-finger touch on the photo while a brush or handle tool is active. */
enum class ToolTouch { Down, Move, Up, Cancel }

/**
 * GL preview: renders the layer stack, pinch-zoom, pan, double-tap reset.
 * With [onTool] set, one finger goes to the tool and two fingers pan and zoom.
 * The bottom inset is the floating chrome: at Fit the photo sits above it, and panning stops once its bottom edge clears it.
 */
@SuppressLint("ViewConstructor")
class PhotoView(context: Context) : GLSurfaceView(context), DefaultLifecycleObserver {
    private val renderer = PhotoRenderer()
    private var imgW = 0
    private var imgH = 0
    private var zoom = 1f
    private var panX = 0f
    private var panY = Float.NaN // NaN = rest at the top on the next update
    private var insetPx = 0
    private var shown = FloatArray(4) // quad scale x, y and offset x, y in NDC, as last drawn
    private var toolDown = false

    /** Visible part of the image (0..1, top-left origin) while zoomed past the view, else null. */
    var onViewport: ((RectF?) -> Unit)? = null

    /** Called after every zoom or pan, so overlays drawn over the photo can follow it. */
    var onTransform: (() -> Unit)? = null

    /** One-finger events in upright image coordinates (0..1). Null = one finger pans. */
    var onTool: ((ToolTouch, Float, Float) -> Unit)? = null

    /** Show the loupe while a tool finger is down (painting, eyedropper). */
    var loupe = false

    /** Brush ring radius on screen in px, drawn at the finger and in the loupe; 0 = no ring. */
    var brushRadiusPx = 0f

    /** Height of the photo on screen, in px. */
    val imageHeightPx: Float get() = shown[1] * height

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        contentDescription = "Photo"
    }

    fun setImage(bmp: Bitmap) {
        imgW = bmp.width
        imgH = bmp.height
        queueEvent { renderer.upload(bmp) }
        reset()
    }

    fun setLayers(layers: List<Layer>) {
        renderer.layers = layers
        requestRender()
    }

    /** Shows layer [id]'s mask over the photo as a red tint with a dashed edge. Null hides it. */
    fun setOverlay(id: Long?) {
        renderer.overlay = id
        requestRender()
    }

    /** Eyedropper: color of [layers] at upright (u, v), sRGB 0..1, delivered on the main thread. */
    fun sample(layers: List<Layer>, u: Float, v: Float, done: (FloatArray) -> Unit) {
        queueEvent {
            val c = renderer.sample(layers, u, v) ?: return@queueEvent
            post { done(c) }
            requestRender() // the readback replaced the cached render
        }
    }

    fun toImage(x: Float, y: Float): Pair<Float, Float> {
        val t = shown
        return ((2f * x / width - 1f - t[2]) / t[0] + 1f) / 2f to (1f - (1f - 2f * y / height - t[3]) / t[1]) / 2f
    }

    fun toView(u: Float, v: Float): Pair<Float, Float> {
        val t = shown
        return ((2f * u - 1f) * t[0] + t[2] + 1f) / 2f * width to (1f - ((1f - 2f * v) * t[1] + t[3])) / 2f * height
    }

    fun setBottomInset(px: Int) {
        if (px != insetPx) {
            insetPx = px
            update()
        }
    }

    /** Pans so image point (u, v) sits at the centre of the visible area. */
    fun centerOn(u: Float, v: Float) {
        val (sx, sy) = scales()
        panX = -(2f * u - 1f) * sx
        panY = -(1f - 2f * v) * sy
        update()
    }

    private fun reset() {
        zoom = 1f
        panX = 0f
        panY = Float.NaN
        update()
    }

    private fun scales(): Pair<Float, Float> = fitScale(imgW, imgH, width, height).let { (fx, fy) -> fx * zoom to fy * zoom }

    private fun update() {
        if (imgW == 0 || width == 0) return
        val (sx, sy) = scales()
        val cy = insetPx.toFloat() / height // centre of the area above the chrome, in NDC
        val a = 1f - cy // half-height of that area
        val mx = (sx - 1f).coerceAtLeast(0f)
        val my = (sy - a).coerceAtLeast(0f)
        panX = panX.coerceIn(-mx, mx)
        panY = if (panY.isNaN()) -my else panY.coerceIn(-my, my)
        val oy = panY + cy
        shown = floatArrayOf(sx, sy, panX, oy)
        renderer.setTransform(sx, sy, panX, oy)
        requestRender()
        onTransform?.invoke()
        onViewport?.invoke(
            if (zoom <= 1.01f || (sx <= 1f && sy <= a)) null
            else RectF(
                ((-1f - panX) / sx + 1f) / 2f, (1f - (1f - oy) / sy) / 2f,
                ((1f - panX) / sx + 1f) / 2f, (1f - (1f - 2f * a - oy) / sy) / 2f,
            ).apply { intersect(0f, 0f, 1f, 1f) },
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        update()
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val newZoom = (zoom * d.scaleFactor).coerceIn(1f, 20f)
            val k = newZoom / zoom
            val cy = insetPx.toFloat() / height
            // Keep the pinch focus point fixed on screen.
            val fx = 2f * d.focusX / width - 1f
            val fy = 1f - 2f * d.focusY / height
            panX = fx - (fx - panX) * k
            panY = fy - (fy - (panY + cy)) * k - cy
            zoom = newZoom
            update()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (onTool != null && e2.pointerCount < 2) return false
            panX -= 2f * dx / width
            panY += 2f * dy / height
            update()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (onTool != null) return false
            reset()
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        onTool?.let { tool ->
            val (u, v) = toImage(e.x, e.y)
            renderer.finger = if (loupe && e.actionMasked in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE) && (toolDown || e.actionMasked == MotionEvent.ACTION_DOWN)) {
                val d = resources.displayMetrics.density
                floatArrayOf(e.x, e.y, u, v, brushRadiusPx, d)
            } else null
            requestRender()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { toolDown = true; tool(ToolTouch.Down, u, v) }
                MotionEvent.ACTION_MOVE -> if (toolDown) tool(ToolTouch.Move, u, v)
                MotionEvent.ACTION_UP -> if (toolDown) { toolDown = false; tool(ToolTouch.Up, u, v) }
                // a second finger turns the touch into pan/zoom and drops the stroke
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> if (toolDown) { toolDown = false; tool(ToolTouch.Cancel, u, v) }
            }
        }
        scaleDetector.onTouchEvent(e)
        gestureDetector.onTouchEvent(e)
        return true
    }

    override fun onResume(owner: LifecycleOwner) = onResume()
    override fun onPause(owner: LifecycleOwner) = onPause()
}

private class PhotoRenderer : GLSurfaceView.Renderer {
    @Volatile var layers: List<Layer> = emptyList()
    @Volatile private var t = floatArrayOf(1f, 1f, 0f, 0f) // scale x, scale y, offset x, offset y
    @Volatile var overlay: Long? = null // layer id
    @Volatile var finger: FloatArray? = null // view x, y, image u, v, brush ring px, density; null = no loupe

    private var bitmap: Bitmap? = null // kept to re-upload after EGL context loss
    private var pipeline: Pipeline? = null
    private var program = 0
    private var uScale = 0
    private var uOffset = 0
    private var viewW = 1
    private var viewH = 1

    fun setTransform(sx: Float, sy: Float, ox: Float, oy: Float) {
        t = floatArrayOf(sx, sy, ox, oy)
    }

    fun sample(layers: List<Layer>, u: Float, v: Float): FloatArray? = pipeline?.takeIf { bitmap != null }?.sample(layers, u, v)

    fun upload(bmp: Bitmap) {
        bitmap = bmp
        pipeline?.setSource(bmp)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        glClearColor(0.055f, 0.055f, 0.055f, 1f)
        program = link(DISPLAY_VERTEX, DISPLAY_FRAGMENT)
        uScale = glGetUniformLocation(program, "uScale")
        uOffset = glGetUniformLocation(program, "uOffset")
        glUseProgram(program)
        glUniform1i(glGetUniformLocation(program, "uMask"), 1)
        pipeline = Pipeline().also { p -> bitmap?.let(p::setSource) }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        viewW = w
        viewH = h
    }

    override fun onDrawFrame(gl: GL10?) {
        val p = pipeline ?: return
        if (bitmap == null) {
            glClear(GL_COLOR_BUFFER_BIT)
            return
        }
        val start = System.nanoTime()
        val ls = layers
        val tex = p.render(ls)
        val ov = overlay
        val ol = ov?.let { o -> ls.firstOrNull { it.id == o && it.visible && it.mask.active } }
        val maskTex = ol?.let { p.maskTexture(it.id) } ?: 0
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, viewW, viewH)
        glClear(GL_COLOR_BUFFER_BIT)
        glUseProgram(program)
        val tr = t
        glUniform2f(uScale, tr[0], tr[1])
        glUniform2f(uOffset, tr[2], tr[3])
        glUniform1i(glGetUniformLocation(program, "uOverlay"), if (maskTex == 0) 0 else 1)
        glUniform1i(glGetUniformLocation(program, "uInvert"), if (ol?.mask?.invert == true) 1 else 0)
        glActiveTexture(GL_TEXTURE1)
        glBindTexture(GL_TEXTURE_2D, maskTex)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, tex)
        val f = finger
        val uLoupe = glGetUniformLocation(program, "uLoupe")
        val uRing = glGetUniformLocation(program, "uRing")
        glUniform4f(uLoupe, 0f, 0f, 0f, 0f)
        // brush ring at the finger (GL window coordinates have y up)
        glUniform3f(uRing, f?.get(0) ?: 0f, viewH - (f?.get(1) ?: 0f), f?.get(4) ?: 0f)
        drawFullQuad()
        if (f != null) {
            // Loupe: 104dp circle, top-left; moves to the top-right when the finger is within 150dp of that corner.
            val d = f[5]
            val r = 52f * d
            val near = f[0] < 150f * d + 2 * r && f[1] < 150f * d + 2 * r
            val cx = if (near) viewW - 72f * d - r else 16f * d + r
            val cy = viewH - (16f * d + r)
            glUniform2f(uScale, 1f, 1f)
            glUniform2f(uOffset, 0f, 0f)
            // 2× zoom, less for big brushes so the ring still fits inside (never below 1×)
            val zoom = if (f[4] > 0f) (0.8f * r / f[4]).coerceIn(1f, 2f) else 2f
            glUniform4f(uLoupe, cx, cy, r, zoom)
            glUniform2f(glGetUniformLocation(program, "uFinger"), f[2], f[3])
            glUniform2f(glGetUniformLocation(program, "uUvPerPx"), 1f / (tr[0] * viewW), 1f / (tr[1] * viewH))
            glUniform3f(uRing, cx, cy, f[4] * zoom)
            drawFullQuad()
        }
        // ponytail: glFinish + log for the M1 fps check; drop once a perf overlay exists.
        glFinish()
        Log.d("Bloom", "frame ${layers.size} layers ${(System.nanoTime() - start) / 100_000 / 10f} ms")
    }

    private companion object {
        const val DISPLAY_VERTEX = """#version 300 es
layout(location = 0) in vec2 aPos;
uniform vec2 uScale;
uniform vec2 uOffset;
out vec2 vUv;
void main() {
    vUv = vec2(aPos.x * 0.5 + 0.5, 0.5 - aPos.y * 0.5);
    gl_Position = vec4(aPos * uScale + uOffset, 0.0, 1.0);
}"""
        const val DISPLAY_FRAGMENT = """#version 300 es
precision highp float;
in vec2 vUv;
uniform sampler2D uTex;
uniform sampler2D uMask;
uniform int uOverlay; // 0 none, 1 red tint + hatch + edge
uniform bool uInvert;
uniform vec4 uLoupe; // centre x, y (window px), radius px, zoom; radius 0 = the normal photo pass
uniform vec2 uFinger; // image uv under the finger
uniform vec2 uUvPerPx; // image uv per screen px at the current zoom
uniform vec3 uRing; // brush ring centre x, y (window px) and radius px; radius 0 = none
out vec4 outColor;
""" + DITHER + """
void main() {
    vec2 uv = vUv;
    if (uLoupe.z > 0.0) {
        vec2 d = gl_FragCoord.xy - uLoupe.xy;
        float r = length(d);
        if (r > uLoupe.z) discard;
        if (r > uLoupe.z - 3.0) { outColor = vec4(vec3(r > uLoupe.z - 1.5 ? 0.0 : 1.0), 1.0); return; } // white rim, black outline
        uv = uFinger + vec2(d.x, -d.y) * uUvPerPx / uLoupe.w;
        if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) { outColor = vec4(0.055, 0.055, 0.055, 1.0); return; }
    }
    vec3 c = texture(uTex, uv).rgb;
    if (uOverlay > 0) {
        float m = texture(uMask, uv).r;
        if (uInvert) m = 1.0 - m;
        float diag = gl_FragCoord.x + gl_FragCoord.y;
        c = mix(c, vec3(1.0, 0.231, 0.188), m * (0.45 - 0.12 * step(0.5, fract(diag / 14.0))));
        // black-and-white dashed line where the mask crosses 50%
        float edge = 1.0 - smoothstep(0.0, 1.5 * fwidth(m) + 1e-4, abs(m - 0.5));
        c = mix(c, vec3(step(0.5, fract(diag / 16.0))), edge);
    }
    if (uRing.z > 0.0) {
        // white ring with a dark halo, readable on any photo
        float e = abs(length(gl_FragCoord.xy - uRing.xy) - uRing.z);
        c = mix(c, vec3(0.0), 0.5 * (1.0 - smoothstep(1.0, 2.5, e)));
        c = mix(c, vec3(1.0), 1.0 - smoothstep(0.5, 1.2, e));
    }
    outColor = vec4(dither(c), 1.0);
}"""
    }
}
