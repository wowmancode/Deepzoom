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

        uniform float uStripRBase;
        uniform float uStripRowBase;
        uniform float uStripStep;
        uniform float uStripWidth;

        uniform sampler2D uTiles;
        uniform int   uTileSize;
        uniform int   uUseTiles;

        // Colour depends only on the escape count, never on zoom. An escape count does
        // not change as you descend, so a pixel keeps its colour at any depth. The
        // palette is sampled with repeat wrapping, so no fract() is needed and the
        // seam blends.
        vec3 shade(int n, vec2 z) {
            float sn = float(n) + 1.0 - log2(0.5 * log2(dot(z, z)));
            return texture(uPalette, vec2(sn / uCycle + uOffset, 0.5)).rgb;
        }

        vec2 cmul(vec2 a, vec2 b) {
            return vec2(a.x * b.x - a.y * b.y, a.x * b.y + a.y * b.x);
        }

        // True when this pixel's tile was proven entirely interior by the border pass.
        bool tileIsSolid() {
            if (uUseTiles == 0) return false;
            ivec2 t = ivec2(gl_FragCoord.xy) / uTileSize;
            return texelFetch(uTiles, t, 0).r > 0.5;
        }
    """.trimIndent()

    /**
     * The border test behind tile skipping.
     *
     * If every pixel on a tile's border fails to escape within uMaxIter, so does every
     * pixel inside it. The truncated level set — points whose orbit stays bounded for
     * the first uMaxIter steps — is a closed topological disk, so its complement is
     * connected: an escaping point inside the tile would need a path to infinity
     * through escaping points, and that path has to cross the border. So this is exact,
     * not a heuristic, and it never touches the filaments.
     *
     * Written as a serial loop in one invocation per tile rather than as a compute
     * workgroup, specifically so it can bail the moment a border pixel escapes. Tiles
     * that straddle the boundary — the ones that cannot be skipped — therefore cost
     * close to nothing, and only genuinely solid tiles pay for the whole perimeter.
     */
    private val TILE_BODY = """
        void main() {
            ivec2 tile = ivec2(gl_FragCoord.xy);
            int x0 = tile.x * uTileSize;
            int y0 = tile.y * uTileSize;
            int x1 = min(x0 + uTileSize - 1, int(uResolution.x) - 1);
            int y1 = min(y0 + uTileSize - 1, int(uResolution.y) - 1);

            if (x0 > x1 || y0 > y1) { fragColor = vec4(0.0); return; }

            int n;
            vec2 z;

            for (int x = x0; x <= x1; x++) {
                if (escapes(vec2(float(x) + 0.5, float(y0) + 0.5), n, z)) {
                    fragColor = vec4(0.0); return;
                }
                if (y1 != y0 && escapes(vec2(float(x) + 0.5, float(y1) + 0.5), n, z)) {
                    fragColor = vec4(0.0); return;
                }
            }
            for (int y = y0 + 1; y < y1; y++) {
                if (escapes(vec2(float(x0) + 0.5, float(y) + 0.5), n, z)) {
                    fragColor = vec4(0.0); return;
                }
                if (x1 != x0 && escapes(vec2(float(x1) + 0.5, float(y) + 0.5), n, z)) {
                    fragColor = vec4(0.0); return;
                }
            }
            fragColor = vec4(1.0);
        }
    """.trimIndent()

    /** Direct float32 iteration, used above ~1e-4 span. */
    private val DIRECT_CORE = """
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

        bool escapesOffset(vec2 off, out int outN, out vec2 outZ) {
            vec2 c = uCenter + off;
            outN = uMaxIter;
            outZ = vec2(0.0);

            if (inMainBulbs(c)) return false;

            vec2 z = vec2(0.0);
            float d = 0.0;
            int i;

            // Periodicity check: interior points settle into a cycle, and comparing
            // against a lazily-updated earlier value detects that in O(1) space.
            vec2 hare = vec2(0.0);
            int period = 1;
            int periodLimit = 1;

            for (i = 0; i < uMaxIter; i++) {
                z = vec2(z.x * z.x - z.y * z.y, 2.0 * z.x * z.y) + c;
                d = dot(z, z);
                if (d > 65536.0) { outN = i; outZ = z; return true; }

                if (abs(z.x - hare.x) < 1e-9 && abs(z.y - hare.y) < 1e-9) return false;
                period--;
                if (period == 0) {
                    hare = z;
                    periodLimit *= 2;
                    period = periodLimit;
                }
            }
            return false;
        }

        bool escapes(vec2 frag, out int outN, out vec2 outZ) {
            float pixelSpan = uSpanY / uResolution.y;
            return escapesOffset((frag - 0.5 * uResolution) * pixelSpan, outN, outZ);
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
     * Rebasing (Zhuoran) — when a pixel's true value falls below its own delta in
     * magnitude, the reference has stopped being informative, so the pixel restarts at
     * orbit index 0 carrying its full value forward. Exact, rather than detecting
     * glitched pixels heuristically and re-rendering them.
     *
     * BLA (Zhuoran) — where the squared term is negligible the recurrence is linear,
     * and composed runs of it are precomputed at every power-of-two length. A pixel
     * takes the longest jump whose validity radius contains its delta. Radii shrink
     * monotonically as levels merge, so the lookup climbs from level 0 and stops at the
     * first failure instead of searching.
     */
    private val PERTURB_CORE = """
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

        bool escapesOffset(vec2 off, out int outN, out vec2 outZ) {
            vec2 dc = uDeltaCenter + off;
            outN = uMaxIter;
            outZ = vec2(0.0);

            vec2 dz = vec2(0.0);
            int m = 0;
            int n = 0;
            vec4 t = fetchZ(0);

            while (n < uMaxIter) {
                float dzMag = max(abs(dz.x), abs(dz.y));

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
                    outN = n;
                    outZ = zs * uInvScale;
                    return true;
                }

                if (zMag < max(abs(dz.x), abs(dz.y)) || m >= uOrbitLen) {
                    dz = zs;
                    m = 0;
                    t = fetchZ(0);
                }
            }
            return false;
        }

        bool escapes(vec2 frag, out int outN, out vec2 outZ) {
            return escapesOffset((frag - 0.5 * uResolution) * uPixelSpan, outN, outZ);
        }
    """.trimIndent()

    private val MAIN_BODY = """
        void main() {
            if (tileIsSolid()) {
                fragColor = vec4(uInterior, 1.0);
                return;
            }
            int n;
            vec2 z;
            if (!escapes(gl_FragCoord.xy, n, z)) {
                fragColor = vec4(uInterior, 1.0);
                return;
            }
            fragColor = vec4(shade(n, z), 1.0);
        }
    """.trimIndent()


    /**
     * Renders rows of the exponential-map strip.
     *
     * The strip is the whole zoom in log-polar coordinates: horizontal is angle over
     * 2*pi, vertical is log radius from the zoom centre. Every scale in the zoom
     * appears exactly once, at exactly the resolution the animation needs, so no
     * iteration is ever computed twice across the whole video. Advancing the zoom by
     * one frame only extends the strip by a few rows, instead of recomputing a whole
     * frame's worth of pixels.
     *
     * The radius is built up multiplicatively from a per-chunk base rather than from
     * an absolute log, because at depth the absolute log radius is around -130 and a
     * float there has nowhere near enough resolution to separate adjacent rows.
     */
    private val STRIP_BODY = """
        void main() {
            float angle = (gl_FragCoord.x / uStripWidth) * 6.28318530718;
            float r = uStripRBase * exp((gl_FragCoord.y - uStripRowBase) * uStripStep);
            vec2 off = r * vec2(cos(angle), sin(angle));

            int n;
            vec2 z;
            if (!escapesOffset(off, n, z)) {
                fragColor = vec4(uInterior, 1.0);
                return;
            }
            fragColor = vec4(shade(n, z), 1.0);
        }
    """.trimIndent()

    /**
     * Turns strip rows back into a normal frame.
     *
     * Both texture axes wrap: angle wraps at 2*pi, and the vertical axis is a ring
     * buffer holding only the rows the current frame needs. The seam between oldest
     * and newest row always falls outside that window, so linear filtering across it
     * never shows.
     */
    val UNWARP = """
        #version 310 es
        precision highp float;
        precision highp sampler2D;

        out vec4 fragColor;

        uniform sampler2D uStrip;
        uniform vec2  uResolution;
        uniform float uRingHeight;
        uniform float uRowBase;     // strip row for a one-pixel radius
        uniform float uStepInv;     // rows per unit of log radius
        uniform float uMinRadius;

        void main() {
            vec2 p = gl_FragCoord.xy - 0.5 * uResolution;
            // The few pixels at the very centre would need rows from arbitrarily deep
            // in the strip, so they are clamped to the innermost one available.
            float rpx = max(length(p), uMinRadius);
            float row = uRowBase + log(rpx) * uStepInv;
            fragColor = vec4(
                texture(uStrip, vec2(atan(p.y, p.x) / 6.28318530718, row / uRingHeight)).rgb,
                1.0
            );
        }
    """.trimIndent()

    val DIRECT = "#version 310 es\n$COMMON\n$DIRECT_CORE\n$MAIN_BODY"
    val DIRECT_TILE = "#version 310 es\n$COMMON\n$DIRECT_CORE\n$TILE_BODY"
    val PERTURBATION = "#version 310 es\n$COMMON\n$PERTURB_CORE\n$MAIN_BODY"
    val PERTURB_TILE = "#version 310 es\n$COMMON\n$PERTURB_CORE\n$TILE_BODY"
    val DIRECT_STRIP = "#version 310 es\n$COMMON\n$DIRECT_CORE\n$STRIP_BODY"
    val PERTURB_STRIP = "#version 310 es\n$COMMON\n$PERTURB_CORE\n$STRIP_BODY"

    /**
     * Presents an already-rendered frame, optionally reprojected.
     *
     * When the view has moved since the frame was made, the old frame is resampled
     * rather than recomputed, which costs one texture fetch per pixel.
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
            // smearing edge pixels across newly exposed area.
            if (any(lessThan(q, vec2(0.0))) || any(greaterThan(q, vec2(1.0)))) {
                fragColor = vec4(uBackground, 1.0);
                return;
            }
            fragColor = vec4(texture(uScene, q * uValidFrac).rgb, 1.0);
        }
    """.trimIndent()
}
