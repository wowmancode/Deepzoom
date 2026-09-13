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
            orbit = ReferenceOrbit.compute(cx, cy, iter, span)
        } else if (iter > orbit.iterBuilt) {
            orbit.extendTo(iter)
        }
        if (buildingForExport) exportOrbit = orbit else workingOrbit = orbit

        val snap = orbit.snapshot()
        return OrbitBundle(snap, OrbitGpuData.build(snap, exp, maxC))
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
    private var stripRowsDone = 0
    private var stripStarted = false

    fun stripBegin(geom: StripGeometry) {
        ensureStrip(geom.width, geom.ringHeight)
        stripRowsDone = 0
        stripStarted = true
        exportOrbit = null
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

    fun stripExtendTo(s: ViewState, geom: StripGeometry, lastRow: Int, cached: OrbitBundle?): OrbitBundle? {
        if (!stripStarted) stripBegin(geom)
        var bundle = cached
        if (lastRow < stripRowsDone) return bundle

        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, stripFbo)
        uploadPaletteIfDirty()

        var row = stripRowsDone
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
            // A chunk must always advance. If any of the arithmetic above ever yields
            // zero or less, the loop would spin forever rather than fail.
            if (count < 1) count = 1

            val probe = s.snapshot()
            probe.spanY = max(radius * 2.0, s.minSpan())
            if (deep) bundle = bundleForExport(probe, 1.0, bundle)

            val u = if (deep) perturbStrip else directStrip
            val program = if (deep) perturbStripProgram else directStripProgram
            val scale = if (deep) bundle!!.gpu.scale else 1.0

            GLES31.glViewport(0, dest, stripW, count)
            GLES31.glUseProgram(program)
            GLES31.glBindVertexArray(vao)

            GLES31.glUniform2f(u["uResolution"]!!, stripW.toFloat(), stripRing.toFloat())
            GLES31.glUniform1i(u["uMaxIter"]!!, if (deep) min(s.maxIter, bundle!!.orbit.iterBuilt) else s.maxIter)
            applyColorUniforms(u)
            applyTileUniforms(u, false)

            // Radius is built multiplicatively from a per-chunk base: at depth the
            // absolute log radius is around -130, where a float cannot separate rows.
            GLES31.glUniform1f(u["uStripRBase"]!!, Math.exp(geom.logR0 + row * geom.step + ln(scale)).toFloat())
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

            GLES31.glDrawArrays(GLES31.GL_TRIANGLES, 0, 3)
            GLES31.glBindVertexArray(0)
            row += count
            // The first frame builds the whole window at once — thousands of rows
            // against a handful for every frame after it — so it needs its own
            // progress or it reads as a freeze.
            onStripProgress?.invoke(row, lastRow + 1)
        }

        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)
        stripRowsDone = lastRow + 1
        return bundle
    }

    /** Resamples the strip into a normal frame and reads it back. */
    fun stripUnwarp(geom: StripGeometry, spanY: Double, w: Int, h: Int, out: ByteBuffer) {
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

        out.position(0)
        GLES31.glReadPixels(0, 0, w, h, GLES31.GL_RGBA, GLES31.GL_UNSIGNED_BYTE, out)
        out.position(0)

        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)
        GLES31.glViewport(0, 0, surfaceW, surfaceH)
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
