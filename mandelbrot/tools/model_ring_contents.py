"""
Tracks, per texel, which absolute strip row currently occupies it, then checks what
the unwarp shader actually samples against that. The guard in stripEnsureRange only
checks bookkeeping; this checks contents.
"""
import math
import numpy as np
from model_strip_freeze import (build, window_for, span_for_frame, frame_count,
                                half_diag, MIN_RADIUS_PX)

f32 = np.float32


def run(deepest, frame_w=1920, frame_h=1080, zpf=1.03, fps=30,
        max_tex=8192, budget=160 * 1024 * 1024, limit_frames=None):
    g = build(frame_w, frame_h, deepest, max(max_tex, 2048), budget)
    total = frame_count(deepest, zpf)
    if limit_frames:
        total = min(total, limit_frames)
    hd = half_diag(frame_w, frame_h)

    # texel -> absolute row currently stored, -1 = never written
    contents = np.full(g.ring, -1, dtype=np.int64)
    built_lo, built_hi = 0, -1

    print(f"deepest={deepest:.0e} ring={g.ring} window="
          f"{(math.log(hd)-math.log(MIN_RADIUS_PX))/g.step:.0f} frames={total}")

    first_stale = None
    first_unwritten = None
    max_abs_row = 0

    for i in range(total):
        span = span_for_frame(deepest, zpf, i, total)
        lo, hi = window_for(g, span, frame_h, frame_w)

        # --- renderRows: write every missing row, wrapping as the Kotlin does -----
        if built_hi < built_lo:
            for r in range(lo, hi + 1):
                contents[r % g.ring] = r
            built_lo, built_hi = lo, hi
        else:
            if hi > built_hi:
                for r in range(built_hi + 1, hi + 1):
                    contents[r % g.ring] = r
                built_hi = hi
                built_lo = max(built_lo, hi - g.ring + 1)
            if lo < built_lo:
                for r in range(lo, built_lo):
                    contents[r % g.ring] = r
                built_lo = lo
                built_hi = min(built_hi, lo + g.ring - 1)
        max_abs_row = max(max_abs_row, built_hi)

        # --- unwarp: sample the radii a real frame covers, in float32 ------------
        u_row_base = f32(g.row_for(math.log(span / frame_h)) + 0.5)
        u_step_inv = f32(1.0 / g.step)
        u_ring = f32(g.ring)

        rpx = np.geomspace(MIN_RADIUS_PX, hd, 400).astype(np.float32)
        row = u_row_base + np.log(rpx).astype(np.float32) * u_step_inv
        coord = (row / u_ring).astype(np.float32)
        texel = np.floor((coord % f32(1.0)) * u_ring).astype(np.int64) % g.ring

        stored = contents[texel]
        want = np.floor(row).astype(np.int64)

        if np.any(stored < 0) and first_unwritten is None:
            first_unwritten = (i, int(np.min(stored)))
        bad = (stored >= 0) & (np.abs(stored - want) > 1)
        if np.any(bad) and first_stale is None:
            k = int(np.argmax(bad))
            first_stale = (i, i / fps, int(want[k]), int(stored[k]))

    print(f"  max absolute row reached: {max_abs_row}")
    print(f"  first sample of a never-written texel: {first_unwritten}")
    if first_stale:
        print(f"  FIRST STALE SAMPLE: frame {first_stale[0]} (t={first_stale[1]:.1f}s) "
              f"wanted row {first_stale[2]}, texel holds {first_stale[3]}")
    else:
        print("  every sampled texel holds the row the unwarp asked for")


if __name__ == "__main__":
    for d in (1e-20, 1e-30, 1e-45):
        run(d)
        print()
