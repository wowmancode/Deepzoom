# Deep Zoom

GPU Mandelbrot viewer for Android 13+. All per-pixel work happens in a GLES 3.1
fragment shader; the CPU only tracks view state and uploads four uniforms per frame.

## Controls

- Drag to pan, pinch to zoom (zoom is anchored to the midpoint between your fingers)
- **Detail** — maximum iterations, 32 to 65536 on a log scale. Deep zooms need far
  more than shallow ones; if a deep view looks like a flat black field, raise this
- **Render resolution** — 25% to 100% of native. The GL surface is allocated at this
  size and the display hardware upscales it, so lowering it is a real cost saving
- Controls fade back after a couple of seconds so they stop competing with the image

Rendering is on-demand (`RENDERMODE_WHEN_DIRTY`), and drops to half the chosen
resolution while a gesture is in progress.

## Building

No local Android SDK required. Pushing to `main` triggers the GitHub Actions workflow
in `.github/workflows/build.yml`, which builds a debug APK and attaches it to a
rolling `latest` prerelease. Release assets download as a raw `.apk`, so the link
opens directly in Android's installer — no unzipping.

You can also trigger a build by hand from the Actions tab (Build APK → Run workflow),
which is the easier route from a phone.

The APK is signed with the standard debug key: fine for sideloading onto your own
device, not for distribution. No Gradle wrapper JAR is committed; the workflow
supplies the Gradle CLI instead.

## Export

- **Save PNG** — offscreen render at 720p through 8640p, in any of seven aspect
  ratios, written to Pictures/DeepZoom. Independent of the render-resolution slider.
  Sizes above the device's texture limit are allowed and flagged rather than blocked.
- **Save video** — a zoom-out from wherever you are back to the whole set. Aspect
  ratio, resolution, frame rate, zoom-out per frame, and quality are all adjustable,
  and the dialog shows resulting frame count, duration, and bitrate before you commit.
  H.264 MP4 in Movies/DeepZoom.
- **Copy position / Go to** — the current location as a text code, and back again.

Height sets the vertical span in every ratio, so a wider ratio reveals more of the
plane to the sides rather than cropping the framing you set up.

Zoom-out steps are multiplicative. A constant percentage per frame reads as constant
speed; constant additive steps would crawl at depth and lurch at the end.

Position codes write the centre as a plain decimal string rather than a double. At
depth the coordinate needs more digits than a double holds, so round-tripping through
one would silently land you somewhere else.

### Video quality

Fractal frames are near the worst case for an inter-frame codec: every pixel is
high-contrast detail that changes every frame, so motion estimation has almost nothing
to reuse, and rates that look generous for ordinary video are visibly destructive.
The quality setting is expressed in bits per pixel per frame — Standard 0.25, High
0.5, Maximum 1.0 — which at 1080p30 works out to roughly 15, 31, and 62 Mbps. The
encoder also requests High profile (CABAC, 8x8 transforms) where the device offers it,
falling back rather than failing, and keyframes every second instead of every two.

Chroma is still subsampled to 4:2:0, which is inherent to H.264 and does cost some
colour detail on the finest filaments.

## How deep it goes

Roughly **1e60**, set by float32's exponent range rather than by precision.

Two rendering paths, switched automatically and shown in the readout:

- **direct** — above 1e-4 span. Plain float32 iteration, with analytic cardioid and
  period-2 bulb tests to skip the two largest interior regions.
- **perturbed** — below 1e-4 span. Each pixel iterates its offset from a shared
  reference orbit.

### How perturbation works here

The CPU iterates one point in `BigDecimal` at whatever precision the current depth
needs (`30 + decades` digits). Those orbit values are O(1), so they ship to the GPU
as plain floats in an `RG32F` texture. Each pixel then iterates its *offset* from
that orbit:

    d(n+1) = 2*Z(n)*d(n) + d(n)^2 + dc

Precision is paid for once per frame on the CPU instead of once per pixel on the GPU.

**Scaling.** At 1e-50 the deltas are far below float32's smallest normal value
(~1e-38), so every delta is carried pre-multiplied by a power of two chosen to put
pixel-scale deltas near 2^-80. That leaves 46 binary orders above the denormal floor
and 8 below overflow. The `d^2` term is computed as `d * (d/scale)` rather than
`d * d`, which would overflow the intermediate.

**Rebasing instead of glitch correction.** When a pixel's true value falls below its
own delta in magnitude, the reference has stopped being informative for that pixel,
so it restarts at orbit index 0 carrying its full value as the new delta. This is
Zhuoran's method, and it is exact — unlike the older approach of detecting glitched
pixels with Pauldelbrot's criterion and re-rendering them against secondary
references, there are no glitch blobs to patch and no second reference orbit.

**Orbit reuse.** The reference does not need to sit at the view centre, so it is kept
across pans and small zooms and only rebuilt when it leaves the visible region, the
zoom moves by more than 4x, or the iteration count rises. Rebuilds happen on a
background thread; the old orbit keeps rendering meanwhile.

### Performance notes

There is no `-O3` for shaders. GLSL is compiled by the GPU driver at runtime and is
always optimised at full strength; there is no flag to turn. The Kotlin side is a
rounding error against per-pixel GPU work. The wins here are algorithmic.

**Bivariate linear approximation** is the large one, and it is what the deep-zoom
numbers rest on. Where the delta is small and the reference is not near a critical
point, the squared term in the perturbed iteration is negligible, leaving a map that
is linear in both delta and c. Linear maps compose, so runs of consecutive iterations
collapse into a single `d -> A*d + B*dc` valid inside a radius r. The table holds
those composites at every power-of-two length, and a pixel takes the longest jump its
delta fits inside. Radii are non-increasing as levels merge, so the lookup climbs from
level 0 and stops at the first failure rather than searching.

Measured against high-precision ground truth, in loop iterations per pixel:

| span | without BLA | with BLA | speedup |
|------|------------|----------|---------|
| 1e-20 | 9069 | 4294 | 2.1x |
| 1e-28 | 20000 | 3101 | 6.4x |
| 1e-40 | 20000 | 37 | 540x |
| 1e-55 | 20000 | 10 | 2000x |

The speedup grows with depth, which is the opposite of how the naive loop behaves.

Other optimisations:

- **Periodicity detection** on the direct path. Interior points settle into a cycle,
  detected in O(1) space against a lazily-updated earlier value. Catching an interior
  pixel at iteration 200 instead of 65536 is the largest saving on that path.
- **Pre-scaled orbit data.** The orbit texture stores `2*Z` and `Z*scale` already
  computed, removing two multiplies from every iteration. The delta scale is fixed
  when the orbit is built rather than per frame, which is what makes this possible.
- **Mask-and-shift table indexing** instead of integer division and modulo, which are
  slow on mobile GPUs. Texture widths are powers of two specifically for this.
- **Analytic interior tests** for the main cardioid and period-2 bulb.

### Verification

`tools/validate_bla.py` mirrors the Kotlin BLA construction and the GLSL iteration
loop in Python, then checks escape counts against arbitrary-precision ground truth.
It is what the epsilon choice above is based on, and it is worth re-running if the
table construction or the shader loop is ever changed — a wrong merge formula produces
images that look plausible rather than obviously broken.

### Going deeper than 1e60

The limit is float32 exponent range, not the algorithm. Past this point deltas need
a **floatexp** representation — mantissa plus a separate integer exponent — which
removes the range ceiling entirely at some cost in shader speed.

The other worthwhile addition is **series approximation**: a truncated power series
can skip the first several thousand iterations for most pixels, which is what makes
very deep zooms fast rather than merely possible.
