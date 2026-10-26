// SPDX-License-Identifier: AGPL-3.0-or-later
package com.fractal.deepzoom

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * Everything about a reference orbit that depends on the current zoom: the delta
 * scaling applied to the orbit values, and the BLA table whose radii are expressed in
 * those scaled units.
 *
 * Split out from ReferenceOrbit because these are cheap — doubles and floats, a few
 * milliseconds — while the orbit itself costs hundreds of milliseconds of BigDecimal
 * work. Zooming rebuilds this and keeps the orbit.
 */
class OrbitGpuData(
    /** Two floats per point: (2*Zx, 2*Zy). */
    val packed: FloatArray,
    val points: Int,
    /**
     * Four floats per point: 2Z as a mantissa and a binary exponent, (mx, my, k, 0)
     * with 2Z = (mx, my) * 2^k -- filled only where |2Z| < TINY_Z, zero elsewhere.
     *
     * Near a minibrot the reference passes very close to zero: about the square root
     * of the minibrot's size, so 1e-39 by a zoom of 1e78 and 1e-45 here and there
     * deeper. That is below float32's normal range (1.2e-38), so the plain float in
     * [packed] keeps a few bits or, on GPUs that flush denormals, none at all. Every
     * pixel that rebases there picks up the same wrong value, and whole regions of the
     * view come out wrong together -- rectangles and copies of nearby structure.
     * The shaders read this array only when the plain value is that small.
     */
    val tiny: FloatArray,
    val bla: BlaTable?,
    val scaleExp: Int,
    val maxC: Double,
    /** Built for the rescaled shader: BLA radii are logs. See ViewState.needsRescaled. */
    val rescaled: Boolean = false
) {
    val scale: Double get() = 2.0.pow(scaleExp)

    companion object {
        /** 2^-64. Must match TINY_Z in the shaders. */
        const val TINY_Z = 5.421010862427522E-20

        fun build(
            orbit: ReferenceOrbit,
            scaleExp: Int,
            maxC: Double,
            rescaled: Boolean = false
        ): OrbitGpuData {
            val scale = 2.0.pow(scaleExp)
            val points = orbit.count + 1
            val packed = FloatArray(points * 2)
            val tiny = FloatArray(points * 4)

            for (n in 0 until points) {
                val i = n * 2
                // Only the doubled orbit is stored. The scaled form the shader also
                // needs is that value times scale/2, and scale is a power of two, so
                // the shader recovers it with a multiply that only shifts an exponent
                // -- the same bits the fourth and third components used to hold.
                //
                // Worth the multiply because this is the hottest fetch in the loop:
                // one per pass, a couple of thousand passes per pixel at depth, and it
                // is dependent, so its latency is on the critical path. Halving the
                // texel from sixteen bytes to eight halves that traffic.
                val x = orbit.zx[n] * 2.0
                val y = orbit.zy[n] * 2.0
                packed[i] = x.toFloat()
                packed[i + 1] = y.toFloat()

                val mag = max(abs(x), abs(y))
                if (mag > 0.0 && mag < TINY_Z) {
                    // Mantissa in [1, 2) by max-norm, exponent kept separately.
                    val k = Math.getExponent(mag)
                    val o = n * 4
                    tiny[o] = Math.scalb(x, -k).toFloat()
                    tiny[o + 1] = Math.scalb(y, -k).toFloat()
                    tiny[o + 2] = k.toFloat()
                }
            }

            return OrbitGpuData(
                packed, points, tiny,
                BlaTable.build(orbit, maxC, scale, logRadius = rescaled),
                scaleExp, maxC, rescaled
            )
        }
    }
}
