package com.fractal.deepzoom

import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * A single point's orbit, iterated in arbitrary precision on the CPU.
 *
 * Precision beyond float32 lives only here. Every pixel renders as a small offset
 * from this orbit, and those offsets stay representable no matter how deep the
 * reference sits — so 90-digit arithmetic is paid for once per frame rather than
 * two million times.
 *
 * The delta scale is fixed at build time rather than derived per frame. An orbit is
 * only reused across a 4x zoom range, so a scale chosen for the build span stays
 * within two binary orders of ideal, which is nothing against 46 orders of headroom.
 * Fixing it here is what lets the GPU-side data be stored pre-scaled.
 */
class ReferenceOrbit(
    val centerX: BigDecimal,
    val centerY: BigDecimal,
    /**
     * Four floats per point: (2*Zx, 2*Zy, Zx*scale, Zy*scale).
     *
     * Both forms are needed every iteration — the doubled value for the 2*Z*d term,
     * the scaled value to reconstruct the true position — so precomputing both here
     * removes two multiplies from the inner loop at the cost of texture width.
     */
    val data: FloatArray,
    /** Valid point indices are 0..count. */
    val count: Int,
    val scaleExp: Int,
    val spanAtBuild: Double,
    val iterAtBuild: Int
) {
    val scale: Double get() = 2.0.pow(scaleExp)

    companion object {
        private val TWO = BigDecimal(2)
        private const val ESCAPE_SQ = 4.0

        fun precisionFor(spanY: Double): Int {
            val decades = max(0.0, ceil(-log10(spanY)))
            return 30 + decades.toInt()
        }

        fun compute(
            cx: BigDecimal,
            cy: BigDecimal,
            maxIter: Int,
            spanY: Double,
            scaleExp: Int
        ): ReferenceOrbit {
            val mc = MathContext(precisionFor(spanY))
            val scale = 2.0.pow(scaleExp)

            val data = FloatArray((maxIter + 2) * 4)
            var x = BigDecimal.ZERO
            var y = BigDecimal.ZERO
            var n = 0

            while (n <= maxIter) {
                val xd = x.toDouble()
                val yd = y.toDouble()
                val i = n * 4
                data[i] = (xd * 2.0).toFloat()
                data[i + 1] = (yd * 2.0).toFloat()
                data[i + 2] = (xd * scale).toFloat()
                data[i + 3] = (yd * scale).toFloat()

                val x2 = x.multiply(x, mc)
                val y2 = y.multiply(y, mc)

                // The reference escaping is normal and expected. It just bounds how
                // far the shader can walk before it has to rebase.
                if (x2.add(y2, mc).toDouble() > ESCAPE_SQ) break

                val nx = x2.subtract(y2, mc).add(cx, mc)
                val ny = x.multiply(y, mc).multiply(TWO, mc).add(cy, mc)
                x = nx.round(mc)
                y = ny.round(mc)
                n++
            }

            // A normal exit leaves n one past the last written index; an escape break
            // leaves it pointing at it.
            return ReferenceOrbit(cx, cy, data, min(n, maxIter), scaleExp, spanY, maxIter)
        }
    }
}
