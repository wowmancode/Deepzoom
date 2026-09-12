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
        return (-TARGET_EXPONENT - log2Span).roundToInt().coerceIn(0, MAX_SCALE_EXP)
    }

    /**
     * Smallest representable span. The ceiling is float32's exponent range, not
     * precision — past this the scaled deltas overflow, and the next step would be a
     * floatexp representation in the shader.
     */
    fun minSpan(): Double = 2.0.pow(-TARGET_EXPONENT - MAX_SCALE_EXP)

    fun needsPerturbation(): Boolean = spanY < DIRECT_LIMIT

    companion object {
        const val DEFAULT_SPAN = 3.0
        const val MAX_SPAN = 8.0

        /** Below this span, direct float32 iteration visibly quantises. */
        const val DIRECT_LIMIT = 1e-4

        private const val LN2 = 0.6931471805599453
        private const val TARGET_EXPONENT = 80

        // 2^118 leaves headroom for the squared term at bailout magnitude without
        // overflowing float32.
        private const val MAX_SCALE_EXP = 118
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
    if (orbit.iterAtBuild < maxIter) return false

    val zoomRatio = orbit.spanAtBuild / spanY
    if (zoomRatio > 4.0 || zoomRatio < 0.25) return false

    val off = offsetFrom(orbit)
    val halfY = spanY * 0.5
    val halfX = halfY * aspect
    return abs(off[0]) < halfX * 0.7 && abs(off[1]) < halfY * 0.7
}
