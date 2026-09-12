package com.fractal.deepzoom

import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * A single point's orbit, iterated in arbitrary precision on the CPU.
 *
 * This is the only place in the app that needs precision beyond float32. Every pixel
 * is then rendered as a small offset from this orbit, and those offsets stay large
 * enough for float32 to handle no matter how deep the reference itself sits. That
 * asymmetry is the whole trick: precision cost is paid once per frame, not once per
 * pixel.
 */
class ReferenceOrbit(
    val centerX: BigDecimal,
    val centerY: BigDecimal,
    /** Interleaved x,y pairs: [x0, y0, x1, y1, ...]. Values are O(1), so float is fine. */
    val data: FloatArray,
    /** Number of valid orbit points, i.e. data holds indices 0..count. */
    val count: Int,
    val spanAtBuild: Double,
    val iterAtBuild: Int
) {
    companion object {
        private val TWO = BigDecimal(2)
        private const val ESCAPE_SQ = 4.0

        /**
         * Digits required to resolve a pixel at this zoom, plus guard digits for
         * error accumulated across the iteration.
         */
        fun precisionFor(spanY: Double): Int {
            val decades = max(0.0, ceil(-log10(spanY)))
            return 30 + decades.toInt()
        }

        fun compute(
            cx: BigDecimal,
            cy: BigDecimal,
            maxIter: Int,
            spanY: Double
        ): ReferenceOrbit {
            val mc = MathContext(precisionFor(spanY))

            val data = FloatArray((maxIter + 2) * 2)
            var x = BigDecimal.ZERO
            var y = BigDecimal.ZERO
            var n = 0

            while (n <= maxIter) {
                data[n * 2] = x.toFloat()
                data[n * 2 + 1] = y.toFloat()

                val x2 = x.multiply(x, mc)
                val y2 = y.multiply(y, mc)

                // The reference itself escaping is fine and common — it just bounds
                // how many points the shader can walk before it has to rebase.
                if (x2.add(y2, mc).toDouble() > ESCAPE_SQ) break

                val nx = x2.subtract(y2, mc).add(cx, mc)
                val ny = x.multiply(y, mc).multiply(TWO, mc).add(cy, mc)

                x = nx.round(mc)
                y = ny.round(mc)
                n++
            }

            // On a normal exit n has run one past the last written index; on an escape
            // break it points at it. Clamp so count is always the last valid point.
            val valid = min(n, maxIter)
            return ReferenceOrbit(cx, cy, data, valid, spanY, maxIter)
        }
    }
}
