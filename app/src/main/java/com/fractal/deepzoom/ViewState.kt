package com.fractal.deepzoom

import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Where the camera is, and how the shader's fixed-point-ish delta scaling is derived
 * from it.
 *
 * The centre is BigDecimal because at 1e-50 zoom a double cannot even name the
 * coordinate, let alone a pixel within it. The span stays a double: it only needs
 * exponent range, which doubles have in abundance.
 */
class ViewState {

    var centerX: BigDecimal = BigDecimal("-0.5")
    var centerY: BigDecimal = BigDecimal.ZERO
    var spanY: Double = 3.0
    var maxIter: Int = 512

    val mathContext: MathContext
        get() = MathContext(ReferenceOrbit.precisionFor(spanY))

    fun pixelSpan(viewHeightPx: Int): Double = spanY / viewHeightPx

    /** Depth relative to the default view, as a power of ten. */
    fun zoomDepth(): Double = log10(DEFAULT_SPAN / spanY)

    fun reset() {
        centerX = BigDecimal("-0.5")
        centerY = BigDecimal.ZERO
        spanY = DEFAULT_SPAN
        maxIter = 512
    }

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
     * Perturbation deltas are tiny in absolute terms — around 1e-50 at depth — which
     * is far below float32's smallest normal value. Every delta is therefore carried
     * in the shader multiplied by this power of two, chosen so the pixel-scale delta
     * lands at roughly 2^-80: comfortably above the denormal floor, and leaving room
     * for the delta to grow to bailout magnitude without overflowing.
     *
     * A power of two is used so the scaling is exact and introduces no rounding of
     * its own.
     */
    fun deltaScaleExponent(): Int {
        val log2Span = ln(spanY) / LN2
        return (-TARGET_EXPONENT - log2Span).roundToInt().coerceIn(0, MAX_SCALE_EXP)
    }

    fun deltaScale(): Double = 2.0.pow(deltaScaleExponent())

    /**
     * Smallest span the float32 delta range can still represent. Below this the
     * scaled deltas would overflow, and the next step would be a floatexp
     * (mantissa + separate exponent) representation in the shader.
     */
    fun minSpan(): Double = 2.0.pow(-TARGET_EXPONENT - MAX_SCALE_EXP)

    /** Perturbation is only worth its overhead once direct float32 starts failing. */
    fun needsPerturbation(): Boolean = spanY < DIRECT_LIMIT

    companion object {
        const val DEFAULT_SPAN = 3.0
        const val MAX_SPAN = 8.0

        /** Below this span, direct float32 iteration visibly quantises. */
        const val DIRECT_LIMIT = 1e-4

        private const val LN2 = 0.6931471805599453

        // Pixel-scale deltas are scaled to land near 2^-80: 46 binary orders above
        // the denormal floor at 2^-126.
        private const val TARGET_EXPONENT = 80

        // Largest scale factor. 2^120 leaves 8 binary orders before float32 overflow
        // once a delta grows to bailout magnitude.
        private const val MAX_SCALE_EXP = 120
    }
}

/** Offset of the view centre from a reference point, in plain doubles. Always small. */
fun ViewState.offsetFrom(orbit: ReferenceOrbit): DoubleArray {
    val mc = mathContext
    return doubleArrayOf(
        centerX.subtract(orbit.centerX, mc).toDouble(),
        centerY.subtract(orbit.centerY, mc).toDouble()
    )
}

/**
 * Whether a cached orbit can still serve the current view.
 *
 * Recomputing is expensive, so an orbit is kept as long as it remains inside the
 * visible region and the zoom has not moved far enough to change how many iterations
 * or digits are needed. A reference does not have to sit at the view centre to work.
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
