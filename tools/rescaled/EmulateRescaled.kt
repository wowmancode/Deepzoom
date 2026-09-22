// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Emulates the rescaled perturbation shader (Shaders.PERTURB_RESCALED_CORE) on the CPU
// in float32, line for line, using the app's own ReferenceOrbit, BlaTable and
// ViewState, and checks it two ways:
//
//   * where both paths apply, pixel for pixel against an emulation of the fast core
//     (Shaders.PERTURB_CORE) -- the rescaled core must reproduce what ships today;
//   * below the fast core's floor, against arbitrary-precision ground truth.
//
// Ground truth is not matched on every pixel by either core. Near a minibrot's edge,
// at escape counts in the thousands, float32 perturbation puts about one pixel in a
// hundred off by more than an iteration. The fast core does the same at the same kind
// of view, the same algorithm in double matches truth exactly, so this is float32,
// not the rescaling -- and the ground-truth check allows for it.
//
// Run with tools/rescaled/run.sh; exits non-zero on failure.
//
// Kotlin Float is IEEE float32, as the GPU's highp is. Mobile GPUs flush denormals to
// zero, so every case runs twice: with denormals kept, and with them flushed.
import com.fractal.deepzoom.BlaTable
import com.fractal.deepzoom.FixedIter
import com.fractal.deepzoom.OrbitGpuData
import com.fractal.deepzoom.ReferenceOrbit
import com.fractal.deepzoom.ViewState
import com.fractal.deepzoom.offsetFrom
import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.system.exitProcess

private var ftz = false
private const val DER_LIMIT_SQ = 0f   // as MandelbrotRenderer.DER_LIMIT_SQ
private const val BAILOUT = 8.0f

private fun f(x: Float): Float = if (ftz && x != 0f && abs(x) < java.lang.Float.MIN_NORMAL) 0f * x else x

private class V(val x: Float, val y: Float) {
    operator fun plus(o: V) = V(f(x + o.x), f(y + o.y))
    fun mul(k: Float) = V(f(x * k), f(y * k))
    fun maxAbs() = max(abs(x), abs(y))
    override fun equals(other: Any?) = other is V && other.x == x && other.y == y
    override fun hashCode() = x.hashCode() * 31 + y.hashCode()
}
private fun cmul(a: V, b: V) = V(f(a.x * b.x - a.y * b.y), f(a.x * b.y + a.y * b.x))
private fun dot(a: V) = f(a.x * a.x + a.y * a.y)

private fun expOf(x: Float): Int = ((java.lang.Float.floatToRawIntBits(x) shr 23) and 255) - 127
private fun pow2(k: Int): Float = java.lang.Float.intBitsToFloat((k + 127) shl 23)
private fun scaleBy(v: V, kIn: Int): V {
    val k = kIn.coerceIn(-252, 252)
    val h = k shr 1
    return v.mul(pow2(h)).mul(pow2(k - h))
}
private fun log2f(x: Float): Float = (Math.log(x.toDouble()) / Math.log(2.0)).toFloat()

private class Gpu(val g: OrbitGpuData, val len: Int, val maxIter: Int) {
    val bla: BlaTable = g.bla!!
    fun z(i: Int) = V(g.packed[2 * i], g.packed[2 * i + 1])
    fun ab(i: Int) = Pair(V(bla.ab[4 * i], bla.ab[4 * i + 1]), V(bla.ab[4 * i + 2], bla.ab[4 * i + 3]))
    fun r(i: Int) = bla.radius[i]
    val levels = min(bla.levels, BlaTable.MAX_LEVELS)

    fun blaMaxLevel(m: Int, n: Int): Int {
        val a = m - 1
        var k = levels - 1
        if (a != 0) k = min(k, Integer.numberOfTrailingZeros(a))
        for (i in 0 until 24) {
            if (k < 0) break
            if (n + (1 shl k) <= maxIter && (a shr k) < bla.levelCount[k]) break
            k--
        }
        return k
    }
    fun valid(k: Int, m: Int, logDz: Float) = logDz < r(bla.levelOffset[k] + ((m - 1) shr k))
    fun idx(k: Int, m: Int) = bla.levelOffset[k] + ((m - 1) shr k)

