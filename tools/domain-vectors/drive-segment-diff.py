# Per-symbol line delta between two versions of a TS file (vault pass 2026-10-30
# item 5). Segments start at top-level/closure `function`s, the DataHome
# methods and top-level const/interface/type; a doc comment counts toward the
# segment ABOVE it, so read the numbers as "where the churn is", not as exact.
#
#   git show 7389f82:tools/domain-vectors/vendor/web-vault/driveDataHome.ts > /tmp/old.ts
#   python3 -I tools/domain-vectors/drive-segment-diff.py /tmp/old.ts \
#       tools/domain-vectors/vendor/web-vault/drive/driveDataHome.ts
import re,difflib,sys,json
def segs(path):
    lines=open(path).read().split('\n')
    pat=re.compile(r'^(?:export )?(?:async )?function ([A-Za-z0-9_]+)|^  (?:async )?function ([A-Za-z0-9_]+)|^    async (read|write|info)\(|^(?:export )?(?:const|interface|type) ([A-Za-z0-9_]+)')
    starts=[]
    for i,l in enumerate(lines):
        m=pat.match(l)
        if m:
            name=next(g for g in m.groups() if g)
            if m.group(3): name='DataHome.'+name
            starts.append((i,name))
    out={}
    for k,(i,name) in enumerate(starts):
        j=starts[k+1][0] if k+1<len(starts) else len(lines)
        # include preceding comment block
        s=i
        while s>0 and (lines[s-1].strip().startswith('*') or lines[s-1].strip().startswith('/**') or lines[s-1].strip().startswith('//')):
            s-=1
        out.setdefault(name,[]).extend(lines[i:j])
    return out
a=segs(sys.argv[1]); b=segs(sys.argv[2])
names=list(dict.fromkeys(list(b.keys())+list(a.keys())))
rows=[]
for n in names:
    x=a.get(n); y=b.get(n)
    if x is None: rows.append((n,'NEW',len(y),0)); continue
    if y is None: rows.append((n,'GONE',0,len(x))); continue
    plus=minus=0
    for d in difflib.ndiff(x,y):
        if d.startswith('+ '): plus+=1
        elif d.startswith('- '): minus+=1
    rows.append((n,'same' if plus==minus==0 else 'changed',plus,minus))
for r in rows: print('%-28s %-8s +%d -%d'%r)
