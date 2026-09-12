package com.fractal.deepzoom

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import java.nio.ByteBuffer
import java.nio.IntBuffer

object ImageExporter {

    /**
     * Writes an RGBA buffer from glReadPixels into Pictures/DeepZoom.
     *
     * Two conversions happen here. glReadPixels returns rows bottom-up, so they are
     * reversed. And GL's RGBA byte order is not Android's ARGB_8888 integer order, so
     * the red and blue channels are swapped as the rows are copied.
     */
    fun savePng(
        context: Context,
        rgba: ByteBuffer,
        width: Int,
        height: Int,
        displayName: String
    ): Uri? {
        val pixels = IntArray(width * height)
        rgba.position(0)
        val src: IntBuffer = rgba.asIntBuffer()

        val row = IntArray(width)
        for (y in 0 until height) {
            src.position(y * width)
            src.get(row, 0, width)
            val destRow = (height - 1 - y) * width
            for (x in 0 until width) {
                val v = row[x]
                // Source little-endian int is 0xAABBGGRR; ARGB_8888 wants 0xAARRGGBB.
                val r = v and 0xFF
                val g = (v shr 8) and 0xFF
                val b = (v shr 16) and 0xFF
                pixels[destRow + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DeepZoom")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null

        try {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: return null
        } finally {
            bitmap.recycle()
        }

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }
}
