// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Sweeps the BLA tolerance (BlaTable.EPS) and measures, per view:
//
//   * loop passes per pixel -- what the fast shader actually spends;
//   * pixels more than one iteration off arbitrary-precision truth, with BLA;
//   * the same for plain float32 perturbation with BLA switched off.
//
// The fast core (Shaders.PERTURB_CORE) is emulated in float32 line for line, with
// denormals flushed as mobile GPUs do, on the app's own ReferenceOrbit, BlaTable and
// ViewState. Float32 perturbation is not exact near a minibrot's edge even without
// BLA, so the bar for a tolerance is "no worse than BLA off", not "zero mismatches".
//
// Run with tools/blatune/run.sh [grid]. Exits non-zero if the shipped EPS fails the
// accuracy bar in case() below.
import com.fractal.deepzoom.BlaTable
import com.fractal.deepzoom.FixedIter
import com.fractal.deepzoom.OrbitGpuData
import com.fractal.deepzoom.ReferenceOrbit
import com.fractal.deepzoom.ViewState
import com.fractal.deepzoom.offsetFrom
import java.math.BigDecimal
import java.math.MathContext
import java.util.stream.IntStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.system.exitProcess

private fun f(x: Float): Float = if (x != 0f && abs(x) < java.lang.Float.MIN_NORMAL) 0f * x else x

private class V(val x: Float, val y: Float) {
    operator fun plus(o: V) = V(f(x + o.x), f(y + o.y))
    fun mul(k: Float) = V(f(x * k), f(y * k))
    fun maxAbs() = max(abs(x), abs(y))
    override fun equals(other: Any?) = other is V && other.x == x && other.y == y
    override fun hashCode() = x.hashCode() * 31 + y.hashCode()
}
private fun cmul(a: V, b: V) = V(f(a.x * b.x - a.y * b.y), f(a.x * b.y + a.y * b.x))

/** Shaders.PERTURB_CORE in float32. bla == null runs plain perturbation. */
private class Fast(val g: OrbitGpuData, val bla: BlaTable?, val len: Int, val maxIter: Int) {
    val levels = if (bla == null) 0 else min(bla.levels, BlaTable.MAX_LEVELS)
    val scale = g.scale
    val invScale = (1.0 / scale).toFloat(); val halfScale = (scale * 0.5).toFloat(); val bail = (8.0 * scale).toFloat()
    fun z(i: Int) = V(g.packed[2 * i], g.packed[2 * i + 1])
    fun blaMaxLevel(m: Int, n: Int): Int {
        val a = m - 1; var k = levels - 1
        if (a != 0) k = min(k, Integer.numberOfTrailingZeros(a))
        for (i in 0 until 24) { if (k < 0) break; if (n + (1 shl k) <= maxIter && (a shr k) < bla!!.levelCount[k]) break; k-- }
        return k
    }
    fun idx(k: Int, m: Int) = bla!!.levelOffset[k] + ((m - 1) shr k)
    fun valid(k: Int, m: Int, mag: Float) = mag < f(bla!!.radius[idx(k, m)] * 0.7f)

