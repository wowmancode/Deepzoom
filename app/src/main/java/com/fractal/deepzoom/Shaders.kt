package com.fractal.deepzoom

/**
 * Shader sources live here rather than inline in the renderer so a second
 * implementation (perturbation) can be added as a sibling constant and selected
 * at program-build time without touching the renderer's structure.
 */
object Shaders {

    /**
     * Direct iteration in float32. Good to roughly 1e-6 span before pixel-scale
     * quantisation becomes visible as blocky banding — that is the point where
     * the perturbation path has to take over.
     */
    val MANDELBROT_SIMPLE = """
        #version 310 es
        precision highp float;

        out vec4 fragColor;

        uniform vec2  uResolution;
        uniform vec2  uCenter;
        uniform float uSpanY;
        uniform int   uMaxIter;

        const float BAILOUT_SQ = 65536.0;

        vec3 palette(float t) {
            // Cosine palette. Phase offsets chosen so the interior edge reads cool
            // and the escape bands warm, which keeps filament structure legible.
            return 0.5 + 0.5 * cos(6.28318530718 * (vec3(0.95, 1.00, 1.05) * t
                                                    + vec3(0.10, 0.42, 0.74)));
        }

        // Cheap analytic interior tests. The main cardioid and the period-2 bulb are
        // the two largest solid regions; skipping them avoids running every interior
        // pixel to uMaxIter and is where most of the frame time is saved when zoomed out.
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
                if (d > BAILOUT_SQ) break;
            }

            if (i >= uMaxIter) {
                fragColor = vec4(0.0, 0.0, 0.0, 1.0);
                return;
            }

            // Continuous escape count. Without this you get visible integer banding
            // that no amount of palette tuning hides.
            float sn = float(i) + 1.0 - log2(0.5 * log2(d));

            // Scale colour cycling with zoom depth so bands stay a similar visual
            // width instead of compressing into noise as you descend.
            float cycle = 0.035 + 0.02 * log2(3.0 / uSpanY) * 0.01;
            fragColor = vec4(palette(sn * cycle), 1.0);
        }
    """.trimIndent()
}
