"""
Checks whether a periodicity test wrongly calls escaping points interior.

This is the check that should have been run before the test went into the shader. The
speed benchmark measured how much faster it was; it never measured whether it was
right. Black speckles in the render are pixels that escape but were reported interior.

Ground truth is the escape count with no periodicity test at all, at a generous
iteration budget. Any point that escapes but which the test stops early on is a false
interior -- a visible black dot where there should be colour.

The hard case is a point just outside a minibrot. Its orbit passes close to zero once
per period, drifting away slowly. Two such near-zero visits differ by very little in
absolute terms, so an absolute threshold fires even though the point is escaping.
"""
import numpy as np

NX, NY = -1.7548776662466927, 0.0     # period-3 nucleus
MAX_ITER = 200000


def truth(c, max_iter=MAX_ITER):
    """Escape iteration, or max_iter if it never escapes. No periodicity test."""
    z = np.zeros_like(c)
    out = np.full(c.shape, max_iter, dtype=np.int64)
    alive = np.ones(c.shape, dtype=bool)
    for n in range(max_iter):
        z[alive] = z[alive] * z[alive] + c[alive]
        esc = alive & (z.real * z.real + z.imag * z.imag > 4.0)
        out[esc] = n
        alive &= ~esc
        if not alive.any():
            break
    return out


def with_test(c, mode, eps, max_iter=MAX_ITER):
    """
    Returns True where the periodicity test declared the point interior.

    mode 'abs':  |z - hare| < eps            (what shipped, and what speckled)
    mode 'rel':  |z - hare| < eps * |z|      (scale-free)
    """
    z = np.zeros_like(c)
    hare = np.zeros_like(c)
    alive = np.ones(c.shape, dtype=bool)
    called_interior = np.zeros(c.shape, dtype=bool)
    period = 1
    limit = 1
    for n in range(1, max_iter + 1):
        z[alive] = z[alive] * z[alive] + c[alive]
        esc = alive & (z.real * z.real + z.imag * z.imag > 4.0)
        alive &= ~esc

        d = z - hare
        d2 = d.real * d.real + d.imag * d.imag
        if mode == "abs":
            hit = alive & (d2 < eps * eps)
        else:
            z2 = z.real * z.real + z.imag * z.imag
            hit = alive & (d2 < eps * eps * np.maximum(z2, 1e-300))
        called_interior |= hit
        alive &= ~hit

        period -= 1
        if period == 0:
            hare = z.copy()
            limit *= 2
            period = limit
        if not alive.any():
            break
    return called_interior


def check(radius, label, n=2000):
    ang = np.linspace(0.0, 2.0 * np.pi, n, endpoint=False)
    c = (NX + radius * np.cos(ang)) + 1j * (NY + radius * np.sin(ang))
    t = truth(c)
    escapes = t < MAX_ITER

    print(f"{label}  radius {radius:.3e}   escaping {escapes.mean()*100:5.1f}%"
          f"   median escape iter {int(np.median(t[escapes])) if escapes.any() else 0}")
    for mode, eps in (("abs", 1e-9), ("abs", 1e-12), ("rel", 1e-6), ("rel", 1e-8)):
        called = with_test(c, mode, eps)
        false_interior = (called & escapes).sum()
        missed = (~called & ~escapes).sum()
        print(f"    {mode} {eps:.0e}:  false black pixels {false_interior:5d}"
              f"   interior not detected {missed:5d}")
    print()


if __name__ == "__main__":
    # Just outside the atom: orbits escape, but only after very many iterations.
    check(1.6e-2, "just outside atom")
    check(1.75e-2, "further out      ")
    check(8e-3, "inside atom      ")
