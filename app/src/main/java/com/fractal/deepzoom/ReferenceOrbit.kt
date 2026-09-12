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
 * This is the most expensive thing the app does — roughly 300 ms for 65536 iterations
 * on a desktop JVM, and noticeably worse on a phone — so the design here is mostly
 * about *not* recomputing it.
 *
 * Two properties make that possible. The orbit depends on the centre and the iteration
 * count, but not on the zoom: zoom only sets how many digits are needed, and an orbit
 * computed with spare guard digits stays valid as you zoom in, and stays valid forever
 * as you zoom out. And the iteration is a plain forward recurrence, so raising the
 * detail slider extends the existing orbit instead of restarting it.
 *
 * Everything that does depend on zoom — the delta scaling and the BLA radii — lives in
 * OrbitGpuData, which is cheap to rebuild.
 */
class ReferenceOrbit(
    val centerX: BigDecimal,
    val centerY: BigDecimal,
    var zx: DoubleArray,
    var zy: DoubleArray,
    /** Valid point indices are 0..count. */
    var count: Int,
    var iterBuilt: Int,
    val spanAtBuild: Double,
    private val precision: Int,
    private var lastX: BigDecimal,
    private var lastY: BigDecimal,
    private var escaped: Boolean
) {
    /**
     * Continues an existing orbit to a higher iteration count.
     *
     * The saved BigDecimal state is the whole reason this is possible; without it a
     * nudge of the detail slider would mean paying the full cost again.
     */
    fun extendTo(newMaxIter: Int) {
        if (newMaxIter <= iterBuilt) return
        if (escaped) {
            iterBuilt = newMaxIter
            return
        }

        val mc = MathContext(precision)
        if (zx.size < newMaxIter + 2) {
            zx = zx.copyOf(newMaxIter + 2)
            zy = zy.copyOf(newMaxIter + 2)
        }

        var x = lastX
        var y = lastY
        var n = count

        while (n <= newMaxIter) {
            zx[n] = x.toDouble()
            zy[n] = y.toDouble()

            val x2 = x.multiply(x, mc)
            val y2 = y.multiply(y, mc)
            if (x2.add(y2, mc).toDouble() > ESCAPE_SQ) {
                escaped = true
                break
            }

            val nx = x2.subtract(y2, mc).add(centerX, mc)
            val ny = x.multiply(y, mc).multiply(TWO, mc).add(centerY, mc)
            x = nx.round(mc)
            y = ny.round(mc)
            n++
        }

        lastX = x
        lastY = y
        count = min(n, newMaxIter)
        iterBuilt = newMaxIter
    }

    /**
     * A stable view of the orbit as it stands now.
     *
     * extendTo mutates, and the render thread holds onto whatever it was last given,
     * so the builder hands over an immutable snapshot instead. The arrays are shared
     * rather than copied: extendTo only writes past the old count and reallocates
     * rather than overwriting, so existing indices never change under a reader.
     */
    fun snapshot(): ReferenceOrbit = ReferenceOrbit(
        centerX, centerY, zx, zy, count, iterBuilt, spanAtBuild, precision,
        lastX, lastY, escaped
    )

    companion object {
        private val TWO = BigDecimal(2)
        private const val ESCAPE_SQ = 4.0

        /**
         * Guard digits beyond what the current zoom strictly needs. Generous on
         * purpose: each spare digit is a decade of zooming in that reuses the orbit
         * rather than rebuilding it, and the benchmark says digit count barely affects
         * build time anyway.
         */
        private const val GUARD_DIGITS = 30

        fun precisionFor(spanY: Double): Int {
            val decades = max(0.0, ceil(-log10(spanY)))
            return GUARD_DIGITS + decades.toInt()
        }

        /** How far in this orbit can be zoomed before its digits run short. */
        const val ZOOM_IN_MARGIN = 1e-6

        fun compute(
            cx: BigDecimal,
            cy: BigDecimal,
            maxIter: Int,
            spanY: Double
        ): ReferenceOrbit {
            val orbit = ReferenceOrbit(
                cx, cy,
                DoubleArray(maxIter + 2), DoubleArray(maxIter + 2),
                0, 0, spanY, precisionFor(spanY),
                BigDecimal.ZERO, BigDecimal.ZERO, false
            )
            orbit.extendTo(maxIter)
            return orbit
        }
    }
}
