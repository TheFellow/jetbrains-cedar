import json,os,sys
S=sys.argv[1]  # the work dir; compares S/dump/kotlin against S/dump/ts
def diff(a,b,p):
    if type(a)!=type(b) and not (isinstance(a,(int,float)) and isinstance(b,(int,float))):
        print(p,'TYPE',repr(a)[:200],'|',repr(b)[:200]); return 1
    if isinstance(a,dict):
        n=0
        for k in set(a)|set(b):
            if k not in a or k not in b: print(p+'/'+k,'MISSING', 'kotlin' if k not in a else 'ts', repr(a.get(k,b.get(k)))[:200]); n+=1
            else: n+=diff(a[k],b[k],p+'/'+k)
        return n
    if isinstance(a,list):
        if len(a)!=len(b):
            print(p,'LEN',len(a),len(b))
            for i,(x,y) in enumerate(zip(a,b)):
                if x!=y: print('  first diff',i,repr(x)[:200],'|',repr(y)[:200]); break
            else: print('  extra', repr((a[len(b):] or b[len(a):])[:3])[:300])
            return 1
        return sum(diff(x,y,f'{p}[{i}]') for i,(x,y) in enumerate(zip(a,b)))
    if a!=b: print(p,'VAL',repr(a)[:200],'|',repr(b)[:200]); return 1
    return 0
tot=0
for d,_,fs in os.walk(S+'/dump/kotlin'):
    for f in fs:
        k=os.path.join(d,f); t=k.replace('/kotlin/','/ts/')
        tot+=diff(json.load(open(k)),json.load(open(t)),os.path.relpath(k,S+'/dump/kotlin'))
print('total diffs',tot)
sys.exit(1 if tot else 0)
