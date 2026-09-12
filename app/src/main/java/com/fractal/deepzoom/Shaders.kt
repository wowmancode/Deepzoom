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

        uniform sampler2D uPalette;
        uniform float uCycle;      // iterations per full trip around the palette
        uniform float uOffset;
        uniform vec3  uInterior;

        // Colour depends only on the escape count, never on zoom. An escape count does
        // not change as you descend, so a pixel keeps its colour at any depth. The
        // palette texture is sampled with repeat wrapping, so no fract() is needed and
        // the seam blends.
        vec3 shade(int n, vec2 z) {
            float sn = float(n) + 1.0 - log2(0.5 * log2(dot(z, z)));
            return texture(uPalette, vec2(sn / uCycle + uOffset, 0.5)).rgb;
        }

        vec2 cmul(vec2 a, vec2 b) {
            return vec2(a.x * b.x - a.y * b.y, a.x * b.y + a.y * b.x);
        }
    """.trimIndent()

    /**
     * Direct iteration, used above ~1e-4 span where float32 still resolves pixels and
     * perturbation would be pure overhead.
     */
    val DIRECT = """
        #version 310 es
        $COMMON

        uniform vec2  uCenter;
        uniform float uSpanY;

        // The main cardioid and period-2 bulb are the two largest solid regions.
        // Testing them analytically avoids running their pixels to uMaxIter.
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
                fragColor = vec4(uInterior, 1.0);
                return;
            }

            vec2 z = vec2(0.0);
            float d = 0.0;
            int i;

            // Periodicity check: interior points settle into a cycle, and comparing
            // against a lazily-updated earlier value detects that in O(1) space.
            // Catching an interior pixel at iteration 200 instead of 65536 is the
            // largest saving available on this path.
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
                fragColor = vec4(uInterior, 1.0);
                return;
            }
            fragColor = vec4(shade(i, z), 1.0);
        }
    """.trimIndent()


    /**
     * Presents an already-rendered frame, optionally reprojected.
     *
     * While a gesture is in progress the fractal is not recomputed at all. The last
     * completed frame is resampled according to how far the view has moved since it was
     * made, which costs one texture fetch per pixel instead of a full iteration loop.
     * Panning and pinching therefore run at the same speed whatever the depth or the
     * iteration count.
     */
    val BLIT = """
        #version 310 es
        precision highp float;
        precision highp sampler2D;

        out vec4 fragColor;

        uniform sampler2D uScene;
        uniform vec2  uResolution;
        uniform vec2  uValidFrac;   // portion of the scene texture actually rendered
        uniform vec2  uShift;       // view movement since the frame was made
        uniform float uZoom;        // span ratio since the frame was made
        uniform vec3  uBackground;

        void main() {
            vec2 s = gl_FragCoord.xy / uResolution;
            vec2 q = vec2(0.5) + uShift + (s - vec2(0.5)) * uZoom;

            // Anything the old frame never covered stays background rather than
            // smearing the edge pixels across newly exposed area.
            if (any(lessThan(q, vec2(0.0))) || any(greaterThan(q, vec2(1.0)))) {
                fragColor = vec4(uBackground, 1.0);
                return;
            }
            fragColor = vec4(texture(uScene, q * uValidFrac).rgb, 1.0);
        }
    """.trimIndent()

    /**
     * Perturbation, with rebasing and bivariate linear approximation.
     *
     * Each pixel tracks its offset d from a high-precision reference orbit Z:
     *
     *     d(n+1) = 2*Z(n)*d(n) + d(n)^2 + dc
     *
     * Z stays O(1) and d stays small, so both fit in float32 even where the true
     * coordinates need 60 decimal digits. Deltas are carried pre-multiplied by the
     * orbit's scale, because the true values sit below float32's denormal floor.
     *
     * Two accelerations sit on top of that recurrence:
     *
     * Rebasing (Zhuoran) — when a pixel's true value falls below its own delta in
     * magnitude, the reference has stopped being informative, so the pixel restarts at
     * orbit index 0 carrying its full value forward. Exact, rather than detecting
     * glitched pixels heuristically and re-rendering them.
     *
     * BLA (Zhuoran) — where the squared term is negligible the recurrence is linear,
     * and composed runs of it are precomputed at every power-of-two length. A pixel
     * takes the longest jump whose validity radius still contains its delta. Because
     * radii shrink monotonically as levels merge, the lookup climbs from level 0 and
     * stops at the first failure instead of searching.
     */
    val PERTURBATION = """
        #version 310 es
        $COMMON

        uniform sampler2D uOrbit;
        uniform sampler2D uBlaAB;
        uniform sampler2D uBlaR;

        uniform int   uWidthMask;
        uniform int   uWidthShift;
        uniform int   uOrbitLen;

        uniform int   uBlaLevels;
        uniform int   uBlaOffset[24];
        uniform int   uBlaCount[24];

        uniform vec2  uDeltaCenter;    // (view centre - reference), pre-scaled
        uniform float uPixelSpan;      // complex units per pixel, pre-scaled
        uniform float uInvScale;
        uniform float uBailoutScaled;

        vec4 fetchZ(int i) {
            return texelFetch(uOrbit, ivec2(i & uWidthMask, i >> uWidthShift), 0);
        }

        vec4 fetchAB(int i) {
            return texelFetch(uBlaAB, ivec2(i & uWidthMask, i >> uWidthShift), 0);
        }

        float fetchR(int i) {
            return texelFetch(uBlaR, ivec2(i & uWidthMask, i >> uWidthShift), 0).r;
        }

        void main() {
            vec2 dc = uDeltaCenter + (gl_FragCoord.xy - 0.5 * uResolution) * uPixelSpan;

            vec2 dz = vec2(0.0);
            int m = 0;
            int n = 0;
            vec4 t = fetchZ(0);

            while (n < uMaxIter) {
                float dzMag = max(abs(dz.x), abs(dz.y));

                // Longest valid jump from here. Radii are non-increasing with level,
                // so the first failure ends the climb.
                int skip = 0;
                int chosen = -1;
                if (m >= 1) {
                    for (int k = 0; k < uBlaLevels; k++) {
                        int step = 1 << k;
                        if (((m - 1) & (step - 1)) != 0) break;
                        if (n + step > uMaxIter) break;
                        int j = (m - 1) >> k;
                        if (j >= uBlaCount[k]) break;
                        int idx = uBlaOffset[k] + j;
                        // 0.7 compensates for using the max-norm rather than the true
                        // length, which would risk overflowing at this magnitude.
                        if (dzMag >= fetchR(idx) * 0.7) break;
                        skip = step;
                        chosen = idx;
                    }
                }

                if (chosen >= 0) {
                    vec4 ab = fetchAB(chosen);
                    dz = cmul(ab.xy, dz) + cmul(ab.zw, dc);
                    n += skip;
                    m += skip;
                } else {
                    // d^2 in scaled units is d*(d/scale). Computed this way the
                    // intermediate stays in range; a plain d*d would overflow.
                    vec2 sq = cmul(dz, dz * uInvScale);
                    dz = cmul(t.xy, dz) + sq + dc;
                    n++;
                    m++;
                }

                t = fetchZ(m);

                // True value, still scaled. Tests use the max-norm because a squared
                // length would overflow up here.
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

            fragColor = vec4(uInterior, 1.0);
        }
    """.trimIndent()
}