    /** Returns (escape count or maxIter, loop passes). */
    fun run(dc: V): Long {
        var dz = V(0f, 0f); var m = 0; var n = 0; var t = z(0); var kWarm = 0
        var hare = V(0f, 0f); var hareM = -1; var period = 1; var pl = 1
        var passes = 0L
        fun pack(r: Int) = (r.toLong() shl 32) or passes
        while (n < maxIter) {
            passes++
            val mag = dz.maxAbs(); var skip = 0; var chosen = -1
            if (bla != null && m >= 1) {
                val kMax = blaMaxLevel(m, n)
                if (kMax >= 0) {
                    var k = kWarm.coerceIn(0, kMax)
                    if (valid(k, m, mag)) { for (i in 0 until 24) { if (k >= kMax || !valid(k + 1, m, mag)) break; k++ }; skip = 1 shl k; chosen = idx(k, m) }
                    else { for (i in 0 until 24) { if (k <= 0) break; k--; if (valid(k, m, mag)) { skip = 1 shl k; chosen = idx(k, m); break } }; if (chosen < 0) k = 0 }
                    kWarm = k
                }
            }
            if (chosen >= 0) {
                val a = V(bla!!.ab[4 * chosen], bla.ab[4 * chosen + 1]); val b = V(bla.ab[4 * chosen + 2], bla.ab[4 * chosen + 3])
                dz = cmul(a, dz) + cmul(b, dc); n += skip; m += skip
            } else {
                val sq = cmul(dz, dz.mul(invScale)); dz = cmul(t, dz) + sq + dc; n++; m++
            }
            t = z(m)
            val zs = t.mul(halfScale) + dz; val zMag = zs.maxAbs()
            if (zMag > bail) return pack(n)
            if (zMag < dz.maxAbs() || m >= len) { dz = zs; m = 0; t = z(0); kWarm = 0 }
            if (dz == hare && m == hareM) return pack(maxIter)
            period--; if (period == 0) { hare = dz; hareM = m; pl *= 2; period = pl }
        }
        return pack(maxIter)
    }
}


/**
 * The same loop in double, in true units. Float32 rounding is chaotic near the
 * boundary and swamps everything else; in double, plain perturbation matches truth,
 * so any mismatch here is BLA truncation alone.
 */
private class FastD(val orbit: ReferenceOrbit, val bla: BlaTable?, val len: Int, val maxIter: Int, val scale: Double) {
    val levels = if (bla == null) 0 else min(bla.levels, BlaTable.MAX_LEVELS)
    fun blaMaxLevel(m: Int, n: Int): Int {
        val a = m - 1; var k = levels - 1
        if (a != 0) k = min(k, Integer.numberOfTrailingZeros(a))
        for (i in 0 until 24) { if (k < 0) break; if (n + (1 shl k) <= maxIter && (a shr k) < bla!!.levelCount[k]) break; k-- }
        return k
    }
    fun idx(k: Int, m: Int) = bla!!.levelOffset[k] + ((m - 1) shr k)
    fun valid(k: Int, m: Int, mag: Double) = mag < bla!!.radius[idx(k, m)] / scale * 0.7
    fun run(cx: Double, cy: Double): Int {
        var x = 0.0; var y = 0.0; var m = 0; var n = 0; var kWarm = 0
        while (n < maxIter) {
            val mag = max(abs(x), abs(y)); var skip = 0; var chosen = -1
            if (bla != null && m >= 1) {
                val kMax = blaMaxLevel(m, n)
                if (kMax >= 0) {
                    var k = kWarm.coerceIn(0, kMax)
                    if (valid(k, m, mag)) { for (i in 0 until 24) { if (k >= kMax || !valid(k + 1, m, mag)) break; k++ }; skip = 1 shl k; chosen = idx(k, m) }
                    else { for (i in 0 until 24) { if (k <= 0) break; k--; if (valid(k, m, mag)) { skip = 1 shl k; chosen = idx(k, m); break } }; if (chosen < 0) k = 0 }
                    kWarm = k
                }
            }
            if (chosen >= 0) {
                val ax = bla!!.ab[4 * chosen].toDouble(); val ay = bla.ab[4 * chosen + 1].toDouble()
                val bx = bla.ab[4 * chosen + 2].toDouble(); val by = bla.ab[4 * chosen + 3].toDouble()
                val nx = ax * x - ay * y + bx * cx - by * cy
                val ny = ax * y + ay * x + bx * cy + by * cx
                x = nx; y = ny; n += skip; m += skip
            } else {
                val zx = 2 * orbit.zx[m]; val zy = 2 * orbit.zy[m]
                val nx = zx * x - zy * y + x * x - y * y + cx
                val ny = zx * y + zy * x + 2 * x * y + cy
                x = nx; y = ny; n++; m++
            }
            val sx = orbit.zx[m] + x; val sy = orbit.zy[m] + y
            val zMag = max(abs(sx), abs(sy))
            if (zMag > 8.0) return n
            if (zMag < max(abs(x), abs(y)) || m >= len) { x = sx; y = sy; m = 0; kWarm = 0 }
        }
        return maxIter
    }
}

