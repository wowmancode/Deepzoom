// SPDX-License-Identifier: AGPL-3.0-or-later
package com.fractal.deepzoom

import android.content.Context
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Runs export work on the GL thread.
 *
 * Everything here touches GL state, so it all goes through queueEvent. That does
 * block interactive rendering for the duration — which is why callers show progress
 * rather than pretending the app is still responsive.
 */
class ExportManager(private val view: MandelbrotView) {

    data class VideoSettings(
        val width: Int,
        val height: Int,
        val fps: Int,
        val zoomPerFrame: Double,
        val bitsPerPixel: Double,
        val exponentialMap: Boolean = true,
        /** True renders the zoom inward, ending at the current view. */
        val zoomIn: Boolean = false,
        val dumpStrip: Boolean = false,
        /** Frames held on the destination at the end. Short on purpose. */
        val holdFrames: Int = 4
    )

    interface Progress {
        fun onProgress(frame: Int, total: Int)
        /** Strip rows built, for the first frame where that dominates. */
        fun onStripBuild(rowsDone: Int, rowsTarget: Int) {}

        /**
         * A short breakdown of the frames just rendered, issued periodically.
         *
         * The end-of-export summary is no use while an export is still running, and an
         * export whose cost piles up at one end can leave hours between the slowdown
         * and any report of it. This describes the recent window only, so it tracks
         * what the export is doing now rather than its average.
         */
        fun onPhaseSummary(line: String) {}
    }

    fun savePng(
        context: Context,
        width: Int,
        height: Int,
        onDone: (Uri?, String?) -> Unit
    ) {
        val snapshot = view.state.snapshot()
        view.queueEvent {
            var uri: Uri? = null
            var error: String? = null
            try {
                val buf = allocate(width, height)
                view.renderer.renderOffscreen(snapshot, width, height, buf, null)
                uri = ImageExporter.savePng(
                    context, buf, width, height, "deepzoom_${timestamp()}.png"
                )
                if (uri == null) error = "Could not write to Pictures/DeepZoom"
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                view.renderer.releaseExportResources()
                view.requestRender()
            }
            onDone(uri, error)
        }
    }

