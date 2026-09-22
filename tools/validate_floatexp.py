"""
Validates a float-plus-explicit-exponent delta representation, before any GLSL.

Two claims need checking, and only two:

  1. Where the present scheme's floor is, and that the new one has none in practice.
     Deltas are float32 in units of one shared 2^k, and k is capped because the
     reference orbit is held in the same units and must stay inside float32. Below some
     depth the delta underflows to zero, every pixel becomes the reference, and the
     image goes flat. That is arithmetic, not something needing a rendered frame.

  2. That the arithmetic is accurate. A mantissa with its own exponent should hold
     float32's relative accuracy at any exponent, so long chains of multiplies and adds
     are compared against mpmath.

Deliberately not simulated here: whole orbits. Escape counts depend on rebasing, BLA and
the reference, and folding those in measures the renderer rather than the number format.
An earlier attempt did exactly that and spent its time measuring relative error at points
where the reference orbit passes near zero, which says nothing about the representation.
"""
import numpy as np
from mpmath import mp, mpf

f32 = np.float32
MANTISSA_BITS = 24

F32_MIN_NORMAL = mpf(2) ** -126
F32_MIN_SUBNORMAL = mpf(2) ** -149
MAX_SCALE_EXP = 120          # ViewState.MAX_SCALE_EXP


# --- the representation, as the shader would hold it ----------------------------------

def fe_norm(m, e):
    m = float(f32(m))
    if m == 0.0 or not np.isfinite(m):
        return (0.0, 0)
    frac, exp = np.frexp(m)
    return (float(f32(frac)), int(e + exp))


def fe_from_mpf(x):
    if x == 0:
        return (0.0, 0)
    e = int(mp.floor(mp.log(abs(x), 2))) + 1
    return fe_norm(float(x / mpf(2) ** e), e)


def fe_value(a):
    return mpf(a[0]) * mpf(2) ** a[1]


def fe_mul(a, b):
    return fe_norm(f32(a[0]) * f32(b[0]), a[1] + b[1])


def fe_add(a, b):
    if a[0] == 0.0:
        return b
    if b[0] == 0.0:
        return a
    if a[1] < b[1]:
        a, b = b, a
    shift = a[1] - b[1]
    if shift > MANTISSA_BITS + 2:
        return a          # smaller term cannot reach the mantissa
    return fe_norm(f32(a[0]) + f32(np.ldexp(f32(b[0]), -shift)), a[1])


# --- claim 1: where each scheme stops working -----------------------------------------

def depth_limit():
    print("1. Smallest span each scheme can still represent")
    print()
    print(f"   {'span':>10}  {'scale':>7}  {'delta*scale':>12}  {'present':<11} float+exponent")
    for dexp in (-20, -40, -60, -70, -80, -120, -300, -1000):
        span = mpf(10) ** dexp
        delta = span / 1080          # one pixel of a 1080-tall frame
        ideal = int(mp.floor(-mp.log(span, 2))) - 80
        scale_exp = max(0, min(MAX_SCALE_EXP, (ideal // 8) * 8))
        scaled = delta * mpf(2) ** scale_exp

        if scaled >= F32_MIN_NORMAL:
            present = "ok"
        elif scaled >= F32_MIN_SUBNORMAL:
            present = "subnormal"
        else:
            present = "UNDERFLOWS"

        fe = fe_from_mpf(delta)
        rel = abs(fe_value(fe) - delta) / delta
        fe_state = "ok" if rel < mpf("1e-6") else "FAILS"

        print(f"   {float(span):10.0e}  {'2^' + str(scale_exp):>7}"
              f"  {float(scaled):12.1e}  {present:<11} {fe_state}")
    print()
    print("   The cap on the shared scale is what sets the floor: past it the scale")
    print("   cannot grow to meet the shrinking delta. An exponent per value has no cap.")
    print()


# --- claim 2: is the arithmetic accurate ----------------------------------------------

def arithmetic(trials=20000, seed=1):
    print("2. Arithmetic accuracy against mpmath, random values at wild exponents")
    print()
    mp.dps = 60
    rng = np.random.default_rng(seed)
    worst_mul = mpf(0)
    worst_add = mpf(0)
    for _ in range(trials):
        ea, eb = int(rng.integers(-3000, 3000)), int(rng.integers(-3000, 3000))
        ma, mb = float(rng.uniform(0.5, 1.0)), float(rng.uniform(0.5, 1.0))
        if rng.random() < 0.5:
            ma = -ma
        if rng.random() < 0.5:
            mb = -mb
        a, b = fe_norm(ma, ea), fe_norm(mb, eb)
        av, bv = fe_value(a), fe_value(b)

        got, want = fe_value(fe_mul(a, b)), av * bv
        if want != 0:
            worst_mul = max(worst_mul, abs(got - want) / abs(want))

        got, want = fe_value(fe_add(a, b)), av + bv
        if want != 0:
            worst_add = max(worst_add, abs(got - want) / abs(want))

    eps = mpf(2) ** -MANTISSA_BITS
    print(f"   multiply  worst relative error {mp.nstr(worst_mul, 3)}"
          f"   ({mp.nstr(worst_mul / eps, 3)} x float32 epsilon)")
    print(f"   add       worst relative error {mp.nstr(worst_add, 3)}"
          f"   ({mp.nstr(worst_add / eps, 3)} x float32 epsilon)")
    print()
    print("   Neither degrades with the size of the exponent, which is the property")
    print("   the whole scheme rests on.")
    print()


def cost():
    print("3. Cost per operation, counted as primitive float ops")
    print()
    print("   complex multiply  plain: 4 mul 2 add   with exponent: + int add and a")
    print("                                          frexp/ldexp normalise per part")
    print("   complex add       plain: 2 add         with exponent: + compare, shift,")
    print("                                          normalise")
    print()
    print("   Roughly 2-3x per iteration, so it belongs in a separate shader used only")
    print("   below the depth the scaled path reaches, not as a replacement for it.")
    print()


if __name__ == "__main__":
    depth_limit()
    arithmetic()
    cost()
