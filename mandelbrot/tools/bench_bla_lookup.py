"""
Compares BLA level-search strategies on the real table.

The existing shader climbs levels from 0 and stops at the first failure. The set of
valid levels at a given start index is a prefix -- radii are non-increasing as levels
merge, and the alignment and bounds tests are monotone in k too -- so any search that
finds the same prefix end returns the same jump. That makes the choice purely a cost
question: how many radius texture fetches each strategy spends to find it.

Reuses the project's own validator so the table and the loop are the shipped ones.
"""
import sys, math, random
sys.path.insert(0, "/home/claude/mandelbrot/tools")

from validate_bla import (
    reference_orbit, build_bla, ground_truth, CX, CY, SPAN, MAXITER,
)

# Fetch counters, one per strategy.
STATS = {}


def _limits(bla, m, n, maxiter):
    """Highest level allowed by alignment and bounds, ignoring radius."""
    levels = bla["levels"]
    kmax = levels - 1
    a = m - 1
    if a != 0:
        # trailing zeros of (m-1): alignment allows level k iff (m-1) % 2^k == 0
        tz = (a & -a).bit_length() - 1
        kmax = min(kmax, tz)
    while kmax >= 0:
        step = 1 << kmax
        if n + step > maxiter or (a >> kmax) >= bla["counts"][kmax]:
            kmax -= 1
            continue
        break
    return kmax


def lookup_climb(bla, m, n, dzMag, maxiter, stat):
    """Current shader: climb from 0, stop at first failure."""
    chosen, skip = -1, 0
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
        stat[0] += 1
        if dzMag >= bla["rr"][idx] * 0.7:
            break
        skip, chosen = step, idx
    return chosen, skip


def _ok(bla, k, m, n, dzMag, stat):
    idx = bla["offsets"][k] + ((m - 1) >> k)
    stat[0] += 1
    return dzMag < bla["rr"][idx] * 0.7


def lookup_descend(bla, m, n, dzMag, maxiter, stat):
    """Start at the highest permitted level, walk down, take the first valid."""
    kmax = _limits(bla, m, n, maxiter)
    for k in range(kmax, -1, -1):
        if _ok(bla, k, m, n, dzMag, stat):
            return bla["offsets"][k] + ((m - 1) >> k), 1 << k
    return -1, 0


def lookup_hybrid(bla, m, n, dzMag, maxiter, stat):
    """Test the top level first, then binary search below it.

    Deep pixels sit far inside every radius, so the top level is usually valid and
    costs one fetch. When it is not, the prefix property makes binary search the
    cheapest way to find the edge, bounded by log2 of the level count.
    """
    kmax = _limits(bla, m, n, maxiter)
    if kmax < 0:
        return -1, 0
    if _ok(bla, kmax, m, n, dzMag, stat):
        return bla["offsets"][kmax] + ((m - 1) >> kmax), 1 << kmax
    lo, hi, best = 0, kmax - 1, -1
    while lo <= hi:
        mid = (lo + hi) // 2
        if _ok(bla, mid, m, n, dzMag, stat):
            best, lo = mid, mid + 1
        else:
            hi = mid - 1
    if best < 0:
        return -1, 0
    return bla["offsets"][best] + ((m - 1) >> best), 1 << best


def render_pixel(zx, zy, count, bla, dcx, dcy, maxiter, lookup, stat):
    dzx = dzy = 0.0
    m = n = 0
    while n < maxiter:
        dzMag = max(abs(dzx), abs(dzy))
        chosen, skip = -1, 0
        if bla is not None and m >= 1:
            chosen, skip = lookup(bla, m, n, dzMag, maxiter, stat)

        if chosen >= 0:
            a_x, a_y = bla["ax"][chosen], bla["ay"][chosen]
            b_x, b_y = bla["bx"][chosen], bla["by"][chosen]
            dzx, dzy = (a_x * dzx - a_y * dzy + (b_x * dcx - b_y * dcy),
                        a_x * dzy + a_y * dzx + (b_x * dcy + b_y * dcx))
            n += skip; m += skip
        else:
            zxm2, zym2 = 2.0 * zx[m], 2.0 * zy[m]
            sqx = dzx * dzx - dzy * dzy
            sqy = 2.0 * dzx * dzy
            dzx, dzy = (zxm2 * dzx - zym2 * dzy + sqx + dcx,
                        zxm2 * dzy + zym2 * dzx + sqy + dcy)
            n += 1; m += 1

        zsx, zsy = zx[m] + dzx, zy[m] + dzy
        if max(abs(zsx), abs(zsy)) > 8.0:
            return n
        if max(abs(zsx), abs(zsy)) < max(abs(dzx), abs(dzy)) or m >= count:
            dzx, dzy = zsx, zsy
            m = 0
    return maxiter


zx, zy, count = reference_orbit(CX, CY, MAXITER)
aspect = 0.5
maxc = 0.5 * SPAN * math.hypot(1.0, aspect) * 8.0
bla = build_bla(zx, zy, count, maxc)
print(f"reference orbit {count} points, BLA {bla['levels']} levels, "
      f"{bla['total']} entries")

strategies = [("climb (current)", lookup_climb),
              ("descend", lookup_descend),
              ("hybrid", lookup_hybrid)]

random.seed(7)
pixels = [(random.uniform(-0.5, 0.5) * SPAN * aspect,
           random.uniform(-0.5, 0.5) * SPAN) for _ in range(300)]

results = {}
for name, fn in strategies:
    stat = [0]
    outs = []
    for px, py in pixels:
        outs.append(render_pixel(zx, zy, count, bla, px, py, MAXITER, fn, stat))
    results[name] = (outs, stat[0])

base_out = results["climb (current)"][0]

print()
for name, _ in strategies:
    outs, fetches = results[name]
    same = sum(1 for a, b in zip(outs, base_out) if a == b)
    print(f"{name:18s} radius fetches {fetches:9,d}   "
          f"identical to current: {same}/{len(pixels)}")

base_fetches = results["climb (current)"][1]
print()
for name, _ in strategies[1:]:
    f = results[name][1]
    print(f"{name:18s} {base_fetches / f:.2f}x fewer radius fetches")
