import sys, math, io, contextlib
from decimal import Decimal, getcontext
sys.path.insert(0,"/home/claude/mandelbrot/tools")
with contextlib.redirect_stdout(io.StringIO()):
    import validate_bla as V
getcontext().prec=60
CX=Decimal("-1.768490385585856928950283986324999229122")
CY=Decimal("-0.001621046083305245492004693258694734966812")
SPAN=1.0003741395708394e-10; MAXITER=8192; ASPECT=1920.0/1080.0
maxc = 0.5*SPAN*math.hypot(1.0,ASPECT)*16.0
zx,zy,count = V.reference_orbit(CX,CY,MAXITER)
bla = V.build_bla(zx,zy,count,maxc)
print(f"maxC = {maxc:.3e}   EPS = {V.EPS:.3e}")
print(f"{'level':>5} {'entries':>8} {'nonzero r':>10} {'median r':>12} {'max r':>12}")
for k in range(bla["levels"]):
    o=bla["offsets"][k]; c=bla["counts"][k]
    rs=[bla["rr"][o+j] for j in range(c)]
    nz=[r for r in rs if r>0]
    med = sorted(nz)[len(nz)//2] if nz else 0.0
    print(f"{k:5d} {c:8d} {len(nz):10d} {med:12.3e} {max(rs):12.3e}")
# how big is dz in practice?
print(f"\ntypical |dc| (half-diagonal of the view) = {0.5*SPAN*math.hypot(1.0,ASPECT):.3e}")
# |Z| distribution -- radii are proportional to it
absZ=[math.hypot(zx[i],zy[i]) for i in range(1,min(count,4000))]
absZ.sort()
print(f"|Z| over the orbit: min {absZ[0]:.3e}  median {absZ[len(absZ)//2]:.3e}  max {absZ[-1]:.3e}")
