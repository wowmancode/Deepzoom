// SPDX-License-Identifier: AGPL-3.0-or-later
package com.fractal.deepzoom

import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * A single point's orbit, iterated in arbitrary precision on the CPU.
 *
 * The iteration runs in fixed-point (FixedIter), not BigDecimal. BigDecimal's cost here
 * was allocation and decimal rounding rather than arithmetic, and neither is needed:
 * the iterates are bounded, so a binary point that never moves serves exactly.
 *
 * Two properties still decide how often this runs at all. The orbit depends on the
 * centre and the iteration count, but not on the zoom: zoom only sets how many digits
 * are needed, and an orbit computed with spare guard digits stays valid as you zoom in,
 * and stays valid forever as you zoom out. And the iteration is a plain forward
 * recurrence, so raising the detail slider extends the existing orbit instead of
 * restarting it.
 *
 * A reference at a minibrot nucleus adds a third: its orbit returns to zero after one
 * period, so only that one period is computed and stored, whatever the iteration
 * count. The shader already rebases a pixel when it reaches the end of the stored
 * orbit, carrying its full value forward, and rebasing is exact at any index — so
 * ending the orbit at the period changes no pixel, it only stops paying for the same
 * cycle over and over.
 *
 * Everything that does depend on zoom — the delta scaling and the BLA radii — lives in
 * OrbitGpuData, which is cheap to rebuild.
 */
