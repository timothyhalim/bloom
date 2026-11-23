package dev.bloom.editor.gl

import android.opengl.EGL14.*
import android.opengl.EGLExt

/** A GLES 3 context with a 1x1 pbuffer, current on the creating thread until [close]. Used by export. */
class Offscreen : AutoCloseable {
    private val display = eglGetDisplay(EGL_DEFAULT_DISPLAY)
    private val surface: android.opengl.EGLSurface
    private val context: android.opengl.EGLContext

    init {
        check(eglInitialize(display, IntArray(1), 0, IntArray(1), 0)) { "eglInitialize failed" }
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        val attribs = intArrayOf(
            EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE,
        )
        check(eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "no GLES 3 config" }
        context = eglCreateContext(display, configs[0], EGL_NO_CONTEXT, intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE), 0)
        surface = eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE), 0)
        check(context != EGL_NO_CONTEXT && surface != EGL_NO_SURFACE && eglMakeCurrent(display, surface, surface, context)) {
            "offscreen GL context failed: 0x${Integer.toHexString(eglGetError())}"
        }
    }

    override fun close() {
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT)
        eglDestroySurface(display, surface)
        eglDestroyContext(display, context)
    }
}
