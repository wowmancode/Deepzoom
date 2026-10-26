// SPDX-License-Identifier: AGPL-3.0-or-later
package com.fractal.deepzoom

import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

class ViewState {

    var centerX: BigDecimal = BigDecimal("-0.5")
    var centerY: BigDecimal = BigDecimal.ZERO
    var spanY: Double = DEFAULT_SPAN
    var maxIter: Int = 512

    val mathContext: MathContext
        get() = MathContext(ReferenceOrbit.precisionFor(spanY))

    fun zoomDepth(): Double = log10(DEFAULT_SPAN / spanY)

    fun reset() {
        centerX = BigDecimal("-0.5")
        centerY = BigDecimal.ZERO
        spanY = DEFAULT_SPAN
        maxIter = 512
    }

    fun copyFrom(other: ViewState) {
        centerX = other.centerX
        centerY = other.centerY
        spanY = other.spanY
        maxIter = other.maxIter
    }

    fun snapshot(): ViewState = ViewState().also { it.copyFrom(this) }

    fun panBy(dxComplex: Double, dyComplex: Double) {
        val mc = mathContext
        centerX = centerX.add(BigDecimal(dxComplex), mc)
        centerY = centerY.add(BigDecimal(dyComplex), mc)
    }

    fun zoomBy(factor: Double) {
        spanY = (spanY / factor).coerceIn(minSpan(), MAX_SPAN)
    }

    // --- Delta scaling ---------------------------------------------------------------

    /**
     * Perturbation deltas are around 1e-50 at depth, far below float32's smallest
     * normal value. Every delta is carried pre-multiplied by this power of two,
     * chosen to place pixel-scale deltas near 2^-80: 46 binary orders above the
     * denormal floor, with room above for a delta to grow to bailout magnitude.
     *
     * A power of two keeps the scaling exact.
     */
    fun deltaScaleExponent(): Int {
        val log2Span = ln(spanY) / LN2
        val ideal = -TARGET_EXPONENT - log2Span
        // Quantised so the exponent only changes every 256x of zoom. Each change forces
        // the orbit to be repacked and the BLA table rebuilt, and there is ample range
        // headroom to absorb being a few binary orders off the ideal.
        val stepped = (ideal / SCALE_QUANTUM).roundToInt() * SCALE_QUANTUM
        return stepped.coerceIn(0, if (needsRescaled()) MAX_RESCALED_SCALE_EXP else MAX_SCALE_EXP)
    }

    /**
     * Whether this view is past what one shared delta scale can reach.
     *
     * The scaled path carries every delta in float units of one 2^k, and k is capped
     * because the reference orbit and bailout are held in the same units. Below
     * FAST_MIN_SPAN the rescaled shader takes over: each pixel carries its own
     * exponent, so the cap no longer applies. Above it nothing changes, and the fast
     * path is exactly what it was.
     */
    fun needsRescaled(): Boolean = rescaledFor(spanY)

    /**
     * Smallest representable span.
     *
     * The shader no longer sets this: the rescaled path's per-pixel exponent has no
     * floor worth mentioning. What does is the CPU side, which holds the span, the
     * offset from the reference and the BLA radii as doubles — and doubles end at
     * 2.2e-308. This stops about eighteen decades short of that, so a pixel (span over
     * a few thousand rows) and the radii derived from it stay normal doubles.
     */
    fun minSpan(): Double = 2.0.pow(-TARGET_EXPONENT - MAX_RESCALED_SCALE_EXP)

    /**
     * Whether this view, drawn `heightPx` pixels tall, is past what float32 resolves.
     *
     * A span alone cannot answer this. What matters is the pixel spacing against the
     * float spacing of the coordinates themselves: at 1e-4 across 2400 rows a pixel is
     * about 4e-8 wide, while a float near -1.75 can only step by 1.2e-7, so three
     * neighbouring pixels share one c and the image goes blocky well before the old
     * span threshold. An 8640-row export at the same span is far worse.
     *
     * DIRECT_LIMIT stays as a floor, so no view goes direct deeper than it used to.
     */
    fun needsPerturbation(heightPx: Int): Boolean {
        if (spanY < DIRECT_LIMIT) return true
        val pixel = spanY / heightPx.coerceAtLeast(1)
        val magnitude = maxOf(abs(centerX.toDouble()), abs(centerY.toDouble())) + spanY
        return !directResolves(pixel, magnitude)
    }

