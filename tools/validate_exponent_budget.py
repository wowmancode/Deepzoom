"""
How deep the shared-scale delta scheme can go before float32's exponent range runs out.

Deltas are float32 in units of one shared 2^k. Two ends bound the choice:

  top     a delta may reach bailout magnitude before the escape test fires, and the
          next plain step multiplies it by |2Z|, up to about 3.8. That product has to
          stay inside float32, which is what pins MAX_SCALE_EXP.

  bottom  the delta of a pixel one step from the reference must stay a normal float.
          TARGET_EXPONENT places the view's half-diagonal at 2^-TARGET, so a pixel sits
          TARGET + log2(width) below 1. Past 2^-126 it goes subnormal and starts
          shedding mantissa bits.

Only the bottom end limits depth, and it was set with far more room than it needs.
"""
import math

F32_MAX_EXP = 128
F32_MIN_NORMAL_EXP = -126
STRIP_WIDTH = 4096          # widest delta grid in use

def probe(target, maxscale, width=STRIP_WIDTH):
    min_span = 2.0 ** -(target + maxscale)
    top = math.log2(8.0 * 2.0 ** maxscale) + math.log2(3.8)
    one_pixel = -target - math.log2(width)
    return min_span, top, one_pixel

print(f"{'TARGET':>7} {'MAXSCALE':>9} {'min span':>12} {'depth':>8} "
      f"{'top 2^':>8} {'1px 2^':>8} {'spare':>7}  verdict")
for t, m in [(80, 120), (96, 120), (104, 120), (108, 120), (110, 120), (118, 120)]:
    ms, top, px = probe(t, m)
    spare = px - F32_MIN_NORMAL_EXP
    ok = ("TOP OVERFLOWS" if top >= F32_MAX_EXP
          else "1px SUBNORMAL" if spare <= 0
          else "tight" if spare < 4 else "ok")
    print(f"{t:7d} {m:9d} {ms:12.2e} {-math.log10(ms):8.1f} "
          f"{top:8.1f} {px:8.1f} {spare:7.1f}  {ok}")
