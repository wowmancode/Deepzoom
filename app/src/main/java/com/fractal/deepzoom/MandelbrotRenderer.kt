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
import kotlin.math.max
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min

/** An orbit together with the zoom-dependent data derived from it. */
class OrbitBundle(val orbit: ReferenceOrbit, val gpu: OrbitGpuData)

class MandelbrotRenderer(private val state: ViewState) : GLSurfaceView.Renderer {

    var onOrbitStateChanged: ((building: Boolean) -> Unit)? = null
    var requestRender: (() -> Unit)? = null

    @Volatile var palette: PaletteSpec = Palettes.PRESETS[0]
        set(value) {
            field = value
            paletteDirty = true
        }
    @Volatile private var paletteDirty = true

    private var directProgram = 0
    private var perturbProgram = 0
    private var directTileProgram = 0
    private var perturbTileProgram = 0
    private var blitProgram = 0
    private var directStripProgram = 0
    private var perturbStripProgram = 0
    private var unwarpProgram = 0

    // Exponential-map strip, held as a ring buffer of rows.
    private var stripFbo = 0
    private var stripTex = 0
    private var stripW = 0
    private var stripRing = 0

    // One texel per tile: 1 means the border pass proved the tile entirely interior.
    private var tileFbo = 0
    private var tileTex = 0
    private var tileTexW = 0
    private var tileTexH = 0
    private var vao = 0

    // The scene is rendered here, possibly at reduced size, and presented from here.
    // Keeping the last completed frame around is what makes gestures free.
    private var sceneFbo = 0
    private var sceneTex = 0
    private var sceneW = 0
    private var sceneH = 0

    private var snapValid = false
    private var snapCenterX: java.math.BigDecimal = java.math.BigDecimal.ZERO
    private var snapCenterY: java.math.BigDecimal = java.math.BigDecimal.ZERO
    private var snapSpanY = 1.0
    private var snapW = 0
    private var snapH = 0

    /** Levels are powers of two below native: 3 is eighth-size, 0 is native. */
    private var refineLevel = -1
    var finestLevel: Int = 0
        set(value) {
            field = value.coerceIn(0, 3)
            markDirty()
        }

    @Volatile var interactive = false

    /** 0 = always native, 1 = drop resolution only if frames get slow, 2 = always fast. */
    @Volatile var motionQuality: Int = QUALITY_ADAPTIVE

    // Adaptive state. Only consulted while moving, and it walks back to native as soon
    // as frames are cheap again.
    private var adaptiveLevel = 0
    private var lastRenderMs = 0.0

    private var orbitTexture = 0
    private var blaAbTexture = 0
    private var blaRTexture = 0
    private var paletteTexture = 0

    private var surfaceW = 1
    private var surfaceH = 1

    /** Owned by the builder thread. */
    private var workingOrbit: ReferenceOrbit? = null

    /**
     * Export runs on the GL thread and would otherwise mutate the same orbit the
     * builder thread is extending, so it keeps its own.
     */
    private var exportOrbit: ReferenceOrbit? = null
    private var buildingForExport = false

    /** Owned by the GL thread. */
    private var active: OrbitBundle? = null

    @Volatile private var pending: OrbitBundle? = null

    private var uploadedLen = 0
    private var uploadedBlaLevels = 0
    private val uploadedBlaOffset = IntArray(BlaTable.MAX_LEVELS)
    private val uploadedBlaCount = IntArray(BlaTable.MAX_LEVELS)

