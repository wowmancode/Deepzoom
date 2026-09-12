package com.fractal.deepzoom

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Colour is a pure function of the smooth escape count, with no dependence on zoom.
 *
 * That is deliberate: a point's escape count does not change when you zoom, so mapping
 * count to colour through a fixed function means a pixel keeps its colour no matter
 * how far you descend. An earlier version scaled the colour cycle with depth to keep
 * band widths even, which made the whole image rotate through the palette as you
 * zoomed — technically prettier bands, but it read as the colours sliding around.
 *
 * Band width is instead under direct control via cycleLength, in iterations per full
 * trip around the palette.
 */
data class PaletteSpec(
    val name: String,
    /** Colour stops as 0xRRGGBB. The gradient wraps from the last back to the first. */
    val colors: List<Int>,
    val interior: Int = 0x000000,
    val cycleLength: Float = 64f,
    val offset: Float = 0f
) {
    fun encode(): String {
        val hex = colors.joinToString(",") { "%06x".format(it and 0xFFFFFF) }
        return "$hex;%06x;%.3f;%.4f".format(interior and 0xFFFFFF, cycleLength, offset)
    }

    companion object {
        fun decode(text: String): PaletteSpec? {
            return try {
                val parts = text.split(";")
                if (parts.size != 4) return null
                val colors = parseColors(parts[0]) ?: return null
                PaletteSpec(
                    name = "Custom",
                    colors = colors,
                    interior = parts[1].toInt(16),
                    cycleLength = parts[2].toFloat().coerceIn(2f, 100000f),
                    offset = parts[3].toFloat()
                )
            } catch (e: Exception) {
                null
            }
        }

        /** Accepts "#rrggbb, rrggbb, ..." in any reasonable spacing. */
        fun parseColors(text: String): List<Int>? {
            val items = text.split(",", " ", "\n")
                .map { it.trim().removePrefix("#") }
                .filter { it.isNotEmpty() }
            if (items.size < 2) return null
            return try {
                items.map { hex ->
                    when (hex.length) {
                        6 -> hex.toInt(16)
                        3 -> {
                            val r = hex[0].digitToInt(16) * 17
                            val g = hex[1].digitToInt(16) * 17
                            val b = hex[2].digitToInt(16) * 17
                            (r shl 16) or (g shl 8) or b
                        }
                        else -> return null
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}

object Palettes {

    val PRESETS: List<PaletteSpec> = listOf(
        PaletteSpec("Ultra Fractal", listOf(0x00072d, 0x1e4a8c, 0xedd8a1, 0xd8891a, 0x4a1c05)),
        PaletteSpec("Ember", listOf(0x03030a, 0x51102b, 0xc23b22, 0xf5b041, 0xfdf3d0)),
        PaletteSpec("Glacier", listOf(0x02111f, 0x11507a, 0x63c7d6, 0xe8f7fb, 0x2a6f8e)),
        PaletteSpec("Orchid", listOf(0x140021, 0x5c1a72, 0xc74fa0, 0xf5c6d6, 0x3b1250)),
        PaletteSpec("Moss", listOf(0x05140c, 0x1f5130, 0x74a83c, 0xe4e9b2, 0x2d3a17)),
        PaletteSpec("Copper", listOf(0x0d0704, 0x5a2a11, 0xb06b2c, 0xe8c39a, 0x2f1a0d)),
        PaletteSpec("Monochrome", listOf(0x000000, 0xffffff)),
        PaletteSpec("Spectrum", listOf(0xff0044, 0xffaa00, 0x44dd22, 0x00aaff, 0x8844ff)),
        PaletteSpec("Ink", listOf(0x060810, 0x243b6b, 0x9fb4d9, 0xf2f4f8, 0x121a33))
    )

    const val TEXTURE_WIDTH = 1024

    /**
     * Renders the stops into a lookup ramp. Sampling this with linear filtering and
     * repeat wrapping is what gives the smooth blending, and it handles arbitrary
     * stop counts without any shader changes.
     */
    fun buildRamp(spec: PaletteSpec): ByteArray {
        val stops = if (spec.colors.size >= 2) spec.colors else listOf(0x000000, 0xffffff)
        val n = stops.size
        val out = ByteArray(TEXTURE_WIDTH * 4)

        for (i in 0 until TEXTURE_WIDTH) {
            val p = i.toDouble() / TEXTURE_WIDTH * n
            val seg = p.toInt()
            val f = p - seg
            val a = stops[seg % n]
            val b = stops[(seg + 1) % n]

            val o = i * 4
            out[o] = lerpChannel(a shr 16, b shr 16, f)
            out[o + 1] = lerpChannel(a shr 8, b shr 8, f)
            out[o + 2] = lerpChannel(a, b, f)
            out[o + 3] = 255.toByte()
        }
        return out
    }

    private fun lerpChannel(a: Int, b: Int, f: Double): Byte {
        val av = (a and 0xFF).toDouble()
        val bv = (b and 0xFF).toDouble()
        return max(0, (av + (bv - av) * f).roundToInt().coerceAtMost(255)).toByte()
    }
}
