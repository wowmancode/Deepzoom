"""
Benchmarks the strip render by counting iterations.

There is no GPU here, but the strip shader is iteration-bound: what it costs is
overwhelmingly the number of z = z^2 + c steps it performs. That count is
hardware-independent, so counting it on the CPU measures the same thing the device
measures, and lets optimizations be compared before any shader is written.

Modelled around a real minibrot nucleus, since that is what the app's reference
snapping produces and it is the structure the whole cost profile depends on.
"""
import math
import numpy as np

# Period-3 nucleus on the real axis, with an atom about 1.5e-2 across.
NX, NY = -1.7548776662466927, 0.0

STEP = 2.0 * math.pi / 4096      # strip log-radius step, matches StripGeometry
TILE = 32
MAX_ITER = 5000


def escape(radii, angles, max_iter):
    """Iterations per point; max_iter means it never escaped."""
    r = radii[:, None]
    a = angles[None, :]
    c = (NX + r * np.cos(a)) + 1j * (NY + r * np.sin(a))
    z = np.zeros_like(c)
    iters = np.zeros(c.shape, dtype=np.int64)
    alive = np.ones(c.shape, dtype=bool)
    for _ in range(max_iter):
        z[alive] = z[alive] * z[alive] + c[alive]
        iters[alive] += 1
        alive &= (z.real * z.real + z.imag * z.imag <= 4.0)
        if not alive.any():
            break
    return iters, alive


def row_cost(iters, interior, cols):
    """Iterations to render these rows with no skipping at all."""
    return iters.sum()


def cost_row_skip(iters, interior):
    """
    Current implementation: a fully-interior row proves every row below it interior,
    so those rows cost nothing beyond the probes that found the boundary.
    """
    rows = iters.shape[0]
    # Highest fully-interior row.
    ceiling = -1
    for i in range(rows):
        if interior[i].all():
            ceiling = i
        else:
            break
    if ceiling < 0:
        return iters.sum(), 0
    # Probes are binary search over the range: about log2(rows) rows rendered.
    probes = max(1, int(math.log2(max(rows, 2))))
    probe_cost = iters[:ceiling + 1].mean(axis=1).mean() * iters.shape[1] * probes
    return iters[ceiling + 1:].sum() + probe_cost, ceiling + 1


def cost_tile_skip(iters, interior):
    """32px tile skipping applied to whatever rows remain."""
    rows, cols = iters.shape
    total = 0
    for ty in range(0, rows, TILE):
        for tx in range(0, cols, TILE):
            b = interior[ty:ty + TILE, tx:tx + TILE]
            c = iters[ty:ty + TILE, tx:tx + TILE]
            if b.size == 0:
                continue
            border = np.concatenate([b[0, :], b[-1, :], b[:, 0], b[:, -1]])
            if border.all():
                # Only the border is iterated.
                total += c[0, :].sum() + c[-1, :].sum() + c[:, 0].sum() + c[:, -1].sum()
            else:
                total += c.sum()
    return total


def band(lo, hi, rows, cols, label):
    radii = np.geomspace(lo, hi, rows)
    angles = np.linspace(0.0, 2.0 * np.pi, cols, endpoint=False)
    iters, alive = escape(radii, angles, MAX_ITER)
    interior = alive

    base = iters.sum()
    rowskip, skipped = cost_row_skip(iters, interior)
    tileskip = cost_tile_skip(iters, interior)
    both = cost_tile_skip(iters[skipped:], interior[skipped:]) if skipped else tileskip
    if skipped:
        probes = max(1, int(math.log2(max(rows, 2))))
        both += iters[:skipped].mean(axis=1).mean() * cols * probes

    print(f"{label}  radii {lo:.0e}..{hi:.0e}")
    print(f"  mean iters/px {iters.mean():8.0f}   interior {interior.mean()*100:5.1f}%")
    print(f"  no skipping        {base/1e6:9.1f} M iterations")
    print(f"  row skipping       {rowskip/1e6:9.1f} M  ({(1-rowskip/base)*100:5.1f}% saved)")
    print(f"  tile skipping      {tileskip/1e6:9.1f} M  ({(1-tileskip/base)*100:5.1f}% saved)")
    print(f"  row + tile         {both/1e6:9.1f} M  ({(1-both/base)*100:5.1f}% saved)")
    print()
    return base


if __name__ == "__main__":
    print(f"strip cost model, maxIter {MAX_ITER}, {TILE}px tiles\n")
    band(1e-6, 1e-3, 128, 256, "deep   (inside the atom)")
    band(3e-3, 2e-2, 128, 256, "atom boundary           ")
    band(3e-2, 3e-1, 128, 256, "outer structure         ")
    band(5e-1, 3e+0, 128, 256, "shallow (mostly escape) ")
