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

Rendering is on-demand (`RENDERMODE_WHEN_DIRTY`). A fresh view is drawn coarse first
and refined to the target over the next couple of frames, so something is on screen
immediately and the wait for native resolution happens behind it.

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
- **Colors** — nine presets, adjustable band width and rotation, custom palettes as a
  list of hex values, and a custom interior colour. The fractal updates live as you
  drag, which is the only honest way to judge band width.
- **Copy position / Go to** — location and palette as a text code, and back again.

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

About **1e-290**, set by the doubles the CPU side uses for the span, the offset from
the reference and the BLA radii. The GPU side no longer sets a limit.

Three rendering paths, switched automatically and shown in the readout:

- **direct** — while float32 can still tell neighbouring pixels apart. Plain float32
  iteration, with analytic cardioid and period-2 bulb tests to skip the two largest
  interior regions.
- **perturbed** — once a pixel is narrower than four float steps at the view's
  coordinates, and always below 1e-4 span. Each pixel iterates its offset from a
  shared reference orbit.
- **rescaled** — below 2.3e-69, where the perturbed path's one shared delta scale
  runs out. Each pixel carries its own exponent; see *Past 1e-69* below.

The switch is measured in pixels, not span: at 1e-4 across 2400 rows a pixel is about
4e-8 wide, while a float near -1.75 steps by 1.2e-7, so three pixels would share one c.
On a phone screen perturbation now takes over around 5e-4, and an 8640p export
switches earlier still.

### How perturbation works here

The CPU iterates one point in fixed-point arithmetic (`FixedPoint.kt`) at whatever
precision the current depth needs (`30 + decades` digits). Those orbit values are O(1), so they ship to the GPU
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

**Reference cost.** The orbit used to be iterated in `BigDecimal`, at roughly 300 ms
per 65536 iterations on a desktop JVM. That cost was allocation and decimal rounding,
not arithmetic, so it is now plain fixed-point: 28-bit limbs in preallocated arrays,
with two guard limbs, and no allocation per step. Measured on a desktop JVM against
the old code, 8–12x faster for a full-length orbit from 1e-20 to 1e-65, matching
3x-precision ground truth to the last bit of a double.

**Nucleus references stop at one period.** When the reference is a minibrot nucleus
its orbit returns to zero after one period, and the shader already rebases a pixel
at the end of the stored orbit, carrying its full value — which is exact at any
index. So only one period is computed and stored. A period-8007 nucleus under a
65536-iteration limit is an eighth of the orbit, and a smaller BLA table with it.
Checked by running the shader's loop against both orbits: identical escape counts
on every pixel, and both match arbitrary-precision truth.

The nucleus search itself stops Newton once its step falls below 1e-20 of the span
rather than always running twelve full periods, and carries the Newton derivative in
doubles with its own exponent (it only sets the step; the iterate keeps full
precision). Together with fixed-point, `findNucleus` went from about 400 ms to about
10 ms on a desktop JVM, and the orbit behind it from about 160 ms to about 3 ms.

**Orbit reuse** is the other half. The orbit is decoupled from zoom entirely:

- Zooming **out** never rebuilds. The orbit has more digits than it needs.
- Zooming **in** reuses it for six decades, on the guard digits it was built with.
- Raising the detail slider **extends** the orbit from its saved state instead of
  restarting it.
- The delta scale is quantised to powers of 256, so the cheap repack happens every 256x
  of zoom rather than every 2x, and BLA tables are built with a bound generous enough
  to survive 16x of zooming out.

For a long zoom-out video this means one orbit for the entire render instead of one per
couple of decades.

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
### Resolution

At native resolution a modern phone screen is three to four million pixels, each
running the iteration loop, so pixel count rather than pixel cost is what limits the
frame rate now.

The fractal is rendered into an offscreen target and presented through a second pass,
rather than by resizing the surface as before. That makes the render resolution
independent of the window, so changing it reallocates nothing, and it allows a coarse
pass to be shown while a finer one is still coming.

While moving, the default is to render at full resolution and only give ground if the
measured frame time says it must — backing off a power of two above ~33 ms and
recovering below ~15 ms, with the gap between those keeping it from oscillating. The
measurement uses `glFinish`, because without it the draw call returns long before the
GPU has finished and the adaptation would be chasing noise. Both extremes are available
directly if you would rather not have it decide: always-native or always-fast.

- **Interior tile skipping.** The frame is divided into 32-pixel tiles and a first pass
  iterates only each tile's border. If no border pixel escapes, none of the interior
  does either, and the whole tile is filled flat without touching it.

  This is exact rather than approximate. The truncated level set — points whose orbit
  stays bounded for the first `maxIter` steps — is a closed topological disk, so its
  complement is connected; an escaping point inside the tile would need a path to
  infinity through escaping points, and that path must cross the border. Checked
  against a full-grid reference at 1e-6, 1e-20 and 1e-30: zero violations.

  The border pass runs as one fragment invocation per tile rather than as a compute
  workgroup, specifically so the serial loop can bail the instant a border pixel
  escapes. Tiles straddling the boundary — the ones that cannot be skipped — therefore
  cost almost nothing, and only genuinely solid tiles pay for the full perimeter.

  Measured pixel savings were 58% on a view with mixed structure and 88% on an
  interior-heavy one, the latter being the ceiling: a solid tile still costs its 124
  border pixels out of 1024. Savings scale with resolution, since tiles cover more
  pixels, and with depth, since more of the screen is interior. Skipped below 512 px
  wide, where the perimeter is too large a fraction to be worth it.

