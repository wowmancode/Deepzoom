package com.fractal.deepzoom

import java.math.BigDecimal

/**
 * Text encoding of a location, for sharing a spot in the set and returning to it.
 *
 * The centre is written as a plain decimal string rather than a double, because at
 * depth the coordinate needs more digits than a double can hold — round-tripping
 * through Double would silently land you somewhere else entirely.
 */
object PositionCodec {

    private const val PREFIX = "DZ1"
    private const val SEP = ":"

    fun encode(s: ViewState): String = listOf(
        PREFIX,
        s.centerX.toPlainString(),
        s.centerY.toPlainString(),
        s.spanY.toString(),
        s.maxIter.toString()
    ).joinToString(SEP)

    /** Returns null on anything malformed rather than throwing at the call site. */
    fun decode(text: String): ViewState? {
        val parts = text.trim().split(SEP)
        if (parts.size != 5 || parts[0] != PREFIX) return null

        return try {
            val s = ViewState()
            s.centerX = BigDecimal(parts[1])
            s.centerY = BigDecimal(parts[2])

            val span = parts[3].toDouble()
            if (!span.isFinite() || span <= 0.0) return null
            s.spanY = span.coerceIn(s.minSpan(), ViewState.MAX_SPAN)

            s.maxIter = parts[4].toInt().coerceIn(32, 65536)
            s
        } catch (e: Exception) {
            null
        }
    }
}