    /**
     * Renders a zoom-out from the current position back to the full set.
     *
     * Each frame widens the span by a fixed factor, which is what makes the motion
     * look linear: constant multiplicative steps read as constant speed, whereas
     * constant additive steps would crawl at depth and then lurch at the end.
     */
    fun saveVideo(
        context: Context,
        settings: VideoSettings,
        progress: Progress,
        onDone: (Uri?, String?) -> Unit
    ) {
        val snapshot = view.state.snapshot()
        val total = frameCount(snapshot.spanY, settings)
        lastVideoDiag = ""

        view.queueEvent {
            var uri: Uri? = null
            var error: String? = null
            val encoder = VideoExporter(
                context, settings.width, settings.height, settings.fps,
                "deepzoom_${timestamp()}.mp4", settings.bitsPerPixel
            )

            try {
                encoder.start()
                // Two frame buffers, so conversion and encoding of one frame overlap
                // with rendering the next. The YUV conversion alone is a couple of
                // million pixels; running it serially left the GPU idle for most of
                // every frame.
                val buffers = arrayOf(
                    allocate(settings.width, settings.height),
                    allocate(settings.width, settings.height)
                )
                val pipeline = java.util.concurrent.Executors.newSingleThreadExecutor()
                var inFlight: java.util.concurrent.Future<*>? = null
                // Counted so a path that silently skips frames cannot produce an empty
                // file again.
                var encoded = 0
                val buf = buffers[0]

                // Frame-identity check.
                //
                // A frozen image with frames still being written is invisible to every
                // counter we have: the loop runs, the encoder accepts every frame, the
                // file is the right length. Checksumming what the renderer actually
                // produced is the one measurement that separates "the renderer emitted
                // the same picture twice" from "the renderer was fine and the freeze is
                // downstream of it". Views are made once; asIntBuffer allocates.
                val frameInts = arrayOf(buffers[0].asIntBuffer(), buffers[1].asIntBuffer())
                var prevSum = 0L
                var run = 0          // length of the current identical-frame run
                var runAt = -1       // frame the current run started on
                var longestRun = 0
                var longestAt = -1
                var firstDup = -1
                var dupTotal = 0

                // Exact equality is too narrow a test. A frame differing in a handful
                // of pixels looks frozen but checksums differently, and an alternating
                // good/bad sequence never repeats two frames in a row at all. So also
                // measure how much of each frame actually changed, and keep a short
                // checksum history to catch repeats at a lag.
                val history = LongArray(HISTORY)
                var minChanged = 1.0
                var minChangedAt = -1
                var firstStall = -1
                var stallTotal = 0
                var lagHit = -1
                var lagHitAt = -1
                // Where the failed readbacks fall matters: a contiguous tail means the
                // context died and never came back, scattered means an intermittent
                // fault that recovers.
                var firstFailAt = -1
                var lastFailAt = -1
                var failRunLongest = 0
                var failRunCur = 0
                var prevFailCount = 0
                // Filled in the moment a freeze is first seen, while the strip still
                // holds the rows that produced it.
                var freezeReport = ""

                val frameState = snapshot.snapshot()
                var bundle: OrbitBundle? = null

                // Exponential map renders every scale in the zoom exactly once, so a
                // frame costs only the few new strip rows it exposes rather than a
                // full render. Falls back silently if the strip will not fit.
                val geom = stripFor(
                    settings, snapshot.spanY, view.renderer.maxTextureSizeCached
                )
                if (geom != null) {
                    view.renderer.stripBegin(geom)
                    view.renderer.onStripProgress = { done, target ->
                        progress.onStripBuild(done, target)
                    }
                }

                // Diagnostic: advance the strip frame by frame the way a real export
                // does, sampling what actually landed in the newest rows, then write the
                // strip and the last unwarped frame out as images.
                //
                // Building only frame 0 cannot see this class of fault: the first frame
                // builds the whole window in one call and looks fine, and the question
                // is whether the *extensions* after it carry any content.
                if (geom != null && settings.dumpStrip) {
                    // Probe far enough that the window has moved entirely off the rows
                    // the first frame built, with margin. A fixed count cannot do this:
                    // rows gained per frame vary more than tenfold across the zoom-rate
                    // slider, so any constant is either short at slow rates or wasteful
                    // at fast ones.
                    val firstWindow = StripGeometry.windowFor(
                        geom, frameState.spanY, settings.height, settings.width
                    )
                    val rowsPerFrame = ln(settings.zoomPerFrame) / geom.step
                    val toClear = (firstWindow.last - firstWindow.first) / max(rowsPerFrame, 1e-6)
                    val probeFrames = min(total, (toClear * 1.5 + 20).toInt().coerceIn(20, DUMP_FRAMES_MAX))
                    val log = StringBuilder()
                    var firstBlank = -1
                    var firstUniform = -1
                    var firstNoDraw = -1

                    for (i in 0 until probeFrames) {
                        frameState.spanY = spanForFrame(snapshot.spanY, settings, i, total)
                        val w = StripGeometry.windowFor(
                            geom, frameState.spanY, settings.height, settings.width
                        )
                        view.renderer.stripResetRowsDrawn()
                        bundle = view.renderer.stripEnsureRange(
                            frameState, geom, w.first, w.last, bundle
                        )
                        val drawn = view.renderer.stripRowsDrawn
                        val dLo = view.renderer.stripDrawnLo
                        val dHi = view.renderer.stripDrawnHi
                        // Sample the rows this frame actually drew, whichever end of the
                        // window they were added to, plus both ends of the window so a
                        // stale end still shows up.
                        val fresh = if (drawn > 0) view.renderer.stripRowLit((dLo + dHi) / 2) else -1
                        val lo = view.renderer.stripRowLit(w.first)
                        val hi = view.renderer.stripRowLit(w.last)
                        if (i > 0 && drawn == 0 && firstNoDraw < 0) firstNoDraw = i
                        if (i > 0 && fresh == 0 && firstBlank < 0) firstBlank = i
                        if (i > 0 && fresh == geom.width && firstUniform < 0) firstUniform = i
                        if (i % 10 == 0 || i == probeFrames - 1 ||
                            i == firstBlank || i == firstNoDraw
                        ) {
                            log.append(
                                "f%d win[%d..%d] drew=%d@[%d..%d] fresh=%d lo=%d hi=%d\n".format(
                                    i, w.first, w.last, drawn, dLo, dHi, fresh, lo, hi
                                )
                            )
                        }
                        progress.onProgress(i + 1, probeFrames)
                    }

                    // Keep the dump small enough to survive the Bitmap round trip,
                    // while holding the strip's own aspect so it stays readable.
                    val dw = min(1024, geom.width)
                    val dh = min(2048, geom.ringHeight)
                    val dump = allocate(dw, dh)
                    view.renderer.stripDump(dump, dw, dh)
                    val stamp = timestamp()
                    ImageExporter.savePng(
                        context, dump, dw, dh, "deepzoom_strip_$stamp.png"
                    )

                    // Also write the last unwarped frame. If the strip is good and this
                    // is blank, the fault is in the resampling; if this looks right, the
                    // fault is downstream in the encoder.
                    view.renderer.stripUnwarp(
                        geom, frameState.spanY, settings.width, settings.height, buf
                    )
                    val frameUri = ImageExporter.savePng(
                        context, buf, settings.width, settings.height,
                        "deepzoom_frame_$stamp.png"
                    )

                    encoder.abort()
                    val direction = if (settings.zoomIn) "zoom-in (window descends)"
                                    else "zoom-out (window ascends)"
                    val verdict = when {
                        firstNoDraw >= 0 ->
                            "Frame $firstNoDraw drew no rows at all. The range handed to " +
                                "renderRows is wrong, not the draw."
                        firstBlank >= 0 ->
                            "Rows were drawn every frame, but the rows drawn at frame " +
                                "$firstBlank came out blank. The draw produces nothing."
                        firstUniform >= 0 ->
                            "Rows drawn at frame $firstUniform are uniformly lit: drawn, " +
                                "but every pixel resolves the same."
                        else ->
                            "Every frame drew rows and they had content. The strip " +
                                "extends correctly in this direction."
                    }
                    // Surface the numbers as the "error" so they show in a dialog that
                    // can be screenshotted, rather than only in logcat.
                    onDone(
                        frameUri,
                        "Probed $probeFrames frames, $direction,\n" +
                            "strip ${geom.width}x${geom.ringHeight}.\n\n" +
                            verdict + "\n\n" + log + "\nLast chunk:\n" + view.renderer.lastDiag
                    )
                    return@queueEvent
                }

                val phaseNs = LongArray(4)
                view.renderer.profReset()
                val tExport = System.nanoTime()

                // The last stretch is measured on its own as well as in the total.
                // An export whose cost is concentrated at one end averages out to
                // nothing useful otherwise: a few hundred expensive frames disappear
                // behind a few thousand cheap ones, and the summary describes the
                // cheap ones.
                // Rolling window for the live summary.
                var winStart = tExport
                var winFrame = 0
                var winProf = view.renderer.profSnapshot()
                var winPhase = LongArray(4)

                val tailFrom = (total * 4) / 5
                val tailPhase = LongArray(4)
                var tailMark: LongArray? = null
                var tTail = 0L

                for (i in 0 until total) {
                    if (i == tailFrom) {
                        tailMark = view.renderer.profSnapshot()
                        tTail = System.nanoTime()
                    }
                    val phaseBefore = if (i >= tailFrom) phaseNs.copyOf() else null
                    frameState.spanY = spanForFrame(snapshot.spanY, settings, i, total)

                    if (geom != null) {
                        val window = StripGeometry.windowFor(
                            geom, frameState.spanY, settings.height, settings.width
                        )
                        view.renderer.stripResetRowsDrawn()
                        val tRows = System.nanoTime()
                        bundle = view.renderer.stripEnsureRange(
                            frameState, geom, window.first, window.last, bundle
                        )
                        phaseNs[PH_ROWS] += System.nanoTime() - tRows
                        // Asynchronous readback through pixel buffer objects was
                        // tried here and removed: glMapBufferRange returns null on some
                        // drivers, and there is no way to know without attempting it
                        // mid-export. The overlap it bought was modest next to the
                        // conversion pipelining below, which works everywhere.
                        val tUnwarp = System.nanoTime()
                        view.renderer.stripUnwarp(
                            geom, frameState.spanY, settings.width, settings.height,
                            buffers[i % 2]
                        )
                        phaseNs[PH_UNWARP] += System.nanoTime() - tUnwarp
                        val failNow = view.renderer.readbackFailures
                        if (failNow > prevFailCount) {
                            if (firstFailAt < 0) firstFailAt = i
                            lastFailAt = i
                            failRunCur++
                            if (failRunCur > failRunLongest) failRunLongest = failRunCur
                        } else {
                            failRunCur = 0
                        }
                        prevFailCount = failNow
                    } else {
                        bundle = view.renderer.renderOffscreen(
                            frameState, settings.width, settings.height,
                            buffers[i % 2], bundle
                        )
                    }
                    // Checksum before handing the buffer to the encoder. The frame that
                    // last used this buffer was awaited on the previous iteration, so
                    // nothing else is reading it now.
                    val tCheck = System.nanoTime()
                    val sum = checksum(frameInts[i % 2])

                    // Fraction of pixels differing from the previous frame. Both frames
                    // are still live in the two ping-pong buffers, so this costs a pass
                    // and no extra memory.
                    var changed = 1.0
                    if (i > 0) {
                        val cur = frameInts[i % 2]
                        val prv = frameInts[(i - 1) % 2]
                        // Strided to match the checksum: this is an estimate of the
                        // fraction that changed, and an estimate is all the threshold
                        // below needs.
                        var diff = 0
                        var seen = 0
                        var k = 0
                        val n = cur.capacity()
                        while (k < n) {
                            if (cur.get(k) != prv.get(k)) diff++
                            seen++
                            k += VERIFY_STRIDE
                        }
                        changed = diff.toDouble() / max(1, seen)
                        if (changed < minChanged) { minChanged = changed; minChangedAt = i }
                        if (changed < STALL_FRACTION) {
                            stallTotal++
                            if (firstStall < 0) firstStall = i
                        }
                        // Repeat at a lag: an alternating pattern shows up as a match
                        // two or more frames back while consecutive frames all differ.
                        // history[k] holds frame i-1-k, so a match there is a repeat at
                        // a lag of k+1. Starting at k=1 is what catches a plain
                        // alternating pattern, which is lag 2.
                        for (k in 1 until min(HISTORY, i)) {
                            if (sum == history[k]) {
                                if (lagHit < 0) {
                                    lagHit = k + 1
                                    lagHitAt = i
                                    // A repeat at a lag is as much a failure as a
                                    // repeat outright, so it gets a strip snapshot too.
                                    if (geom != null && freezeReport.isEmpty()) {
                                        freezeReport = stripSnapshot(
                                            view, geom, frameState, settings, i, changed
                                        )
                                    }
                                }
                                break
                            }
                        }
                    }
                    phaseNs[PH_VERIFY] += System.nanoTime() - tCheck

                    if (phaseBefore != null) {
                        for (k in tailPhase.indices) tailPhase[k] += phaseNs[k] - phaseBefore[k]
                    }

                    if ((i + 1) % PHASE_REPORT_EVERY == 0) {
                        val now = System.nanoTime()
                        val windowS = (now - winStart) / 1e9
                        val frames = i + 1 - winFrame
                        val prof = view.renderer.profSnapshot()
                        if (windowS > 0 && frames > 0) {
                            val raster = (prof[3] - winProf[3]) / 1e9
                            val orbit = (prof[0] - winProf[0]) / 1e9
                            val read = ((phaseNs[PH_UNWARP] - winPhase[PH_UNWARP]) +
                                (phaseNs[PH_VERIFY] - winPhase[PH_VERIFY])) / 1e9
                            progress.onPhaseSummary(
                                "%.1fs/frame · raster %.0f%% · orbit %.0f%% · readback %.0f%%"
                                    .format(
                                        windowS / frames,
                                        100.0 * raster / windowS,
                                        100.0 * orbit / windowS,
                                        100.0 * read / windowS
                                    )
                            )
                        }
                        winStart = now; winFrame = i + 1
                        winProf = prof; winPhase = phaseNs.copyOf()
                    }

                    for (k in HISTORY - 1 downTo 1) history[k] = history[k - 1]
                    history[0] = sum

                    if (i > 0 && sum == prevSum) {
                        if (run == 0) { run = 2; runAt = i - 1 } else run++
                        dupTotal++
                        if (firstDup < 0) {
                            firstDup = i
                            if (geom != null && freezeReport.isEmpty()) {
                                freezeReport =
                                    stripSnapshot(view, geom, frameState, settings, i, 0.0)
                            }
                        }
                        if (run > longestRun) { longestRun = run; longestAt = runAt }
                    } else {
                        run = 0
                    }
                    prevSum = sum

                    // A stall counts as a freeze for reporting purposes, so capture the
                    // strip state the first time one happens even if no two frames were
                    // ever byte-identical.
                    if (i > 0 && changed < STALL_FRACTION && freezeReport.isEmpty() && geom != null) {
                        freezeReport = stripSnapshot(view, geom, frameState, settings, i, changed)
                    }

                    // Wait for the frame before last, which is the one that used this
                    // buffer, then hand this frame off and carry on rendering.
                    val tEnc = System.nanoTime()
                    inFlight?.get()
                    val ready = buffers[i % 2]
                    inFlight = pipeline.submit { encoder.encodeFrame(ready) }
                    phaseNs[PH_ENCODE] += System.nanoTime() - tEnc
                    encoded++
                    progress.onProgress(i + 1, total)
                }

                inFlight?.get()

                // The last holdFrames frames sit on the destination on purpose, so
                // duplicates there are expected and not worth reporting.
                lastVideoDiag = describeDuplicates(
                    settings, total, firstDup, dupTotal, longestRun, longestAt,
                    firstStall, stallTotal,
                    view.renderer.readbackFailures, view.renderer.lastReadbackError,
                    firstFailAt, lastFailAt, failRunLongest,
                    view.renderer.readbackHealth,
                    minChanged, minChangedAt, lagHit, lagHitAt
                )
                val wallS = (System.nanoTime() - tExport) / 1e9
                val names = arrayOf(
                    "building rows", "unwarp + readback", "checksum + diff", "encode wait"
                )
                val breakdown = buildString {
                    append("Export took %.0fs for %d frames (%.2fs per frame).\n"
                        .format(wallS, total, wallS / max(1, total)))
                    append("Frame time by phase:\n")
                    for (k in names.indices) {
                        append("  %-18s %7.1fs  %4.1f%%\n".format(
                            names[k], phaseNs[k] / 1e9,
                            if (wallS > 0) 100.0 * phaseNs[k] / 1e9 / wallS else 0.0
                        ))
                    }
                    val acc = phaseNs.sum() / 1e9
                    append("  %-18s %7.1fs  %4.1f%%\n".format(
                        "unaccounted", wallS - acc,
                        if (wallS > 0) 100.0 * (wallS - acc) / wallS else 0.0
                    ))
                    val strip = view.renderer.profReport()
                    if (strip.isNotEmpty()) append("\n").append(strip)

                    val tailCount = total - tailFrom
                    if (tailMark != null && tailCount > 0) {
                        val tailS = (System.nanoTime() - tTail) / 1e9
                        append("\n--- last %d frames only ---\n".format(tailCount))
                        append("Took %.0fs (%.2fs per frame, %.1fx the overall rate).\n"
                            .format(tailS, tailS / tailCount,
                                if (wallS > 0) (tailS / tailCount) / (wallS / total) else 0.0))
                        for (k in names.indices) {
                            append("  %-18s %7.1fs  %4.1f%%\n".format(
                                names[k], tailPhase[k] / 1e9,
                                if (tailS > 0) 100.0 * tailPhase[k] / 1e9 / tailS else 0.0))
                        }
                        val ts = view.renderer.profReport(tailMark)
                        if (ts.isNotEmpty()) append("\n").append(ts)
                    }
                }
                lastVideoDiag =
                    if (lastVideoDiag.isEmpty()) breakdown
                    else lastVideoDiag + "\n\n" + breakdown
                if (freezeReport.isNotEmpty()) {
                    lastVideoDiag += "\n\n" + freezeReport
                }

                if (encoded != total) {
                    throw IllegalStateException(
                        "Encoded $encoded of $total frames — refusing to write a " +
                            "truncated video"
                    )
                }
                // finish() queues end-of-stream and drains the encoder. MediaCodec is
                // not safe to drive from two threads, so it runs where every
                // encodeFrame ran rather than on the GL thread.
                uri = pipeline.submit<Uri?> { encoder.finish() }.get()
                pipeline.shutdown()
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                encoder.abort()
            } finally {
                view.renderer.onStripProgress = null
                view.renderer.stripEnd()
                view.renderer.releaseExportResources()
                view.renderer.invalidateOrbit()
                view.requestRender()
            }
            onDone(uri, error)
        }
    }