- **Parallel YUV conversion** during video export. It is the dominant CPU cost per
  frame and every row is independent, so it is split across all cores.

### Colour

Colour is a pure function of the smooth escape count, with no zoom term anywhere. A
point's escape count does not change when you zoom, so a pixel keeps its colour at any
depth. An earlier version scaled the colour cycle with depth to keep band widths even;
that made the whole image rotate through the palette as you zoomed. Band width is
instead set directly, in iterations per trip around the palette.

Palettes are rendered to a 1024-wide ramp texture and sampled with linear filtering and
repeat wrapping, which is what gives the blending and a seamless wrap. Any number of
colour stops works without touching the shader.

### Interior row skipping on the strip

A strip row is a full circle around the view centre. The exterior of the set is
connected and unbounded, so an escaping point inside a circle would need a path to
infinity crossing it — a circle on which nothing escapes therefore encloses nothing
that escapes, and **every row below it is interior**. That is a stronger statement than
the live view's 32px tile test, and it fits the strip's geometry exactly: one proven
row settles every row beneath it.

The strip shader writes the escape flag into alpha (0 interior, 1 escaped); the unwarp
samples `.rgb`, so alpha was spare. A rendered row is then tested by reading it back.
Large ranges probe their top row first: if it is interior the whole range is filled at
once, and if not the range is halved and each half probed, finding the boundary in
about a dozen probes rather than by iterating every row to the cap. A failed readback
counts as "unknown" and never as interior, so a silent read failure cannot blank the
strip.

Measured on circles around a nucleus (`tools/model_strip_skip.py`): rows inside the
atom are 100% interior and cost the full iteration cap per pixel — these are the rows
that dominate render time — while rows outside cost around 15 iterations a pixel and
gain nothing. The saving is therefore concentrated exactly where the cost is.

**Tile skipping at the atom boundary.** The whole-circle test settles rows that are
interior all the way round, which is the deep bulk. Around the edge of a minibrot's
atom rows are only partly interior and it cannot help. There the ordinary 32px border
test wins instead, so the strip also builds a tile mask over absolute rows. Tiles are
identified by `absoluteRow / 32` and looked up by `texelRow / 32`; those agree only
because the ring height is a whole number of tiles, and the code refuses to build a
mask when it is not.

Measured in iterations (`tools/bench_strip.py`), which is what the shader actually
spends: inside the atom the circle test saves 94.5% against the tile test's 87.5%, at
the atom boundary the tile test saves 61% against the circle test's 35%, and outside
neither saves anything because those rows already cost about 15 iterations a pixel.
The two together cover the whole radius range.

**Periodicity check — exact repeat, never an epsilon.** An interior point settles into
a cycle, so comparing each iterate against a lazily-updated earlier one (Brent, resaving
at each power of two) detects it and stops instead of running to `maxIter`.

The test must be exact equality. An epsilon cannot be made safe: an escaping orbit just
outside a minibrot returns close to an earlier value once per period, and how close
scales with the atom size, so any fixed threshold starts calling escaping pixels
interior once the zoom is deep enough. That renders as black speckles scattered through
the coloured bands. Measured at an atom of 1.5e-2, escaping points return to within
5.6e-6 absolute and 3.2e-6 relative, while some genuinely interior points only reach
9.4e-5 relative — the two ranges overlap, so no threshold separates them.

Exact equality has no such failure mode, and the reason is a proof rather than a
measurement: if the float orbit lands on a value it already held, it is periodic in
float arithmetic and can never escape, so stopping returns precisely what running to
the cap would. In the perturbation path the complete state is the delta together with
the reference index, since two iterations can share a value while sitting at different
points of the reference orbit; both are compared.

Measured in float32 (`tools/validate_cycle.py`, and the float32 check in the notes):
escape verdicts identical to running with no test at all, with **14.6x** on the atom
boundary, 42x inside the atom and 396x deep inside. Slightly negative (0.85x) far
outside, where orbits escape in a few iterations and there is nothing to save.

Note the obvious dz/dz0 = prod 2*z_k derivative test does *not* work here: the orbit
starts at the critical point z0 = 0, so the product is identically zero and every pixel
would be flagged interior.

### Licence

AGPL-3.0-or-later. Chosen over a permissive licence so improvements stay available to
the community, and over plain GPL because it keeps the option of incorporating code
from Kalles Fraktaler 2+ and Fraktaler 3, both AGPL, which plain GPL would not allow.
No code from either is present; the techniques here were implemented from their
published descriptions, and techniques are not copyrightable.