    /** Returns escape count, or maxIter if the pixel was judged interior. */
    fun escapes(u: V, scaleExp: Int): Int {
        val ec = -scaleExp
        val eu = ec + expOf(u.maxAbs())
        var w = V(0f, 0f); var e = ec
        var m = 0; var n = 0
        var t = z(0)
        var kWarm = 0
        var hareW = V(0f, 0f); var hareE = 0; var hareM = -1
        var period = 1; var periodLimit = 1
        var der = V(1f, 0f)
        while (n < maxIter) {
            val wMag = w.maxAbs()
            val logDz = if (wMag > 0f) e.toFloat() + log2f(wMag) else -1.0e20f
            var skip = 0; var chosen = -1
            if (m >= 1) {
                val kMax = blaMaxLevel(m, n)
                if (kMax >= 0) {
                    var k = kWarm.coerceIn(0, kMax)
                    if (valid(k, m, logDz)) {
                        for (i in 0 until 24) { if (k >= kMax || !valid(k + 1, m, logDz)) break; k++ }
                        skip = 1 shl k; chosen = idx(k, m)
                    } else {
                        for (i in 0 until 24) {
                            if (k <= 0) break
                            k--
                            if (valid(k, m, logDz)) { skip = 1 shl k; chosen = idx(k, m); break }
                        }
                        if (chosen < 0) k = 0
                    }
                    kWarm = k
                }
            }
            if (chosen >= 0) {
                val (a, b) = ab(chosen)
                if (n >= 1) der = cmul(der, a)
                val aw = cmul(a, w); val bu = cmul(b, u)
                val E = max(e + expOf(aw.maxAbs()), ec + expOf(bu.maxAbs()))
                w = scaleBy(aw, e - E) + scaleBy(bu, ec - E); e = E
                n += skip; m += skip
            } else {
                if (n >= 1) der = cmul(der, t + scaleBy(w, e + 1))
                val tw = cmul(t, w); val ww = cmul(w, w)
                val E = max(max(e + expOf(tw.maxAbs()), 2 * e + expOf(ww.maxAbs())), eu)
                w = scaleBy(tw, e - E) + scaleBy(ww, 2 * e - E) + scaleBy(u, ec - E); e = E
                n++; m++
            }
            val mx = w.maxAbs()
            if (mx > 0f) { val k = expOf(mx); w = scaleBy(w, -k); e += k }
            if (dot(der) < DER_LIMIT_SQ) return maxIter
            t = z(m)
            val zz = t.mul(0.5f)
            val zf = zz + scaleBy(w, e)
            if (zf.maxAbs() > BAILOUT) return n
            val forced = m >= len
            var rebase = false
            val zM = zz.maxAbs()
            if (zM > 0f && expOf(zM) > e + 30) {
                if (forced) { w = zz; e = 0; rebase = true }
            } else {
                val zw = w + scaleBy(zz, -e)
                if (forced || zw.maxAbs() < w.maxAbs()) { w = zw; rebase = true }
            }
            if (rebase) {
                val rm = w.maxAbs()
                if (rm > 0f) { val k = expOf(rm); w = scaleBy(w, -k); e += k }
                m = 0; t = z(0); kWarm = 0
            }
            if (w == hareW && e == hareE && m == hareM) return maxIter
            period--
            if (period == 0) { hareW = w; hareE = e; hareM = m; periodLimit *= 2; period = periodLimit }
        }
        return maxIter
    }
}

