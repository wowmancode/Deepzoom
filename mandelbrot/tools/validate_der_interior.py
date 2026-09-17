"""
How early does the dz/dz1 derivative test call a pixel interior, versus the Brent
cycle detection already in the shader?

Both are exact-stop tests for interior points; escaping pixels are untouched by either.
So the only question is which fires sooner on the pixels that currently run to the cap.

Derivative recurrence: D_1 = 1, D_{n+1} = 2 w_n D_n with w = Z_m + z_n the full value.
Across a BLA jump of length l the same factor is exactly the A coefficient already
fetched, since z_{n+l} = A z_n + B c gives dz_{n+l}/dz_n = A. So it costs one complex
multiply on a plain step and nothing extra on a jump.
"""
import sys, math, random, io, contextlib
from decimal import Decimal, getcontext
sys.path.insert(0, "/home/claude/mandelbrot/tools")
buf = io.StringIO()
with contextlib.redirect_stdout(buf):
    import validate_bla as V

getcontext().prec = 60
CX = Decimal("-1.76852518952364282431236837176776582801")
CY = Decimal("-0.000645802518172428657688116959238859000131")
SPAN = 2.3127190993716446e-9
MAXITER = 5793
ASPECT = 1920.0/1080.0
DER_THRESHOLD = 1e-3

zx, zy, count = V.reference_orbit(CX, CY, MAXITER)
maxc = 0.5*SPAN*math.hypot(1.0, ASPECT)*16.0
bla = V.build_bla(zx, zy, count, maxc)

def run(dcx, dcy):
    """Returns (escaped, brent_stop, der_stop) iteration counts; None if never."""
    dzx=dzy=0.0; m=n=0; kw=0
    Dx, Dy = 1.0, 0.0
    brent=None; der=None
    sx=sy=0.0; period=1; periodLimit=1     # Brent, as the shader does it
    while n < MAXITER:
        dzMag=max(abs(dzx),abs(dzy)); chosen=-1; skip=0
        if m>=1:
            a=m-1; lv=bla["levels"]-1
            if a!=0: lv=min(lv,(a&-a).bit_length()-1)
            while lv>=0 and (n+(1<<lv)>MAXITER or (a>>lv)>=bla["counts"][lv]): lv-=1
            if lv>=0:
                k=min(max(kw,0),lv)
                def ok(kk): return dzMag < bla["rr"][bla["offsets"][kk]+((m-1)>>kk)]*0.7
                if ok(k):
                    while k+1<=lv and ok(k+1): k+=1
                    chosen=bla["offsets"][k]+((m-1)>>k); skip=1<<k
                else:
                    while k>0:
                        k-=1
                        if ok(k): chosen=bla["offsets"][k]+((m-1)>>k); skip=1<<k; break
                    if chosen<0: k=0
                kw=k
        # full value before the step, for the derivative factor
        wx, wy = zx[m]+dzx, zy[m]+dzy
        if chosen>=0:
            ax,ay=bla["ax"][chosen],bla["ay"][chosen]; bx,by=bla["bx"][chosen],bla["by"][chosen]
            dzx,dzy=(ax*dzx-ay*dzy+(bx*dcx-by*dcy), ax*dzy+ay*dzx+(bx*dcy+by*dcx))
            if n>=1: Dx,Dy = Dx*ax-Dy*ay, Dx*ay+Dy*ax
            n+=skip; m+=skip
        else:
            z2x,z2y=2.0*zx[m],2.0*zy[m]
            sqx=dzx*dzx-dzy*dzy; sqy=2.0*dzx*dzy
            dzx,dzy=(z2x*dzx-z2y*dzy+sqx+dcx, z2x*dzy+z2y*dzx+sqy+dcy)
            if n>=1:
                fx,fy = 2.0*wx, 2.0*wy
                Dx,Dy = Dx*fx-Dy*fy, Dx*fy+Dy*fx
            n+=1; m+=1
        zsx,zsy=zx[m]+dzx,zy[m]+dzy
        if max(abs(zsx),abs(zsy))>8.0:
            return n, brent, der
        if der is None and (Dx*Dx+Dy*Dy) < DER_THRESHOLD*DER_THRESHOLD:
            der = n
        if zsx==sx and zsy==sy and brent is None:
            brent = n
        period -= 1
        if period==0:
            sx,sy=zsx,zsy; periodLimit*=2; period=periodLimit
        if max(abs(zsx),abs(zsy))<max(abs(dzx),abs(dzy)) or m>=count:
            dzx,dzy=zsx,zsy; m=0; kw=0
    return None, brent, der

random.seed(21)
inter=0; bsum=0; dsum=0; bnone=0; dnone=0; both=0
N=600
for _ in range(N):
    px=random.uniform(-.5,.5)*SPAN*ASPECT; py=random.uniform(-.5,.5)*SPAN
    esc, b, d = run(px,py)
    if esc is not None: continue
    inter += 1
    if b is None: bnone+=1
    if d is None: dnone+=1
    if d is not None:
        both+=1; dsum+=d

print(f"{N} pixels, {inter} interior")
print(f"  never detected by Brent      : {bnone}")
print(f"  never detected by derivative : {dnone}")
if both:
    print(f"  derivative caught {both} of {inter} interior pixels")
    print(f"    mean stop iteration {dsum/both:8.0f} of cap {MAXITER}")
    print(f"    work saved on those {100*(1-dsum/both/MAXITER):5.1f}%")
