package com.fractal.deepzoom

object Shaders {

    val VERTEX = """
        #version 310 es
        void main() {
            // One oversized triangle covering the clip volume. No vertex buffer needed.
            vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
            gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
        }
    """.trimIndent()

    private val COMMON = """
        precision highp float;
        precision highp int;
        precision highp sampler2D;

        out vec4 fragColor;

        uniform vec2  uResolution;
        uniform int   uMaxIter;
        uniform float uColorCycle;

        vec3 palette(float t) {
            return 0.5 + 0.5 * cos(6.28318530718 * (vec3(0.95, 1.00, 1.05) * t
                                                    + vec3(0.10, 0.42, 0.74)));
        }

        // Continuous escape count. Without it the bands are integer steps and no
        // palette tuning hides the contouring.
        vec3 shade(int n, vec2 z) {
            float sn = float(n) + 1.0 - log2(0.5 * log2(dot(z, z)));
            return palette(sn * uColorCycle);
        }

        vec2 cmul(vec2 a, vec2 b) {
            return vec2(a.x * b.x - a.y * b.y, a.x * b.y + a.y * b.x);
        }
    """.trimIndent()

    /**
     * Direct iteration. Used above ~1e-4 span, where float32 still resolves pixels
     * and perturbation would be pure overhead.
     */
    val DIRECT = """
        #version 310 es
        $COMMON

        uniform vec2  uCenter;
        uniform float uSpanY;

        // The main cardioid and period-2 bulb are the two largest solid regions.
        // Testing them analytically avoids running their pixels to uMaxIter, which is
        // where most of the frame time goes when zoomed out.
        bool inMainBulbs(vec2 c) {
            float xm = c.x - 0.25;
            float y2 = c.y * c.y;
            float q  = xm * xm + y2;
            if (q * (q + xm) <= 0.25 * y2) return true;
            vec2 d = c + vec2(1.0, 0.0);
            return dot(d, d) <= 0.0625;
        }

        void main() {
            float pixelSpan = uSpanY / uResolution.y;
            vec2 c = uCenter + (gl_FragCoord.xy - 0.5 * uResolution) * pixelSpan;

            if (inMainBulbs(c)) {
                fragColor = vec4(0.0, 0.0, 0.0, 1.0);
                return;
            }

            vec2 z = vec2(0.0);
            float d = 0.0;
            int i;

            // Periodicity check: interior points settle into a cycle, and comparing
            // against a lazily-updated earlier value detects that in O(1) space.
            // Catching an interior pixel at iteration 200 instead of 65536 is the
            // single largest saving available on this path.
            vec2 hare = vec2(0.0);
            int period = 1;
            int periodLimit = 1;

            for (i = 0; i < uMaxIter; i++) {
                z = vec2(z.x * z.x - z.y * z.y, 2.0 * z.x * z.y) + c;
                d = dot(z, z);
                if (d > 65536.0) break;

                if (abs(z.x - hare.x) < 1e-9 && abs(z.y - hare.y) < 1e-9) {
                    i = uMaxIter;
                    break;
                }
                period--;
                if (period == 0) {
                    hare = z;
                    periodLimit *= 2;
                    period = periodLimit;
                }
            }

            if (i >= uMaxIter) {
                fragColor = vec4(0.0, 0.0, 0.0, 1.0);
                return;
            }
            fragColor = vec4(shade(i, z), 1.0);
        }
    """.trimIndent()

    /**
     * Perturbation with rebasing.
     *
     * Each pixel tracks its offset d from a high-precision reference orbit Z:
     *
     *     d(n+1) = 2*Z(n)*d(n) + d(n)^2 + dc
     *
     * Z stays O(1) and d stays small, so both fit in float32 even where the true
     * coordinates need 60 decimal digits. All deltas are carried pre-multiplied by
     * uScale because the true deltas sit below float32's denormal floor at depth.
     *
     * Rebasing (Zhuoran's method): when a pixel's true value falls below its own
     * delta in magnitude, the reference has stopped being informative, so the pixel
     * restarts at orbit index 0 carrying its full value forward. This is exact,
     * unlike detecting glitched pixels heuristically and re-rendering them against
     * secondary references — no glitch blobs, one orbit.
     *
     * Inner-loop cost has been pared to one texture fetch and one complex multiply
     * beyond the recurrence itself: the orbit texture stores 2*Z and Z*scale
     * precomputed, and the index splits by mask and shift rather than integer
     * division, which is slow on mobile GPUs.
     */
    val PERTURBATION = """
        #version 310 es
        $COMMON

        uniform sampler2D uOrbit;
        uniform int   uWidthMask;
        uniform int   uWidthShift;
        uniform int   uOrbitLen;

        uniform vec2  uDeltaCenter;    // (view centre - reference), pre-scaled
        uniform float uPixelSpan;      // complex units per pixel, pre-scaled
        uniform float uInvScale;
        uniform float uBailoutScaled;

        vec4 fetchZ(int i) {
            return texelFetch(uOrbit, ivec2(i & uWidthMask, i >> uWidthShift), 0);
        }

        void main() {
            vec2 dc = uDeltaCenter + (gl_FragCoord.xy - 0.5 * uResolution) * uPixelSpan;

            vec2 dz = vec2(0.0);
            int m = 0;
            vec4 t = fetchZ(0);        // t.xy = 2*Z, t.zw = Z*scale

            for (int n = 0; n < uMaxIter; n++) {
                // d^2 in scaled units is d*(d/scale). Computing it this way keeps the
                // intermediate in range; a plain d*d would overflow.
                vec2 sq = cmul(dz, dz * uInvScale);
                dz = cmul(t.xy, dz) + sq + dc;

                m++;
                t = fetchZ(m);

                // True value, still scaled. Tests use the max-norm because a squared
                // length would overflow at this magnitude.
                vec2 zs = t.zw + dz;
                float zMag = max(abs(zs.x), abs(zs.y));

                if (zMag > uBailoutScaled) {
                    // Escaped values are O(1), so unscaling is safe here.
                    fragColor = vec4(shade(n, zs * uInvScale), 1.0);
                    return;
                }

                if (zMag < max(abs(dz.x), abs(dz.y)) || m >= uOrbitLen) {
                    dz = zs;
                    m = 0;
                    t = fetchZ(0);
                }
            }

            fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        }
    """.trimIndent()
}
