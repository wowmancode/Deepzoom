// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Checks the fixed-point reference orbit against arbitrary-precision ground truth, and
// that ending a nucleus orbit at its period changes no pixel. Run with
// tools/fixedpoint/run.sh; exits non-zero on any failure.
import com.fractal.deepzoom.FixedIter
import com.fractal.deepzoom.ReferenceOrbit
import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.system.exitProcess

private var failures = 0
private fun expect(ok: Boolean, what: String) {
    println((if (ok) "ok   " else "FAIL ") + what)
    if (!ok) failures++
}

private const val PX = "-0.743643887037158704752191506114774"
private const val PY = "0.131825904205311970493132056385139"

private fun truthOrbit(cx: BigDecimal, cy: BigDecimal, n: Int, digits: Int): Pair<DoubleArray, DoubleArray> {
    val mc = MathContext(digits); val two = BigDecimal(2)
    var x = BigDecimal.ZERO; var y = BigDecimal.ZERO
    val zx = DoubleArray(n + 1); val zy = DoubleArray(n + 1)
    for (i in 0..n) {
        zx[i] = x.toDouble(); zy[i] = y.toDouble()
        if (zx[i] * zx[i] + zy[i] * zy[i] > 4) return Pair(zx.copyOf(i + 1), zy.copyOf(i + 1))
        val nx = x.multiply(x, mc).subtract(y.multiply(y, mc), mc).add(cx, mc)
        y = two.multiply(x.multiply(y, mc), mc).add(cy, mc); x = nx
    }
    return Pair(zx, zy)
}

/** The shader's perturbation loop with rebasing, in double, without BLA. */
private fun perturbed(zx: DoubleArray, zy: DoubleArray, len: Int, dcx: Double, dcy: Double, maxIter: Int): Int {
    var dx = 0.0; var dy = 0.0; var m = 0; var n = 0
    while (n < maxIter) {
        val tx = 2 * zx[m]; val ty = 2 * zy[m]
        val nx = tx * dx - ty * dy + dx * dx - dy * dy + dcx
        val ny = tx * dy + ty * dx + 2 * dx * dy + dcy
        dx = nx; dy = ny; n++; m++
        val fx = zx[m] + dx; val fy = zy[m] + dy
        val f2 = fx * fx + fy * fy
        if (f2 > 65536.0) return n
        if (f2 < dx * dx + dy * dy || m >= len) { dx = fx; dy = fy; m = 0 }
    }
    return maxIter
}

fun main() {
    // Conversions, including tiny negatives whose limbs cancel if summed naively.
    run {
        val it = FixedIter(FixedIter.limbsFor(80)); val mc = MathContext(80)
        var ok = true
        for (s in listOf("-0.5", "1.75", "-1.999999", "-1e-40", "3.3e-60", "-7.25", "0")) {
            val v = BigDecimal(s); val a = LongArray(it.n); it.fromBigDecimal(v, a)
            if (it.toBigDecimal(a, mc).subtract(v).abs() >= BigDecimal("1e-80")) ok = false
            if (abs(it.toDouble(a) - v.toDouble()) > abs(v.toDouble()) * 1e-15) ok = false
        }
        expect(ok, "fixed-point conversions round-trip")
    }

    // Full precision on the first iterates, before chaos amplifies anything: the
    // fixed-point result must carry the digits precisionFor asks for, not just the 16
    // a double comparison can see.
    for (digits in listOf(50, 80, 110)) {
        val it = FixedIter(FixedIter.limbsFor(digits))
        val cx = BigDecimal(PX + "31415926535897932384626433832795028841971693993751"); val cy = BigDecimal(PY)
        it.reset(cx, cy)
        val mc = MathContext(digits * 3); val two = BigDecimal(2)
        var x = BigDecimal.ZERO; var y = BigDecimal.ZERO
        var worst = BigDecimal.ZERO
        for (k in 1..4) {
            val nx = x.multiply(x, mc).subtract(y.multiply(y, mc), mc).add(cx, mc)
            y = two.multiply(x.multiply(y, mc), mc).add(cy, mc); x = nx
            it.step()
            val e = it.xBig(mc).subtract(x, mc).abs().max(it.yBig(mc).subtract(y, mc).abs())
            if (e > worst) worst = e
        }
        expect(worst < BigDecimal.ONE.movePointLeft(digits),
            "fixed point keeps $digits digits (worst error ${worst.round(MathContext(2))})")
    }

    // Orbit against 3x-precision truth at several depths.
    for (depth in listOf(20, 35, 50, 65)) {
        val span = 10.0.pow(-depth)
        val cx = BigDecimal(PX); val cy = BigDecimal(PY)
        val (tx, ty) = truthOrbit(cx, cy, 5000, ReferenceOrbit.precisionFor(span) * 3)
        val o = ReferenceOrbit.compute(cx, cy, 5000, span)
        var err = 0.0
        for (i in 0..minOf(o.count, tx.size - 1)) {
            err = max(err, hypot(o.zx[i] - tx[i], o.zy[i] - ty[i]))
        }
        expect(err < 1e-14, "orbit at 1e-$depth matches truth (max error %.1e)".format(err))
    }

    // Extending must give the same orbit as computing to the larger count directly.
    run {
        val a = ReferenceOrbit.compute(BigDecimal(PX), BigDecimal(PY), 1000, 1e-40)
        a.extendTo(3000)
        val b = ReferenceOrbit.compute(BigDecimal(PX), BigDecimal(PY), 3000, 1e-40)
        var d = 0.0
        for (i in 0..minOf(a.count, b.count)) d = max(d, hypot(a.zx[i] - b.zx[i], a.zy[i] - b.zy[i]))
        expect(a.count == b.count && d == 0.0, "extendTo(3000) after 1000 equals compute(3000)")
    }

    // Period truncation: identical escape counts with the full and one-period orbit.
    run {
        val span = 1e-20; val maxIter = 30000; val mc = MathContext(80)
        val px = BigDecimal(PX); val py = BigDecimal(PY)
        val nuc = ReferenceOrbit.findNucleus(px, py, maxIter, span, ReferenceOrbit.precisionFor(span))
        expect(nuc != null, "nucleus found near the test point")
        if (nuc != null) {
            val full = ReferenceOrbit.compute(nuc.cx, nuc.cy, maxIter, span)
            val trunc = ReferenceOrbit.compute(nuc.cx, nuc.cy, maxIter, span, nuc.period)
            val zp = hypot(trunc.zx[trunc.count], trunc.zy[trunc.count])
            expect(trunc.count == nuc.period && zp < 1e-20,
                "orbit ends at period ${nuc.period} with |Z_p| = %.1e".format(zp))
            val offX = px.subtract(nuc.cx, mc).toDouble(); val offY = py.subtract(nuc.cy, mc).toDouble()
            val n = 48; var diff = 0
            for (j in 0 until n) for (i in 0 until n) {
                val dcx = offX + (i - n / 2 + 0.5) * span / n
                val dcy = offY + (j - n / 2 + 0.5) * span / n
                if (perturbed(full.zx, full.zy, full.count, dcx, dcy, maxIter) !=
                    perturbed(trunc.zx, trunc.zy, trunc.count, dcx, dcy, maxIter)) diff++
            }
            expect(diff == 0, "truncated orbit: $diff of ${n * n} pixels differ from full orbit")
        }
    }

    if (failures > 0) { println("$failures failure(s)"); exitProcess(1) }
    println("all checks passed")
}
