package com.fractal.deepzoom

import android.opengl.GLES31
import android.opengl.GLSurfaceView
import android.util.Log
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Renders the Mandelbrot set with a single fullscreen pass.
 *
 * View state is kept in Double on the CPU and narrowed to Float only at upload time.
 * That costs nothing now and is what makes the perturbation upgrade a drop-in later:
 * the reference orbit has to be computed in high precision from this same center.
 */
class MandelbrotRenderer : GLSurfaceView.Renderer {

    // --- View state (complex plane) -------------------------------------------------
    // Stored as Double deliberately. Float32 loses the center long before the shader
    // math does, so keeping these wide is free headroom.
    @Volatile var centerX: Double = -0.5
    @Volatile var centerY: Double = 0.0

    /** Vertical extent of the view in complex-plane units. Smaller = deeper zoom. */
    @Volatile var spanY: Double = 3.0

    @Volatile var maxIter: Int = 512

    private var program = 0
    private var vao = 0

    private var uResolution = -1
    private var uCenter = -1
    private var uSpanY = -1
    private var uMaxIter = -1

    private var surfaceW = 1
    private var surfaceH = 1

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        GLES31.glClearColor(0f, 0f, 0f, 1f)

        program = buildProgram(VERTEX_SHADER, Shaders.MANDELBROT_SIMPLE)

        uResolution = GLES31.glGetUniformLocation(program, "uResolution")
        uCenter = GLES31.glGetUniformLocation(program, "uCenter")
        uSpanY = GLES31.glGetUniformLocation(program, "uSpanY")
        uMaxIter = GLES31.glGetUniformLocation(program, "uMaxIter")

        // Attribute-less rendering: the vertex shader synthesises a covering triangle
        // from gl_VertexID, so there is no vertex buffer to manage at all.
        val ids = IntArray(1)
        GLES31.glGenVertexArrays(1, ids, 0)
        vao = ids[0]
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        surfaceW = width
        surfaceH = height
        GLES31.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(unused: GL10?) {
        GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT)
        GLES31.glUseProgram(program)
        GLES31.glBindVertexArray(vao)

        GLES31.glUniform2f(uResolution, surfaceW.toFloat(), surfaceH.toFloat())
        GLES31.glUniform2f(uCenter, centerX.toFloat(), centerY.toFloat())
        GLES31.glUniform1f(uSpanY, spanY.toFloat())
        GLES31.glUniform1i(uMaxIter, maxIter)

        GLES31.glDrawArrays(GLES31.GL_TRIANGLES, 0, 3)
        GLES31.glBindVertexArray(0)
    }

    // --- Shader plumbing ------------------------------------------------------------

    private fun buildProgram(vsSrc: String, fsSrc: String): Int {
        val vs = compile(GLES31.GL_VERTEX_SHADER, vsSrc)
        val fs = compile(GLES31.GL_FRAGMENT_SHADER, fsSrc)
        val p = GLES31.glCreateProgram()
        GLES31.glAttachShader(p, vs)
        GLES31.glAttachShader(p, fs)
        GLES31.glLinkProgram(p)

        val status = IntArray(1)
        GLES31.glGetProgramiv(p, GLES31.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES31.glGetProgramInfoLog(p)
            GLES31.glDeleteProgram(p)
            throw RuntimeException("Program link failed: $log")
        }

        GLES31.glDeleteShader(vs)
        GLES31.glDeleteShader(fs)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES31.glCreateShader(type)
        GLES31.glShaderSource(s, src)
        GLES31.glCompileShader(s)

        val status = IntArray(1)
        GLES31.glGetShaderiv(s, GLES31.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES31.glGetShaderInfoLog(s)
            Log.e(TAG, "Shader compile failed: $log")
            GLES31.glDeleteShader(s)
            throw RuntimeException("Shader compile failed: $log")
        }
        return s
    }

    companion object {
        private const val TAG = "MandelbrotRenderer"

        private val VERTEX_SHADER = """
            #version 310 es
            void main() {
                // One oversized triangle covering the whole clip volume.
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
        """.trimIndent()
    }
}
