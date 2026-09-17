// SPDX-License-Identifier: AGPL-3.0-or-later
package com.fractal.deepzoom

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Geometry of the exponential-map strip used for zoom-out video.
 *
 * The strip holds the entire zoom in log-polar coordinates: horizontal is angle across
 * 2*pi, vertical is log radius from the zoom centre. Every scale appears exactly once,
 * at exactly the resolution the animation needs, so across a whole video no iteration
 * is computed twice. Advancing one frame extends the strip by a handful of rows rather
 * than costing a full frame of pixels — which is why the saving grows as the zoom
 * slows down.
 *
 * Only a sliding window of rows is ever needed, since a frame spans a bounded range of
 * radii, so the texture is a ring buffer rather than the full height. The full strip
 * for a 60-decade zoom would be tens of thousands of rows tall and far too large to
 * hold.
 */
class StripGeometry(
    val width: Int,
    val ringHeight: Int,
    /** Log radius of strip row 0. */
    val logR0: Double,
    /** Log radius per row. */
    val step: Double
) {
    fun rowFor(logRadius: Double): Double = (logRadius - logR0) / step

    fun bytes(): Long = width.toLong() * ringHeight * 4

    companion object {
        /** Radii below this many pixels are clamped, bounding the window height. */
        const val MIN_RADIUS_PX = 2.0

        /** Ring rows beyond the strict minimum, so a frame never races the writer. */
        private const val RING_SLACK = 1.15

        fun halfDiagonalPixels(w: Int, h: Int): Double = 0.5 * hypot(w.toDouble(), h.toDouble())

        /**
         * Angular resolution is set by the outermost pixels of a frame, where one pixel
         * subtends the smallest angle. Matching it there keeps the frame corners as
         * sharp as a direct render; anything less shows as softness away from centre.
         */
        fun build(
            frameW: Int,
            frameH: Int,
            deepestSpanY: Double,
            maxWidth: Int,
            memoryBudgetBytes: Long
        ): StripGeometry? {
            val halfDiag = halfDiagonalPixels(frameW, frameH)
            var width = nextPow2(ceil(2.0 * PI * halfDiag).toInt()).coerceAtMost(maxWidth)

            while (width >= 512) {
                val step = 2.0 * PI / width
                val rows = ceil((ln(halfDiag) - ln(MIN_RADIUS_PX)) / step * RING_SLACK).toInt()
                val ring = nextPow2(rows).coerceAtMost(maxWidth)
                if (ring >= rows) {
                    val geom = StripGeometry(
                        width, ring,
                        ln(deepestSpanY / frameH) + ln(MIN_RADIUS_PX), step
                    )
                    if (geom.bytes() <= memoryBudgetBytes) return geom
                }
                // Halving trades angular sharpness for memory. Better a slightly softer
                // frame than an export that cannot run at all.
                width /= 2
            }
            return null
        }

        /** Rows needed to render a frame at this span, as an absolute row range. */
        fun windowFor(geom: StripGeometry, spanY: Double, frameH: Int, frameW: Int): IntRange {
            val pixelSpan = spanY / frameH
            val halfDiag = halfDiagonalPixels(frameW, frameH)
            val lo = geom.rowFor(ln(pixelSpan * MIN_RADIUS_PX))
            val hi = geom.rowFor(ln(pixelSpan * halfDiag))
            return max(0, lo.toInt() - 1)..(ceil(hi).toInt() + 1)
        }

        /**
         * Pixels computed per frame relative to a plain render.
         *
         * Narrowing the strip helps twice over — fewer columns and, because the row
         * step grows with it, fewer rows per frame — so the ratio goes up as the
         * square. That is why a narrower strip is both faster and smaller, and why the
         * only thing it costs is angular sharpness.
         */
        fun speedup(geom: StripGeometry, frameW: Int, frameH: Int, zoomPerFrame: Double): Double {
            val rowsPerFrame = ln(zoomPerFrame) / geom.step
            return (frameW.toDouble() * frameH) / max(1.0, geom.width * rowsPerFrame)
        }

        /**
         * How much softer the frame corners are than a direct render. 1.0 is exact;
         * the corners are where a pixel subtends the smallest angle, so they are the
         * first thing to go when the strip is narrowed to fit memory.
         */
        fun cornerSoftness(geom: StripGeometry, frameW: Int, frameH: Int): Double =
            2.0 * PI * halfDiagonalPixels(frameW, frameH) / geom.width

        private fun nextPow2(v: Int): Int {
            var p = 1
            while (p < v) p = p shl 1
            return min(p, 1 shl 14)
        }
    }
}
