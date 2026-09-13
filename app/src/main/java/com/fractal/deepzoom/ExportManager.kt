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
        val holdFrames: Int = 12
    )

    interface Progress {
        fun onProgress(frame: Int, total: Int)
        /** Strip rows built, for the first frame where that dominates. */
        fun onStripBuild(rowsDone: Int, rowsTarget: Int) {}
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
                val buf = buffers[0]
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

                // Diagnostic: build the first frame's strip window, write it out as an
                // image, and stop. Splits "strip is wrong" from "unwarp is wrong".
                if (geom != null && settings.dumpStrip) {
                    val window = StripGeometry.windowFor(
                        geom, frameState.spanY, settings.height, settings.width
                    )
                    view.renderer.debugSampling = true
                    try {
                            view.renderer.stripEnsureRange(
                            frameState, geom, window.first, window.last, null
                        )
                    } finally {
                        view.renderer.debugSampling = false
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

                    // Also write the first unwarped frame. If the strip is good and
                    // this is blank, the fault is in the resampling; if this looks
                    // right, the fault is downstream in the encoder.
                    view.renderer.stripUnwarp(
                        geom, frameState.spanY, settings.width, settings.height, buf
                    )
                    val frameUri = ImageExporter.savePng(
                        context, buf, settings.width, settings.height,
                        "deepzoom_frame_$stamp.png"
                    )

                    encoder.abort()
                    // Surface the strip's own numbers as the "error" so they show in a
                    // dialog that can be screenshotted, rather than only in logcat.
                    onDone(frameUri, "Debug images saved.\n\n" + view.renderer.lastDiag)
                    return@queueEvent
                }

                for (i in 0 until total) {
                    frameState.spanY = spanForFrame(snapshot.spanY, settings, i, total)

                    if (geom != null) {
                        val window = StripGeometry.windowFor(
                            geom, frameState.spanY, settings.height, settings.width
                        )
                        bundle = view.renderer.stripEnsureRange(
                            frameState, geom, window.first, window.last, bundle
                        )
                        view.renderer.stripUnwarp(
                            geom, frameState.spanY, settings.width, settings.height,
                            buffers[i % 2]
                        )
                    } else {
                        bundle = view.renderer.renderOffscreen(
                            frameState, settings.width, settings.height,
                            buffers[i % 2], bundle
                        )
                    }
                    // Wait for the frame before last, which is the one that used this
                    // buffer, then hand this frame off and carry on rendering.
                    inFlight?.get()
                    val ready = buffers[i % 2]
                    inFlight = pipeline.submit { encoder.encodeFrame(ready) }
                    progress.onProgress(i + 1, total)
                }

                inFlight?.get()
                pipeline.shutdown()
                uri = encoder.finish()
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
            val moving = total - settings.holdFrames
            return if (settings.zoomIn) {
                // Hold on the destination at the end.
                if (index >= moving) targetSpan
                else (ViewState.DEFAULT_SPAN *
                    Math.pow(settings.zoomPerFrame, -(index).toDouble()))
                    .coerceAtLeast(targetSpan)
            } else {
                if (index >= moving) ViewState.DEFAULT_SPAN
                else (targetSpan * Math.pow(settings.zoomPerFrame, index.toDouble()))
                    .coerceAtMost(ViewState.DEFAULT_SPAN)
            }
        }

        /** Ceiling on the strip ring buffer. Beyond this, fall back to plain frames. */
        const val STRIP_MEMORY_BUDGET = 160L * 1024 * 1024

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
