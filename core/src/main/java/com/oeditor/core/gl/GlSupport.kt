package com.oeditor.core.gl

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Thin, explicit wrapper around a GL program with cached uniform locations. */
class GlProgram private constructor(val id: Int) {

    private val uniforms = HashMap<String, Int>()

    fun uniform(name: String): Int = uniforms.getOrPut(name) { GLES30.glGetUniformLocation(id, name) }

    fun use() = GLES30.glUseProgram(id)

    fun setFloat(name: String, value: Float) = GLES30.glUniform1f(uniform(name), value)

    fun setInt(name: String, value: Int) = GLES30.glUniform1i(uniform(name), value)

    fun setVec2(name: String, x: Float, y: Float) = GLES30.glUniform2f(uniform(name), x, y)

    fun setVec3(name: String, x: Float, y: Float, z: Float) = GLES30.glUniform3f(uniform(name), x, y, z)

    fun setFloats(name: String, values: FloatArray) = GLES30.glUniform1fv(uniform(name), values.size, values, 0)

    fun setMatrix(name: String, matrix: FloatArray) = GLES30.glUniformMatrix4fv(uniform(name), 1, false, matrix, 0)

    fun delete() = GLES30.glDeleteProgram(id)

    companion object {
        fun create(vertexSource: String, fragmentSource: String): GlProgram {
            val vs = compile(GLES30.GL_VERTEX_SHADER, vertexSource)
            val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
            val program = GLES30.glCreateProgram()
            GLES30.glAttachShader(program, vs)
            GLES30.glAttachShader(program, fs)
            GLES30.glLinkProgram(program)
            val status = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES30.glGetProgramInfoLog(program)
                GLES30.glDeleteProgram(program)
                throw IllegalStateException("Program link failed: $log")
            }
            GLES30.glDeleteShader(vs)
            GLES30.glDeleteShader(fs)
            return GlProgram(program)
        }

        private fun compile(type: Int, source: String): Int {
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(shader)
                GLES30.glDeleteShader(shader)
                throw IllegalStateException("Shader compile failed: $log")
            }
            return shader
        }
    }
}

/** Off-screen colour target used for ping-pong effect passes. */
class Framebuffer(width: Int, height: Int) {
    var width: Int = width
        private set
    var height: Int = height
        private set

    var textureId: Int = 0
        private set
    private var framebufferId: Int = 0

    init {
        create(width, height)
    }

    private fun create(w: Int, h: Int) {
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        textureId = tex[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA,
            w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val fbo = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        framebufferId = fbo[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebufferId)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, textureId, 0
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            throw IllegalStateException("Framebuffer incomplete: $status")
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    fun resize(w: Int, h: Int) {
        if (w == width && h == height) return
        width = w
        height = h
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA,
            w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
        )
    }

    fun bind() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebufferId)
        GLES30.glViewport(0, 0, width, height)
    }

    fun delete() {
        GLES30.glDeleteFramebuffers(1, intArrayOf(framebufferId), 0)
        GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
    }
}

/** Full-screen quad shared by every pass. */
class Quad {
    private val vertexBuffer: FloatBuffer
    private val texBuffer: FloatBuffer

    init {
        val vertices = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val texCoords = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(vertices); position(0) }
        texBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(texCoords); position(0) }
    }

    fun draw(program: GlProgram) {
        val posLoc = GLES30.glGetAttribLocation(program.id, "a_pos")
        val texLoc = GLES30.glGetAttribLocation(program.id, "a_tex")
        GLES30.glEnableVertexAttribArray(posLoc)
        GLES30.glVertexAttribPointer(posLoc, 2, GLES30.GL_FLOAT, false, 0, vertexBuffer)
        GLES30.glEnableVertexAttribArray(texLoc)
        GLES30.glVertexAttribPointer(texLoc, 2, GLES30.GL_FLOAT, false, 0, texBuffer)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(posLoc)
        GLES30.glDisableVertexAttribArray(texLoc)
    }
}

/**
 * EGL context bound to either a window surface (encoder input) or a pbuffer
 * (offscreen). Preview rendering reuses the GLSurfaceView context instead.
 */
class GlEnv private constructor(
    private val display: EGLDisplay,
    private val context: EGLContext,
    private val surface: EGLSurface,
) {
    fun makeCurrent() {
        EGL14.eglMakeCurrent(display, surface, surface, context)
    }

    fun swap() {
        EGL14.eglSwapBuffers(display, surface)
    }

    fun setPresentationTime(nanos: Long) {
        try {
            android.opengl.EGLExt.eglPresentationTimeANDROID(display, surface, nanos)
        } catch (_: Throwable) {
            // Some drivers omit EGL_ANDROID_presentation_time; PTS then comes from the encoder.
        }
    }

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
    }

    companion object {
        fun createWindow(target: Surface): GlEnv = create(target, 0, 0)

        fun createPbuffer(width: Int, height: Int): GlEnv = create(null, width, height)

        private fun create(target: Surface?, width: Int, height: Int): GlEnv {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

            val configAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, if (target == null) EGL14.EGL_PBUFFER_BIT else EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            check(
                EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) &&
                    numConfigs[0] > 0
            ) { "No EGL config" }
            val config = configs[0]!!

            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
            val context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0
            )
            check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

            val surface: EGLSurface = if (target != null) {
                EGL14.eglCreateWindowSurface(display, config, target, intArrayOf(EGL14.EGL_NONE), 0)
            } else {
                EGL14.eglCreatePbufferSurface(
                    display, config,
                    intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0
                )
            }
            check(surface != EGL14.EGL_NO_SURFACE) { "eglCreateSurface failed" }
            EGL14.eglMakeCurrent(display, surface, surface, context)
            return GlEnv(display, context, surface)
        }
    }
}

object GlTextures {

    fun fromBitmap(bitmap: Bitmap): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        return ids[0]
    }

    fun delete(id: Int) {
        if (id != 0) GLES30.glDeleteTextures(1, intArrayOf(id), 0)
    }
}
