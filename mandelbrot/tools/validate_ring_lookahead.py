"""Checks the lookahead never evicts rows the asking frame still needs."""
import math, random
def window(ring, winH, lo, ahead_cap):
    hi = lo + winH - 1
    slack = ring - (hi - lo + 1) - 1
    ahead = min(ahead_cap, max(0, slack))
    return hi, ahead

random.seed(3)
bad = 0; tested = 0
for _ in range(20000):
    ring = random.choice([1024, 2048, 4096, 8192, 16384])
    winH = random.randint(1, ring)          # window may fill the whole ring
    lo = random.randint(0, 10**6)
    hi, ahead = window(ring, winH, lo, 512)
    target = hi + ahead                      # ascending build
    newLo = target - ring + 1                # rows evicted below this
    tested += 1
    if newLo > lo:                           # evicted a row this frame needs
        bad += 1
    # descending
    t2 = max(0, lo - ahead)
    newHi = t2 + ring - 1
    if newHi < hi:
        bad += 1
print(f"tested {tested} ring/window combinations, violations: {bad}")