// The fast core (Shaders.PERTURB_CORE), float32, for comparison.
private class GpuFast(val g: OrbitGpuData, val len: Int, val maxIter: Int) {
    val bla: BlaTable = g.bla!!
    val levels = min(bla.levels, BlaTable.MAX_LEVELS)
    val scale = g.scale
    val invScale = (1.0 / scale).toFloat(); val halfScale = (scale * 0.5).toFloat(); val bail = (8.0 * scale).toFloat()
    fun z(i: Int) = V(g.packed[2 * i], g.packed[2 * i + 1])
    fun blaMaxLevel(m: Int, n: Int): Int {
        val a = m - 1; var k = levels - 1
        if (a != 0) k = min(k, Integer.numberOfTrailingZeros(a))
        for (i in 0 until 24) { if (k < 0) break; if (n + (1 shl k) <= maxIter && (a shr k) < bla.levelCount[k]) break; k-- }
        return k
    }
    fun idx(k: Int, m: Int) = bla.levelOffset[k] + ((m - 1) shr k)
    fun valid(k: Int, m: Int, mag: Float) = mag < f(bla.radius[idx(k, m)] * 0.7f)
    fun escapes(dc: V): Int {
        var dz = V(0f, 0f); var m = 0; var n = 0; var t = z(0); var kWarm = 0
        var hare = V(0f, 0f); var hareM = -1; var period = 1; var pl = 1
        while (n < maxIter) {
            val mag = dz.maxAbs(); var skip = 0; var chosen = -1
            if (m >= 1) {
                val kMax = blaMaxLevel(m, n)
                if (kMax >= 0) {
                    var k = kWarm.coerceIn(0, kMax)
                    if (valid(k, m, mag)) { for (i in 0 until 24) { if (k >= kMax || !valid(k + 1, m, mag)) break; k++ }; skip = 1 shl k; chosen = idx(k, m) }
                    else { for (i in 0 until 24) { if (k <= 0) break; k--; if (valid(k, m, mag)) { skip = 1 shl k; chosen = idx(k, m); break } }; if (chosen < 0) k = 0 }
                    kWarm = k
                }
            }
            if (chosen >= 0) {
                val a = V(bla.ab[4*chosen], bla.ab[4*chosen+1]); val b = V(bla.ab[4*chosen+2], bla.ab[4*chosen+3])
                dz = cmul(a, dz) + cmul(b, dc); n += skip; m += skip
            } else {
                val sq = cmul(dz, dz.mul(invScale)); dz = cmul(t, dz) + sq + dc; n++; m++
            }
            t = z(m)
            val zs = t.mul(halfScale) + dz; val zMag = zs.maxAbs()
            if (zMag > bail) return n
            if (zMag < dz.maxAbs() || m >= len) { dz = zs; m = 0; t = z(0); kWarm = 0 }
            if (dz == hare && m == hareM) return maxIter
            period--; if (period == 0) { hare = dz; hareM = m; pl *= 2; period = pl }
        }
        return maxIter
    }
}
/** Ground truth: fixed-point direct iteration, escape by max-norm > 8 as the shader does. */
private fun truth(cx: BigDecimal, cy: BigDecimal, maxIter: Int, digits: Int): Int {
    val it = FixedIter(FixedIter.limbsFor(digits)); it.reset(cx, cy)
    for (n in 1..maxIter) {
        it.step()
        if (max(abs(it.xDouble()), abs(it.yDouble())) > 8.0) return n
    }
    return maxIter
}

private var failures = 0

private fun case(label: String, cxs: String, cys: String, span: Double, maxIter: Int, grid: Int) {
    val s = ViewState(); s.centerX = BigDecimal(cxs); s.centerY = BigDecimal(cys); s.spanY = span; s.maxIter = maxIter
    val prec = ReferenceOrbit.precisionFor(span)
    val nuc = ReferenceOrbit.findNucleus(s.centerX, s.centerY, maxIter, span, prec)
    val orbit = if (nuc != null) ReferenceOrbit.compute(nuc.cx, nuc.cy, maxIter, span, nuc.period)
                else ReferenceOrbit.compute(s.centerX, s.centerY, maxIter, span)
    val exp = s.deltaScaleExponent()
    val off = s.offsetFrom(orbit)
    val maxC = BlaTable.maxCFor(span, 1.0, kotlin.math.hypot(off[0], off[1]))
    val g = OrbitGpuData.build(orbit, exp, maxC, rescaled = true)
    val gpu = Gpu(g, g.points - 1, maxIter)
    // The fast core only where it applies, with its own table (scaled radii).
    val fastOk = !ViewState.rescaledFor(span)
    val gf = if (fastOk) GpuFast(OrbitGpuData.build(orbit, exp, maxC, rescaled = false), g.points - 1, maxIter) else null
    val scale = g.scale
    val dcx = (off[0] * scale).toFloat(); val dcy = (off[1] * scale).toFloat()
    val pix = (span / grid * scale).toFloat()
    val mc = MathContext(prec + 20)
    val unit = BigDecimal(2).pow(exp).let { BigDecimal.ONE.divide(it, mc) }

    for (flush in listOf(false, true)) {
        ftz = flush
        var agree = 0; var off1 = 0; var worst = 0; var escaped = 0; var bad = 0
        val counts = HashSet<Int>(); var lo = Int.MAX_VALUE; var hi = 0
        var sameAsFast = 0
        for (j in 0 until grid) for (i in 0 until grid) {
            val ox = f(((i + 0.5f) - 0.5f * grid) * pix); val oy = f(((j + 0.5f) - 0.5f * grid) * pix)
            val u = V(dcx, dcy) + V(ox, oy)
            val got = gpu.escapes(u, exp)
            if (gf != null && gf.escapes(u) == got) sameAsFast++
            // Truth at exactly the c the shader represents: reference + u * 2^-exp.
            val cx = orbit.centerX.add(BigDecimal(u.x.toDouble()).multiply(unit, mc), mc)
            val cy = orbit.centerY.add(BigDecimal(u.y.toDouble()).multiply(unit, mc), mc)
            val want = truth(cx, cy, maxIter, prec + 20)
            if (want < maxIter) { escaped++; counts.add(want); lo = min(lo, want); hi = max(hi, want) }
            val d = abs(got - want)
            if (d == 0) agree++ else if (d <= 1) off1++ else bad++
            worst = max(worst, d)
        }
        val total = grid * grid
        // Against the fast core: identical. Against truth: within float32's rate.
        val ok = if (gf != null) sameAsFast == total else bad * 50 <= total
        println("%s %-4s %s: ref=%s period=%d scaleExp=%d | %d/%d exact, %d off by 1, %d worse (worst %d) | escaped %d, %d distinct counts in %s".format(
            if (ok) "ok  " else "FAIL", if (flush) "ftz" else "", label,
            if (nuc != null) "nucleus" else "centre", nuc?.period ?: 0, exp, agree, total, off1, bad, worst,
            escaped, counts.size, if (escaped > 0) "$lo..$hi" else "-") +
            (if (gf != null) " | same as fast core: $sameAsFast/$total" else ""))
        if (!ok) failures++
    }
}

