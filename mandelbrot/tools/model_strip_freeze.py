"""
Models the zoom-video strip pipeline end to end, in the same order the Kotlin does:

  StripGeometry.build -> windowFor -> stripEnsureRange bookkeeping -> UNWARP sampling

The unwarp side is evaluated in float32 to match the shader, because that is the
only part of the chain that is not double precision.
"""
import math
import numpy as np

f32 = np.float32

DEFAULT_SPAN = 3.0
MIN_RADIUS_PX = 2.0
RING_SLACK = 1.15


def next_pow2(v):
    p = 1
    while p < v:
        p <<= 1
    return min(p, 1 << 14)


class Geom:
    def __init__(self, width, ring, logR0, step):
        self.width, self.ring, self.logR0, self.step = width, ring, logR0, step

    def row_for(self, log_radius):
        return (log_radius - self.logR0) / self.step

    def bytes(self):
        return self.width * self.ring * 4


def half_diag(w, h):
    return 0.5 * math.hypot(w, h)


def build(frame_w, frame_h, deepest_span, max_width, budget):
    hd = half_diag(frame_w, frame_h)
    width = min(next_pow2(math.ceil(2.0 * math.pi * hd)), max_width)
    while width >= 512:
        step = 2.0 * math.pi / width
        rows = math.ceil((math.log(hd) - math.log(MIN_RADIUS_PX)) / step * RING_SLACK)
        ring = min(next_pow2(rows), max_width)
        if ring >= rows:
            g = Geom(width, ring, math.log(deepest_span / frame_h) + math.log(MIN_RADIUS_PX), step)
            if g.bytes() <= budget:
                return g
        width //= 2
    return None


def window_for(g, span_y, frame_h, frame_w):
    pixel_span = span_y / frame_h
    hd = half_diag(frame_w, frame_h)
    lo = g.row_for(math.log(pixel_span * MIN_RADIUS_PX))
    hi = g.row_for(math.log(pixel_span * hd))
    return max(0, int(lo) - 1), int(math.ceil(hi)) + 1


def span_for_frame(target, zpf, index, total, hold=4, zoom_in=False):
    moving = max(1, total - hold)
    if index >= moving:
        return target if zoom_in else DEFAULT_SPAN
    step = (moving - 1 - index) if zoom_in else index
    return min(max(target * (zpf ** step), target), DEFAULT_SPAN)


def frame_count(start_span, zpf, hold=4):
    if start_span >= DEFAULT_SPAN:
        return hold + 1
    steps = math.log(DEFAULT_SPAN / start_span) / math.log(zpf)
    return max(1, math.ceil(steps)) + hold


def run(deepest, frame_w=1920, frame_h=1080, zpf=1.03, fps=30,
        max_tex=8192, budget=160 * 1024 * 1024, report_every=None):
    g = build(frame_w, frame_h, deepest, max(max_tex, 2048), budget)
    if g is None:
        print("no strip geometry -> plain frame fallback")
        return
    total = frame_count(deepest, zpf)
    hd = half_diag(frame_w, frame_h)

    print(f"deepest={deepest:.0e} strip={g.width}x{g.ring} step={g.step:.6e} "
          f"rows/frame={math.log(zpf)/g.step:.2f} window={(math.log(hd)-math.log(MIN_RADIUS_PX))/g.step:.0f} "
          f"total_frames={total} ({total/fps:.0f}s)")

    built_lo, built_hi = 0, -1
    first_bad = None
    prev_texels = None
    frozen_since = None

    for i in range(total):
        span = span_for_frame(deepest, zpf, i, total)
        lo, hi = window_for(g, span, frame_h, frame_w)

        if built_hi < built_lo:
            built_lo, built_hi = lo, hi
        else:
            if hi > built_hi:
                built_hi = hi
                built_lo = max(built_lo, hi - g.ring + 1)
            if lo < built_lo:
                built_lo = lo
                built_hi = min(built_hi, lo + g.ring - 1)

        guard_fires = lo < built_lo or hi > built_hi
        if guard_fires and first_bad is None:
            first_bad = (i, lo, hi, built_lo, built_hi)

        # --- UNWARP, in float32 exactly as the shader computes it ---------------
        u_row_base = f32(g.row_for(math.log(span / frame_h)) + 0.5)
        u_step_inv = f32(1.0 / g.step)
        u_ring = f32(g.ring)

        # Sample the radii a real frame covers: centre clamp out to the corner.
        rpx = np.array([MIN_RADIUS_PX, 10.0, 100.0, frame_h / 2.0, hd], dtype=np.float32)
        row = u_row_base + np.log(rpx).astype(np.float32) * u_step_inv
        coord = (row / u_ring).astype(np.float32)
        texel = (coord * u_ring) % u_ring          # what REPEAT resolves to

        # Has the frame actually changed since the last one?
        if prev_texels is not None:
            if np.allclose(texel, prev_texels, atol=1e-3):
                if frozen_since is None:
                    frozen_since = i
            else:
                frozen_since = None
        prev_texels = texel

        if report_every and i % report_every == 0:
            print(f"  f{i:5d} t={i/fps:6.1f}s span={span:.3e} win=[{lo},{hi}] "
                  f"valid=[{built_lo},{built_hi}] rowBase={float(u_row_base):10.2f} "
                  f"rows={float(row[0]):.1f}..{float(row[-1]):.1f} "
                  f"coordMax={float(coord[-1]):.4f}")

    if first_bad:
        print(f"  GUARD would fire first at frame {first_bad[0]} "
              f"(t={first_bad[0]/fps:.1f}s): win={first_bad[1:3]} valid={first_bad[3:]}")
    else:
        print("  guard never fires: window stays inside valid rows for the whole video")
    if frozen_since is not None:
        print(f"  sampled rows stopped advancing at frame {frozen_since}")
    else:
        print("  sampled rows advance on every frame")


for d in (1e-20, 1e-30, 1e-45, 1e-60):
    run(d, report_every=None)
    print()