    /**
     * What the frame-identity check found on the most recent video export. Empty when
     * every frame differed from the one before it, which is the healthy case.
     */
    @Volatile
    var lastVideoDiag: String = ""
        private set

    /**
     * Order-sensitive checksum of one rendered frame.
     *
     * Reads every pixel rather than sampling: the whole point is to be able to say two
     * frames were identical without hedging, and a strided hash cannot. Absolute gets,
     * so the buffer's own position is left alone for the encoder.
     */
    /**
     * Frame fingerprint, over a strided sample rather than every pixel.
     *
     * This exists to notice frames that came out identical, and it is pure diagnostics
     * -- nothing in the output depends on it. Hashing all two million pixels of every
     * frame, and then diffing them all again, cost about a quarter of a measured
     * export, which is more than the freeze detection is worth.
     *
     * The stride is coprime with the frame width so the sample walks across columns
     * instead of landing in the same one on every row. Two frames that differ
     * anywhere structural differ in the sample; what a stride can miss is a difference
     * confined to a handful of pixels, and a frame that changed in a handful of pixels
     * is a stall already, which the changed-fraction test below reports on its own.
     */
    private fun checksum(pixels: java.nio.IntBuffer): Long {
        var h = -3750763034362895579L          // FNV-1a 64-bit offset basis
        var i = 0
        val n = pixels.capacity()
        while (i < n) {
            h = (h xor pixels.get(i).toLong()) * 1099511628211L
            i += VERIFY_STRIDE
        }
        return h
    }

