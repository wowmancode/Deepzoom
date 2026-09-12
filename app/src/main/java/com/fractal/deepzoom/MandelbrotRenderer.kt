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
    private var blaAbTexture = 0
    private var blaRTexture = 0

    private var surfaceW = 1
    private var surfaceH = 1

    private var orbit: ReferenceOrbit? = null
    @Volatile private var pendingOrbit: ReferenceOrbit? = null

    private var uploadedLen = 0
    private var uploadedScaleExp = 0
    private var uploadedBlaLevels = 0
    private var uploadedBlaOffset = IntArray(BlaTable.MAX_LEVELS)
    private var uploadedBlaCount = IntArray(BlaTable.MAX_LEVELS)

    private val building = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "orbit-builder").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    private val direct = HashMap<String, Int>()
    private val perturb = HashMap<String, Int>()

    private var fbo = 0
    private var fboTex = 0
    private var fboW = 0
    private var fboH = 0

    @Volatile var maxTextureSizeCached: Int = 0
        private set

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        GLES31.glClearColor(0f, 0f, 0f, 1f)

        directProgram = buildProgram(Shaders.VERTEX, Shaders.DIRECT)
        perturbProgram = buildProgram(Shaders.VERTEX, Shaders.PERTURBATION)

        cacheUniforms(directProgram, direct,
            "uResolution", "uMaxIter", "uColorCycle", "uCenter", "uSpanY")
        cacheUniforms(perturbProgram, perturb,
            "uResolution", "uMaxIter", "uColorCycle", "uOrbit", "uBlaAB", "uBlaR",
            "uWidthMask", "uWidthShift", "uOrbitLen", "uBlaLevels", "uBlaOffset[0]",
            "uBlaCount[0]", "uDeltaCenter", "uPixelSpan", "uInvScale", "uBailoutScaled")

        val ids = IntArray(1)
        GLES31.glGenVertexArrays(1, ids, 0)
        vao = ids[0]

        orbitTexture = createDataTexture()
        blaAbTexture = createDataTexture()
        blaRTexture = createDataTexture()

        val sizeQuery = IntArray(1)
        GLES31.glGetIntegerv(GLES31.GL_MAX_TEXTURE_SIZE, sizeQuery, 0)
        maxTextureSizeCached = sizeQuery[0]

        // Everything from the previous context died with it.
        orbit = null
        fbo = 0
        fboW = 0
    }

    /** Data arrays addressed in 2D. Any filtering would be corruption. */
    private fun createDataTexture(): Int {
        val ids = IntArray(1)
        GLES31.glGenTextures(1, ids, 0)
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, ids[0])
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_NEAREST)
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_NEAREST)
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_S, GLES31.GL_CLAMP_TO_EDGE)
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_T, GLES31.GL_CLAMP_TO_EDGE)
        return ids[0]
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
            upload(it)
        }

        if (state.needsPerturbation()) {
            ensureOrbitAsync()
            val o = orbit
            if (o != null && o.scaleExp == uploadedScaleExp) {
                drawPerturbation(state, o, surfaceW, surfaceH)
            } else {
                drawDirect(state, surfaceW, surfaceH)
            }
        } else {
            drawDirect(state, surfaceW, surfaceH)
        }
    }

    // --- Draw paths -------------------------------------------------------------------

    private fun drawDirect(s: ViewState, w: Int, h: Int) {
        GLES31.glUseProgram(directProgram)
        GLES31.glBindVertexArray(vao)

        GLES31.glUniform2f(direct["uResolution"]!!, w.toFloat(), h.toFloat())
        GLES31.glUniform1i(direct["uMaxIter"]!!, s.maxIter)
        GLES31.glUniform1f(direct["uColorCycle"]!!, colorCycle(s))
        GLES31.glUniform2f(direct["uCenter"]!!, s.centerX.toFloat(), s.centerY.toFloat())
        GLES31.glUniform1f(direct["uSpanY"]!!, s.spanY.toFloat())

        GLES31.glDrawArrays(GLES31.GL_TRIANGLES, 0, 3)
        GLES31.glBindVertexArray(0)
    }

    private fun drawPerturbation(s: ViewState, o: ReferenceOrbit, w: Int, h: Int) {
        GLES31.glUseProgram(perturbProgram)
        GLES31.glBindVertexArray(vao)

        val scale = o.scale
        val offset = s.offsetFrom(o)
        val pixelSpan = s.spanY / h

        GLES31.glUniform2f(perturb["uResolution"]!!, w.toFloat(), h.toFloat())
        GLES31.glUniform1i(perturb["uMaxIter"]!!, s.maxIter)
        GLES31.glUniform1f(perturb["uColorCycle"]!!, colorCycle(s))

        // Pre-scaled on the way in, so the shader never has to represent 1e-50.
        GLES31.glUniform2f(
            perturb["uDeltaCenter"]!!,
            (offset[0] * scale).toFloat(),
            (offset[1] * scale).toFloat()
        )
        GLES31.glUniform1f(perturb["uPixelSpan"]!!, (pixelSpan * scale).toFloat())
        GLES31.glUniform1f(perturb["uInvScale"]!!, (1.0 / scale).toFloat())
        GLES31.glUniform1f(perturb["uBailoutScaled"]!!, (BAILOUT * scale).toFloat())

        GLES31.glUniform1i(perturb["uWidthMask"]!!, TEX_WIDTH - 1)
        GLES31.glUniform1i(perturb["uWidthShift"]!!, TEX_SHIFT)
        GLES31.glUniform1i(perturb["uOrbitLen"]!!, uploadedLen)

        GLES31.glUniform1i(perturb["uBlaLevels"]!!, uploadedBlaLevels)
        if (uploadedBlaLevels > 0) {
            GLES31.glUniform1iv(
                perturb["uBlaOffset[0]"]!!, uploadedBlaLevels, uploadedBlaOffset, 0
            )
            GLES31.glUniform1iv(
                perturb["uBlaCount[0]"]!!, uploadedBlaLevels, uploadedBlaCount, 0
            )
        }

        bindTexture(0, orbitTexture, perturb["uOrbit"]!!)
        bindTexture(1, blaAbTexture, perturb["uBlaAB"]!!)
        bindTexture(2, blaRTexture, perturb["uBlaR"]!!)

        GLES31.glDrawArrays(GLES31.GL_TRIANGLES, 0, 3)
        GLES31.glBindVertexArray(0)
    }

    private fun bindTexture(unit: Int, texture: Int, location: Int) {
        GLES31.glActiveTexture(GLES31.GL_TEXTURE0 + unit)
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, texture)
        GLES31.glUniform1i(location, unit)
    }

    /** Widen colour bands with depth so detail does not compress into noise. */
    private fun colorCycle(s: ViewState): Float {
        val depth = s.zoomDepth().coerceAtLeast(0.0)
        return (0.035 / (1.0 + depth * 0.04)).toFloat()
    }

    // --- Reference orbit --------------------------------------------------------------

    private fun ensureOrbitAsync() {
        val aspect = surfaceW.toDouble() / surfaceH
        if (state.canReuse(orbit, aspect)) return
        if (!building.compareAndSet(false, true)) return

        // Snapshot: the UI thread keeps mutating state while this runs.
        val cx = state.centerX
        val cy = state.centerY
        val span = state.spanY
        val iter = state.maxIter
        val exp = state.deltaScaleExponent()
        val maxC = BlaTable.maxCFor(span, aspect)

        onOrbitStateChanged?.invoke(true)
        executor.execute {
            try {
                pendingOrbit = ReferenceOrbit.compute(cx, cy, iter, span, exp, maxC)
            } finally {
                building.set(false)
                onOrbitStateChanged?.invoke(false)
                requestRender?.invoke()
            }
        }
    }

    /** Synchronous variant for export, where frames must not be skipped. */
    private fun orbitForExport(
        s: ViewState,
        aspect: Double,
        cached: ReferenceOrbit?
    ): ReferenceOrbit {
        if (s.canReuse(cached, aspect)) return cached!!
        val built = ReferenceOrbit.compute(
            s.centerX, s.centerY, s.maxIter, s.spanY,
            s.deltaScaleExponent(), BlaTable.maxCFor(s.spanY, aspect)
        )
        upload(built)
        return built
    }

    private fun upload(o: ReferenceOrbit) {
        uploadOrbit(o)
        uploadBla(o.bla)
        uploadedScaleExp = o.scaleExp
    }

    private fun uploadOrbit(o: ReferenceOrbit) {
        val points = min(o.count + 1, MAX_POINTS)
        val packed = o.packForGpu()
        val rows = rowsFor(points)
        val buf = floatBuffer(rows * TEX_WIDTH * 4)

        buf.put(packed, 0, points * 4)
        // The shader never reads past uOrbitLen, but leaving the row tail
        // uninitialised invites driver-dependent surprises.
        while (buf.position() < rows * TEX_WIDTH * 4) buf.put(0f)
        buf.position(0)

        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, orbitTexture)
        GLES31.glPixelStorei(GLES31.GL_UNPACK_ALIGNMENT, 1)
        GLES31.glTexImage2D(
            GLES31.GL_TEXTURE_2D, 0, GLES31.GL_RGBA32F,
            TEX_WIDTH, rows, 0, GLES31.GL_RGBA, GLES31.GL_FLOAT, buf
        )
        uploadedLen = points - 1
    }

    private fun uploadBla(bla: BlaTable?) {
        if (bla == null || bla.total == 0 || bla.total > MAX_BLA_ENTRIES) {
            uploadedBlaLevels = 0
            return
        }

        val rows = rowsFor(bla.total)

        val abBuf = floatBuffer(rows * TEX_WIDTH * 4)
        abBuf.put(bla.ab, 0, bla.total * 4)
        while (abBuf.position() < rows * TEX_WIDTH * 4) abBuf.put(0f)
        abBuf.position(0)

        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, blaAbTexture)
        GLES31.glPixelStorei(GLES31.GL_UNPACK_ALIGNMENT, 1)
        GLES31.glTexImage2D(
            GLES31.GL_TEXTURE_2D, 0, GLES31.GL_RGBA32F,
            TEX_WIDTH, rows, 0, GLES31.GL_RGBA, GLES31.GL_FLOAT, abBuf
        )

        val rBuf = floatBuffer(rows * TEX_WIDTH)
        rBuf.put(bla.radius, 0, bla.total)
        // Zero radius means "never valid", so padding is inert by construction.
        while (rBuf.position() < rows * TEX_WIDTH) rBuf.put(0f)
        rBuf.position(0)

        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, blaRTexture)
        GLES31.glTexImage2D(
            GLES31.GL_TEXTURE_2D, 0, GLES31.GL_R32F,
            TEX_WIDTH, rows, 0, GLES31.GL_RED, GLES31.GL_FLOAT, rBuf
        )

        uploadedBlaLevels = min(bla.levels, BlaTable.MAX_LEVELS)
        java.util.Arrays.fill(uploadedBlaOffset, 0)
        java.util.Arrays.fill(uploadedBlaCount, 0)
        for (k in 0 until uploadedBlaLevels) {
            uploadedBlaOffset[k] = bla.levelOffset[k]
            uploadedBlaCount[k] = bla.levelCount[k]
        }
    }

    private fun rowsFor(entries: Int): Int =
        ceil(entries / TEX_WIDTH.toDouble()).toInt().coerceAtLeast(1)

    private fun floatBuffer(floats: Int): FloatBuffer =
        ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun invalidateOrbit() {
        orbit = null
    }

    // --- Offscreen rendering ----------------------------------------------------------

    private fun ensureFbo(w: Int, h: Int) {
        if (fboW == w && fboH == h && fbo != 0) return
        releaseFbo()

        val ids = IntArray(1)
        GLES31.glGenTextures(1, ids, 0)
        fboTex = ids[0]
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, fboTex)
        GLES31.glTexImage2D(
            GLES31.GL_TEXTURE_2D, 0, GLES31.GL_RGBA8, w, h, 0,
            GLES31.GL_RGBA, GLES31.GL_UNSIGNED_BYTE, null
        )
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_NEAREST)
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_NEAREST)

        GLES31.glGenFramebuffers(1, ids, 0)
        fbo = ids[0]
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, fbo)
        GLES31.glFramebufferTexture2D(
            GLES31.GL_FRAMEBUFFER, GLES31.GL_COLOR_ATTACHMENT0,
            GLES31.GL_TEXTURE_2D, fboTex, 0
        )

        val status = GLES31.glCheckFramebufferStatus(GLES31.GL_FRAMEBUFFER)
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)
        if (status != GLES31.GL_FRAMEBUFFER_COMPLETE) {
            releaseFbo()
            throw RuntimeException(
                "Cannot render at ${w}x${h} on this device (framebuffer status " +
                    "0x${status.toString(16)}). Try a smaller size."
            )
        }

        fboW = w
        fboH = h
    }

    private fun releaseFbo() {
        val ids = IntArray(1)
        if (fbo != 0) {
            ids[0] = fbo
            GLES31.glDeleteFramebuffers(1, ids, 0)
            fbo = 0
        }
        if (fboTex != 0) {
            ids[0] = fboTex
            GLES31.glDeleteTextures(1, ids, 0)
            fboTex = 0
        }
        fboW = 0
        fboH = 0
    }

    /**
     * Renders one view offscreen at an arbitrary size and reads it back as RGBA.
     * Must be called on the GL thread. The buffer is bottom-up, as glReadPixels gives it.
     */
    fun renderOffscreen(
        s: ViewState,
        w: Int,
        h: Int,
        out: ByteBuffer,
        cachedOrbit: ReferenceOrbit?
    ): ReferenceOrbit? {
        ensureFbo(w, h)
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, fbo)
        GLES31.glViewport(0, 0, w, h)
        GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT)

        var used: ReferenceOrbit? = null
        if (s.needsPerturbation()) {
            used = orbitForExport(s, w.toDouble() / h, cachedOrbit)
            drawPerturbation(s, used, w, h)
        } else {
            drawDirect(s, w, h)
        }

        out.position(0)
        GLES31.glReadPixels(0, 0, w, h, GLES31.GL_RGBA, GLES31.GL_UNSIGNED_BYTE, out)
        out.position(0)

        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)
        GLES31.glViewport(0, 0, surfaceW, surfaceH)

        // The live view's uploads were clobbered by the export's.
        invalidateOrbit()
        return used
    }

    fun releaseExportResources() {
        releaseFbo()
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    // --- Shader plumbing --------------------------------------------------------------

    private fun cacheUniforms(program: Int, into: HashMap<String, Int>, vararg names: String) {
        names.forEach { into[it] = GLES31.glGetUniformLocation(program, it) }
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
        const val TEX_WIDTH = 1024
        const val TEX_SHIFT = 10
        const val MAX_POINTS = 1024 * 128
        const val MAX_BLA_ENTRIES = 1024 * 256
        const val BAILOUT = 8.0
    }
}
