// SPDX-License-Identifier: AGPL-3.0-or-later
package com.fractal.deepzoom

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.ceil

/**
 * Fixed-point complex iteration z -> z^2 + c, with no allocation per step.
 *
 * This replaces BigDecimal in the reference orbit and the nucleus search. BigDecimal
 * was slow for reasons that have nothing to do with arithmetic: every operation
 * allocates, and every `round(mc)` rounds in *decimal*, which means dividing by a power
 * of ten. The iterates here never leave |z| <= 2 before the escape test, so a fixed
 * binary point loses nothing, and each step becomes a few hundred integer multiply-adds
 * into preallocated arrays.
 *
 * Representation. A number is n limbs of 28 bits: limb 0 is the signed integer part,
 * limbs 1..n-1 are fraction digits in [0, 2^28). Negative values are two's complement
 * in that sense: -0.5 is (-1, 2^27). The value is sum(limb[k] * 2^(-28k)).
 *
 * Why 28 bits. A limb product is below 2^56, and a column of the truncated product
 * sums at most n of them (twice that for the doubled cross terms of a square), so for
 * n <= 64 a column stays below 2^63 without any intermediate carrying.
 *
 * Precision. Only product columns up to n are formed, and the truncation error that
 * leaves is about n units of the last limb. Two guard limbs sit below the requested
 * precision to absorb it.
 */
class FixedIter(val n: Int) {
    val x = LongArray(n)
    val y = LongArray(n)
    val cx = LongArray(n)
    val cy = LongArray(n)

    private val colA = LongArray(n + 1)
    private val colB = LongArray(n + 1)
    private val scratch = LongArray(n)

    init {
        require(n in 3..MAX_LIMBS) { "limb count $n out of range" }
    }

    /** Sets c and restarts the orbit at z = 0. */
    fun reset(cRe: BigDecimal, cIm: BigDecimal) {
        fromBigDecimal(cRe, cx)
        fromBigDecimal(cIm, cy)
        x.fill(0L)
        y.fill(0L)
    }

    fun copy(): FixedIter {
        val o = FixedIter(n)
        x.copyInto(o.x); y.copyInto(o.y)
        cx.copyInto(o.cx); cy.copyInto(o.cy)
        return o
    }

    /**
     * One step of z -> z^2 + c.
     *
     * Real part: x^2 - y^2 + cx, formed as one set of columns using the symmetry of
     * the squares, so the cross terms are computed once and doubled. Imaginary part:
     * 2xy + cy. Both read the old x and y and are written only after both are formed.
     */
    fun step() {
        val n = n
        val a = colA
        val b = colB
        java.util.Arrays.fill(a, 0L)
        java.util.Arrays.fill(b, 0L)

        for (i in 0 until n) {
            val xi = x[i]
            val yi = y[i]
            if (xi == 0L && yi == 0L) continue

            // Diagonal of the squares.
            val d = i + i
            if (d <= n) a[d] += xi * xi - yi * yi

            // Cross terms of the squares, i < j, doubled.
            val jMax = minOf(n - 1, n - i)
            var j = i + 1
            while (j <= jMax) {
                a[i + j] += 2L * (xi * x[j] - yi * y[j])
                j++
            }

            // x*y has no symmetry to use.
            j = 0
            while (j <= jMax) {
                b[i + j] += xi * y[j]
                j++
            }
        }

        for (k in 0 until n) {
            a[k] += cx[k]
            b[k] = 2L * b[k] + cy[k]
        }
        b[n] = 2L * b[n]

        normalize(a, x)
        normalize(b, y)
    }

    /** |z|^2 as a double. Accurate relative to the value even when z is tiny. */
    fun normSq(): Double {
        val re = xDouble()
        val im = yDouble()
        return re * re + im * im
    }

    fun xDouble(): Double = toDouble(x)
    fun yDouble(): Double = toDouble(y)

