"""
Mirrors the Kotlin BLA construction and the GLSL iteration loop, then checks the
result against high-precision ground truth. Catches merge-formula and index-alignment
errors, which produce plausible-looking but wrong images.
"""
from decimal import Decimal, getcontext
import math, random

getcontext().prec = 60

# Classic seahorse-valley point.
CX = Decimal("-0.743643887037158704752191506114774")
CY = Decimal("0.131825904205311970493132056385139")

SPAN = 1e-20
MAXITER = 4000
EPS = 5.9604645e-08


def reference_orbit(cx, cy, maxiter):
    zx = [0.0] * (maxiter + 2)
    zy = [0.0] * (maxiter + 2)
    x = Decimal(0); y = Decimal(0)
    n = 0
    while n <= maxiter:
        zx[n] = float(x); zy[n] = float(y)
        x2 = x * x; y2 = y * y
        if float(x2 + y2) > 4.0:
            break
        nx = x2 - y2 + cx
        ny = 2 * x * y + cy
        x, y = +nx, +ny
        n += 1
    return zx, zy, min(n, maxiter)


def build_bla(zx, zy, count, maxc):
    count0 = count - 1
    if count0 < 1:
        return None
    counts = []
    c = count0
    while c >= 1 and len(counts) < 24:
        counts.append(c)
        if c == 1:
            break
        c //= 2
    levels = len(counts)
    offsets = []
    total = 0
    for k in range(levels):
        offsets.append(total)
        total += counts[k]

    ax = [0.0] * total; ay = [0.0] * total
    bx = [0.0] * total; by = [0.0] * total
    rr = [0.0] * total

    for j in range(counts[0]):
        m = j + 1
        zxm, zym = zx[m], zy[m]
        absZ = math.hypot(zxm, zym)
        a = 2.0 * absZ
        ax[j] = 2.0 * zxm; ay[j] = 2.0 * zym
        bx[j] = 1.0; by[j] = 0.0
        rr[j] = max(0.0, EPS * (absZ * 0.5 - maxc) / (a + 1.0))

    for k in range(1, levels):
        prev = offsets[k - 1]; cur = offsets[k]
        for j in range(counts[k]):
            xi = prev + 2 * j; yi = prev + 2 * j + 1; di = cur + j
            axy, ayy = ax[yi], ay[yi]
            axx, ayx = ax[xi], ay[xi]
            bxx, byx = bx[xi], by[xi]
            ax[di] = axy * axx - ayy * ayx
            ay[di] = axy * ayx + ayy * axx
            bx[di] = axy * bxx - ayy * byx + bx[yi]
            by[di] = axy * byx + ayy * bxx + by[yi]
            absAx = math.hypot(axx, ayx)
            absBx = math.hypot(bxx, byx)
            inner = (rr[yi] - absBx * maxc) / absAx if absAx > 0 else 0.0
            rr[di] = min(rr[xi], max(0.0, inner))

    # Disable entries whose coefficients would leave float range on the GPU.
    for i in range(total):
        if not (math.isfinite(ax[i]) and math.isfinite(ay[i])
                and math.isfinite(bx[i]) and math.isfinite(by[i])):
            rr[i] = 0.0
        elif math.hypot(ax[i], ay[i]) > 1e30 or math.hypot(bx[i], by[i]) > 1e30:
            rr[i] = 0.0
    return dict(ax=ax, ay=ay, bx=bx, by=by, rr=rr,
                offsets=offsets, counts=counts, levels=levels, total=total)


def render_pixel(zx, zy, count, bla, dcx, dcy, maxiter, use_bla=True):
    """Mirrors the GLSL loop, including rebasing and the BLA level climb."""
    dzx = dzy = 0.0
    m = 0; n = 0
    bla_steps = 0; plain_steps = 0

    while n < maxiter:
        dzMag = max(abs(dzx), abs(dzy))
        chosen = -1; skip = 0
        if use_bla and bla is not None and m >= 1:
            for k in range(bla["levels"]):
                step = 1 << k
                if ((m - 1) & (step - 1)) != 0:
                    break
                if n + step > maxiter:
                    break
                j = (m - 1) >> k
                if j >= bla["counts"][k]:
                    break
                idx = bla["offsets"][k] + j
                if dzMag >= bla["rr"][idx] * 0.7:
                    break
                skip = step; chosen = idx

        if chosen >= 0:
            a_x, a_y = bla["ax"][chosen], bla["ay"][chosen]
            b_x, b_y = bla["bx"][chosen], bla["by"][chosen]
            ndzx = a_x * dzx - a_y * dzy + (b_x * dcx - b_y * dcy)
            ndzy = a_x * dzy + a_y * dzx + (b_x * dcy + b_y * dcx)
            dzx, dzy = ndzx, ndzy
            n += skip; m += skip
            bla_steps += skip
        else:
            zxm2, zym2 = 2.0 * zx[m], 2.0 * zy[m]
            sqx = dzx * dzx - dzy * dzy
            sqy = 2.0 * dzx * dzy
            ndzx = zxm2 * dzx - zym2 * dzy + sqx + dcx
            ndzy = zxm2 * dzy + zym2 * dzx + sqy + dcy
            dzx, dzy = ndzx, ndzy
            n += 1; m += 1
            plain_steps += 1

        zsx = zx[m] + dzx
        zsy = zy[m] + dzy
        zMag = max(abs(zsx), abs(zsy))

        if zMag > 8.0:
            return n, bla_steps, plain_steps
        if zMag < max(abs(dzx), abs(dzy)) or m >= count:
            dzx, dzy = zsx, zsy
            m = 0
    return maxiter, bla_steps, plain_steps


def ground_truth(cx, cy, maxiter):
    x = Decimal(0); y = Decimal(0)
    for n in range(maxiter):
        x2 = x * x; y2 = y * y
        nx = x2 - y2 + cx
        ny = 2 * x * y + cy
        x, y = +nx, +ny
        if float(x * x + y * y) > 64.0:
            return n + 1
    return maxiter


zx, zy, count = reference_orbit(CX, CY, MAXITER)
print(f"reference orbit: {count} points")

aspect = 0.5
maxc = 0.5 * SPAN * math.hypot(1.0, aspect) * 8.0
bla = build_bla(zx, zy, count, maxc)
print(f"BLA: {bla['levels']} levels, {bla['total']} entries, counts={bla['counts'][:6]}...")

random.seed(7)
mismatch = 0
tested = 0
tot_bla = tot_plain = 0

for _ in range(300):
    px = random.uniform(-0.5, 0.5) * SPAN * aspect
    py = random.uniform(-0.5, 0.5) * SPAN
    n_bla, bs, ps = render_pixel(zx, zy, count, bla, px, py, MAXITER, use_bla=True)
    n_ref, _, _ = render_pixel(zx, zy, count, None, px, py, MAXITER, use_bla=False)
    truth = ground_truth(CX + Decimal(repr(px)), CY + Decimal(repr(py)), MAXITER)
    tot_bla += bs; tot_plain += ps
    tested += 1
    if abs(n_bla - truth) > 1 or abs(n_ref - truth) > 1:
        mismatch += 1
        if mismatch <= 5:
            print(f"  MISMATCH bla={n_bla} perturb={n_ref} truth={truth}")

print(f"\ntested {tested} pixels, {mismatch} mismatches")
print(f"iterations via BLA: {tot_bla}, via plain steps: {tot_plain}")
if tot_bla + tot_plain:
    print(f"loop iterations saved: {100*tot_bla/(tot_bla+tot_plain):.1f}%")
