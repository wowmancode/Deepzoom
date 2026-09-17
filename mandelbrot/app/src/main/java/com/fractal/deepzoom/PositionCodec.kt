// SPDX-License-Identifier: AGPL-3.0-or-later
package com.fractal.deepzoom

import java.math.BigDecimal

/**
 * Text encoding of a location, and optionally the colours used to render it.
 *
 * The centre is written as a plain decimal string rather than a double, because at
 * depth the coordinate needs more digits than a double holds — round-tripping through
 * one would silently land you somewhere else.
 *
 * Two versions exist. DZ1 is position only; DZ2 appends the palette, so a shared code
 * reproduces the whole image rather than just the spot. DZ1 codes still load.
 */
object PositionCodec {

    private const val V1 = "DZ1"
    private const val V2 = "DZ2"
    private const val SEP = ":"

    class Decoded(val state: ViewState, val palette: PaletteSpec?)

    fun encode(s: ViewState, palette: PaletteSpec?): String {
        val head = listOf(
            if (palette == null) V1 else V2,
            s.centerX.toPlainString(),
            s.centerY.toPlainString(),
            s.spanY.toString(),
            s.maxIter.toString()
        ).joinToString(SEP)
        return if (palette == null) head else head + SEP + palette.encode()
    }

    /** Returns null on anything malformed rather than throwing at the call site. */
    fun decode(text: String): Decoded? {
        val parts = text.trim().split(SEP)
        if (parts.size < 5) return null
        if (parts[0] != V1 && parts[0] != V2) return null

        return try {
            val s = ViewState()
            s.centerX = BigDecimal(parts[1])
            s.centerY = BigDecimal(parts[2])

            val span = parts[3].toDouble()
            if (!span.isFinite() || span <= 0.0) return null
            s.spanY = span.coerceIn(s.minSpan(), ViewState.MAX_SPAN)
            s.maxIter = parts[4].toInt().coerceIn(32, 65536)

            // A malformed palette should not cost you the position.
            val palette = if (parts[0] == V2 && parts.size >= 6)
                PaletteSpec.decode(parts.subList(5, parts.size).joinToString(SEP))
            else null

            Decoded(s, palette)
        } catch (e: Exception) {
            null
        }
    }
}