    /**
     * True once z has left any region a real orbit visits before the escape test.
     *
     * Only the nucleus search iterates without an escape test, and there a runaway orbit
     * would eventually overflow the integer limb. This lets it stop long before that.
     */
    fun runaway(): Boolean {
        val lx = x[0]
        val ly = y[0]
        return lx > RUNAWAY || lx < -RUNAWAY || ly > RUNAWAY || ly < -RUNAWAY
    }

    fun xBig(mc: MathContext): BigDecimal = toBigDecimal(x, mc)
    fun yBig(mc: MathContext): BigDecimal = toBigDecimal(y, mc)

    // --- Conversions -------------------------------------------------------------------

    private val fracBits = BITS * (n - 1)
    private val unit: BigDecimal = BigDecimal(BigInteger.ONE.shiftLeft(fracBits))

    fun fromBigDecimal(v: BigDecimal, out: LongArray) {
        var big = v.multiply(unit).setScale(0, RoundingMode.FLOOR).toBigIntegerExact()
        for (k in n - 1 downTo 1) {
            out[k] = big.toLong() and MASK
            big = big.shiftRight(BITS)
        }
        out[0] = big.toLong()
    }

    fun toBigDecimal(a: LongArray, mc: MathContext): BigDecimal {
        var big = BigInteger.valueOf(a[0])
        for (k in 1 until n) big = big.shiftLeft(BITS).add(BigInteger.valueOf(a[k]))
        // Division by a power of two always terminates in decimal, so this is exact.
        return BigDecimal(big).divide(unit).round(mc)
    }

    /**
     * Correctly scaled even for tiny values. Summing the limbs from the top would lose
     * everything for a small negative number, whose two's-complement limbs are all near
     * the maximum and cancel, so negatives are negated first and the sum starts at the
     * first non-zero limb.
     */
    fun toDouble(a: LongArray): Double {
        if (a[0] >= 0L) return magnitude(a)
        for (k in 0 until n) scratch[k] = -a[k]
        normalizeInPlace(scratch)
        return -magnitude(scratch)
    }

    private fun magnitude(a: LongArray): Double {
        var k = 0
        while (k < n && a[k] == 0L) k++
        if (k == n) return 0.0
        var v = a[k].toDouble()
        if (k + 1 < n) v += a[k + 1] * INV_LIMB
        if (k + 2 < n) v += a[k + 2] * INV_LIMB2
        if (k + 3 < n) v += a[k + 3] * INV_LIMB3
        return Math.scalb(v, -BITS * k)
    }

    /**
     * Carries columns 0..n into limbs 0..n-1. Column n only contributes its carry: it
     * is the first column below the kept precision, and it is there so the truncation
     * rounds a little better than dropping it outright would.
     *
     * Shifts are arithmetic, so carries are floors and every fraction limb lands in
     * [0, 2^28) whatever the sign.
     */
    private fun normalize(col: LongArray, out: LongArray) {
        var carry = col[n] shr BITS
        for (k in n - 1 downTo 1) {
            val v = col[k] + carry
            out[k] = v and MASK
            carry = v shr BITS
        }
        out[0] = col[0] + carry
    }

    private fun normalizeInPlace(a: LongArray) {
        var carry = 0L
        for (k in n - 1 downTo 1) {
            val v = a[k] + carry
            a[k] = v and MASK
            carry = v shr BITS
        }
        a[0] += carry
    }

    companion object {
        const val BITS = 28
        const val MASK = (1L shl BITS) - 1
        const val MAX_LIMBS = 64
        private const val RUNAWAY = 1024L

        private const val INV_LIMB = 1.0 / (1L shl 28)
        private const val INV_LIMB2 = INV_LIMB * INV_LIMB
        private const val INV_LIMB3 = INV_LIMB2 * INV_LIMB

        private const val BITS_PER_DIGIT = 3.3219280948873626

        /**
         * Limbs for a given decimal precision: one integer limb, enough fraction limbs
         * for the digits, and two guard limbs for truncation error.
         */
        fun limbsFor(digits: Int): Int =
            (1 + ceil(digits * BITS_PER_DIGIT / BITS).toInt() + 2).coerceIn(3, MAX_LIMBS)
    }
}