private fun truth(cx: BigDecimal, cy: BigDecimal, maxIter: Int, digits: Int): Int {
    val it = FixedIter(FixedIter.limbsFor(digits)); it.reset(cx, cy)
    for (n in 1..maxIter) {
        it.step()
        if (max(abs(it.xDouble()), abs(it.yDouble())) > 8.0) return n
    }
    return maxIter
}

private val EPS_LIST = listOf(-24, -26, -28, -30, -32, -36, -44)
private var failures = 0
private val totals = HashMap<Int, DoubleArray>()

private fun case(label: String, cxs: String, cys: String, span: Double, maxIter: Int, grid: Int) {
    val s = ViewState(); s.centerX = BigDecimal(cxs); s.centerY = BigDecimal(cys); s.spanY = span; s.maxIter = maxIter
    if (ViewState.rescaledFor(span)) { println("skip $label: rescaled path"); return }
    val prec = ReferenceOrbit.precisionFor(span)
    val nuc = ReferenceOrbit.findNucleus(s.centerX, s.centerY, maxIter, span, prec)
    val orbit = if (nuc != null) ReferenceOrbit.compute(nuc.cx, nuc.cy, maxIter, span, nuc.period)
                else ReferenceOrbit.compute(s.centerX, s.centerY, maxIter, span)
    val exp = s.deltaScaleExponent()
    val off = s.offsetFrom(orbit)
    val maxC = BlaTable.maxCFor(span, 1.0, kotlin.math.hypot(off[0], off[1]))
    val g = OrbitGpuData.build(orbit, exp, maxC)
    val len = g.points - 1
    val scale = g.scale
    val dcx = (off[0] * scale).toFloat(); val dcy = (off[1] * scale).toFloat()
    val pix = (span / grid * scale).toFloat()
    val mc = MathContext(prec + 20)
    val unit = BigDecimal.ONE.divide(BigDecimal(2).pow(exp), mc)
    val total = grid * grid
    val us = Array(total) { p ->
        val i = p % grid; val j = p / grid
        V(dcx, dcy) + V(f(((i + 0.5f) - 0.5f * grid) * pix), f(((j + 0.5f) - 0.5f * grid) * pix))
    }
    val want = IntArray(total)
    IntStream.range(0, total).parallel().forEach { p ->
        val u = us[p]
        val cx = orbit.centerX.add(BigDecimal(u.x.toDouble()).multiply(unit, mc), mc)
        val cy = orbit.centerY.add(BigDecimal(u.y.toDouble()).multiply(unit, mc), mc)
        want[p] = truth(cx, cy, maxIter, prec + 20)
    }
    val escaped = want.count { it < maxIter }

    fun measure(bla: BlaTable?): LongArray {
        val fast = Fast(g, bla, len, maxIter)
        val res = LongArray(total)
        IntStream.range(0, total).parallel().forEach { p -> res[p] = fast.run(us[p]) }
        return res
    }
    fun iter(r: Long) = (r shr 32).toInt()
    fun passesOf(res: LongArray) = res.sumOf { it and 0xffffffffL }.toDouble() / total

    fun measureD(bla: BlaTable?): IntArray {
        val fd = FastD(orbit, bla, len, maxIter, scale)
        val res = IntArray(total)
        IntStream.range(0, total).parallel().forEach { p ->
            res[p] = fd.run(us[p].x.toDouble() / scale, us[p].y.toDouble() / scale)
        }
        return res
    }
    fun badOf(r: IntArray) = (0 until total).count { abs(r[it] - want[it]) > 1 }

    val plain = measure(null)
    val plainBad = badOf(IntArray(total) { iter(plain[it]) })
    val plainBadD = badOf(measureD(null))
    val sb = StringBuilder("%-22s %-6s esc=%3d | plain %6.0f/px f32bad %3d f64bad %d |".format(
        label, if (nuc != null) "p${nuc.period}" else "centre", escaped, passesOf(plain), plainBad, plainBadD))
    for (e in EPS_LIST) {
        val bla = BlaTable.build(orbit, maxC, scale, eps = Math.scalb(1.0, e))
        val res = measure(bla)
        val bad = badOf(IntArray(total) { iter(res[it]) })
        val badD = badOf(measureD(bla))
        val ps = passesOf(res)
        sb.append(" %d: %5.0f %3d %d |".format(e, ps, bad, badD))
        val t = totals.getOrPut(e) { DoubleArray(5) }
        t[0] += ps; t[1] += bad.toDouble(); t[2] += passesOf(plain); t[3] += badD.toDouble(); t[4] += plainBad.toDouble()
        // The shipped tolerance must not add float32 error beyond BLA off. 1% slack,
        // since float32 rounding near the boundary is chaotic with or without BLA and
        // the two land on different wrong pixels. The double column is reported, not
        // gated: it uses the float32 A and B the GPU gets, whose rounding is its floor.
        if (e == Math.getExponent(BlaTable.EPS) && (bad - plainBad) * 100 > total) failures++
    }
    // How much an oversized bound costs: a table is kept while zooming in until the
    // delta scale moves (every 256x), so its bound can be up to that much too large.
    if (System.getenv("SLACK") != null) {
        sb.append("\n    slack @eps:")
        for (mult in listOf(1.0, 4.0, 16.0, 64.0, 256.0)) {
            val bla = BlaTable.build(orbit, maxC * mult, scale)
            sb.append(" x%.0f %.0f".format(mult, passesOf(measure(bla))))
        }
    }
    println(sb)
}

