// SPDX-License-Identifier: AGPL-3.0-or-later
package com.fractal.deepzoom

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Encodes rendered frames to an H.264 MP4 in Movies/DeepZoom.
 *
 * Frames arrive as RGBA from glReadPixels and are converted to YUV420 on the CPU.
 * Feeding the encoder through an input Surface would skip that conversion, but it
 * requires standing up a second EGL surface alongside the one GLSurfaceView owns.
 * At the frame counts involved here the conversion is not the bottleneck — rendering
 * a deep-zoom frame costs far more — so the simpler path is the better trade.
 */
class VideoExporter(
    private val context: Context,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val displayName: String,
    /**
     * Bits per pixel per frame. Fractal frames are close to the worst case for an
     * inter-frame codec — every pixel is high-contrast detail that changes each frame,
     * so motion estimation has almost nothing to reuse. Rates that look generous for
     * ordinary video are visibly destructive here.
     */
    private val bitsPerPixel: Double
) {

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var frameIndex = 0L

    private val bufferInfo = MediaCodec.BufferInfo()
    private var rgbaFrame: ByteArray = ByteArray(0)

    private val threads = max(1, Runtime.getRuntime().availableProcessors())
    private val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)

    fun start() {
        val encoder = MediaCodec.createEncoderByType(MIME)
        val caps = encoder.codecInfo.getCapabilitiesForType(MIME)
        val bitrate = clampBitrate(caps, targetBitrate())

        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // Keyframes every second. Detail this dense benefits from frequent
            // refreshes, since predicted frames drift badly against it.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            )
        }

        // High profile gives CABAC and 8x8 transforms, both of which matter a lot for
        // fine detail. Not every encoder accepts being told, so fall back rather than
        // fail the export.
        val high = highProfileLevel(caps)
        codec = try {
            if (high != null) {
                format.setInteger(MediaFormat.KEY_PROFILE, high.first)
                format.setInteger(MediaFormat.KEY_LEVEL, high.second)
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder
        } catch (e: Exception) {
            try { encoder.release() } catch (_: Exception) {}
            format.removeKey(MediaFormat.KEY_PROFILE)
            format.removeKey(MediaFormat.KEY_LEVEL)
            MediaCodec.createEncoderByType(MIME).also {
                it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
        }
        codec!!.start()

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DeepZoom")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        uri = context.contentResolver
            .insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create output file")

        pfd = context.contentResolver.openFileDescriptor(uri!!, "rw")
            ?: throw IllegalStateException("Could not open output file")
        muxer = MediaMuxer(pfd!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    private fun targetBitrate(): Int =
        (width.toDouble() * height * fps * bitsPerPixel)
            .toLong().coerceIn(1_000_000L, 240_000_000L).toInt()

    private fun clampBitrate(caps: MediaCodecInfo.CodecCapabilities, wanted: Int): Int {
        val range = caps.videoCapabilities?.bitrateRange ?: return wanted
        return wanted.coerceIn(range.lower, range.upper)
    }

    /** Highest AVC profile/level the device advertises, or null if High is absent. */
    private fun highProfileLevel(caps: MediaCodecInfo.CodecCapabilities): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        for (p in caps.profileLevels) {
            if (p.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh) {
                val current = best
                if (current == null || p.level > current.second) {
                    best = Pair(p.profile, p.level)
                }
            }
        }
        return best
    }

    /** Feeds one bottom-up RGBA frame. Blocks until the encoder accepts it. */
    fun encodeFrame(rgba: ByteBuffer) {
        val c = codec ?: return
        drain(false)

        var inputIndex = -1
        while (inputIndex < 0) {
            inputIndex = c.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex < 0) drain(false)
        }

        val image = c.getInputImage(inputIndex)
            ?: throw IllegalStateException("Encoder rejected flexible YUV input")
        fillYuv(image, rgba)

        val pts = frameIndex * 1_000_000L / fps
        c.queueInputBuffer(inputIndex, 0, imageSize(), pts, 0)
        frameIndex++
    }

    private fun imageSize(): Int = width * height * 3 / 2

    /**
     * RGBA to YUV420, flipping vertically on the way.
     *
     * Plane strides are read from the Image rather than assumed, because encoders
     * differ on whether chroma is planar or interleaved, and on row padding.
     *
     * This is the dominant CPU cost of an export — a few tens of milliseconds per 1080p
     * frame single-threaded — and every row is independent, so it is split across the
     * available cores. For a long zoom-out that is the difference between an export you
     * wait through and one you leave running.
     */
    private fun fillYuv(image: android.media.Image, rgba: ByteBuffer) {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixStride = uPlane.pixelStride
        val vPixStride = vPlane.pixelStride

        // Pull the frame into a heap array once: random access into a direct buffer is
        // far slower than into a ByteArray, and every worker reads from it.
        if (rgbaFrame.size < width * height * 4) rgbaFrame = ByteArray(width * height * 4)
        rgba.position(0)
        rgba.get(rgbaFrame, 0, width * height * 4)

        val rowPairs = (height + 1) / 2
        val workers = min(threads, max(1, rowPairs))
        val chunk = (rowPairs + workers - 1) / workers

        val tasks = (0 until workers).map { w ->
            java.util.concurrent.Callable {
                val from = w * chunk
                val to = min(rowPairs, from + chunk)
                for (pair in from until to) {
                    val y = pair * 2
                    convertRowPair(
                        y, yBuf, uBuf, vBuf,
                        yRowStride, uRowStride, vRowStride, uPixStride, vPixStride
                    )
                }
                true
            }
        }
        pool.invokeAll(tasks).forEach { it.get() }
    }

    /**
     * Two source rows at a time: luma needs both, and chroma averages across them so
     * fine detail dithers rather than aliasing to whichever corner got sampled.
     */
    private fun convertRowPair(
        y: Int,
        yBuf: ByteBuffer, uBuf: ByteBuffer, vBuf: ByteBuffer,
        yRowStride: Int, uRowStride: Int, vRowStride: Int,
        uPixStride: Int, vPixStride: Int
    ) {
        val row0 = (height - 1 - y) * width * 4
        val row1 = if (y + 1 < height) (height - 1 - (y + 1)) * width * 4 else row0

        var x = 0
        while (x < width) {
            val a0 = row0 + x * 4
            yBuf.put(
                y * yRowStride + x,
                lumaOf(
                    rgbaFrame[a0].toInt() and 0xFF,
                    rgbaFrame[a0 + 1].toInt() and 0xFF,
                    rgbaFrame[a0 + 2].toInt() and 0xFF
                )
            )
            if (y + 1 < height) {
                val a1 = row1 + x * 4
                yBuf.put(
                    (y + 1) * yRowStride + x,
                    lumaOf(
                        rgbaFrame[a1].toInt() and 0xFF,
                        rgbaFrame[a1 + 1].toInt() and 0xFF,
                        rgbaFrame[a1 + 2].toInt() and 0xFF
                    )
                )
            }
            x++
        }

        val cy = y / 2
        var cx = 0
        while (cx < width / 2) {
            val x0 = cx * 2
            val x1 = if (x0 + 1 < width) x0 + 1 else x0
            // Unrolled over the 2x2 block. Looping over the offsets instead reads
            // better, but the loop subjects have to be materialised as arrays, and at
            // one chroma sample per iteration that allocates twice per sample -- about
            // a million short-lived arrays per frame, whose collection costs more than
            // the arithmetic they carry.
            val a00 = row0 + x0 * 4
            val a01 = row0 + x1 * 4
            val a10 = row1 + x0 * 4
            val a11 = row1 + x1 * 4
            val rs = (rgbaFrame[a00].toInt() and 0xFF) + (rgbaFrame[a01].toInt() and 0xFF) +
                (rgbaFrame[a10].toInt() and 0xFF) + (rgbaFrame[a11].toInt() and 0xFF)
            val gs = (rgbaFrame[a00 + 1].toInt() and 0xFF) + (rgbaFrame[a01 + 1].toInt() and 0xFF) +
                (rgbaFrame[a10 + 1].toInt() and 0xFF) + (rgbaFrame[a11 + 1].toInt() and 0xFF)
            val bs = (rgbaFrame[a00 + 2].toInt() and 0xFF) + (rgbaFrame[a01 + 2].toInt() and 0xFF) +
                (rgbaFrame[a10 + 2].toInt() and 0xFF) + (rgbaFrame[a11 + 2].toInt() and 0xFF)
            val r = rs shr 2
            val g = gs shr 2
            val b = bs shr 2
            uBuf.put(cy * uRowStride + cx * uPixStride, chromaU(r, g, b))
            vBuf.put(cy * vRowStride + cx * vPixStride, chromaV(r, g, b))
            cx++
        }
    }

    // BT.601 limited range, integer form.
    private fun lumaOf(r: Int, g: Int, b: Int): Byte =
        ((((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(16, 235)).toByte()

    private fun chromaU(r: Int, g: Int, b: Int): Byte =
        ((((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(16, 240)).toByte()

    private fun chromaV(r: Int, g: Int, b: Int): Byte =
        ((((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(16, 240)).toByte()

    private fun drain(endOfStream: Boolean) {
        val c = codec ?: return
        val m = muxer ?: return

        while (true) {
            val index = c.dequeueOutputBuffer(bufferInfo, if (endOfStream) TIMEOUT_US else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        trackIndex = m.addTrack(c.outputFormat)
                        m.start()
                        muxerStarted = true
                    }
                }
                index >= 0 -> {
                    val out = c.getOutputBuffer(index)
                    if (out != null && bufferInfo.size > 0 && muxerStarted &&
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        out.position(bufferInfo.offset)
                        out.limit(bufferInfo.offset + bufferInfo.size)
                        m.writeSampleData(trackIndex, out, bufferInfo)
                    }
                    c.releaseOutputBuffer(index, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }

    fun finish(): Uri? {
        val c = codec
        if (c != null) {
            var inputIndex = -1
            while (inputIndex < 0) {
                inputIndex = c.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex < 0) drain(false)
            }
            c.queueInputBuffer(
                inputIndex, 0, 0,
                frameIndex * 1_000_000L / fps,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM
            )
            drain(true)
        }

        release()

        uri?.let {
            val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            context.contentResolver.update(it, values, null, null)
        }
        return uri
    }

    fun abort() {
        release()
        uri?.let { context.contentResolver.delete(it, null, null) }
        uri = null
    }

    private fun release() {
        pool.shutdownNow()
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null

        try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
        try { muxer?.release() } catch (_: Exception) {}
        muxer = null
        muxerStarted = false

        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
    }

    companion object {
        private const val MIME = "video/avc"
        private const val TIMEOUT_US = 10_000L
    }
}
