import sys, math, random, io, contextlib
sys.path.insert(0, "/home/claude/mandelbrot/tools")
buf = io.StringIO()
with contextlib.redirect_stdout(buf):
    import validate_bla as V
import lookup_experiment as L

def ok(bla,k,m,dzMag,stat):
    idx = bla["offsets"][k] + ((m-1)>>k); stat[0]+=1
    return dzMag < bla["rr"][idx]*0.7

def lookup_warm(bla, m, n, dzMag, maxiter, stat, state):
    """Resume the level search from where the previous iteration settled.

    dz grows smoothly, so the highest valid level drifts by a step at a time. Starting
    from the last answer turns the search into a short local adjustment instead of a
    walk from either end of the level range.
    """
    kmax = L._limits(bla, m, n, maxiter)
    if kmax < 0:
        return -1, 0
    k = state[0]
    if k > kmax: k = kmax
    if k < 0: k = 0
    if ok(bla,k,m,dzMag,stat):
        while k+1 <= kmax and ok(bla,k+1,m,dzMag,stat):
            k += 1
        state[0] = k
        return bla["offsets"][k]+((m-1)>>k), 1<<k
    while k > 0:
        k -= 1
        if ok(bla,k,m,dzMag,stat):
            state[0] = k
            return bla["offsets"][k]+((m-1)>>k), 1<<k
    state[0] = 0
    return -1, 0

def render(zx,zy,count,bla,dcx,dcy,maxiter,fn,stat,warm):
    dzx=dzy=0.0; m=n=0; state=[0]
    while n<maxiter:
        dzMag=max(abs(dzx),abs(dzy)); chosen,skip=-1,0
        if bla is not None and m>=1:
            chosen,skip = fn(bla,m,n,dzMag,maxiter,stat,state) if warm else fn(bla,m,n,dzMag,maxiter,stat)
        if chosen>=0:
            ax,ay=bla["ax"][chosen],bla["ay"][chosen]; bx,by=bla["bx"][chosen],bla["by"][chosen]
            dzx,dzy=(ax*dzx-ay*dzy+(bx*dcx-by*dcy), ax*dzy+ay*dzx+(bx*dcy+by*dcx))
            n+=skip; m+=skip
        else:
            z2x,z2y=2.0*zx[m],2.0*zy[m]
            sx=dzx*dzx-dzy*dzy; sy=2.0*dzx*dzy
            dzx,dzy=(z2x*dzx-z2y*dzy+sx+dcx, z2x*dzy+z2y*dzx+sy+dcy)
            n+=1; m+=1
        zsx,zsy=zx[m]+dzx,zy[m]+dzy
        if max(abs(zsx),abs(zsy))>8.0: return n
        if max(abs(zsx),abs(zsy))<max(abs(dzx),abs(dzy)) or m>=count:
            dzx,dzy=zsx,zsy; m=0; state[0]=0
    return maxiter

for span,maxiter in [(1e-8,2000),(1e-14,4000),(1e-20,8000),(1e-28,12000)]:
    zx,zy,count=V.reference_orbit(V.CX,V.CY,maxiter)
    aspect=0.5; maxc=0.5*span*math.hypot(1.0,aspect)*8.0
    bla=V.build_bla(zx,zy,count,maxc)
    if bla is None: continue
    random.seed(11)
    px=[(random.uniform(-.5,.5)*span*aspect, random.uniform(-.5,.5)*span) for _ in range(120)]
    r={}
    for name,fn,warm in [("climb",L.lookup_climb,False),("descend",L.lookup_descend,False),("warm",lookup_warm,True)]:
        st=[0]; res=[render(zx,zy,count,bla,x,y,maxiter,fn,st,warm) for x,y in px]
        r[name]=(res,st[0])
    s1=sum(1 for a,b in zip(r["climb"][0],r["warm"][0]) if a==b)
    c,d,w=r["climb"][1],r["descend"][1],r["warm"][1]
    print(f"span {span:.0e} lv{bla['levels']:2d}  climb {c:8,d}  descend {d:8,d}  warm {w:8,d}   "
          f"warm vs climb {c/w:5.2f}x   identical {s1}/{len(px)}")