private fun minibrotNear(d: Int): ReferenceOrbit.Companion.Minibrot? {
    val x = BigDecimal("0." + "0".repeat(d - 1) + "231")
    val y = BigDecimal("1." + "0".repeat(d - 1) + "172")
    val wide = Math.pow(10.0, -d + 2.0)
    var cur = ReferenceOrbit.findMinibrot(x, y, 20000, wide, ReferenceOrbit.precisionFor(wide * wide) + 10) ?: return null
    var span = wide
    while (span > cur.size * 10) {
        span = maxOf(span * 1e-18, cur.size * 10)
        cur = ReferenceOrbit.findMinibrot(cur.cx, cur.cy, 20000, span, ReferenceOrbit.precisionFor(cur.size) + 10) ?: return null
    }
    return cur
}

fun main(args: Array<String>) {
    val grid = args.firstOrNull()?.toInt() ?: 24
    val sx = "-0.743643887037158704752191506114774"
    val sy = "0.131825904205311970493132056385139"
    for ((span, it) in listOf(1e-6 to 5000, 1e-10 to 5000, 1e-14 to 8000, 1e-16 to 10000, 1e-18 to 10000, 1e-20 to 10000, 1e-24 to 20000, 1e-28 to 20000)) {
        case("seahorse %.0e".format(span), sx, sy, span, it, grid)
    }
    for ((d, it) in listOf(12 to 3000, 20 to 3000, 30 to 3000, 40 to 3000, 60 to 4000)) {
        val x = "0." + "0".repeat(d) + "231"
        val y = "1." + "0".repeat(d) + "172"
        case("c=i 1e-$d", x, y, Math.pow(10.0, -d.toDouble()), it, grid)
    }
    for (d in listOf(8, 12, 17, 22)) {
        val mb = minibrotNear(d) ?: run { println("no minibrot near 1e-$d"); null } ?: continue
        for (mult in listOf(3.0, 12.0)) {
            case("mb p%d %.0fx %.0e".format(mb.period, mult, mb.size),
                mb.cx.toPlainString(), mb.cy.toPlainString(), mb.size * mult, 20000, grid)
        }
    }
    println()
    for (e in EPS_LIST) {
        val t = totals[e] ?: continue
        println("2^%d: passes/px summed over views %.0f (plain %.0f) | f32 bad %d (plain %d) | f64 bad %d".format(
            e, t[0], t[2], t[1].toInt(), t[4].toInt(), t[3].toInt()))
    }
    if (failures > 0) { println("$failures view(s) where the shipped EPS failed the accuracy bar"); exitProcess(1) }
}
