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
    private var rgbaRow: ByteArray = ByteArray(0)

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
                if (best == null || p.level > best!!.second) {
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

        if (rgbaRow.size < width * 4 * 2) rgbaRow = ByteArray(width * 4 * 2)

        // Two source rows at a time: luma needs both, chroma averages across them.
        var y = 0
        while (y < height) {
            val srcY0 = height - 1 - y
            val srcY1 = if (y + 1 < height) height - 1 - (y + 1) else srcY0

            rgba.position(srcY0 * width * 4)
            rgba.get(rgbaRow, 0, width * 4)
            rgba.position(srcY1 * width * 4)
            rgba.get(rgbaRow, width * 4, width * 4)

            var x = 0
            while (x < width) {
                val a0 = x * 4
                val r0 = rgbaRow[a0].toInt() and 0xFF
                val g0 = rgbaRow[a0 + 1].toInt() and 0xFF
                val b0 = rgbaRow[a0 + 2].toInt() and 0xFF
                yBuf.put(y * yRowStride + x, lumaOf(r0, g0, b0))

                if (y + 1 < height) {
                    val a1 = width * 4 + x * 4
                    val r1 = rgbaRow[a1].toInt() and 0xFF
                    val g1 = rgbaRow[a1 + 1].toInt() and 0xFF
                    val b1 = rgbaRow[a1 + 2].toInt() and 0xFF
                    yBuf.put((y + 1) * yRowStride + x, lumaOf(r1, g1, b1))
                }
                x++
            }

            // Chroma at half resolution, averaged over each 2x2 block so fine detail
            // dithers instead of aliasing to whichever corner got sampled.
            val cy = y / 2
            var cx = 0
            while (cx < width / 2) {
                val x0 = cx * 2
                val x1 = if (x0 + 1 < width) x0 + 1 else x0
                var rs = 0; var gs = 0; var bs = 0
                for (o in intArrayOf(0, width * 4)) {
                    for (px in intArrayOf(x0, x1)) {
                        val a = o + px * 4
                        rs += rgbaRow[a].toInt() and 0xFF
                        gs += rgbaRow[a + 1].toInt() and 0xFF
                        bs += rgbaRow[a + 2].toInt() and 0xFF
                    }
                }
                val r = rs shr 2
                val g = gs shr 2
                val b = bs shr 2
                uBuf.put(cy * uRowStride + cx * uPixStride, chromaU(r, g, b))
                vBuf.put(cy * vRowStride + cx * vPixStride, chromaV(r, g, b))
                cx++
            }

            y += 2
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
