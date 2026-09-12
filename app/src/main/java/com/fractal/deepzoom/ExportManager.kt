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
        val holdFrames: Int = 12
    )

    fun interface Progress {
        fun onProgress(frame: Int, total: Int)
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
                val buf = allocate(settings.width, settings.height)
                val frameState = snapshot.snapshot()
                var orbit: ReferenceOrbit? = null

                for (i in 0 until total) {
                    if (i < total - settings.holdFrames) {
                        frameState.spanY = (snapshot.spanY *
                            Math.pow(settings.zoomPerFrame, i.toDouble()))
                            .coerceAtMost(ViewState.DEFAULT_SPAN)
                    } else {
                        // Hold on the final framing so the video does not end the
                        // instant the motion stops.
                        frameState.spanY = ViewState.DEFAULT_SPAN
                    }

                    orbit = view.renderer.renderOffscreen(
                        frameState, settings.width, settings.height, buf, orbit
                    )
                    encoder.encodeFrame(buf)
                    progress.onProgress(i + 1, total)
                }

                uri = encoder.finish()
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                encoder.abort()
            } finally {
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
}
