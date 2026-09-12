package com.fractal.deepzoom

import android.opengl.GLES31
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.ceil
import kotlin.math.min

class MandelbrotRenderer(private val state: ViewState) : GLSurfaceView.Renderer {

    var onOrbitStateChanged: ((building: Boolean) -> Unit)? = null
    var requestRender: (() -> Unit)? = null

    private var directProgram = 0
    private var perturbProgram = 0
    private var vao = 0
    private var orbitTexture = 0

    private var surfaceW = 1
    private var surfaceH = 1

    private var orbit: ReferenceOrbit? = null

    // Written on the orbit-builder thread, consumed on the GL thread.
    @Volatile private var pendingOrbit: ReferenceOrbit? = null
    private var uploadedOrbitLen = 0

    private val building = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "orbit-builder").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    private val direct = UniformSet()
    private val perturb = UniformSet()

    private class UniformSet {
        val loc = HashMap<String, Int>()
        fun get(name: String) = loc[name] ?: -1
    }

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        GLES31.glClearColor(0f, 0f, 0f, 1f)

        directProgram = buildProgram(Shaders.VERTEX, Shaders.DIRECT)
        perturbProgram = buildProgram(Shaders.VERTEX, Shaders.PERTURBATION)

        cacheUniforms(directProgram, direct,
            "uResolution", "uMaxIter", "uColorCycle", "uColorShift", "uCenter", "uSpanY")
        cacheUniforms(perturbProgram, perturb,
            "uResolution", "uMaxIter", "uColorCycle", "uColorShift", "uOrbit",
            "uOrbitWidth", "uOrbitLen", "uDeltaCenter", "uPixelSpan", "uScale",
            "uInvScale", "uBailoutScaled")

        val ids = IntArray(1)
        GLES31.glGenVertexArrays(1, ids, 0)
        vao = ids[0]

        GLES31.glGenTextures(1, ids, 0)
        orbitTexture = ids[0]
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, orbitTexture)
        // Nearest filtering and clamped wrap: this texture is a data array that
        // happens to be addressed in 2D, not an image. Any interpolation would be
        // corruption.
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_NEAREST)
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_NEAREST)
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_S, GLES31.GL_CLAMP_TO_EDGE)
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_T, GLES31.GL_CLAMP_TO_EDGE)

        // Force a rebuild: the previous orbit's texture died with the old context.
        orbit = null
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        surfaceW = width
        surfaceH = height
        GLES31.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(unused: GL10?) {
        GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT)

        pendingOrbit?.let {
            pendingOrbit = null
            orbit = it
            uploadOrbit(it)
        }

        if (state.needsPerturbation()) {
            ensureOrbit()
            val o = orbit
            if (o != null) drawPerturbation(o) else drawDirect()
        } else {
            drawDirect()
        }
    }

    // --- Draw paths -------------------------------------------------------------------

    private fun drawDirect() {
        GLES31.glUseProgram(directProgram)
        GLES31.glBindVertexArray(vao)

        GLES31.glUniform2f(direct.get("uResolution"), surfaceW.toFloat(), surfaceH.toFloat())
        GLES31.glUniform1i(direct.get("uMaxIter"), state.maxIter)
        GLES31.glUniform1f(direct.get("uColorCycle"), colorCycle())
        GLES31.glUniform1f(direct.get("uColorShift"), 0f)
        GLES31.glUniform2f(
            direct.get("uCenter"),
            state.centerX.toFloat(),
            state.centerY.toFloat()
        )
        GLES31.glUniform1f(direct.get("uSpanY"), state.spanY.toFloat())

        GLES31.glDrawArrays(GLES31.GL_TRIANGLES, 0, 3)
        GLES31.glBindVertexArray(0)
    }

    private fun drawPerturbation(o: ReferenceOrbit) {
        GLES31.glUseProgram(perturbProgram)
        GLES31.glBindVertexArray(vao)

        val scale = state.deltaScale()
        val offset = state.offsetFrom(o)
        val pixelSpan = state.spanY / surfaceH

        GLES31.glUniform2f(perturb.get("uResolution"), surfaceW.toFloat(), surfaceH.toFloat())
        GLES31.glUniform1i(perturb.get("uMaxIter"), state.maxIter)
        GLES31.glUniform1f(perturb.get("uColorCycle"), colorCycle())
        GLES31.glUniform1f(perturb.get("uColorShift"), 0f)

        // Everything below is handed over pre-scaled, so the shader never has to
        // represent a value near 1e-50 itself.
        GLES31.glUniform2f(
            perturb.get("uDeltaCenter"),
            (offset[0] * scale).toFloat(),
            (offset[1] * scale).toFloat()
        )
        GLES31.glUniform1f(perturb.get("uPixelSpan"), (pixelSpan * scale).toFloat())
        GLES31.glUniform1f(perturb.get("uScale"), scale.toFloat())
        GLES31.glUniform1f(perturb.get("uInvScale"), (1.0 / scale).toFloat())
        GLES31.glUniform1f(perturb.get("uBailoutScaled"), (4.0 * scale).toFloat())

        GLES31.glUniform1i(perturb.get("uOrbitWidth"), ORBIT_TEX_WIDTH)
        GLES31.glUniform1i(perturb.get("uOrbitLen"), uploadedOrbitLen)

        GLES31.glActiveTexture(GLES31.GL_TEXTURE0)
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, orbitTexture)
        GLES31.glUniform1i(perturb.get("uOrbit"), 0)

        GLES31.glDrawArrays(GLES31.GL_TRIANGLES, 0, 3)
        GLES31.glBindVertexArray(0)
    }

    /** Widen colour bands as depth increases so detail does not compress into noise. */
    private fun colorCycle(): Float {
        val depth = state.zoomDepth().coerceAtLeast(0.0)
        return (0.035 / (1.0 + depth * 0.04)).toFloat()
    }

    // --- Reference orbit --------------------------------------------------------------

    private fun ensureOrbit() {
        val aspect = surfaceW.toDouble() / surfaceH
        if (state.canReuse(orbit, aspect)) return
        if (!building.compareAndSet(false, true)) return

        // Snapshot before handing off: the UI thread will keep mutating state.
        val cx = state.centerX
        val cy = state.centerY
        val span = state.spanY
        val iter = state.maxIter

        onOrbitStateChanged?.invoke(true)
        executor.execute {
            try {
                val built = ReferenceOrbit.compute(cx, cy, iter, span)
                pendingOrbit = built
            } finally {
                building.set(false)
                onOrbitStateChanged?.invoke(false)
                requestRender?.invoke()
            }
        }
    }

    private fun uploadOrbit(o: ReferenceOrbit) {
        val points = min(o.count + 1, MAX_ORBIT_POINTS)
        val rows = ceil(points / ORBIT_TEX_WIDTH.toDouble()).toInt().coerceAtLeast(1)
        val texels = rows * ORBIT_TEX_WIDTH

        val buf: FloatBuffer = ByteBuffer
            .allocateDirect(texels * 2 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        buf.put(o.data, 0, points * 2)
        // Pad the tail of the last row; the shader never reads past uOrbitLen, but
        // leaving it uninitialised invites driver-dependent surprises.
        while (buf.position() < texels * 2) buf.put(0f)
        buf.position(0)

        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, orbitTexture)
        GLES31.glPixelStorei(GLES31.GL_UNPACK_ALIGNMENT, 1)
        GLES31.glTexImage2D(
            GLES31.GL_TEXTURE_2D, 0, GLES31.GL_RG32F,
            ORBIT_TEX_WIDTH, rows, 0,
            GLES31.GL_RG, GLES31.GL_FLOAT, buf
        )

        uploadedOrbitLen = points - 1
    }

    fun invalidateOrbit() {
        orbit = null
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    // --- Shader plumbing --------------------------------------------------------------

    private fun cacheUniforms(program: Int, set: UniformSet, vararg names: String) {
        names.forEach { set.loc[it] = GLES31.glGetUniformLocation(program, it) }
    }

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
            GLES31.glDeleteShader(s)
            throw RuntimeException("Shader compile failed: $log")
        }
        return s
    }

    companion object {
        const val ORBIT_TEX_WIDTH = 1024
        const val MAX_ORBIT_POINTS = 1024 * 128
    }
}
