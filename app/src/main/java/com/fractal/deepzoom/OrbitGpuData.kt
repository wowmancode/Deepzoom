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
            val packed = FloatArray(points * 4)

            for (n in 0 until points) {
                val i = n * 4
                val x = orbit.zx[n]
                val y = orbit.zy[n]
                // Both forms are needed every iteration — the doubled value for the
                // 2*Z*d term, the scaled value to reconstruct the true position — so
                // precomputing both removes two multiplies from the inner loop.
                packed[i] = (x * 2.0).toFloat()
                packed[i + 1] = (y * 2.0).toFloat()
                packed[i + 2] = (x * scale).toFloat()
                packed[i + 3] = (y * scale).toFloat()
            }

            return OrbitGpuData(
                packed, points,
                BlaTable.build(orbit, maxC, scale),
                scaleExp, maxC
            )
        }
    }
}