private fun minibrotNear(d: Int): ReferenceOrbit.Companion.Minibrot? {
    val x = BigDecimal("0." + "0".repeat(d - 1) + "231")
    val y = BigDecimal("1." + "0".repeat(d - 1) + "172")
    val wide = Math.pow(10.0, -d + 2.0)
    val mb = ReferenceOrbit.findMinibrot(x, y, 20000, wide, ReferenceOrbit.precisionFor(wide * wide) + 10)
        ?: return null
    // Newton stops at 1e-20 of the span it was given, so a nucleus found from a wide
    // view is only that precise. Refine in stages -- each accepts the previous estimate
    // as in view and tightens it by twenty decades -- until it is good at its own scale.
    // (The app never needs this: it finds the nucleus afresh at each view's own span.)
    var cur = mb
    var span = wide
    while (span > cur.size * 10) {
        span = maxOf(span * 1e-18, cur.size * 10)
        cur = ReferenceOrbit.findMinibrot(cur.cx, cur.cy, 20000, span,
            ReferenceOrbit.precisionFor(cur.size) + 10) ?: return null
    }
    return cur
}

fun main(args: Array<String>) {
    val grid = args.firstOrNull()?.toInt() ?: 20

    // The Misiurewicz point c = i inside the view: the set there is a dendrite with
    // filaments at every scale, so any depth has structure and modest escape counts.
    for ((d, it) in listOf(40 to 3000, 60 to 4000, 100 to 6000, 200 to 8000, 285 to 10000)) {
        val x = "0." + "0".repeat(d) + "231"
        val y = "1." + "0".repeat(d) + "172"
        case("c=i view, span 1e-$d", x, y, Math.pow(10.0, -d.toDouble()), it, grid)
    }

    // Minibrots near c = i, where one at distance d has size about d^2: shallow ones
    // the fast core can also render, then ones below its floor. Framed on the nucleus
    // so the view holds interior, the minibrot's edge and the filaments around it --
    // exercising nucleus references, the forced rebase at the period, and the steps
    // after it where Z is zero.
    for (d in listOf(17, 22, 40, 60)) {
        val mb = minibrotNear(d)
        if (mb == null) { println("FAIL no minibrot found near 1e-$d"); failures++; continue }
        for (mult in listOf(3.0, 12.0)) {
            case("minibrot p%d, span %.0fx size %.1e".format(mb.period, mult, mb.size),
                mb.cx.toPlainString(), mb.cy.toPlainString(), mb.size * mult, 20000, grid)
        }
    }

    if (failures > 0) { println("$failures failure(s)"); exitProcess(1) }
    println("all checks passed")
}