    private val building = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "orbit-builder").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    private val direct = HashMap<String, Int>()
    private val perturb = HashMap<String, Int>()
    private val blit = HashMap<String, Int>()
    private val directTile = HashMap<String, Int>()
    private val perturbTile = HashMap<String, Int>()
    private val directStrip = HashMap<String, Int>()
    private val perturbStrip = HashMap<String, Int>()
    private val unwarp = HashMap<String, Int>()

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

        cacheUniforms(directProgram, direct, *DIRECT_UNIFORMS)
        cacheUniforms(perturbProgram, perturb, *PERTURB_UNIFORMS)

        directTileProgram = buildProgram(Shaders.VERTEX, Shaders.DIRECT_TILE)
        perturbTileProgram = buildProgram(Shaders.VERTEX, Shaders.PERTURB_TILE)
        cacheUniforms(directTileProgram, directTile, *DIRECT_UNIFORMS)
        cacheUniforms(perturbTileProgram, perturbTile, *PERTURB_UNIFORMS)

        directStripProgram = buildProgram(Shaders.VERTEX, Shaders.DIRECT_STRIP)
        perturbStripProgram = buildProgram(Shaders.VERTEX, Shaders.PERTURB_STRIP)
        cacheUniforms(directStripProgram, directStrip, *DIRECT_UNIFORMS)
        cacheUniforms(perturbStripProgram, perturbStrip, *PERTURB_UNIFORMS)

        unwarpProgram = buildProgram(Shaders.VERTEX, Shaders.UNWARP)
        cacheUniforms(unwarpProgram, unwarp,
            "uStrip", "uResolution", "uRingHeight", "uRowBase", "uStepInv", "uMinRadius")

        blitProgram = buildProgram(Shaders.VERTEX, Shaders.BLIT)
        cacheUniforms(blitProgram, blit,
            "uScene", "uResolution", "uValidFrac", "uShift", "uZoom", "uBackground")

        val ids = IntArray(1)
        GLES31.glGenVertexArrays(1, ids, 0)
        vao = ids[0]

        orbitTexture = createDataTexture()
        blaAbTexture = createDataTexture()
        blaRTexture = createDataTexture()
        paletteTexture = createRampTexture()

        // Every program binds the tile sampler, including paths that never run the
        // tile pass. Binding texture 0 to a sampler is an incomplete texture, which
        // some drivers treat as a failed draw.
        ensureTileTarget()

        val sizeQuery = IntArray(1)
        GLES31.glGetIntegerv(GLES31.GL_MAX_TEXTURE_SIZE, sizeQuery, 0)
        maxTextureSizeCached = sizeQuery[0]

        // Everything from the previous context died with it.
        active = null
        paletteDirty = true
        fbo = 0
        fboW = 0
        sceneFbo = 0
        sceneW = 0
        tileFbo = 0
        tileTexW = 0
        stripFbo = 0
        stripW = 0
        snapValid = false
        markDirty()
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

    /** Linear filtering and repeat wrapping give the palette its blending and seam. */
    private fun createRampTexture(): Int {
        val ids = IntArray(1)
        GLES31.glGenTextures(1, ids, 0)
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, ids[0])
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_LINEAR)
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_LINEAR)
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_S, GLES31.GL_REPEAT)
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_T, GLES31.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        surfaceW = width
        surfaceH = height
        GLES31.glViewport(0, 0, width, height)
        ensureSceneTarget(width, height)
        snapValid = false
        markDirty()
    }

    /** Restart progressive refinement from the coarsest level. */
    fun markDirty() {
        refineLevel = min(3, finestLevel + COARSE_STEPS)
    }

    /** Called when a gesture starts, so adaptation begins from the native target. */
    fun resetAdaptive() {
        adaptiveLevel = finestLevel
    }

    fun lastFrameMs(): Double = lastRenderMs

    override fun onDrawFrame(unused: GL10?) {
        uploadPaletteIfDirty()

        pending?.let {
            pending = null
            active = it
            upload(it.gpu)
            markDirty()
        }

        // Resolution while moving. Full res is the default target: at this point a
        // frame is mostly texture fetches and a short BLA loop, so native usually
        // sustains interactive rates. The adaptive mode only gives ground when the
        // measured frame time says it has to, and takes it straight back.
        if (interactive) {
            val level = motionLevel()
            renderSceneAtLevel(level, measure = motionQuality == QUALITY_ADAPTIVE)
            presentScene(reproject = false)
            return
        }

        if (refineLevel < 0) {
            if (snapValid) presentScene(reproject = true) else GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT)
            return
        }

        renderSceneAtLevel(refineLevel, measure = false)
        presentScene(reproject = false)

        // Step down one level per frame. The coarse pass appears almost immediately and
        // each refinement replaces it, so the wait for native resolution is hidden
        // behind something already on screen.
        refineLevel = if (refineLevel > finestLevel) refineLevel - 1 else -1
        if (refineLevel >= 0) requestRender?.invoke()
    }

    private fun ensureSceneTarget(w: Int, h: Int) {
        if (sceneW == w && sceneH == h && sceneFbo != 0) return
        releaseSceneTarget()

        val ids = IntArray(1)
        GLES31.glGenTextures(1, ids, 0)
        sceneTex = ids[0]
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, sceneTex)
        GLES31.glTexImage2D(
            GLES31.GL_TEXTURE_2D, 0, GLES31.GL_RGBA8, w, h, 0,
            GLES31.GL_RGBA, GLES31.GL_UNSIGNED_BYTE, null
        )
        // Linear so coarse passes upscale smoothly rather than showing blocks.
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_LINEAR)
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_LINEAR)
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_S, GLES31.GL_CLAMP_TO_EDGE)
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_T, GLES31.GL_CLAMP_TO_EDGE)

        GLES31.glGenFramebuffers(1, ids, 0)
        sceneFbo = ids[0]
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, sceneFbo)
        GLES31.glFramebufferTexture2D(
            GLES31.GL_FRAMEBUFFER, GLES31.GL_COLOR_ATTACHMENT0,
            GLES31.GL_TEXTURE_2D, sceneTex, 0
        )
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)

        sceneW = w
        sceneH = h
    }

    /** One texel per tile, sized for the largest frame we might render. */
    private fun ensureTileTarget() = ensureTileTargetFor(surfaceW, surfaceH)

    private fun ensureTileTargetFor(frameW: Int, frameH: Int) {
        val w = (frameW + TILE_SIZE - 1) / TILE_SIZE
        val h = (frameH + TILE_SIZE - 1) / TILE_SIZE
        if (tileTexW == w && tileTexH == h && tileFbo != 0) return

        val ids = IntArray(1)
        if (tileFbo != 0) { ids[0] = tileFbo; GLES31.glDeleteFramebuffers(1, ids, 0) }
        if (tileTex != 0) { ids[0] = tileTex; GLES31.glDeleteTextures(1, ids, 0) }

        GLES31.glGenTextures(1, ids, 0)
        tileTex = ids[0]
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, tileTex)
        GLES31.glTexImage2D(
            GLES31.GL_TEXTURE_2D, 0, GLES31.GL_R8, w, h, 0,
            GLES31.GL_RED, GLES31.GL_UNSIGNED_BYTE, null
        )
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_NEAREST)
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_NEAREST)
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_S, GLES31.GL_CLAMP_TO_EDGE)
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_T, GLES31.GL_CLAMP_TO_EDGE)

        GLES31.glGenFramebuffers(1, ids, 0)
        tileFbo = ids[0]
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, tileFbo)
        GLES31.glFramebufferTexture2D(
            GLES31.GL_FRAMEBUFFER, GLES31.GL_COLOR_ATTACHMENT0,
            GLES31.GL_TEXTURE_2D, tileTex, 0
        )
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)

        tileTexW = w
        tileTexH = h
    }

    private fun releaseSceneTarget() {
        val ids = IntArray(1)
        if (sceneFbo != 0) { ids[0] = sceneFbo; GLES31.glDeleteFramebuffers(1, ids, 0); sceneFbo = 0 }
        if (sceneTex != 0) { ids[0] = sceneTex; GLES31.glDeleteTextures(1, ids, 0); sceneTex = 0 }
        sceneW = 0; sceneH = 0
    }

    private fun motionLevel(): Int = when (motionQuality) {
        QUALITY_FULL -> finestLevel
        QUALITY_FAST -> min(3, finestLevel + 2)
        else -> adaptiveLevel.coerceIn(finestLevel, min(3, finestLevel + 3))
    }

    /**
     * Frame timing needs glFinish to mean anything — without it the call returns long
     * before the GPU has done the work, and the adaptation would chase noise. The stall
     * costs a little pipelining, which is a fair trade for not guessing.
     */
    private fun updateAdaptive(startNs: Long) {
        GLES31.glFinish()
        lastRenderMs = (System.nanoTime() - startNs) / 1e6
        if (lastRenderMs > SLOW_FRAME_MS) {
            adaptiveLevel = min(adaptiveLevel + 1, min(3, finestLevel + 3))
        } else if (lastRenderMs < FAST_FRAME_MS) {
            adaptiveLevel = max(adaptiveLevel - 1, finestLevel)
        }
    }

    /** Renders the fractal into the lower-left sub-rect of the scene texture. */
    private fun renderSceneAtLevel(level: Int, measure: Boolean) {
        val startNs = if (measure) System.nanoTime() else 0L
        ensureSceneTarget(surfaceW, surfaceH)
        val w = max(1, surfaceW shr level)
        val h = max(1, surfaceH shr level)

        // Tile pass first: classify which tiles are entirely interior, so the main
        // pass can fill them without iterating. Only worth it once the frame is big
        // enough that the perimeter is a small fraction of the tile.
        val tiles = w >= TILE_MIN_WIDTH
        val bundle = if (state.needsPerturbation()) {
            ensureBundleAsync()
            active
        } else null

        if (tiles) {
            val tw = (w + TILE_SIZE - 1) / TILE_SIZE
            val th = (h + TILE_SIZE - 1) / TILE_SIZE
            ensureTileTarget()
            GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, tileFbo)
            GLES31.glViewport(0, 0, tw, th)
            GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT)
            if (bundle != null) drawPerturbation(state, bundle, w, h, false, tilePass = true)
            else drawDirect(state, w, h, false, tilePass = true)
        }

        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, sceneFbo)
        GLES31.glViewport(0, 0, w, h)
        GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT)

        if (state.needsPerturbation()) {
            if (bundle != null) drawPerturbation(state, bundle, w, h, tiles)
            else drawDirect(state, w, h, tiles)
        } else {
            drawDirect(state, w, h, tiles)
        }

        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)
        GLES31.glViewport(0, 0, surfaceW, surfaceH)
        if (measure) updateAdaptive(startNs)

        snapCenterX = state.centerX
        snapCenterY = state.centerY
        snapSpanY = state.spanY
        snapW = w
        snapH = h
        snapValid = true
    }

    /**
     * Draws the scene texture to the screen.
     *
     * When reprojecting, the shift and zoom describe how far the view has moved since
     * the frame was rendered. The centre difference is tiny in absolute terms even at
     * extreme depth, so plain doubles carry it safely.
     */
    private fun presentScene(reproject: Boolean) {
        if (!snapValid || sceneTex == 0) return

        var shiftX = 0.0
        var shiftY = 0.0
        var zoom = 1.0
        if (reproject) {
            val mc = state.mathContext
            val spanX = snapSpanY * (surfaceW.toDouble() / surfaceH)
            shiftX = state.centerX.subtract(snapCenterX, mc).toDouble() / spanX
            shiftY = state.centerY.subtract(snapCenterY, mc).toDouble() / snapSpanY
            zoom = state.spanY / snapSpanY
        }

        GLES31.glUseProgram(blitProgram)
        GLES31.glBindVertexArray(vao)
        GLES31.glUniform2f(blit["uResolution"]!!, surfaceW.toFloat(), surfaceH.toFloat())
        GLES31.glUniform2f(
            blit["uValidFrac"]!!,
            snapW.toFloat() / sceneW, snapH.toFloat() / sceneH
        )
        GLES31.glUniform2f(blit["uShift"]!!, shiftX.toFloat(), shiftY.toFloat())
        GLES31.glUniform1f(blit["uZoom"]!!, zoom.toFloat())
        val p = palette
        GLES31.glUniform3f(
            blit["uBackground"]!!,
            ((p.interior shr 16) and 0xFF) / 255f,
            ((p.interior shr 8) and 0xFF) / 255f,
            (p.interior and 0xFF) / 255f
        )
        bindTexture(4, sceneTex, blit["uScene"]!!)

        GLES31.glDrawArrays(GLES31.GL_TRIANGLES, 0, 3)
        GLES31.glBindVertexArray(0)
    }

    // --- Draw paths -------------------------------------------------------------------

    private fun applyColorUniforms(u: HashMap<String, Int>) {
        val p = palette
        GLES31.glUniform1f(u["uCycle"]!!, p.cycleLength.coerceAtLeast(2f))
        GLES31.glUniform1f(u["uOffset"]!!, p.offset)
        GLES31.glUniform3f(
            u["uInterior"]!!,
            ((p.interior shr 16) and 0xFF) / 255f,
            ((p.interior shr 8) and 0xFF) / 255f,
            (p.interior and 0xFF) / 255f
        )
        bindTexture(3, paletteTexture, u["uPalette"]!!)
    }

    private fun drawDirect(s: ViewState, w: Int, h: Int, tiles: Boolean, tilePass: Boolean = false) {
        val u = if (tilePass) directTile else direct
        GLES31.glUseProgram(if (tilePass) directTileProgram else directProgram)
        GLES31.glBindVertexArray(vao)

        GLES31.glUniform2f(u["uResolution"]!!, w.toFloat(), h.toFloat())
        GLES31.glUniform1i(u["uMaxIter"]!!, s.maxIter)
        applyColorUniforms(u)
        applyTileUniforms(u, tiles && !tilePass)
        GLES31.glUniform2f(u["uCenter"]!!, s.centerX.toFloat(), s.centerY.toFloat())
        GLES31.glUniform1f(u["uSpanY"]!!, s.spanY.toFloat())

        GLES31.glDrawArrays(GLES31.GL_TRIANGLES, 0, 3)
        GLES31.glBindVertexArray(0)
    }

    private fun drawPerturbation(
        s: ViewState, b: OrbitBundle, w: Int, h: Int,
        tiles: Boolean, tilePass: Boolean = false
    ) {
        val u = if (tilePass) perturbTile else perturb
        GLES31.glUseProgram(if (tilePass) perturbTileProgram else perturbProgram)
        GLES31.glBindVertexArray(vao)

        val scale = b.gpu.scale
        val offset = s.offsetFrom(b.orbit)
        val pixelSpan = s.spanY / h

        GLES31.glUniform2f(u["uResolution"]!!, w.toFloat(), h.toFloat())
        GLES31.glUniform1i(u["uMaxIter"]!!, min(s.maxIter, b.orbit.iterBuilt))
        applyColorUniforms(u)
        applyTileUniforms(u, tiles && !tilePass)

        // Pre-scaled on the way in, so the shader never has to represent 1e-50.
        GLES31.glUniform2f(
            u["uDeltaCenter"]!!,
            (offset[0] * scale).toFloat(),
            (offset[1] * scale).toFloat()
        )
        GLES31.glUniform1f(u["uPixelSpan"]!!, (pixelSpan * scale).toFloat())
        GLES31.glUniform1f(u["uInvScale"]!!, (1.0 / scale).toFloat())
        GLES31.glUniform1f(u["uBailoutScaled"]!!, (BAILOUT * scale).toFloat())

        GLES31.glUniform1i(u["uWidthMask"]!!, TEX_WIDTH - 1)
        GLES31.glUniform1i(u["uWidthShift"]!!, TEX_SHIFT)
        GLES31.glUniform1i(u["uOrbitLen"]!!, uploadedLen)

        GLES31.glUniform1i(u["uBlaLevels"]!!, uploadedBlaLevels)
        if (uploadedBlaLevels > 0) {
            GLES31.glUniform1iv(u["uBlaOffset[0]"]!!, uploadedBlaLevels, uploadedBlaOffset, 0)
            GLES31.glUniform1iv(u["uBlaCount[0]"]!!, uploadedBlaLevels, uploadedBlaCount, 0)
        }

        bindTexture(0, orbitTexture, u["uOrbit"]!!)
        bindTexture(1, blaAbTexture, u["uBlaAB"]!!)
        bindTexture(2, blaRTexture, u["uBlaR"]!!)

        GLES31.glDrawArrays(GLES31.GL_TRIANGLES, 0, 3)
        GLES31.glBindVertexArray(0)
    }

    private fun applyTileUniforms(u: HashMap<String, Int>, enabled: Boolean) {
        GLES31.glUniform1i(u["uTileSize"]!!, TILE_SIZE)
        GLES31.glUniform1i(u["uUseTiles"]!!, if (enabled) 1 else 0)
        bindTexture(5, tileTex, u["uTiles"]!!)
    }

    private fun bindTexture(unit: Int, texture: Int, location: Int) {
        GLES31.glActiveTexture(GLES31.GL_TEXTURE0 + unit)
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, texture)
        GLES31.glUniform1i(location, unit)
    }

    // --- Orbit lifecycle --------------------------------------------------------------

    private fun needsRebuild(b: OrbitBundle?, aspect: Double): Boolean {
        if (b == null) return true
        if (state.maxIter > b.orbit.iterBuilt) return true
        if (!state.canReuse(b.orbit, aspect)) return true
        if (b.gpu.scaleExp != state.deltaScaleExponent()) return true
        // A table built for a larger bound stays valid; only growing past it forces work.
        return BlaTable.maxCFor(state.spanY, aspect) > b.gpu.maxC
    }

    private fun ensureBundleAsync() {
        val aspect = surfaceW.toDouble() / surfaceH
        if (!needsRebuild(active, aspect)) return
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
                pending = buildBundle(cx, cy, span, iter, exp, maxC, aspect)
            } finally {
                building.set(false)
                onOrbitStateChanged?.invoke(false)
                requestRender?.invoke()
            }
        }
    }

    /**
     * Produces a bundle, reusing the existing orbit wherever possible.
     *
     * The orbit is the expensive part and it does not depend on zoom, so zooming out,
     * zooming in within the guard digits, and small pans all skip straight to the cheap
     * repack. Raising the detail slider extends rather than restarts.
     */
    private fun buildBundle(
        cx: java.math.BigDecimal,
        cy: java.math.BigDecimal,
        span: Double,
        iter: Int,
        exp: Int,
        maxC: Double,
        aspect: Double
    ): OrbitBundle {
        val probe = ViewState().also {
            it.centerX = cx; it.centerY = cy; it.spanY = span; it.maxIter = iter
        }

        var orbit = if (buildingForExport) exportOrbit else workingOrbit
        if (orbit == null || !probe.canReuse(orbit, aspect)) {
            // Prefer a minibrot nucleus. Its orbit returns near zero every period,
            // which keeps BLA coefficients small and their radii large, so pixels take
            // longer jumps. findNucleus declines when the nucleus is out of view, in
            // which case the view centre is used as before.
            val nucleus = try {
                ReferenceOrbit.findNucleus(
                    cx, cy, iter, span, ReferenceOrbit.precisionFor(span)
                )
            } catch (e: Exception) {
                null
            }
            val rx = nucleus?.get(0) ?: cx
            val ry = nucleus?.get(1) ?: cy
            orbit = ReferenceOrbit.compute(rx, ry, iter, span)
        } else if (iter > orbit.iterBuilt) {
            orbit.extendTo(iter)
        }
        if (buildingForExport) exportOrbit = orbit else workingOrbit = orbit

        val snap = orbit.snapshot()
        val off = probe.offsetFrom(snap)
        val effectiveMaxC = max(maxC, BlaTable.maxCFor(span, aspect, hypot(off[0], off[1])))
        return OrbitBundle(snap, OrbitGpuData.build(snap, exp, effectiveMaxC))
    }

    /** Synchronous variant for export, where frames must not be skipped. */
    private fun bundleForExport(s: ViewState, aspect: Double, cached: OrbitBundle?): OrbitBundle {
        if (cached != null &&
            s.maxIter <= cached.orbit.iterBuilt &&
            s.canReuse(cached.orbit, aspect) &&
            cached.gpu.scaleExp == s.deltaScaleExponent() &&
            BlaTable.maxCFor(s.spanY, aspect) <= cached.gpu.maxC
        ) return cached

        buildingForExport = true
        val built = try {
            buildBundle(
                s.centerX, s.centerY, s.spanY, s.maxIter,
                s.deltaScaleExponent(), BlaTable.maxCFor(s.spanY, aspect), aspect
            )
        } finally {
            buildingForExport = false
        }
        upload(built.gpu)
        return built
    }

    // --- Uploads ----------------------------------------------------------------------

    private fun upload(gpu: OrbitGpuData) {
        uploadOrbit(gpu)
        uploadBla(gpu.bla)
    }

    private fun uploadOrbit(gpu: OrbitGpuData) {
        val points = min(gpu.points, MAX_POINTS)
        val rows = rowsFor(points)
        val buf = floatBuffer(rows * TEX_WIDTH * 4)

        buf.put(gpu.packed, 0, points * 4)
        // The shader never reads past uOrbitLen, but leaving the row tail uninitialised
        // invites driver-dependent surprises.
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

    private fun uploadPaletteIfDirty() {
        if (!paletteDirty) return
        paletteDirty = false

        val ramp = Palettes.buildRamp(palette)
        val buf = ByteBuffer.allocateDirect(ramp.size).order(ByteOrder.nativeOrder())
        buf.put(ramp)
        buf.position(0)

        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, paletteTexture)
        GLES31.glPixelStorei(GLES31.GL_UNPACK_ALIGNMENT, 1)
        GLES31.glTexImage2D(
            GLES31.GL_TEXTURE_2D, 0, GLES31.GL_RGBA,
            Palettes.TEXTURE_WIDTH, 1, 0,
            GLES31.GL_RGBA, GLES31.GL_UNSIGNED_BYTE, buf
        )
    }

    private fun rowsFor(entries: Int): Int =
        ceil(entries / TEX_WIDTH.toDouble()).toInt().coerceAtLeast(1)

    private fun floatBuffer(floats: Int): FloatBuffer =
        ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun invalidateOrbit() {
        active = null
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
        cached: OrbitBundle?
    ): OrbitBundle? {
        ensureFbo(w, h)
        uploadPaletteIfDirty()
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, fbo)
        GLES31.glViewport(0, 0, w, h)
        GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT)

        // Exports are the largest frames the app produces, so the tile pass matters
        // most here. It runs against the export's own dimensions, not the screen's.
        val tiles = w >= TILE_MIN_WIDTH
        var used: OrbitBundle? = null
        if (s.needsPerturbation()) used = bundleForExport(s, w.toDouble() / h, cached)

        if (tiles) {
            ensureTileTargetFor(w, h)
            GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, tileFbo)
            GLES31.glViewport(0, 0, (w + TILE_SIZE - 1) / TILE_SIZE, (h + TILE_SIZE - 1) / TILE_SIZE)
            GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT)
            if (used != null) drawPerturbation(s, used, w, h, false, tilePass = true)
            else drawDirect(s, w, h, false, tilePass = true)
        }

        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, fbo)
        GLES31.glViewport(0, 0, w, h)

        if (used != null) drawPerturbation(s, used, w, h, tiles)
        else drawDirect(s, w, h, tiles)

        out.position(0)
        GLES31.glReadPixels(0, 0, w, h, GLES31.GL_RGBA, GLES31.GL_UNSIGNED_BYTE, out)
        out.position(0)

        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)
        GLES31.glViewport(0, 0, surfaceW, surfaceH)

        // The live view's uploads were clobbered by the export's.
        invalidateOrbit()
        return used
    }


    // --- Exponential-map strip --------------------------------------------------------

    /**
     * Highest absolute row rendered so far, plus the state the strip belongs to.
     * Rows are written once and never revisited, which is the entire point.
     */
    // Inclusive range of strip rows currently valid in the ring buffer. Zoom-out
    // extends upward, zoom-in extends downward, so both ends move.
    private var stripBuiltLo = 0
    private var stripBuiltHi = -1
    private var stripStarted = false

    fun stripBegin(geom: StripGeometry) {
        ensureStrip(geom.width, geom.ringHeight)
        stripBuiltLo = 0
        stripBuiltHi = -1
        stripStarted = true
        exportOrbit = null
        // Start conservative; the first measured chunk corrects it immediately.
        stripRowBudget = 64
        stripSegments = 1
        rowsSinceFinish = 0
        finishStart = System.nanoTime()
        msPerRow = 0.0
    }

    private fun ensureStrip(w: Int, ring: Int) {
        if (stripW == w && stripRing == ring && stripFbo != 0) return

        val ids = IntArray(1)
        if (stripFbo != 0) { ids[0] = stripFbo; GLES31.glDeleteFramebuffers(1, ids, 0); stripFbo = 0 }
        if (stripTex != 0) { ids[0] = stripTex; GLES31.glDeleteTextures(1, ids, 0); stripTex = 0 }

        GLES31.glGenTextures(1, ids, 0)
        stripTex = ids[0]
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, stripTex)
        GLES31.glTexImage2D(
            GLES31.GL_TEXTURE_2D, 0, GLES31.GL_RGBA8, w, ring, 0,
            GLES31.GL_RGBA, GLES31.GL_UNSIGNED_BYTE, null
        )
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_LINEAR)
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_LINEAR)
        // Angle wraps at 2*pi, and the vertical axis is a ring buffer. Repeat on both
        // makes the wrap free rather than something the shader has to handle.
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_S, GLES31.GL_REPEAT)
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_T, GLES31.GL_REPEAT)

        GLES31.glGenFramebuffers(1, ids, 0)
        stripFbo = ids[0]
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, stripFbo)
        GLES31.glFramebufferTexture2D(
            GLES31.GL_FRAMEBUFFER, GLES31.GL_COLOR_ATTACHMENT0,
            GLES31.GL_TEXTURE_2D, stripTex, 0
        )
        val status = GLES31.glCheckFramebufferStatus(GLES31.GL_FRAMEBUFFER)
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)
        if (status != GLES31.GL_FRAMEBUFFER_COMPLETE) {
            throw RuntimeException("Strip target unavailable at ${w}x$ring")
        }

        stripW = w
        stripRing = ring
    }

    /** Extends the strip so every row up to and including lastRow exists. */
    var onStripProgress: ((rowsDone: Int, rowsTarget: Int) -> Unit)? = null

    /** Per-chunk numbers from the most recent strip build, surfaced by the debug dump. */
    @Volatile var lastDiag: String = ""
        private set

    /** When set, each strip chunk reads back one row and counts lit pixels. */
    @Volatile var debugSampling = false

    /** Rows per strip draw, adapted from measured chunk time. */
    private var stripRowBudget = 128
    /** Angular pieces each chunk row is drawn in; raised when one row is still too slow. */
    private var stripSegments = 1
    private var lastPerDrawMs = 0.0
    /** Rows drawn since the pipeline was last drained, and when that was. */
    private var rowsSinceFinish = 0
    private var finishStart = System.nanoTime()
    /** Running estimate of GPU cost per strip row; drives every budget below. */
    private var msPerRow = 0.0
    private var chunksSinceMeasure = 0
    private var lastChunkMs = 0.0

    /**
     * Ensures every row in [lo, hi] is present, rendering only what is missing.
     *
     * Zoom-out asks for ranges that grow upward and zoom-in for ranges that grow
     * downward, so this extends at either end. Rows outside the ring buffer's span are
     * dropped from the valid range as they are overwritten.
     */
    fun stripEnsureRange(
        s: ViewState,
        geom: StripGeometry,
        lo: Int,
        hi: Int,
        cached: OrbitBundle?
    ): OrbitBundle? {
        if (!stripStarted) stripBegin(geom)
        var bundle = cached

        if (stripBuiltHi < stripBuiltLo) {
            bundle = renderRows(s, geom, lo, hi, bundle)
            stripBuiltLo = lo
            stripBuiltHi = hi
            return bundle
        }
        if (hi > stripBuiltHi) {
            bundle = renderRows(s, geom, stripBuiltHi + 1, hi, bundle)
            stripBuiltHi = hi
            stripBuiltLo = max(stripBuiltLo, hi - stripRing + 1)
        }
        if (lo < stripBuiltLo) {
            bundle = renderRows(s, geom, lo, stripBuiltLo - 1, bundle)
            stripBuiltLo = lo
            stripBuiltHi = min(stripBuiltHi, lo + stripRing - 1)
        }

        // The unwarp samples [lo, hi] unconditionally. If those rows are not all valid
        // it silently resamples stale data, and every later frame looks identical —
        // the picture freezes while frames keep being written. Fail loudly instead.
        if (lo < stripBuiltLo || hi > stripBuiltHi) {
            throw IllegalStateException(
                "Strip window [$lo, $hi] outside valid rows " +
                    "[$stripBuiltLo, $stripBuiltHi] (ring $stripRing, " +
                    "needs ${hi - lo + 1} rows)"
            )
        }
        return bundle
    }

    private fun renderRows(
        s: ViewState,
        geom: StripGeometry,
        firstRow: Int,
        lastRow: Int,
        cached: OrbitBundle?
    ): OrbitBundle? {
        var bundle = cached
        if (lastRow < firstRow) return bundle

        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, stripFbo)
        uploadPaletteIfDirty()

        // glGetError reports the oldest unread error, which may date from launch.
        // Drain it so anything caught below is genuinely from this loop.
        while (GLES31.glGetError() != GLES31.GL_NO_ERROR) { /* discard */ }

        var row = firstRow
        while (row <= lastRow) {
            // A chunk stops at the ring wrap, and at the boundary between radii that
            // still need perturbation and radii where plain float32 is fine.
            val dest = row % stripRing
            val untilWrap = stripRing - dest
            val radius = Math.exp(geom.logR0 + row * geom.step)
            val deep = radius < ViewState.DIRECT_LIMIT

            var count = min(untilWrap, lastRow - row + 1)
            if (deep) {
                val crossing = ((ln(ViewState.DIRECT_LIMIT) - geom.logR0) / geom.step).toInt() - row
                if (crossing in 1 until count) count = crossing
            }
            // Cap the radius range a single chunk may cover. The delta scale, the BLA
            // radii and maxC are all derived from the radius at the chunk's start, and
            // an unbounded chunk applies them to radii hundreds of thousands of times
            // larger, where they are meaningless. Four-to-one matches the tolerance
            // used for reusing a bundle anywhere else.
            val maxRows = max(1, (MAX_CHUNK_LOG_RANGE / geom.step).toInt())
            if (count > maxRows) count = maxRows

            // Bound the work in one draw call.
            //
            // Mobile GPU drivers kill draws that run too long, and the kill is silent:
            // the target stays black, glGetError reports nothing, and the call returns
            // immediately. This was the cause of blank exponential-map exports, and it
            // only showed at certain depths because that is where pixels get expensive
            // enough for a chunk to cross the limit.
            //
            // Rows per chunk are adapted from how long the previous chunk actually
            // took, aiming well under any plausible watchdog. A fixed cap cannot work:
            // chunk cost varies by more than 7x across a single strip, so any constant
            // is either unsafe deep or needlessly slow shallow.
            val budgetRows = (stripRowBudget).coerceIn(1, MAX_CHUNK_ROWS)
            if (count > budgetRows) count = budgetRows

            // A chunk must always advance, whatever the arithmetic above produced.
            if (count < 1) count = 1

            val probe = s.snapshot()
            probe.spanY = max(radius * 2.0, s.minSpan())
            if (deep) bundle = bundleForExport(probe, 1.0, bundle)

            val u = if (deep) perturbStrip else directStrip
            val program = if (deep) perturbStripProgram else directStripProgram
            val scale = if (deep) bundle!!.gpu.scale else 1.0

            val chunkStart = System.nanoTime()
            GLES31.glViewport(0, dest, stripW, count)
            GLES31.glUseProgram(program)
            GLES31.glBindVertexArray(vao)

            GLES31.glUniform2f(u["uResolution"]!!, stripW.toFloat(), stripRing.toFloat())
            GLES31.glUniform1i(u["uMaxIter"]!!, if (deep) min(s.maxIter, bundle!!.orbit.iterBuilt) else s.maxIter)
            applyColorUniforms(u)
            applyTileUniforms(u, false)

            // Radius is built multiplicatively from a per-chunk base: at depth the
            // absolute log radius is around -130, where a float cannot separate rows.
            val rBaseD = Math.exp(geom.logR0 + row * geom.step + ln(scale))
            val rBase = rBaseD.toFloat()
            if (!rBase.isFinite() || rBase == 0f || rBase < 1e-36f) {
                // A zero or denormal base makes every strip pixel's offset zero, so
                // every pixel becomes the reference point and renders as its colour —
                // a solid strip with no failed draw to catch. Name it instead.
                throw RuntimeException(
                    "Strip radius out of float range at row $row: base=$rBaseD " +
                        "(logR0=${geom.logR0}, step=${geom.step}, " +
                        "scaleExp=${if (deep) bundle!!.gpu.scaleExp else 0}, " +
                        "probeSpan=${probe.spanY}, radius=$radius)"
                )
            }
            if (row == firstRow) lastDiag = ""
            run {
                lastDiag += String.format(
                    java.util.Locale.US,
                    "row %d (+%d): r=%.2e exp=%d len=%d iter=%d bla=%d %s",
                    row, count, radius,
                    if (deep) bundle!!.gpu.scaleExp else 0,
                    uploadedLen,
                    if (deep) min(s.maxIter, bundle!!.orbit.iterBuilt) else s.maxIter,
                    uploadedBlaLevels, if (deep) "P" else "D"
                )
            }
            GLES31.glUniform1f(u["uStripRBase"]!!, rBase)
            GLES31.glUniform1f(u["uStripRowBase"]!!, dest + 0.5f)
            GLES31.glUniform1f(u["uStripStep"]!!, geom.step.toFloat())
            GLES31.glUniform1f(u["uStripWidth"]!!, stripW.toFloat())

            if (deep) {
                val b = bundle!!
                val offset = probe.offsetFrom(b.orbit)
                GLES31.glUniform2f(u["uDeltaCenter"]!!,
                    (offset[0] * scale).toFloat(), (offset[1] * scale).toFloat())
                GLES31.glUniform1f(u["uPixelSpan"]!!, 0f)
                GLES31.glUniform1f(u["uInvScale"]!!, (1.0 / scale).toFloat())
                GLES31.glUniform1f(u["uBailoutScaled"]!!, (BAILOUT * scale).toFloat())
                GLES31.glUniform1i(u["uWidthMask"]!!, TEX_WIDTH - 1)
                GLES31.glUniform1i(u["uWidthShift"]!!, TEX_SHIFT)
                GLES31.glUniform1i(u["uOrbitLen"]!!, uploadedLen)
                GLES31.glUniform1i(u["uBlaLevels"]!!, uploadedBlaLevels)
                if (uploadedBlaLevels > 0) {
                    GLES31.glUniform1iv(u["uBlaOffset[0]"]!!, uploadedBlaLevels, uploadedBlaOffset, 0)
                    GLES31.glUniform1iv(u["uBlaCount[0]"]!!, uploadedBlaLevels, uploadedBlaCount, 0)
                }
                bindTexture(0, orbitTexture, u["uOrbit"]!!)
                bindTexture(1, blaAbTexture, u["uBlaAB"]!!)
                bindTexture(2, blaRTexture, u["uBlaR"]!!)
            } else {
                GLES31.glUniform2f(u["uCenter"]!!, s.centerX.toFloat(), s.centerY.toFloat())
                GLES31.glUniform1f(u["uSpanY"]!!, 1f)
            }

            // One draw per angular segment rather than one for the whole row.
            //
            // The watchdog limits how long a single draw may run, not how much total
            // work a chunk does, so once the row budget bottoms out at 1 the only way
            // left to shorten a draw is to narrow it. gl_FragCoord.x stays in window
            // coordinates under a narrowed viewport, so the angle the shader derives
            // from it is unchanged and the segments tile the row exactly.
            val segs = stripSegments.coerceIn(1, MAX_SEGMENTS)
            if (segs == 1) {
                GLES31.glDrawArrays(GLES31.GL_TRIANGLES, 0, 3)
            } else {
                // When a single row costs more than a whole submission may, the row is
                // the submission and splitting it alone does not help — the pipeline
                // has to be drained between segments as well.
                val drainPerSegment = msPerRow > SUBMIT_TARGET_MS
                var x = 0
                for (seg in 0 until segs) {
                    // Last segment takes the remainder, so widths always sum to stripW
                    // even when it does not divide evenly.
                    val segW = if (seg == segs - 1) stripW - x else stripW / segs
                    if (segW <= 0) break
                    GLES31.glViewport(x, dest, segW, count)
                    GLES31.glDrawArrays(GLES31.GL_TRIANGLES, 0, 3)
                    if (drainPerSegment) GLES31.glFinish()
                    x += segW
                }
            }
            GLES31.glBindVertexArray(0)

            // A failed draw here is otherwise invisible: the rows just stay black and
            // the export finishes early, which is exactly the reported symptom.
            val err = GLES31.glGetError()
            if (err != GLES31.GL_NO_ERROR) {
                throw RuntimeException(
                    "Strip draw failed (GL error 0x${err.toString(16)}) at row $row, " +
                        "${if (deep) "perturbation" else "direct"} path, chunk $count rows, " +
                        "scale 2^${if (deep) bundle!!.gpu.scaleExp else 0}"
                )
            }
            // Timing needs glFinish, which drains the pipeline — too costly to do on
            // every chunk when a video renders one per frame. Chunk cost changes
            // slowly, so sampling occasionally keeps the budget honest for a fraction
            // of the stalls. Debug mode measures every chunk.
            // What the driver kills is a submission that runs too long, not a single
            // draw. Work queues up until something drains it, so measuring "per chunk"
            // while draining only every sixteenth chunk attributed sixteen chunks of
            // queued work to one chunk — inflating the number about 16x and, worse,
            // letting roughly 2s of work accumulate into one submission, which is
            // exactly the limit being hit.
            //
            // So the unit tracked is cost per row, and the pipeline is drained whenever
            // the estimated work queued behind it approaches the budget for one
            // submission.
            rowsSinceFinish += count
            val pendingMs = rowsSinceFinish * msPerRow
            if (debugSampling || pendingMs >= SUBMIT_TARGET_MS || msPerRow <= 0.0) {
                GLES31.glFinish()
                val elapsed = (System.nanoTime() - finishStart) / 1e6
                val measured = elapsed / rowsSinceFinish.coerceAtLeast(1)
                // Smoothed: cost per row varies between neighbouring rows, and reacting
                // to a single sample makes the budget oscillate.
                msPerRow = if (msPerRow <= 0.0) measured else msPerRow * 0.5 + measured * 0.5
                lastChunkMs = elapsed
                lastPerDrawMs = msPerRow
                rowsSinceFinish = 0
                finishStart = System.nanoTime()

                // Rows per chunk, then angular splitting only if one row alone is over
                // budget — which is the case this whole path exists for.
                stripRowBudget = if (msPerRow > 0.0) {
                    (CHUNK_TARGET_MS / msPerRow).toInt().coerceIn(1, MAX_CHUNK_ROWS)
                } else MAX_CHUNK_ROWS
                stripSegments = if (msPerRow > CHUNK_TARGET_MS) {
                    ceil(msPerRow / CHUNK_TARGET_MS).toInt().coerceIn(1, MAX_SEGMENTS)
                } else 1
            }
            val chunkMs = lastChunkMs

            if (debugSampling) {
                lastDiag += " %.0fms".format(chunkMs)
                // Read one row back and count non-black pixels. Tells us directly
                // whether this chunk rendered structure, interior, or nothing.
                val mid = dest + count / 2
                val rowBuf = ByteBuffer.allocateDirect(stripW * 4).order(ByteOrder.nativeOrder())
                GLES31.glReadPixels(0, mid, stripW, 1, GLES31.GL_RGBA, GLES31.GL_UNSIGNED_BYTE, rowBuf)
                var lit = 0
                for (i in 0 until stripW) {
                    val o = i * 4
                    if ((rowBuf.get(o).toInt() and 0xFF) > 8 ||
                        (rowBuf.get(o + 1).toInt() and 0xFF) > 8 ||
                        (rowBuf.get(o + 2).toInt() and 0xFF) > 8) lit++
                }
                lastDiag += " lit=$lit/$stripW\n"
            } else {
                lastDiag += "\n"
            }
            // Recorded before row advances, so the range refers to the rows this chunk
            // actually covered.
            stripRowsDrawn += count
            if (stripDrawnLo < 0 || row < stripDrawnLo) stripDrawnLo = row
            if (row + count - 1 > stripDrawnHi) stripDrawnHi = row + count - 1
            row += count
            // The first frame builds the whole window at once — thousands of rows
            // against a handful for every frame after it — so it needs its own
            // progress or it reads as a freeze.
            onStripProgress?.invoke(row - firstRow, lastRow - firstRow + 1)
        }

        // One sample of what the final chunk actually wrote, taken here while stripFbo
        // is still bound and in the same GL state the draw ran in. Deliberately not
        // routed through debugSampling: that forces a glFinish on every chunk and so
        // changes how the row budget adapts, which is exactly the behaviour under
        // suspicion. No glFinish, no effect on the budget.
        if (lastRow >= firstRow && stripW > 0) {
            val mid = ((stripDrawnHi + stripDrawnLo) / 2) % stripRing
            val probe = ByteBuffer.allocateDirect(stripW * 4).order(ByteOrder.nativeOrder())
            val pend = (stripW - 1) * 4
            probe.putInt(0, SENTINEL)
            probe.putInt(pend, SENTINEL)
            GLES31.glReadPixels(0, mid, stripW, 1, GLES31.GL_RGBA, GLES31.GL_UNSIGNED_BYTE, probe)
            if (probe.getInt(0) == SENTINEL && probe.getInt(pend) == SENTINEL) {
                lastChunkLit = READ_FAILED
                GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)
                return bundle
            }
            var lit = 0
            for (i in 0 until stripW) {
                val o = i * 4
                if ((probe.get(o).toInt() and 0xFF) > 8 ||
                    (probe.get(o + 1).toInt() and 0xFF) > 8 ||
                    (probe.get(o + 2).toInt() and 0xFF) > 8
                ) lit++
            }
            lastChunkLit = lit
        }

        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)
        return bundle
    }

    /** Lit pixels in the middle row of the last chunk drawn, measured inside renderRows. */
    @Volatile var lastChunkLit = -1
        private set

    /**
     * Rows drawn into the strip since the counter was reset, and the span of absolute
     * rows they covered.
     *
     * The range matters as much as the count. Zoom-out extends the window upward and
     * zoom-in extends it downward, so "the new rows" are not at a fixed end of the
     * window — sampling the range that was actually drawn works in either direction.
     */
    @Volatile var stripRowsDrawn = 0
        private set
    @Volatile var stripDrawnLo = -1
        private set
    @Volatile var stripDrawnHi = -1
        private set

    fun stripResetRowsDrawn() {
        stripRowsDrawn = 0
        stripDrawnLo = -1
        stripDrawnHi = -1
    }

    // Reused so a per-frame probe does not allocate a direct buffer per call. Direct
    // buffers are not reclaimed by an ordinary GC cycle, and a few hundred of them over
    // the course of a dump is enough to bring the process down.
    private var rowProbeBuf: ByteBuffer? = null

    /**
     * Counts non-black pixels in one absolute strip row.
     *
     * Answers what a dump of the whole strip only answers by eye: does this row hold a
     * rendered image? 0 means nothing was drawn, or every pixel in it is the interior
     * colour. A count equal to the strip width means every pixel is lit.
     */
    fun stripRowLit(absoluteRow: Int): Int {
        if (stripFbo == 0 || stripW == 0) return -1
        val texel = ((absoluteRow % stripRing) + stripRing) % stripRing
        // Written so the non-null result is produced by the expression itself. Relying
        // on a smart cast after reassigning a nullable var compiles under K2 but not
        // under the K1 compiler this project builds with.
        val cached = rowProbeBuf
        val buf: ByteBuffer =
            if (cached != null && cached.capacity() >= stripW * 4) {
                cached
            } else {
                ByteBuffer.allocateDirect(stripW * 4)
                    .order(ByteOrder.nativeOrder())
                    .also { rowProbeBuf = it }
            }
        // Same sentinel treatment as the frame readback. Without it a read that does
        // nothing leaves the reused buffer as it was and reports lit=0, which is
        // indistinguishable from a genuinely black row — and would be read as evidence
        // about the fractal when it is really evidence about the readback.
        val end = (stripW - 1) * 4
        buf.putInt(0, SENTINEL)
        buf.putInt(end, SENTINEL)
        buf.position(0)
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, stripFbo)
        GLES31.glReadPixels(0, texel, stripW, 1, GLES31.GL_RGBA, GLES31.GL_UNSIGNED_BYTE, buf)
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)
        if (buf.getInt(0) == SENTINEL && buf.getInt(end) == SENTINEL) return READ_FAILED
        var lit = 0
        for (i in 0 until stripW) {
            val o = i * 4
            if ((buf.get(o).toInt() and 0xFF) > 8 ||
                (buf.get(o + 1).toInt() and 0xFF) > 8 ||
                (buf.get(o + 2).toInt() and 0xFF) > 8
            ) lit++
        }
        return lit
    }

    /** The row range stripEnsureRange believes is built, for diagnostics. */
    val stripValidLo: Int get() = stripBuiltLo
    val stripValidHi: Int get() = stripBuiltHi

    /**
     * Samples lit counts at evenly spaced rows across [lo, hi].
     *
     * Run after a freeze is detected rather than every frame: the rows are still in the
     * ring, so where content stops can be found retrospectively for the cost of one
     * burst of readbacks instead of one per frame for the whole export.
     */
    fun stripProfile(lo: Int, hi: Int, samples: Int = 12): String {
        if (hi < lo) return "(empty range)"
        val sb = StringBuilder()
        for (k in 0 until samples) {
            val row = lo + (hi - lo) * k / (samples - 1).coerceAtLeast(1)
            val v = stripRowLit(row)
            sb.append(
                if (v == READ_FAILED) "  row $row READBACK DID NOTHING\n"
                else "  row $row lit=$v\n"
            )
        }
        return sb.toString()
    }

    /** Frames whose readback reported a GL error or left the buffer untouched. */
    @Volatile var readbackFailures = 0
        private set
    @Volatile var lastReadbackError = 0
        private set

    /** Resamples the strip into a normal frame and reads it back. */
    fun stripUnwarp(geom: StripGeometry, spanY: Double, w: Int, h: Int, out: ByteBuffer) {
        stripUnwarpDraw(geom, spanY, w, h)
        while (GLES31.glGetError() != GLES31.GL_NO_ERROR) { /* discard older errors */ }

        // A sentinel in the first and last pixel. If the read writes nothing the caller
        // keeps whatever it held, which with two ping-pong buffers is the frame from two
        // back — indistinguishable from a correctly rendered repeat unless checked here.
        val last = w * h * 4 - 4
        out.putInt(0, SENTINEL)
        out.putInt(last, SENTINEL)

        out.position(0)
        GLES31.glReadPixels(0, 0, w, h, GLES31.GL_RGBA, GLES31.GL_UNSIGNED_BYTE, out)
        out.position(0)

        val err = GLES31.glGetError()
        if (err != GLES31.GL_NO_ERROR ||
            (out.getInt(0) == SENTINEL && out.getInt(last) == SENTINEL)
        ) {
            readbackFailures++
            lastReadbackError = err
            if (readbackHealth.isEmpty()) readbackHealth = probeGlHealth(err)
        }

        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)
        GLES31.glViewport(0, 0, surfaceW, surfaceH)
    }

    /** GL state at the first failed readback, to tell a dead context from a dead read. */
    @Volatile var readbackHealth: String = ""
        private set

    /**
     * Asks GL some questions that do not involve reading pixels.
     *
     * If queries still answer and the framebuffer is still complete, the context is
     * alive and only the read is failing. If they do not, the context itself is gone —
     * which on GLES 3.1 without the robustness extension is not required to be reported
     * as an error, so it can only be found by asking.
     */
    private fun probeGlHealth(err: Int): String {
        val status = GLES31.glCheckFramebufferStatus(GLES31.GL_FRAMEBUFFER)
        val v = IntArray(1)
        GLES31.glGetIntegerv(GLES31.GL_MAX_TEXTURE_SIZE, v, 0)
        val queryWorks = v[0] > 0
        val afterQuery = GLES31.glGetError()

        // A one-pixel read. If the big read failed but this succeeds, the problem scales
        // with the amount read rather than being a broken context.
        val tiny = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        tiny.putInt(0, SENTINEL)
        GLES31.glReadPixels(0, 0, 1, 1, GLES31.GL_RGBA, GLES31.GL_UNSIGNED_BYTE, tiny)
        val tinyWorked = tiny.getInt(0) != SENTINEL

        return "GL health at first failed readback:\n" +
            "  framebuffer status 0x${status.toString(16)}" +
            (if (status == GLES31.GL_FRAMEBUFFER_COMPLETE) " (complete)" else " (NOT complete)") +
            "\n  queries responding: $queryWorks (maxTex=${v[0]}, err 0x${afterQuery.toString(16)})" +
            "\n  1x1 readback worked: $tinyWorked" +
            "\n  read error 0x${err.toString(16)}" +
            "\n  row budget $stripRowBudget, segments $stripSegments" +
            "\n  last drain %.0fms, cost per row %.1fms\n".format(lastChunkMs, msPerRow)
    }

    /** Draws the unwarped frame and leaves it bound, for an asynchronous read. */
    fun stripUnwarpDraw(geom: StripGeometry, spanY: Double, w: Int, h: Int) {
        ensureFbo(w, h)
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, fbo)
        GLES31.glViewport(0, 0, w, h)

        GLES31.glUseProgram(unwarpProgram)
        GLES31.glBindVertexArray(vao)
        GLES31.glUniform2f(unwarp["uResolution"]!!, w.toFloat(), h.toFloat())
        GLES31.glUniform1f(unwarp["uRingHeight"]!!, stripRing.toFloat())
        // Row for a one-pixel radius, so the shader only adds log(radius in pixels).
        // Row N occupies texel centre N+0.5, so the sample has to be nudged or every
        // row is read half a texel low.
        GLES31.glUniform1f(unwarp["uRowBase"]!!, (geom.rowFor(ln(spanY / h)) + 0.5).toFloat())
        GLES31.glUniform1f(unwarp["uStepInv"]!!, (1.0 / geom.step).toFloat())
        GLES31.glUniform1f(unwarp["uMinRadius"]!!, StripGeometry.MIN_RADIUS_PX.toFloat())
        bindTexture(6, stripTex, unwarp["uStrip"]!!)

        GLES31.glDrawArrays(GLES31.GL_TRIANGLES, 0, 3)
        GLES31.glBindVertexArray(0)
    }

    // --- Asynchronous readback -------------------------------------------------------

    private var pbos = IntArray(0)
    private var pboBytes = 0
    private var pboIndex = 0
    private var pboPrimed = false

    /**
     * Two pixel buffer objects, alternating.
     *
     * A plain glReadPixels blocks until the GPU has finished and 8 MB has crossed to
     * the CPU. Reading into a PBO returns immediately and the transfer proceeds in the
     * background, so the next frame renders while the previous one is still arriving.
     * Mapping the other buffer then costs almost nothing, because its transfer had a
     * whole frame to finish.
     */
    fun readbackEnsure(w: Int, h: Int): Boolean {
        val bytes = w * h * 4
        if (pboBytes == bytes && pbos.size == 2) return true

        if (pbos.isNotEmpty()) GLES31.glDeleteBuffers(pbos.size, pbos, 0)
        pbos = IntArray(2)
        GLES31.glGenBuffers(2, pbos, 0)
        for (id in pbos) {
            GLES31.glBindBuffer(GLES31.GL_PIXEL_PACK_BUFFER, id)
            GLES31.glBufferData(GLES31.GL_PIXEL_PACK_BUFFER, bytes, null, GLES31.GL_STREAM_READ)
        }
        GLES31.glBindBuffer(GLES31.GL_PIXEL_PACK_BUFFER, 0)

        // Buffer mapping is driver-dependent; if setup fails, the caller falls back to
        // the synchronous path rather than the export dying.
        if (GLES31.glGetError() != GLES31.GL_NO_ERROR) {
            pbos = IntArray(0)
            pboBytes = 0
            return false
        }
        pboBytes = bytes
        pboIndex = 0
        pboPrimed = false
        return true
    }

    fun readbackIssue(w: Int, h: Int) {
        GLES31.glBindBuffer(GLES31.GL_PIXEL_PACK_BUFFER, pbos[pboIndex])
        GLES31.glReadPixels(0, 0, w, h, GLES31.GL_RGBA, GLES31.GL_UNSIGNED_BYTE, 0)
        GLES31.glBindBuffer(GLES31.GL_PIXEL_PACK_BUFFER, 0)
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)
        GLES31.glViewport(0, 0, surfaceW, surfaceH)
        pboIndex = 1 - pboIndex
        pboPrimed = true
    }

    /** Maps the frame issued before the current one, or null if none is pending. */
    fun readbackMap(): ByteBuffer? {
        if (!pboPrimed || pbos.isEmpty()) return null
        GLES31.glBindBuffer(GLES31.GL_PIXEL_PACK_BUFFER, pbos[pboIndex])
        return GLES31.glMapBufferRange(
            GLES31.GL_PIXEL_PACK_BUFFER, 0, pboBytes, GLES31.GL_MAP_READ_BIT
        ) as? ByteBuffer
    }

    fun readbackUnmap() {
        GLES31.glUnmapBuffer(GLES31.GL_PIXEL_PACK_BUFFER)
        GLES31.glBindBuffer(GLES31.GL_PIXEL_PACK_BUFFER, 0)
    }

    fun readbackRelease() {
        if (pbos.isNotEmpty()) GLES31.glDeleteBuffers(pbos.size, pbos, 0)
        pbos = IntArray(0)
        pboBytes = 0
        pboPrimed = false
    }

    /**
     * Reads the strip texture back as RGBA, for diagnosing the strip separately from
     * the unwarp. If the strip has content and frames are still blank, the fault is in
     * the resampling; if the strip is blank too, it is in the strip render.
     */
    fun stripDump(out: ByteBuffer, dumpW: Int, dumpH: Int) {
        // Downscale on the GPU before reading back. The strip is 4096x8192, so a
        // full-size readback would need a 134 MB buffer plus an IntArray and a Bitmap
        // of the same size again — enough to kill the process outright.
        ensureFbo(dumpW, dumpH)
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, fbo)
        GLES31.glViewport(0, 0, dumpW, dumpH)

        GLES31.glUseProgram(blitProgram)
        GLES31.glBindVertexArray(vao)
        GLES31.glUniform2f(blit["uResolution"]!!, dumpW.toFloat(), dumpH.toFloat())
        GLES31.glUniform2f(blit["uValidFrac"]!!, 1f, 1f)
        GLES31.glUniform2f(blit["uShift"]!!, 0f, 0f)
        GLES31.glUniform1f(blit["uZoom"]!!, 1f)
        GLES31.glUniform3f(blit["uBackground"]!!, 1f, 0f, 1f)  // magenta marks untouched area
        bindTexture(4, stripTex, blit["uScene"]!!)
        GLES31.glDrawArrays(GLES31.GL_TRIANGLES, 0, 3)
        GLES31.glBindVertexArray(0)

        out.position(0)
        GLES31.glReadPixels(0, 0, dumpW, dumpH, GLES31.GL_RGBA, GLES31.GL_UNSIGNED_BYTE, out)
        out.position(0)

        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)
        GLES31.glViewport(0, 0, surfaceW, surfaceH)
    }

    fun stripEnd() {
        stripStarted = false
        invalidateOrbit()
    }

    fun releaseExportResources() {
        readbackRelease()
        releaseFbo()
        // The tile target was resized for the export; put it back for the screen.
        ensureTileTarget()
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
        /**
         * Improbable-as-real pixel value used to tell "the read wrote nothing" apart
         * from "the read wrote this". Fully opaque would be 0xFF alpha; this is not.
         */
        /**
         * Most angular pieces a row may be split into. At 4096 columns this bottoms out
         * at 64 pixels per draw, which is far below anything a watchdog objects to.
         */
        private const val MAX_SEGMENTS = 64

        private const val SENTINEL = 0x5A3C7E01

        /** Returned by the row probes when the read left the buffer untouched. */
        const val READ_FAILED = -2

        const val TEX_WIDTH = 1024
        const val TEX_SHIFT = 10
        const val MAX_POINTS = 1024 * 128
        const val MAX_BLA_ENTRIES = 1024 * 256
        const val BAILOUT = 8.0

        /** How many extra coarse passes precede the final one. */
        const val COARSE_STEPS = 2

        private val SHARED_UNIFORMS = arrayOf(
            "uResolution", "uMaxIter", "uPalette", "uCycle", "uOffset", "uInterior",
            "uTiles", "uTileSize", "uUseTiles",
            "uStripRBase", "uStripRowBase", "uStripStep", "uStripWidth"
        )
        private val DIRECT_UNIFORMS = SHARED_UNIFORMS + arrayOf("uCenter", "uSpanY")
        private val PERTURB_UNIFORMS = SHARED_UNIFORMS + arrayOf(
            "uOrbit", "uBlaAB", "uBlaR", "uWidthMask", "uWidthShift", "uOrbitLen",
            "uBlaLevels", "uBlaOffset[0]", "uBlaCount[0]", "uDeltaCenter", "uPixelSpan",
            "uInvScale", "uBailoutScaled"
        )

        /**
         * Tile edge in render pixels. At 32 the border is 124 of 1024 pixels, so a
         * solid tile costs about an eighth of rendering it. Smaller tiles classify
         * more finely but spend a larger fraction of themselves on the perimeter.
         */
        const val TILE_SIZE = 32

        /** Ceiling on rows per strip draw, whatever the timing suggests. */
        private const val MAX_CHUNK_ROWS = 512

        /** Target milliseconds per strip draw. Well under typical watchdog limits. */
        private const val CHUNK_TARGET_MS = 150.0

        /**
         * Most GPU work allowed to queue behind one drain, in milliseconds.
         *
         * The driver kills a submission that runs beyond roughly two seconds, and it
         * counts everything queued, not one draw. Well under that leaves room for the
         * estimate to be wrong by several times without tripping it.
         */
        private const val SUBMIT_TARGET_MS = 400.0

        /** Chunks between timing samples outside debug mode. */
        private const val MEASURE_EVERY = 16

        /** Largest log-radius range one strip chunk may span. ln(4). */
        private const val MAX_CHUNK_LOG_RANGE = 1.3862943611198906

        /** Below this width the tile pass costs more than it saves. */
        const val TILE_MIN_WIDTH = 512

        const val QUALITY_FULL = 0
        const val QUALITY_ADAPTIVE = 1
        const val QUALITY_FAST = 2

        // Roughly 30 fps and 60 fps. Backing off above one and recovering below the
        // other leaves a gap so the level does not oscillate every frame.
        private const val SLOW_FRAME_MS = 33.0
        private const val FAST_FRAME_MS = 15.0
    }
}
