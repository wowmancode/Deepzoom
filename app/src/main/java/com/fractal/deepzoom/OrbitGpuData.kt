// SPDX-License-Identifier: AGPL-3.0-or-later
package com.fractal.deepzoom

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
    /** Four floats per point: (2*Zx, 2*Zy, Zx*scale, Zy*scale). */
    val packed: FloatArray,
    val points: Int,
    val bla: BlaTable?,
    val scaleExp: Int,
    val maxC: Double
) {
    val scale: Double get() = 2.0.pow(scaleExp)

    companion object {
        fun build(orbit: ReferenceOrbit, scaleExp: Int, maxC: Double): OrbitGpuData {
            val scale = 2.0.pow(scaleExp)
            val points = orbit.count + 1
            val packed = FloatArray(points * 2)

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
                packed[i] = (orbit.zx[n] * 2.0).toFloat()
                packed[i + 1] = (orbit.zy[n] * 2.0).toFloat()
            }

            return OrbitGpuData(
                packed, points,
                BlaTable.build(orbit, maxC, scale),
                scaleExp, maxC
            )
        }
    }
}
