"""
Can series approximation still help once BLA is present?

SA replaces the opening stretch of the orbit: one Taylor series in dc, evaluated per
pixel, lands everyone at iteration N at once. BLA covers the same stretch by jumping,
and at the start dz is at its smallest, which is when BLA jumps are longest. So the
question is how far BLA already gets before its first plain step -- that prefix is the
only thing SA could replace, and anything beyond it SA cannot reach.
"""
import sys, math, random, io, contextlib
from decimal import Decimal, getcontext
sys.path.insert(0, "/home/claude/mandelbrot/tools")
with contextlib.redirect_stdout(io.StringIO()):
    import validate_bla as V

getcontext().prec = 60
CX = Decimal("-1.768490385585856928950283986324999229122")
CY = Decimal("-0.001621046083305245492004693258694734966812")
SPAN = 1.0003741395708394e-10
MAXITER = 8192
ASPECT = 1920.0/1080.0

zx, zy, count = V.reference_orbit(CX, CY, MAXITER)
bla = V.build_bla(zx, zy, count, 0.5*SPAN*math.hypot(1.0, ASPECT)*16.0)

def run(dcx, dcy):
    dzx=dzy=0.0; m=n=0; kw=0
    first_plain=None; longest=0; jumps=0; passes=0
    while n < MAXITER:
        passes+=1
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
        if chosen>=0:
            ax,ay=bla["ax"][chosen],bla["ay"][chosen]; bx,by=bla["bx"][chosen],bla["by"][chosen]
            dzx,dzy=(ax*dzx-ay*dzy+(bx*dcx-by*dcy), ax*dzy+ay*dzx+(bx*dcy+by*dcx))
            longest=max(longest,skip); jumps+=1
            n+=skip; m+=skip
        else:
            if first_plain is None and n>0: first_plain=n
            z2x,z2y=2.0*zx[m],2.0*zy[m]
            sqx=dzx*dzx-dzy*dzy; sqy=2.0*dzx*dzy
            dzx,dzy=(z2x*dzx-z2y*dzy+sqx+dcx, z2x*dzy+z2y*dzx+sqy+dcy)
            n+=1; m+=1
        zsx,zsy=zx[m]+dzx,zy[m]+dzy
        if max(abs(zsx),abs(zsy))>8.0: return first_plain,longest,passes,n
        if max(abs(zsx),abs(zsy))<max(abs(dzx),abs(dzy)) or m>=count:
            dzx,dzy=zsx,zsy; m=0; kw=0
    return first_plain,longest,passes,MAXITER

random.seed(31)
fp=[]; lo=[]; pa=[]
for _ in range(200):
    x=random.uniform(-.5,.5)*SPAN*ASPECT; y=random.uniform(-.5,.5)*SPAN
    f,l,p,it = run(x,y)
    fp.append(f if f is not None else it); lo.append(l); pa.append(p)
fp.sort()
print(f"iterations covered by BLA before the first plain step (cap {MAXITER}):")
print(f"   median {fp[len(fp)//2]:6d}    mean {sum(fp)/len(fp):8.0f}    max {max(fp):6d}")
print(f"longest single BLA jump:  median {sorted(lo)[len(lo)//2]:6d}   max {max(lo):6d}")
print(f"loop passes per pixel:    mean {sum(pa)/len(pa):8.0f}")