    companion object {
        const val DEFAULT_SPAN = 3.0

        /**
         * Highest iteration limit, 2^20. What bounds it is memory, not the shader: the
         * reference orbit and its BLA table grow with the limit, and the BLA build
         * holds about 40 bytes per entry in doubles while merging (roughly 2 entries
         * per iteration), so 2^20 costs on the order of 130 MB at peak. The GPU tables
         * at this size stay within 2048 rows of 1024, the minimum texture height any
         * OpenGL ES 3 device must support.
         */
        const val MAX_ITER = 1 shl 20
        const val MAX_SPAN = 8.0

        /** Below this span, direct float32 iteration is never used. */
        const val DIRECT_LIMIT = 1e-4

        /**
         * Float steps a pixel must span for the direct path. One would give every
         * pixel its own c, but rounding c and every iterate by up to half a step each
         * leaves visible jitter at that spacing; four keeps it below a quarter pixel.
         */
        private const val DIRECT_ULPS = 4.0

        /**
         * Whether plain float32 separates pixels `pixel` apart at coordinates of about
         * `magnitude`. Shared by the view and the strip, which measure pixels
         * differently but hit the same float grid.
         */
        fun directResolves(pixel: Double, magnitude: Double): Boolean =
            pixel >= DIRECT_ULPS * Math.ulp(magnitude.toFloat()).toDouble()

        private const val LN2 = 0.6931471805599453
        /**
         * Where the view's half-diagonal sits, as a power of two, in scaled units.
         *
         * This is the only thing standing between the renderer and another eight
         * decades of depth, and it was set with far more room than it needs. It has to
         * leave a pixel's delta a normal float: a pixel sits this many powers below one,
         * plus the log of the grid width, and 2^-126 is where normals end. At 80, with
         * a 4096-wide strip, a pixel landed at 2^-92 -- thirty-four powers of spare
         * range that no pixel was ever going to use.
         *
         * At 108 a pixel lands at 2^-120, six powers clear, and the floor moves from
         * 6.2e-61 to 2.3e-69. The top end is untouched: that is MAX_SCALE_EXP's
         * business, and it is genuinely at its limit.
         *
         * tools/validate_exponent_budget.py works both ends out.
         */
        private const val TARGET_EXPONENT = 108
        private const val SCALE_QUANTUM = 8

        // 2^118 leaves headroom for the squared term at bailout magnitude without
        // overflowing float32.
        private const val MAX_SCALE_EXP = 120

        /**
         * Scale cap for the rescaled path. There the scale only places the pixel
         * offsets in float range on the way in -- the shader immediately moves them
         * into its own exponent -- so it can grow as far as the doubles computing it
         * allow. minSpan works out to 2^-964, about 1.3e-290.
         */
        private const val MAX_RESCALED_SCALE_EXP = 856

        /**
         * Deepest span the scaled path serves: the floor it has always had. Below it
         * the rescaled path is used.
         */
        val FAST_MIN_SPAN: Double = 2.0.pow(-TARGET_EXPONENT - MAX_SCALE_EXP)

        fun rescaledFor(spanY: Double): Boolean = spanY < FAST_MIN_SPAN
    }
}

fun ViewState.offsetFrom(orbit: ReferenceOrbit): DoubleArray {
    val mc = mathContext
    return doubleArrayOf(
        centerX.subtract(orbit.centerX, mc).toDouble(),
        centerY.subtract(orbit.centerY, mc).toDouble()
    )
}

/**
 * Whether a cached orbit still serves this view. A reference does not have to sit at
 * the view centre to work, so orbits survive panning and modest zooming — which
 * matters because rebuilding one at depth is the slowest thing the app does.
 */
fun ViewState.canReuse(orbit: ReferenceOrbit?, aspect: Double): Boolean {
    if (orbit == null) return false

    // Zooming out never invalidates an orbit: it has more digits than it needs. Only
    // zooming in past the guard digits does.
    if (spanY < orbit.spanAtBuild * ReferenceOrbit.ZOOM_IN_MARGIN) return false

    val off = offsetFrom(orbit)
    val halfY = spanY * 0.5
    val halfX = halfY * aspect
    return abs(off[0]) < halfX * 0.7 && abs(off[1]) < halfY * 0.7
}