    /**
     * Captures what the strip looked like at the moment a freeze was first seen, while
     * the rows that produced it are still in the ring.
     */
    private fun stripSnapshot(
        view: MandelbrotView,
        geom: StripGeometry,
        frameState: ViewState,
        settings: VideoSettings,
        frame: Int,
        changed: Double
    ): String {
        val w = StripGeometry.windowFor(
            geom, frameState.spanY, settings.height, settings.width
        )
        val r = view.renderer
        return "At frame $frame (%.4f%% of pixels changed):\n".format(changed * 100) +
            "  window [${w.first}..${w.last}]\n" +
            "  valid  [${r.stripValidLo}..${r.stripValidHi}]\n" +
            "  drew ${r.stripRowsDrawn} rows at [${r.stripDrawnLo}..${r.stripDrawnHi}]\n" +
            "  that chunk measured lit=${r.lastChunkLit}/${geom.width} inside renderRows\n\n" +
            "Content across ALL valid rows:\n" +
            r.stripProfile(r.stripValidLo, r.stripValidHi, 16)
    }

    private fun describeDuplicates(
        settings: VideoSettings,
        total: Int,
        firstDup: Int,
        dupTotal: Int,
        longestRun: Int,
        longestAt: Int,
        firstStall: Int,
        stallTotal: Int,
        readbackFailures: Int,
        lastReadbackError: Int,
        firstFailAt: Int,
        lastFailAt: Int,
        failRunLongest: Int,
        readbackHealth: String,
        minChanged: Double,
        minChangedAt: Int,
        lagHit: Int,
        lagHitAt: Int
    ): String {
        val moving = (total - settings.holdFrames).coerceAtLeast(1)
        // Duplicates inside the intentional hold at the end are expected.
        val realDup = firstDup in 0 until moving
        val realStall = firstStall in 0 until moving
        val realLag = lagHitAt in 0 until moving
        if (!realDup && !realStall && !realLag && readbackFailures == 0) return ""

        fun at(frame: Int) = "frame $frame (%.1fs)".format(frame.toDouble() / settings.fps)

        return buildString {
            append("Frozen or near-frozen frames detected.\n\n")
            if (readbackFailures > 0) {
                append("READBACK FAILED on $readbackFailures frames ")
                append("(last GL error 0x${lastReadbackError.toString(16)}).\n")
                append("Those frames kept whatever the buffer already held, which with ")
                append("two alternating buffers is the frame from two back.\n")
                append("  frames $firstFailAt..$lastFailAt, ")
                append("longest unbroken run $failRunLongest\n\n")
                if (readbackHealth.isNotEmpty()) append(readbackHealth + "\n")
            }
            if (realDup) {
                append("Identical frames:\n")
                append("  first repeat ${at(firstDup)}\n")
                append("  longest run $longestRun frames from ${at(longestAt)}\n")
                append("  $dupTotal of $total repeated\n\n")
            }
            if (realStall) {
                append("Barely-changing frames (under %.1f%% of pixels):\n"
                    .format(STALL_FRACTION * 100))
                append("  first ${at(firstStall)}\n")
                append("  $stallTotal of $total\n\n")
            }
            if (minChangedAt >= 0) {
                append("Least motion: %.4f%% of pixels at %s\n\n"
                    .format(minChanged * 100, at(minChangedAt)))
            }
            if (realLag) {
                append("Frame repeats one seen $lagHit frames earlier, at ")
                append("${at(lagHitAt)} — the output is cycling, not advancing.\n\n")
            }
            append(
                if (realDup || realStall)
                    "The renderer produced the same image twice, so the freeze is at " +
                        "or above the unwarp — not in the encoder."
                else
                    "Every frame differed from the one before it, but the output " +
                        "repeats at a lag."
            )
        }
    }

