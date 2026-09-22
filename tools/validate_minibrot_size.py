def est(cx, cy, period, variant):
    px=py=0.0; lx,ly=1.0,0.0; bx,by=1.0,0.0
    for i in range(1, period):
        t = px*px - py*py + cx
        py = 2.0*px*py + cy
        px = t
        nlx = 2.0*(px*lx - py*ly); nly = 2.0*(px*ly + py*lx)
        lx,ly = nlx,nly
        den = lx*lx + ly*ly
        if den == 0: return None
        bx += lx/den; by += -ly/den
    if variant == "b*l":
        dx = bx*lx - by*ly; dy = bx*ly + by*lx
    else:                                   # b * l^2
        l2x = lx*lx - ly*ly; l2y = 2.0*lx*ly
        dx = bx*l2x - by*l2y; dy = bx*l2y + by*l2x
    den = dx*dx + dy*dy
    return den**-0.5 if den else None

cases = [
    ("period 1 cardioid (width 1.0)",      0.0, 0.0, 1),
    ("period 2 disc (width 0.5)",         -1.0, 0.0, 2),
    ("period 3 island (width ~0.017)",    -1.7548776662466927, 0.0, 3),
    ("period 4 island",                   -1.3107026413368329, 0.0, 4),
]
print(f"{'':34s} {'b*l':>12s} {'b*l^2':>12s}")
for name, cx, cy, p in cases:
    a = est(cx,cy,p,"b*l"); b = est(cx,cy,p,"b*l2")
    print(f"{name:34s} {a:12.6g} {b:12.6g}")
