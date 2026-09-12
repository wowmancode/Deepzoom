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
        uniform float uColorShift;

        vec3 palette(float t) {
            return 0.5 + 0.5 * cos(6.28318530718 * (vec3(0.95, 1.00, 1.05) * t
                                                    + vec3(0.10, 0.42, 0.74)));
        }

        // Continuous escape count. Without it the bands are integer steps and no
        // amount of palette tuning hides the contouring.
        vec3 shade(int n, vec2 z) {
            float sn = float(n) + 1.0 - log2(0.5 * log2(dot(z, z)));
            return palette(sn * uColorCycle + uColorShift);
        }
    """.trimIndent()

    /**
     * Direct iteration. Used above ~1e-4 span, where float32 still resolves pixels
     * and the perturbation machinery would be pure overhead.
     */
    val DIRECT = """
        #version 310 es
        $COMMON

        uniform vec2  uCenter;
        uniform float uSpanY;

        // The main cardioid and period-2 bulb are the two largest solid regions.
        // Testing them analytically skips running their pixels to uMaxIter, which is
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
            for (i = 0; i < uMaxIter; i++) {
                z = vec2(z.x * z.x - z.y * z.y, 2.0 * z.x * z.y) + c;
                d = dot(z, z);
                if (d > 65536.0) break;
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
     * Instead of iterating each pixel's own c, every pixel tracks its offset d from a
     * reference orbit Z computed in high precision on the CPU:
     *
     *     d(n+1) = 2*Z(n)*d(n) + d(n)^2 + dc
     *
     * Z stays O(1) and d stays small, so both fit in float32 even when the underlying
     * coordinates need 60 decimal digits. All deltas are carried pre-multiplied by
     * uScale (a power of two) because the true deltas are far below float32's
     * denormal floor at this depth.
     *
     * Rebasing (Zhuoran's method) replaces the older detect-glitches-and-re-render
     * approach: whenever the true value falls below the delta in magnitude, the
     * reference has stopped being informative for this pixel, so the pixel restarts
     * at orbit index 0 carrying its full value as the new delta. This is exact rather
     * than heuristic, so there are no glitch blobs to patch up and no need for
     * secondary reference orbits.
     */
    val PERTURBATION = """
        #version 310 es
        $COMMON

        uniform sampler2D uOrbit;
        uniform int   uOrbitWidth;
        uniform int   uOrbitLen;

        uniform vec2  uDeltaCenter;    // (view centre - reference), pre-scaled
        uniform float uPixelSpan;      // complex units per pixel, pre-scaled
        uniform float uScale;          // delta scaling factor, a power of two
        uniform float uInvScale;
        uniform float uBailoutScaled;

        vec2 fetchZ(int i) {
            return texelFetch(uOrbit, ivec2(i % uOrbitWidth, i / uOrbitWidth), 0).rg;
        }

        vec2 cmul(vec2 a, vec2 b) {
            return vec2(a.x * b.x - a.y * b.y, a.x * b.y + a.y * b.x);
        }

        void main() {
            vec2 dc = uDeltaCenter + (gl_FragCoord.xy - 0.5 * uResolution) * uPixelSpan;

            vec2 dz = vec2(0.0);
            int m = 0;
            vec2 Z = fetchZ(0);

            for (int n = 0; n < uMaxIter; n++) {
                // d^2 in scaled units is d*(d/scale): computing it this way keeps the
                // intermediate from overflowing, which a naive d*d would do.
                vec2 sq = cmul(dz, dz * uInvScale);
                dz = 2.0 * cmul(Z, dz) + sq + dc;

                m++;
                Z = fetchZ(m);

                // True value, still in scaled units. Tests use the max-norm rather
                // than a squared length because squaring would overflow up here.
                vec2 zs = Z * uScale + dz;
                float zMag = max(abs(zs.x), abs(zs.y));

                if (zMag > uBailoutScaled) {
                    // Once escaped the value is O(1), so unscaling is safe and the
                    // smooth iteration count can be computed normally.
                    fragColor = vec4(shade(n, Z + dz * uInvScale), 1.0);
                    return;
                }

                if (zMag < max(abs(dz.x), abs(dz.y)) || m >= uOrbitLen) {
                    dz = zs;
                    m = 0;
                    Z = fetchZ(0);
                }
            }

            fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        }
    """.trimIndent()
}
