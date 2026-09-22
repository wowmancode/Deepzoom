"""
Measures what derivative-based interior detection would save, on top of the skipping
already implemented.

An interior point is attracted to a periodic cycle, so its orbit eventually returns
close to where it was. Brent's cycle detection finds that cheaply: keep a saved point,
compare each new z against it, and replace the saved point whenever the iteration count
reaches the next power of two. A close return means the orbit is periodic, so the pixel
is interior and can stop.

Note this is NOT a derivative test. The obvious dz/dz0 = prod 2 z_k is identically zero
here because the Mandelbrot orbit starts at the critical point z0 = 0, so the first
factor is 2*0. Escaping points are unaffected either way; the cost is one complex
difference and magnitude test per iteration, about what the escape test already costs.

Only pixels that survive the existing tests matter: rows that are interior all the way
round are already filled for free, and so are tiles whose border never escapes. So the
comparison is made on what is left after both.
"""
import math
import numpy as np

NX, NY = -1.7548776662466927, 0.0     # period-3 nucleus
TILE = 32
MAX_ITER = 5000
DER_EPS2 = 1e-24                      # |dz|^2 below this counts as interior

# One extra complex difference and magnitude compare per iteration, against the
# iteration plus the escape test it already does.
DER_COST = 1.3
CYCLE_EPS2 = 1e-20


def run(radii, angles, track_derivative):
    r = radii[:, None]
    a = angles[None, :]
    c = (NX + r * np.cos(a)) + 1j * (NY + r * np.sin(a))
    z = np.zeros_like(c)
    saved = np.zeros_like(c)
    iters = np.zeros(c.shape, dtype=np.int64)
    alive = np.ones(c.shape, dtype=bool)
    escaped = np.zeros(c.shape, dtype=bool)
    next_save = 1
    for n in range(1, MAX_ITER + 1):
        z[alive] = z[alive] * z[alive] + c[alive]
        iters[alive] += 1
        esc = alive & (z.real * z.real + z.imag * z.imag > 4.0)
        escaped |= esc
        alive &= ~esc
        if track_derivative:
            d = z - saved
            done = alive & (d.real * d.real + d.imag * d.imag < CYCLE_EPS2)
            alive &= ~done
            if n == next_save:          # Brent: resave at each power of two
                saved = z.copy()
                next_save *= 2
        if not alive.any():
            break
    return iters, escaped


def surviving_mask(escaped):
    """Pixels not already removed by the circle test or the tile test."""
    interior = ~escaped
    rows, cols = interior.shape
    keep = np.ones_like(interior)

    # Circle test: rows interior all the way round, from the bottom up.
    for i in range(rows):
        if interior[i].all():
            keep[i, :] = False
        else:
            break

    # Tile test on what remains.
    for ty in range(0, rows, TILE):
        for tx in range(0, cols, TILE):
            b = interior[ty:ty + TILE, tx:tx + TILE]
            if b.size == 0 or not keep[ty:ty + TILE, tx:tx + TILE].any():
                continue
            border = np.concatenate([b[0, :], b[-1, :], b[:, 0], b[:, -1]])
            if border.all():
                inner = keep[ty + 1:ty + TILE - 1, tx + 1:tx + TILE - 1]
                inner[:] = False
    return keep


def band(lo, hi, label, rows=128, cols=256):
    radii = np.geomspace(lo, hi, rows)
    angles = np.linspace(0.0, 2.0 * np.pi, cols, endpoint=False)

    base_iters, escaped = run(radii, angles, False)
    det_iters, _ = run(radii, angles, True)
    keep = surviving_mask(escaped)

    base = base_iters[keep].sum()
    det = det_iters[keep].sum() * DER_COST
    interior_left = (~escaped)[keep].mean() * 100 if keep.any() else 0.0

    speedup = base / det if det else float("nan")
    print(f"{label}")
    print(f"  pixels left after existing skipping {keep.mean()*100:5.1f}%"
          f"   of those, interior {interior_left:5.1f}%")
    print(f"  now              {base/1e6:8.1f} M iteration-equivalents")
    print(f"  with detection  {det/1e6:8.1f} M  (x{DER_COST} per iteration)")
    print(f"  speedup          {speedup:8.2f}x")
    print()
    return base, det


if __name__ == "__main__":
    print(f"cycle-detection interior test, maxIter {MAX_ITER}, "
          f"per-iteration cost x{DER_COST}\n")
    tb, td = 0.0, 0.0
    for lo, hi, label in (
        (1e-6, 1e-3, "deep   (inside the atom)"),
        (3e-3, 2e-2, "atom boundary           "),
        (3e-2, 3e-1, "outer structure         "),
        (5e-1, 3e+0, "shallow (mostly escape) "),
    ):
        b, d = band(lo, hi, label)
        tb += b
        td += d
    print(f"across all bands: {tb/1e6:.1f} M -> {td/1e6:.1f} M, overall {tb/td:.2f}x")
