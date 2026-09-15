"""
Estimates what interior tile skipping would save on the strip render.

The live view already skips 32px tiles whose border never escapes. The strip lacks it,
and the measured cost per row is dominated by rows that run every pixel to the cap.
This asks: on strip rows, how much of the work is skippable, and is the border test
still sound in exponential-map space?

Soundness: the exterior of the Mandelbrot set is connected and unbounded, so any
escaping point strictly inside a region must be joined to infinity by a path through
the exterior, which has to cross the region's border. A border with no escaping point
therefore encloses none. A strip tile is an annular sector, whose border is still a
closed curve in the plane, so the argument carries over from the live view unchanged.

The caveat is the same one the live view already accepts: "did not escape within
maxIter" is not "interior". The test inherits that approximation, it does not add one.

Modelled at radii double precision can represent. The deep rows are self-similar to
these, so the skip fractions are representative rather than exact.
"""
import math
import numpy as np

CX = -1.4770987286329669
CY = 0.0052099263526281487

TILE = 32
COLS = 512          # angular samples (real strip is 4096; ratio is what matters)
ROWS = 128
MAX_ITER = 5000


def escape_counts(cx, cy, radii, angles, max_iter):
    """Iteration count per point; max_iter means it never escaped."""
    r = radii[:, None]
    a = angles[None, :]
    c = (cx + r * np.cos(a)) + 1j * (cy + r * np.sin(a))
    z = np.zeros_like(c)
    out = np.full(c.shape, max_iter, dtype=np.int32)
    alive = np.ones(c.shape, dtype=bool)
    for n in range(max_iter):
        z[alive] = z[alive] * z[alive] + c[alive]
        esc = alive & (z.real * z.real + z.imag * z.imag > 4.0)
        out[esc] = n
        alive &= ~esc
        if not alive.any():
            break
    return out, alive


def analyse(r_lo, r_hi, label):
    rows = np.geomspace(r_lo, r_hi, ROWS)
    angles = np.linspace(0.0, 2.0 * np.pi, COLS, endpoint=False)
    counts, interior = escape_counts(CX, CY, rows, angles, MAX_ITER)

    total_iters = counts.sum()
    skipped_iters = 0
    tiles = 0
    skipped_tiles = 0

    for ty in range(0, ROWS, TILE):
        for tx in range(0, COLS, TILE):
            block = interior[ty:ty + TILE, tx:tx + TILE]
            cblock = counts[ty:ty + TILE, tx:tx + TILE]
            if block.size == 0:
                continue
            tiles += 1
            # Border of the tile: wraps in angle, so the left/right edges of the whole
            # strip are genuinely adjacent — but within a tile the border is just its
            # own four edges.
            border = np.concatenate([
                block[0, :], block[-1, :], block[:, 0], block[:, -1]
            ])
            if border.all():                      # nothing on the border escaped
                skipped_tiles += 1
                # Interior of the tile would not be iterated at all.
                skipped_iters += cblock[1:-1, 1:-1].sum()

    frac_tiles = skipped_tiles / max(tiles, 1)
    frac_work = skipped_iters / max(total_iters, 1)
    interior_frac = interior.mean()

    # Sanity: a skipped tile must contain no escaping point, or the test is unsound.
    unsound = 0
    for ty in range(0, ROWS, TILE):
        for tx in range(0, COLS, TILE):
            block = interior[ty:ty + TILE, tx:tx + TILE]
            if block.size and np.concatenate(
                [block[0, :], block[-1, :], block[:, 0], block[:, -1]]
            ).all():
                if not block.all():
                    unsound += 1

    print(f"{label}")
    print(f"  radii {r_lo:.1e}..{r_hi:.1e}   never-escaped pixels {interior_frac*100:5.1f}%")
    print(f"  tiles skipped {skipped_tiles}/{tiles} ({frac_tiles*100:.0f}%)"
          f"   iteration work saved {frac_work*100:.0f}%")
    print(f"  tiles skipped that contained an escaping pixel: {unsound}")
    print()


if __name__ == "__main__":
    print(f"{TILE}px tiles, {COLS} angular samples, maxIter {MAX_ITER}\n")
    analyse(1e-4, 1e-3, "shallow strip rows")
    analyse(1e-7, 1e-6, "mid strip rows")
    analyse(1e-11, 1e-10, "deep strip rows (near the nucleus)")
