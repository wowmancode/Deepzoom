// SPDX-License-Identifier: AGPL-3.0-or-later
package com.fractal.deepzoom

import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.ceil
import kotlin.math.hypot
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

        /**
         * Furthest a nucleus may sit from centre, as a fraction of the span, to be used
         * as the reference. Equal to canReuse's 0.7 of a half-span, measured along the
         * shorter axis, so anything accepted here is also kept there.
         */
        const val NUCLEUS_ACCEPT = 0.35

        /** Periods beyond this are not worth the Newton cost. */
        private const val MAX_NUCLEUS_PERIOD = 8192
        private const val NEWTON_STEPS = 12

        /**
         * Finds the nearest minibrot nucleus, or null if there isn't a useful one.
         *
         * A periodic point makes a better reference than an arbitrary one: the orbit
         * returns close to zero every period, which keeps BLA coefficients small and
         * their validity radii large, so pixels take longer jumps.
         *
         * The period comes from the atom domain — the iteration whose |z| is smallest
         * is the period of the nearest atom — and Newton's method on f^p(0,c) = 0 then
         * lands on the nucleus itself.
         *
         * Returns null unless the nucleus lands inside the view. Measured against
         * high-precision ground truth, a nucleus found this way can sit tens of
         * thousands of view-widths away when the zoom is deeper than its period
         * warrants, and a reference that distant would give every pixel a huge delta,
         * costing far more than the better coefficients gain.
         */
        fun findNucleus(
            cx: BigDecimal,
            cy: BigDecimal,
            maxIter: Int,
            spanY: Double,
            precision: Int
        ): Array<BigDecimal>? {
            val mc = MathContext(precision)

            var x = BigDecimal.ZERO
            var y = BigDecimal.ZERO
            var best = Double.MAX_VALUE
            var period = 0
            val scan = min(maxIter, MAX_NUCLEUS_PERIOD)

            for (n in 1..scan) {
                val nx = x.multiply(x, mc).subtract(y.multiply(y, mc), mc).add(cx, mc)
                val ny = x.multiply(y, mc).multiply(TWO, mc).add(cy, mc)
                x = nx.round(mc)
                y = ny.round(mc)

                val xd = x.toDouble()
                val yd = y.toDouble()
                val m = xd * xd + yd * yd
                if (m > ESCAPE_SQ) break
                if (m < best) { best = m; period = n }
            }
            if (period < 1) return null

            var ccx = cx
            var ccy = cy
            repeat(NEWTON_STEPS) {
                var zx = BigDecimal.ZERO
                var zy = BigDecimal.ZERO
                var dx = BigDecimal.ZERO
                var dy = BigDecimal.ZERO

                for (i in 1..period) {
                    // d = 2*z*d + 1, the derivative with respect to c
                    val ndx = TWO.multiply(
                        zx.multiply(dx, mc).subtract(zy.multiply(dy, mc), mc), mc
                    ).add(BigDecimal.ONE, mc)
                    val ndy = TWO.multiply(
                        zx.multiply(dy, mc).add(zy.multiply(dx, mc), mc), mc
                    )
                    val nzx = zx.multiply(zx, mc).subtract(zy.multiply(zy, mc), mc).add(ccx, mc)
                    val nzy = TWO.multiply(zx.multiply(zy, mc), mc).add(ccy, mc)

                    dx = ndx.round(mc); dy = ndy.round(mc)
                    zx = nzx.round(mc); zy = nzy.round(mc)
                }

                val den = dx.multiply(dx, mc).add(dy.multiply(dy, mc), mc)
                if (den.signum() == 0) return null

                val sx = zx.multiply(dx, mc).add(zy.multiply(dy, mc), mc).divide(den, mc)
                val sy = zy.multiply(dx, mc).subtract(zx.multiply(dy, mc), mc).divide(den, mc)
                ccx = ccx.subtract(sx, mc)
                ccy = ccy.subtract(sy, mc)
            }

            // Only worth it if the nucleus is close enough to be kept.
            //
            // The bound has to agree with canReuse, which drops a reference further
            // than 0.7 of a half-span from centre. Accepting a nucleus beyond that
            // opens a gap: the reference is built, the view rejects it as too far off,
            // a rebuild is ordered, this finds the same nucleus again, and round it
            // goes -- a full high-precision orbit per chunk. Zooming in on a point
            // inside a minibrot but off its nucleus walks straight through that gap,
            // since the offset is fixed while the span shrinks under it, and stays in
            // it for about a threefold change of scale.
            val offX = ccx.subtract(cx, mc).toDouble()
            val offY = ccy.subtract(cy, mc).toDouble()
            if (!offX.isFinite() || !offY.isFinite()) return null
            if (hypot(offX, offY) > spanY * NUCLEUS_ACCEPT) return null

            return arrayOf(ccx, ccy)
        }

        /** A located minibrot: its nucleus, the period of its cycle, and its scale. */
        class Minibrot(
            val cx: BigDecimal,
            val cy: BigDecimal,
            val period: Int,
            val size: Double
        )

        /**
         * Nucleus nearest the given point, with the period and scale that go with it.
         *
         * findNucleus already locates the point but discards both, and the scale is
         * what tells a search when to stop: a minibrot is found once the view is down
         * to about its own width, not merely centred on it.
         *
         * The size follows the standard estimate: carry the derivative l along one
         * period and accumulate b as the running sum of its reciprocals; the component
         * is then about 1/(b*l^2) across. It is an estimate, and a good one only for
         * well separated components, which is all a search needs to decide when to
         * stop.
         */
        fun findMinibrot(
            cx: BigDecimal,
            cy: BigDecimal,
            maxIter: Int,
            spanY: Double,
            precision: Int
        ): Minibrot? {
            val mc = MathContext(precision)
            val n = findNucleus(cx, cy, maxIter, spanY, precision) ?: return null

            // Re-derive the period at the located nucleus rather than at the point the
            // search started from: that is where the orbit actually closes.
            var zx = BigDecimal.ZERO
            var zy = BigDecimal.ZERO
            var best = Double.MAX_VALUE
            var period = 0
            val scan = min(maxIter, MAX_NUCLEUS_PERIOD)
            for (i in 1..scan) {
                val nx = zx.multiply(zx, mc).subtract(zy.multiply(zy, mc), mc).add(n[0], mc)
                val ny = zx.multiply(zy, mc).multiply(TWO, mc).add(n[1], mc)
                zx = nx.round(mc); zy = ny.round(mc)
                val xd = zx.toDouble(); val yd = zy.toDouble()
                val m = xd * xd + yd * yd
                if (m > ESCAPE_SQ) break
                if (m < best) { best = m; period = i }
            }
            if (period < 1) return null

            // Doubles are enough from here: the estimate only needs an order of
            // magnitude, and the orbit values themselves are all of order one.
            val ncx = n[0].toDouble()
            val ncy = n[1].toDouble()
            var px = 0.0; var py = 0.0
            var lx = 1.0; var ly = 0.0
            var bx = 1.0; var by = 0.0
            for (i in 1 until period) {
                val t = px * px - py * py + ncx
                py = 2.0 * px * py + ncy
                px = t
                val nlx = 2.0 * (px * lx - py * ly)
                val nly = 2.0 * (px * ly + py * lx)
                lx = nlx; ly = nly
                val den = lx * lx + ly * ly
                if (den == 0.0 || !den.isFinite()) return null
                bx += lx / den
                by += -ly / den
            }
            // b * l^2, not b * l. Checked against components of known width: this
            // gives 1.0 for the cardioid, 0.5 for the period-2 disc and 0.019 for the
            // period-3 island on the antenna, whose measured width is about 0.017.
            val l2x = lx * lx - ly * ly
            val l2y = 2.0 * lx * ly
            val dx = bx * l2x - by * l2y
            val dy = bx * l2y + by * l2x
            val den = dx * dx + dy * dy
            if (den == 0.0 || !den.isFinite()) return null
            val size = 1.0 / kotlin.math.sqrt(den)
            if (!size.isFinite() || size <= 0.0) return null

            return Minibrot(n[0], n[1], period, size)
        }

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
