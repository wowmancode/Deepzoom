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
 * from this orbit, so 90-digit arithmetic is paid for once per frame rather than two
 * million times.
 *
 * Orbit values are kept as doubles as well as GPU floats: the BLA table is built from
 * them on the CPU, and building it in float would compound rounding across thousands
 * of merge steps.
 */
class ReferenceOrbit(
    val centerX: BigDecimal,
    val centerY: BigDecimal,
    val zx: DoubleArray,
    val zy: DoubleArray,
    /** Valid point indices are 0..count. */
    val count: Int,
    val scaleExp: Int,
    val spanAtBuild: Double,
    val iterAtBuild: Int,
    val maxCAtBuild: Double
) {
    val scale: Double get() = 2.0.pow(scaleExp)

    var bla: BlaTable? = null
        internal set

    /**
     * Four floats per point: (2*Zx, 2*Zy, Zx*scale, Zy*scale).
     *
     * Both forms are needed every iteration — the doubled value for the 2*Z*d term,
     * the scaled value to reconstruct the true position — so precomputing both removes
     * two multiplies from the inner loop.
     */
    fun packForGpu(): FloatArray {
        val scale = this.scale
        val out = FloatArray((count + 1) * 4)
        for (n in 0..count) {
            val i = n * 4
            out[i] = (zx[n] * 2.0).toFloat()
            out[i + 1] = (zy[n] * 2.0).toFloat()
            out[i + 2] = (zx[n] * scale).toFloat()
            out[i + 3] = (zy[n] * scale).toFloat()
        }
        return out
    }

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
            scaleExp: Int,
            maxC: Double
        ): ReferenceOrbit {
            val mc = MathContext(precisionFor(spanY))

            val zx = DoubleArray(maxIter + 2)
            val zy = DoubleArray(maxIter + 2)
            var x = BigDecimal.ZERO
            var y = BigDecimal.ZERO
            var n = 0

            while (n <= maxIter) {
                zx[n] = x.toDouble()
                zy[n] = y.toDouble()

                val x2 = x.multiply(x, mc)
                val y2 = y.multiply(y, mc)

                // The reference escaping is normal. It just bounds how far the shader
                // can walk before it has to rebase.
                if (x2.add(y2, mc).toDouble() > ESCAPE_SQ) break

                val nx = x2.subtract(y2, mc).add(cx, mc)
                val ny = x.multiply(y, mc).multiply(TWO, mc).add(cy, mc)
                x = nx.round(mc)
                y = ny.round(mc)
                n++
            }

            // A normal exit leaves n one past the last written index; an escape break
            // leaves it pointing at it.
            val orbit = ReferenceOrbit(
                cx, cy, zx, zy, min(n, maxIter), scaleExp, spanY, maxIter, maxC
            )
            orbit.bla = BlaTable.build(orbit, maxC)
            return orbit
        }
    }
}