class ReferenceOrbit private constructor(
    val centerX: BigDecimal,
    val centerY: BigDecimal,
    var zx: DoubleArray,
    var zy: DoubleArray,
    /** Valid point indices are 0..count. */
    var count: Int,
    var iterBuilt: Int,
    val spanAtBuild: Double,
    /** Holds z at index `count`, so extension resumes exactly where it stopped. */
    private val state: FixedIter,
    private var escaped: Boolean,
    /** The nucleus period when the reference is a nucleus, else 0. */
    val period: Int,
    /** True once the stored orbit covers a whole period and needs nothing more. */
    private var periodic: Boolean
) {
    /**
     * Continues the orbit to a higher iteration count.
     *
     * The saved fixed-point state is what makes this possible; without it a nudge of
     * the detail slider would mean paying the full cost again.
     *
     * Invariant: `state` holds z_count, and zx/zy[0..count] are already written. Each
     * pass computes the next iterate, stores it, then tests it — so extension picks up
     * at count + 1 and never rewrites an index a snapshot may be reading.
     *
     * (The BigDecimal version saved the iterate *after* the last stored one but resumed
     * writing at the last stored index, so every extension shifted the rest of the
     * orbit by one place and overwrote a value a live snapshot was using.)
     */
    fun extendTo(newMaxIter: Int) {
        if (newMaxIter <= iterBuilt) return
        if (escaped || periodic) {
            iterBuilt = newMaxIter
            return
        }

        val limit = if (period > 0) min(newMaxIter, period) else newMaxIter
        if (zx.size < limit + 2) {
            zx = zx.copyOf(limit + 2)
            zy = zy.copyOf(limit + 2)
        }

        var n = count
        while (n < limit) {
            state.step()
            n++
            val re = state.xDouble()
            val im = state.yDouble()
            zx[n] = re
            zy[n] = im
            if (re * re + im * im > ESCAPE_SQ) {
                escaped = true
                break
            }
        }

        count = n
        if (!escaped && period > 0 && count == period) periodic = true
        iterBuilt = newMaxIter
    }

    /**
     * A stable view of the orbit as it stands now.
     *
     * extendTo mutates, and the render thread holds onto whatever it was last given,
     * so the builder hands over an immutable snapshot instead. The arrays are shared
     * rather than copied: extendTo only writes past the old count and reallocates
     * rather than overwriting, so existing indices never change under a reader. The
     * fixed-point state is small and is copied.
     */
    fun snapshot(): ReferenceOrbit = ReferenceOrbit(
        centerX, centerY, zx, zy, count, iterBuilt, spanAtBuild,
        state.copy(), escaped, period, periodic
    )

    /** A located nucleus and the period of its cycle. */
    class Nucleus(val cx: BigDecimal, val cy: BigDecimal, val period: Int)

    companion object {
        private const val ESCAPE_SQ = 4.0

        /**
         * Guard digits beyond what the current zoom strictly needs. Generous on
         * purpose: each spare digit is a decade of zooming in that reuses the orbit
         * rather than rebuilding it.
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
         * Newton stops once its step is this small relative to the span.
         *
         * It converges quadratically, so the step before this one was already around
         * 1e-10 of the span and the nucleus is settled far below any pixel. Running
         * the remaining fixed steps cost a full period each and moved the point by
         * less than the orbit's own precision.
         */
        private const val NEWTON_TOL = 1e-20

        /** Derivative is carried as mantissa * 2^exp; rescaled past this. */
        private const val DER_RESCALE = 512
        private val DER_LIMIT = Math.scalb(1.0, DER_RESCALE)

        /**
         * Finds the nearest minibrot nucleus, or null if there isn't a useful one.
         *
         * A periodic point makes a better reference than an arbitrary one: the orbit
         * returns close to zero every period, which keeps BLA coefficients small and
         * their validity radii large, so pixels take longer jumps — and the orbit only
         * has to be computed for one period.
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
        ): Nucleus? {
            val mc = MathContext(precision)
            val it = FixedIter(FixedIter.limbsFor(precision))
            it.reset(cx, cy)

            var best = Double.MAX_VALUE
            var period = 0
            val scan = min(maxIter, MAX_NUCLEUS_PERIOD)
            for (n in 1..scan) {
                it.step()
                val m = it.normSq()
                if (m > ESCAPE_SQ) break
                if (m < best) { best = m; period = n }
            }
            if (period < 1) return null

            val c = newtonFixed(it, cx, cy, period, spanY, mc)
                ?: newtonBigDecimal(cx, cy, period, mc)
                ?: return null

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
            val offX = c[0].subtract(cx, mc).toDouble()
            val offY = c[1].subtract(cy, mc).toDouble()
            if (!offX.isFinite() || !offY.isFinite()) return null
            if (hypot(offX, offY) > spanY * NUCLEUS_ACCEPT) return null

            return Nucleus(c[0], c[1], exactPeriod(it, c[0], c[1], period))
        }

        /**
         * Newton on f^p(0, c) = 0 with the iterate in fixed point and the derivative
         * in doubles.
         *
         * The derivative only sets the step direction and length, so its 53 bits are
         * plenty: an error of relative size e in it makes each step cancel all but e of
         * the remaining error instead of squaring it, which from a start inside the view
         * still reaches the tolerance in a step or two more. The iterate, whose value
         * at step p *is* the residual, keeps full precision. The derivative grows like
         * the minibrot's inverse size, so it carries its own binary exponent.
         *
         * Returns null if the orbit runs away, which fixed point cannot follow; the
         * caller then falls back to the BigDecimal version, which can.
         */
        private fun newtonFixed(
            it: FixedIter,
            cx0: BigDecimal,
            cy0: BigDecimal,
            period: Int,
            spanY: Double,
            mc: MathContext
        ): Array<BigDecimal>? {
            var ccx = cx0
            var ccy = cy0
            val tol = spanY * NEWTON_TOL

            for (step in 0 until NEWTON_STEPS) {
                it.reset(ccx, ccy)
                var dx = 0.0
                var dy = 0.0
                var dExp = 0

                for (i in 1..period) {
                    // d_i = 2 z_{i-1} d_{i-1} + 1, the derivative with respect to c.
                    val zr = it.xDouble()
                    val zi = it.yDouble()
                    val one = Math.scalb(1.0, -dExp)
                    val ndx = 2.0 * (zr * dx - zi * dy) + one
                    val ndy = 2.0 * (zr * dy + zi * dx)
                    dx = ndx
                    dy = ndy
                    if (abs(dx) > DER_LIMIT || abs(dy) > DER_LIMIT) {
                        dx = Math.scalb(dx, -DER_RESCALE)
                        dy = Math.scalb(dy, -DER_RESCALE)
                        dExp += DER_RESCALE
                    }

                    it.step()
                    if (it.runaway()) return null
                }

                // Bring d's mantissa to about one first: it may sit anywhere up to
                // 2^512, and squaring that for the denominator would overflow.
                val dMax = maxOf(abs(dx), abs(dy))
                if (dMax > 0.0) {
                    val k = Math.getExponent(dMax)
                    dx = Math.scalb(dx, -k)
                    dy = Math.scalb(dy, -k)
                    dExp += k
                }

                // s = z_p / d, with d = (dx, dy) * 2^dExp.
                val zr = it.xDouble()
                val zi = it.yDouble()
                val den = dx * dx + dy * dy
                if (den == 0.0 || !den.isFinite()) return null
                val sx = Math.scalb((zr * dx + zi * dy) / den, -dExp)
                val sy = Math.scalb((zi * dx - zr * dy) / den, -dExp)
                if (!sx.isFinite() || !sy.isFinite()) return null

                ccx = ccx.subtract(BigDecimal(sx), mc)
                ccy = ccy.subtract(BigDecimal(sy), mc)

                if (hypot(sx, sy) <= tol) break
            }
            return arrayOf(ccx, ccy)
        }

        /**
         * The original all-BigDecimal Newton. Kept only for the rare start whose orbit
         * leaves the fixed-point range partway through a period, which BigDecimal can
         * follow because its numbers simply grow.
         */
        private fun newtonBigDecimal(
            cx: BigDecimal,
            cy: BigDecimal,
            period: Int,
            mc: MathContext
        ): Array<BigDecimal>? {
            val two = BigDecimal(2)
            var ccx = cx
            var ccy = cy
            repeat(NEWTON_STEPS) {
                var zx = BigDecimal.ZERO
                var zy = BigDecimal.ZERO
                var dx = BigDecimal.ZERO
                var dy = BigDecimal.ZERO

                for (i in 1..period) {
                    val ndx = two.multiply(
                        zx.multiply(dx, mc).subtract(zy.multiply(dy, mc), mc), mc
                    ).add(BigDecimal.ONE, mc)
                    val ndy = two.multiply(
                        zx.multiply(dy, mc).add(zy.multiply(dx, mc), mc), mc
                    )
                    val nzx = zx.multiply(zx, mc).subtract(zy.multiply(zy, mc), mc).add(ccx, mc)
                    val nzy = two.multiply(zx.multiply(zy, mc), mc).add(ccy, mc)

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
            return arrayOf(ccx, ccy)
        }

        /**
         * The period of the cycle Newton actually landed on.
         *
         * Newton on f^p can converge to a nucleus whose period q divides p, since that
         * also zeroes f^p. The cycle closes at q, so that is the orbit length to store
         * and the period to report. Found as the first divisor of p at which |z| is
         * within a few orders of the smallest value in the period.
         */
        private fun exactPeriod(it: FixedIter, cx: BigDecimal, cy: BigDecimal, p: Int): Int {
            val norms = DoubleArray(p + 1)
            it.reset(cx, cy)
            var best = Double.MAX_VALUE
            for (n in 1..p) {
                it.step()
                val m = it.normSq()
                norms[n] = m
                if (m < best) best = m
                if (m > ESCAPE_SQ) return p
            }
            val threshold = best * 1e4
            for (q in 1..p) {
                if (p % q == 0 && norms[q] <= threshold) return q
            }
            return p
        }

        /**
         * Whether a nucleus reference may be cut off after one period.
         *
         * At the end of the stored orbit every pixel rebases onto its full value
         * Z_p + dz. That is exact in arithmetic, but in float the pixel's information
         * is in dz, so Z_p has to be negligible beside it -- which only holds if the
         * orbit really returns to zero. findNucleus cannot promise that: when the
         * working precision cannot resolve the minibrot, Newton does not land on it,
         * and exactPeriod's relative test then accepts a period as short as 1 (seen at
         * c ~ i with |Z_1| ~ 1 at 1e-30 and 1e-50 spans). Truncating there throws
         * away every pixel's delta at the first rebase and the view renders as solid
         * interior.
         *
         * dz at step p is about |dZ_p/dc| times the pixel's offset, so this asks that
         * |Z_p| sit well below that for a pixel of the finest export (span / 4096).
         * An orbit that escapes within the period is never truncated, so it passes.
         */
        fun closes(n: Nucleus, spanY: Double, precision: Int): Boolean {
            val it = FixedIter(FixedIter.limbsFor(precision))
            it.reset(n.cx, n.cy)
            var dx = 0.0
            var dy = 0.0
            var dExp = 0
            for (i in 1..n.period) {
                val zr = it.xDouble()
                val zi = it.yDouble()
                val one = Math.scalb(1.0, -dExp)
                val ndx = 2.0 * (zr * dx - zi * dy) + one
                val ndy = 2.0 * (zr * dy + zi * dx)
                dx = ndx
                dy = ndy
                if (abs(dx) > DER_LIMIT || abs(dy) > DER_LIMIT) {
                    dx = Math.scalb(dx, -DER_RESCALE)
                    dy = Math.scalb(dy, -DER_RESCALE)
                    dExp += DER_RESCALE
                }
                it.step()
                if (it.normSq() > ESCAPE_SQ) return true
            }
            val zAbs = hypot(it.xDouble(), it.yDouble())
            if (zAbs == 0.0) return true
            val dAbs = hypot(dx, dy)
            if (dAbs == 0.0) return false
            val log2Z = kotlin.math.ln(zAbs) / LN2
            val log2Spread = kotlin.math.ln(dAbs) / LN2 + dExp +
                kotlin.math.ln(spanY / CLOSE_PIXELS) / LN2
            return log2Z <= log2Spread - CLOSE_MARGIN_BITS
        }

        private const val LN2 = 0.6931471805599453
        private const val CLOSE_PIXELS = 4096.0
        /** |Z_p| must sit this many binary orders below one pixel's spread. */
        private const val CLOSE_MARGIN_BITS = 10.0

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
            // findNucleus reports the period of the cycle it landed on, measured at the
            // nucleus itself, so there is no second scan here.
            val n = findNucleus(cx, cy, maxIter, spanY, precision) ?: return null
            val period = n.period

            // The estimate only needs an order of magnitude, so l and b are doubles.
            // The orbit feeding them is not: it comes from the full-precision iterate.
            // Iterating in doubles from the nucleus rounded to a double worked only
            // while the nucleus was distinguishable in a double. Near c = i the rounded
            // point is i itself from about 1e-16 on, and the estimate drifted from the
            // true size by a factor of about 2.7 by 1e-17 (period 45) through 1e-119
            // (period 159) -- enough to stop the minibrot search at the wrong depth.
            val it = FixedIter(FixedIter.limbsFor(precision))
            it.reset(n.cx, n.cy)
            var px: Double
            var py: Double
            var lx = 1.0; var ly = 0.0
            var bx = 1.0; var by = 0.0
            for (i in 1 until period) {
                it.step()
                px = it.xDouble()
                py = it.yDouble()
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

            return Minibrot(n.cx, n.cy, period, size)
        }

        /**
         * Builds an orbit. With a period, the reference is taken to be a nucleus and
         * only one period is computed; the shader's rebase at the end of the stored
         * orbit covers every iteration after it.
         */
        fun compute(
            cx: BigDecimal,
            cy: BigDecimal,
            maxIter: Int,
            spanY: Double,
            period: Int = 0
        ): ReferenceOrbit {
            val precision = precisionFor(spanY)
            val state = FixedIter(FixedIter.limbsFor(precision))
            state.reset(cx, cy)
            val len = if (period > 0) min(maxIter, period) else maxIter
            val orbit = ReferenceOrbit(
                cx, cy,
                DoubleArray(len + 2), DoubleArray(len + 2),
                0, 0, spanY, state, false, period, false
            )
            orbit.extendTo(maxIter)
            return orbit
        }
    }
}