    private fun allocate(w: Int, h: Int): ByteBuffer =
        ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    companion object {
        /**
         * Span for one frame.
         *
         * Zoom-out starts at the current view and widens to the whole set; zoom-in is
         * the same sequence walked backwards, starting wide and ending on the current
         * view. Rendering inward directly avoids reversing an encoded video, which
         * means decoding and re-encoding every frame.
         */
        fun spanForFrame(
            targetSpan: Double,
            settings: VideoSettings,
            index: Int,
            total: Int
        ): Double {
            val moving = (total - settings.holdFrames).coerceAtLeast(1)

            // Hold on the destination at the end, whichever way we are going.
            if (index >= moving) {
                return if (settings.zoomIn) targetSpan else ViewState.DEFAULT_SPAN
            }

            // Zoom-in is the zoom-out sequence walked backwards, rather than built
            // forwards from the wide end. Built forwards, rounding in the frame count
            // leaves the last moving frame short of the target — the zoom stops just
            // before the place you started from. Mirroring makes the final frame land
            // exactly on it by construction.
            val step = if (settings.zoomIn) moving - 1 - index else index
            return (targetSpan * Math.pow(settings.zoomPerFrame, step.toDouble()))
                .coerceIn(targetSpan, ViewState.DEFAULT_SPAN)
        }

        /** Ceiling on the strip ring buffer. Beyond this, fall back to plain frames. */
        const val STRIP_MEMORY_BUDGET = 160L * 1024 * 1024

        /**
         * Pixel stride for the duplicate and stall checks.
         *
         * Odd, so it is coprime with any even frame width and the sample does not
         * degenerate into a single column.
         */
        private const val VERIFY_STRIDE = 17

        /**
         * Frames between live breakdowns. Short enough to follow a changing export,
         * long enough that the numbers are not noise from a single frame.
         */
        private const val PHASE_REPORT_EVERY = 20

        /** Phase slots for the per-frame export profile. */
        private const val PH_ROWS = 0
        private const val PH_UNWARP = 1
        private const val PH_VERIFY = 2
        private const val PH_ENCODE = 3

        /** Upper bound on the strip dump's probe, so a slow zoom rate cannot run away. */
        const val DUMP_FRAMES_MAX = 2000

        /** Checksums retained, so a repeat at a lag shows up as well as a repeat. */
        const val HISTORY = 8

        /**
         * Below this fraction of pixels changed, a frame is treated as frozen.
         *
         * Byte equality is too strict: a frame differing in a few pixels still reads as
         * stuck, and an alternating good/bad sequence never repeats two frames running.
         * A real zoom step at these rates moves a large share of the frame, so anything
         * under a fraction of a percent is not motion.
         */
        const val STALL_FRACTION = 0.002

        fun stripFor(settings: VideoSettings, deepestSpan: Double, maxTex: Int): StripGeometry? =
            if (!settings.exponentialMap) null
            else StripGeometry.build(
                settings.width, settings.height, deepestSpan,
                maxTex.coerceAtLeast(2048), STRIP_MEMORY_BUDGET
            )

        fun frameCount(startSpan: Double, settings: VideoSettings): Int {
            if (startSpan >= ViewState.DEFAULT_SPAN) return settings.holdFrames + 1
            val steps = ln(ViewState.DEFAULT_SPAN / startSpan) / ln(settings.zoomPerFrame)
            return max(1, ceil(steps).toInt()) + settings.holdFrames
        }

        /** Video dimensions must be even; many encoders prefer multiples of 16. */
        fun alignedWidth(height: Int, aspect: Double): Int {
            val raw = (height * aspect).roundToInt()
            return max(16, (raw / 16.0).roundToInt() * 16)
        }

        /**
         * Aspect ratios offered for export. "Screen" resolves to the live view's own
         * ratio; the rest let a shot be framed for wherever it is going, since the
         * vertical span is fixed and the horizontal extent follows from the ratio.
         */
        val ASPECT_LABELS = arrayOf(
            "Screen", "16:9", "9:16", "4:3", "3:2", "1:1", "21:9"
        )

        fun aspectValue(index: Int, screenAspect: Double): Double = when (index) {
            1 -> 16.0 / 9.0
            2 -> 9.0 / 16.0
            3 -> 4.0 / 3.0
            4 -> 3.0 / 2.0
            5 -> 1.0
            6 -> 21.0 / 9.0
            else -> screenAspect
        }
    }
}