### Verification

`tools/validate_bla.py` mirrors the Kotlin BLA construction and the GLSL iteration
loop in Python, then checks escape counts against arbitrary-precision ground truth.
It is what the epsilon choice above is based on, and it is worth re-running if the
table construction or the shader loop is ever changed — a wrong merge formula produces
images that look plausible rather than obviously broken.

`tools/fixedpoint/run.sh` checks the fixed-point orbit against arbitrary-precision
ground truth — full precision on the first iterates, double precision over thousands
— confirms that `extendTo` agrees with computing the longer orbit directly, and runs
the shader's perturbation loop against full and period-truncated nucleus orbits to
confirm they give identical escape counts. Deliberately dropping a product column or
a doubling makes it fail.

`tools/harness/verify.sh` type-checks the whole Kotlin source set against a real
`android.jar` plus signature-only stubs for AppCompat, Material and the generated `R`,
then compiles every shader with `glslangValidator`. No Android SDK or Gradle needed.
The compiler version must match `build.gradle.kts` and the script refuses to run if it
does not — 1.9.x is K1 and 2.0+ is K2, and K2 accepts code K1 rejects. See
`tools/harness/README.md`.

`tools/model_strip_freeze.py` and `tools/model_ring_contents.py` model the zoom-video
strip pipeline — geometry, the per-frame row window, the ring-buffer bookkeeping, and
what the unwarp shader samples in float32. The second one tracks which absolute row
occupies each texel, so it checks contents rather than just the bookkeeping the
in-app guard checks. Both were used to rule the strip out as the cause of a reported
mid-video freeze.

**Frame-identity check.** Every exported video frame is checksummed before it reaches
the encoder, and runs of identical consecutive frames are counted. A frozen image with
frames still being written is otherwise invisible to every counter there is: the loop
runs, the encoder accepts each frame, the file comes out the right length. Duplicates
inside the deliberate hold at the end are ignored; anything else is reported in a
dialog on save. It costs one pass over the frame buffer, and it splits a freeze in the
renderer from one downstream of it without needing logcat.

### Past 1e-69: rescaled perturbation

The perturbed path carries every delta as a float in units of one shared 2^k, and k
is capped because the reference orbit and the bailout live in the same units. Around
2.3e-69 no single k can hold both a pixel's starting delta and what it grows into
before escaping.

Below that, a separate shader takes over. Each pixel's delta is `w * 2^e`: `w` a
float mantissa held in [1, 2) by max-norm, `e` a per-pixel int. The reference stays a
plain float, since it is O(1). Each step is formed as terms whose exponents are known
— `2Z*dz` at `e`, `dz^2` at `2e`, `dc` at its own fixed exponent — and they are scaled
to the largest before adding. The result therefore never underflows even when it is
far smaller than the delta it came from, as on the step after a rebase, where Z is
zero and the new value is `dz^2 + dc`. Powers of two are built from their bits rather
than with `exp2`, so every rescale is exact. BLA radii are stored as log2, because at
these depths they span far more than float's range, so validity is one compare
against `e + log2|w|`.

Above 2.3e-69 nothing changes: the fast shader is used exactly as before, and this one
is never compiled into its loop.

**Checked by emulation** (`tools/rescaled/run.sh`). The GLSL is ported line for line
to Kotlin `Float`, which is IEEE float32 as the GPU's highp is. It uses the app's own
orbit, nucleus and BLA code, and runs every case twice, with denormals kept and
flushed, since mobile GPUs flush them.

- Where both shaders apply, the rescaled one reproduces the fast one pixel for pixel,
  on views around c = i and around period-45 and period-59 minibrots.
- Below the old floor it matches arbitrary-precision truth: views around c = i at
  1e-100, 1e-200 and 1e-285, and framed on minibrots of period 107 (5e-80) and
  period 159 (4e-119), with nucleus references and up to 264 distinct escape counts
  in a 20x20 grid.
- Planting an error in the step, doubling the `dz^2` term, makes it fail.

Neither shader matches truth on every pixel near a minibrot's edge. At escape counts
in the thousands, float32 perturbation puts about one pixel in a hundred off by more
than an iteration. The fast shader does the same on the same kind of view, and the
identical algorithm in double matches truth exactly, so that is float32, not the
rescaling.

**Cost.** The rescaled step does more integer and scaling work than the fast one,
roughly 2–3x the ALU per plain iteration, which is the repo's own earlier estimate for
floatexp. At these depths most of a pixel's iterations are BLA jumps, and those cost
only a little more than before. The reference orbit at 315 digits (1e-285) takes
about 160 ms for 65536 iterations on a desktop JVM, and the nucleus search about 30 ms.

**The next limit** is the CPU's doubles. The span, offsets and BLA radii are all
doubles, which end at 2.2e-308, and the floor sits about eighteen decades short of
that. Going further would mean carrying those as mantissa-plus-exponent too.

Series approximation is not planned. BLA already covers what it would skip: about
ten loop iterations per pixel at 1e-55.
