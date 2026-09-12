# Deep Zoom

GPU Mandelbrot viewer for Android 13+. All per-pixel work happens in a GLES 3.1
fragment shader; the CPU only tracks view state and uploads four uniforms per frame.

## Controls

- Drag to pan, pinch to zoom (zoom is anchored to the midpoint between your fingers)
- **Detail** — maximum iterations, 32 to 16384 on a log scale
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

## Current limits

`MIN_SPAN` in `MandelbrotView.kt` is clamped to `1e-6`. That is roughly where float32
runs out of mantissa and the image degrades into blocky quantisation rather than
detail.

## Path to e-100 zoom

The structure here is built for perturbation theory to drop in, which is what lifts
the depth limit by ~94 orders of magnitude:

1. **Reference orbit on the CPU.** Iterate a single point (the view centre) in high
   precision — `BigDecimal`, or a hand-rolled double-double for speed — and store
   `Z_0..Z_n` as float32 pairs. This is why `centerX`/`centerY` are already `Double`
   rather than `Float`.
2. **Upload as an SSBO.** GLES 3.1 gives you shader storage buffers, which is the
   reason this project targets 3.1 rather than using AGSL.
3. **Iterate the delta, not the point.** Each pixel tracks its offset from the
   reference: `d(n+1) = 2*Z_n*d_n + d_n^2 + dc`. The deltas stay small enough that
   float32 is sufficient no matter how deep the reference is — the precision lives
   entirely in the CPU-side orbit.
4. **Detect glitches.** Where `|Z_n + d_n|` is much smaller than `|Z_n|`, the pixel's
   result is unreliable (Pauldelbrot's criterion). Flag those pixels, pick a new
   reference inside the glitched region, and re-render it.
5. **Series approximation (optional).** Skip the first several thousand iterations
   for most pixels with a truncated power series. This is what makes deep zooms fast
   rather than merely possible.

Add the new shader as a second constant in `Shaders.kt` and select it at program-build
time in `MandelbrotRenderer`; the renderer's structure does not need to change.
