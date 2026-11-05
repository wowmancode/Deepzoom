// SPDX-License-Identifier: AGPL-3.0-or-later
package com.fractal.deepzoom

import kotlin.math.PI
import kotlin.math.sin

/**
 * Kalles Fraktaler 2+ palettes: its two random generators, and reading its palette
 * text so a .kfp/.kfr can be pasted in.
 *
 * Ported from KF2+ (Settings::GenerateColors, Settings::AddWave, the colour dialog's
 * "random values" button, CFraktalSFT::UpdateColorTable), (C) Karl Runmo, Claude
 * Heiland-Allen et al., AGPL-3.0 -- the licence this app is under for exactly this.
 *
 * KF's key colours are blended with a sine ease rather than linearly; that is
 * PaletteSpec.ease, and every palette made here sets it.
 */
object KfPalette {

    /** KF's limit on key colours: they are spread over its 1024-entry table. */
    const val MAX_KEYS = 1024

    /**
     * The C runtime's rand() as KF2+ gets it on Windows (MSVC / mingw msvcrt):
     * state = state * 214013 + 2531011, output bits 16..30. Using the same generator
     * means a seed here gives the same palette it gives in KF.
     */
    class MsvcRand(seed: Long) {
        private var state = seed and 0xFFFFFFFFL
        fun next(): Int {
            state = (state * 214013L + 2531011L) and 0xFFFFFFFFL
            return ((state ushr 16) and 0x7FFF).toInt()
        }
    }

    /**
     * Settings::GenerateColors: each key colour is three rand() % 256 draws. KF's
     * quirk is kept: with seed 1 the first key stays black and drawing starts at the
     * second, so seed 1 matches KF too.
     */
    fun randomKeys(count: Int, seed: Long): List<Int> {
        val n = count.coerceIn(2, MAX_KEYS)
        val rng = MsvcRand(seed)
        val keys = IntArray(n)
        for (i in (if (seed == 1L) 1 else 0) until n) {
            val r = rng.next() % 256
            val g = rng.next() % 256
            val b = rng.next() % 256
            keys[i] = (r shl 16) or (g shl 8) or b
        }
        return keys.toList()
    }

    class Waves(val colors: List<Int>, val periods: IntArray, val starts: IntArray)

    /**
     * The colour dialog's "Generate RGB key colors from random values": four sine
     * waves, for blue, green, red and then grey, each with a random prime period of
     * up to half the key count and a random phase. Blue, green and red set their
     * channel; grey is averaged into all three.
     *
     * KF reseeds from the clock here, so a given result cannot be reproduced there;
     * here the seed makes it repeatable.
     */
    fun randomWaves(count: Int, seed: Long): Waves {
        val n = count.coerceIn(2, MAX_KEYS)
        val rng = MsvcRand(seed)
        val half = n / 2
        val periods = IntArray(4)
        val starts = IntArray(4)
        for (w in 0 until 4) {
            var v = rng.next() % half
            if (v == 0) v = 1
            periods[w] = makePrime(v)
            starts[w] = rng.next() % periods[w]
        }
        val r = IntArray(n); val g = IntArray(n); val b = IntArray(n)
        // AddWave(2, ...), AddWave(1, ...), AddWave(0, ...), AddWave(7, ...)
        fun wave(i: Int, w: Int): Int =
            // C truncates double to int; the value is never negative, so that is floor.
            (127.0 + 127.0 * sin((i + starts[w]).toDouble() * 2.0 * PI *
                (periods[w].toDouble() / n))).toInt()
        for (i in 0 until n) {
            b[i] = wave(i, 0)
            g[i] = wave(i, 1)
            r[i] = wave(i, 2)
            val grey = wave(i, 3)
            r[i] = (r[i] + grey) / 2
            g[i] = (g[i] + grey) / 2
            b[i] = (b[i] + grey) / 2
        }
        return Waves(List(n) { (r[it] shl 16) or (g[it] shl 8) or b[it] }, periods, starts)
    }

    /**
     * Smallest prime at or above n (n below 2 is returned as is).
     *
     * KF's own MakePrime was not in the source provided; this is what its callers
     * need from it -- a period sharing no factor with the others, so the waves do not
     * fall into step -- but its exact rounding may differ from KF's.
     */
    fun makePrime(n: Int): Int {
        if (n < 2) return n
        var p = n
        while (!isPrime(p)) p++
        return p
    }

    private fun isPrime(p: Int): Boolean {
        if (p < 2) return false
        if (p % 2 == 0) return p == 2
        var d = 3
        while (d * d <= p) {
            if (p % d == 0) return false
            d += 2
        }
        return true
    }

    /**
     * Reads KF palette text -- a .kfp or .kfr file's contents, or just its "Colors:"
     * line -- into a palette. Null if there is no usable Colors list.
     *
     * KF maps (iterations / IterDiv + ColorOffset) onto a 1024-entry table that spans
     * every key colour once, so a full cycle is 1024 * IterDiv iterations and the
     * offset is ColorOffset / 1024 of a cycle.
     */
    fun parse(text: String, fallback: PaletteSpec): PaletteSpec? {
        val fields = HashMap<String, String>()
        for (line in text.lines()) {
            val i = line.indexOf(':')
            if (i <= 0) continue
            fields[line.substring(0, i).trim()] = line.substring(i + 1).trim()
        }
        val colorsField = fields["Colors"] ?: return null
        val nums = ints(colorsField)
        val keys = (0 until nums.size / 3).take(MAX_KEYS).map {
            ((nums[3 * it] and 255) shl 16) or ((nums[3 * it + 1] and 255) shl 8) or
                (nums[3 * it + 2] and 255)
        }
        if (keys.size < 2) return null
        val interior = fields["InteriorColor"]?.let { ints(it) }?.takeIf { it.size >= 3 }
            ?.let { ((it[0] and 255) shl 16) or ((it[1] and 255) shl 8) or (it[2] and 255) }
        val div = fields["IterDiv"]?.toDoubleOrNull()?.takeIf { it > 0.0 } ?: 1.0
        val off = fields["ColorOffset"]?.toDoubleOrNull() ?: 0.0
        return PaletteSpec(
            name = "KF palette",
            colors = keys,
            interior = interior ?: fallback.interior,
            cycleLength = (1024.0 * div).toFloat().coerceIn(2f, 100000f),
            offset = ((off / 1024.0) % 1.0).toFloat(),
            ease = true
        )
    }

    private fun ints(s: String): List<Int> =
        s.split(',', ' ', '\t').mapNotNull { it.trim().toIntOrNull() }
}

/**
 * Whatever a person might paste as a palette: a Deep Zoom position code (its palette
 * is taken), a palette code from Copy, KF palette text, or a bare list of hex
 * colours (which keeps the current band width, rotation and interior). Null if none.
 */
object PalettePaste {
    fun parse(textIn: String, current: PaletteSpec): PaletteSpec? {
        val text = textIn.trim()
        if (text.isEmpty()) return null
        if (text.startsWith("DZ1") || text.startsWith("DZ2")) {
            return PositionCodec.decode(text)?.palette
        }
        if (text.contains("Colors:")) return KfPalette.parse(text, current)
        PaletteSpec.decode(text)?.let { return it }
        PaletteSpec.parseColors(text)?.let {
            return current.copy(name = "Custom", colors = it, positions = null)
        }
        return null
    }
}
