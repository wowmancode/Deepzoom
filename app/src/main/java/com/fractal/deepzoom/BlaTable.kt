package com.fractal.deepzoom

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Bivariate linear approximation: a table that lets a pixel skip many iterations at
 * once.
 *
 * When the delta z is small and the reference Z is not near a critical point, the
 * squared term in
 *
 *     z -> 2*Z*z + z^2 + c
 *
 * is negligible, leaving a map that is linear in both z and c — hence "bivariate
 * linear". Linear maps compose, so a run of consecutive iterations collapses into a
 * single `z -> A*z + B*c` with a radius r inside which the approximation holds.
 *
 * The table holds those composites at every power-of-two length: level 0 is one step,
 * level 1 is two, and so on. A pixel at depth typically has a delta many orders of
 * magnitude below the reference, so it can take the longest jumps available and cover
 * thousands of iterations in a handful of steps. That is where the speedup comes from,
 * and it grows with depth — exactly the opposite of how the naive loop behaves.
 *
 * Formulation follows Zhuoran's, as written up by Claude Heiland-Allen.
 *
 * Merging neighbours x then y:
 *     A = Ay*Ax,  B = Ay*Bx + By,  r = min(rx, max(0, (ry - |Bx|*maxC) / |Ax|))
 *
 * Radii are non-increasing as levels merge, which is what makes lookup cheap: if the
 * one-step BLA at an index is invalid, every longer one starting there is invalid too,
 * so the shader can climb levels and stop at the first failure.
 */
class BlaTable(
    /** Four floats per entry: (Ax, Ay, Bx, By). */
    val ab: FloatArray,
    /** Validity radius per entry, pre-multiplied by the delta scale. */
    val radius: FloatArray,
    val levelOffset: IntArray,
    val levelCount: IntArray,
    val levels: Int,
    val total: Int
) {
    companion object {
        /**
         * Tolerance for dropping the squared term.
         *
         * The published formulation uses hardware epsilon (2^-24 for float32). Tested
         * against high-precision ground truth that turned out to be too permissive:
         * around 1e-16 to 1e-20 span it put a few percent of escaping pixels wrong by
         * up to 340 iterations, because error in a delta amplifies chaotically over
         * thousands of iterations near the boundary.
         *
         * 2^-44 was exact on every case tested, from 1e-10 down to 1e-55. It costs
         * almost nothing where it matters: skipping drops from 100% to 100% at 1e-40,
         * and from 99.9% to 89% at 1e-28. The loss is concentrated at shallow depths
         * where iteration counts are low and BLA was never the bottleneck.
         */
        private const val EPS = 5.684341886080802e-14  // 2^-44

        /** Beyond this, coefficients are treated as unusable rather than stored as inf. */
        private const val COEF_LIMIT = 1e30

        const val MAX_LEVELS = 24

        fun build(orbit: ReferenceOrbit, maxC: Double, scale: Double): BlaTable? {
            // Level 0 starts at iteration 1: iteration 0 sits on the critical point,
            // where the validity radius is always zero.
            val count0 = orbit.count - 1
            if (count0 < 1) return null

            val counts = ArrayList<Int>()
            var c = count0
            while (c >= 1 && counts.size < MAX_LEVELS) {
                counts.add(c)
                if (c == 1) break
                c /= 2
            }
            val levels = counts.size
            val offsets = IntArray(levels)
            var total = 0
            for (k in 0 until levels) {
                offsets[k] = total
                total += counts[k]
            }

            val ab = FloatArray(total * 4)
            val radius = FloatArray(total)

            // Doubles throughout: the merge chain is thousands deep, and float would
            // compound its rounding the whole way down.
            val ax = DoubleArray(total)
            val ay = DoubleArray(total)
            val bx = DoubleArray(total)
            val by = DoubleArray(total)
            val rr = DoubleArray(total)

            // Level 0: a single perturbation step is A = 2Z, B = 1.
            for (j in 0 until counts[0]) {
                val m = j + 1
                val zxm = orbit.zx[m]
                val zym = orbit.zy[m]
                val absZ = hypot(zxm, zym)
                val a = 2.0 * absZ

                ax[j] = 2.0 * zxm
                ay[j] = 2.0 * zym
                bx[j] = 1.0
                by[j] = 0.0
                // |Z| is halved as a safety margin, following the reference write-up.
                rr[j] = max(0.0, EPS * (absZ * 0.5 - maxC) / (a + 1.0))
            }

            for (k in 1 until levels) {
                val prev = offsets[k - 1]
                val cur = offsets[k]
                for (j in 0 until counts[k]) {
                    val xi = prev + 2 * j
                    val yi = prev + 2 * j + 1
                    val di = cur + j

                    val axy = ax[yi]; val ayy = ay[yi]
                    val axx = ax[xi]; val ayx = ay[xi]
                    val bxx = bx[xi]; val byx = by[xi]

                    // A = Ay * Ax
                    ax[di] = axy * axx - ayy * ayx
                    ay[di] = axy * ayx + ayy * axx

                    // B = Ay * Bx + By
                    bx[di] = axy * bxx - ayy * byx + bx[yi]
                    by[di] = axy * byx + ayy * bxx + by[yi]

                    val absAx = hypot(axx, ayx)
                    val absBx = hypot(bxx, byx)
                    val inner = if (absAx > 0.0) (rr[yi] - absBx * maxC) / absAx else 0.0
                    rr[di] = min(rr[xi], max(0.0, inner))
                }
            }

            for (i in 0 until total) {
                val a = hypot(ax[i], ay[i])
                val b = hypot(bx[i], by[i])
                val rScaled = rr[i] * scale

                // Coefficients grow without bound as levels merge. Where they leave
                // float range the entry is simply disabled: its radius would have been
                // vanishing anyway, so nothing useful is lost.
                val usable = a.isFinite() && b.isFinite() &&
                    a < COEF_LIMIT && b < COEF_LIMIT &&
                    rScaled.isFinite() && rScaled > 0.0

                val o = i * 4
                if (usable) {
                    ab[o] = ax[i].toFloat()
                    ab[o + 1] = ay[i].toFloat()
                    ab[o + 2] = bx[i].toFloat()
                    ab[o + 3] = by[i].toFloat()
                    radius[i] = rScaled.toFloat()
                } else {
                    radius[i] = 0f
                }
            }

            return BlaTable(
                ab, radius, offsets,
                IntArray(levels) { counts[it] },
                levels, total
            )
        }

        /** Largest |dc| any pixel can have, with margin for orbit reuse across zooms. */
        /**
         * Variant accounting for a reference that is not at the view centre. A nucleus
         * reference sits off-centre by design, and every pixel's |dc| grows by that
         * offset — leaving it out would make the radii optimistic and the image wrong.
         */
        fun maxCFor(spanY: Double, aspect: Double, offset: Double): Double =
            maxCFor(spanY, aspect) + offset * 2.0

        fun maxCFor(spanY: Double, aspect: Double): Double {
            val halfDiag = 0.5 * spanY * hypot(1.0, abs(aspect))
            // Tables are reused while the view grows, so this is deliberately
            // overstated: a larger bound shrinks radii, which costs a little speed but
            // never correctness, and it means zooming out 16x before a rebuild.
            return halfDiag * 16.0
        }
    }
}
