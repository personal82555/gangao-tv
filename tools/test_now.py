import re, base64, urllib.parse
html = open('/tmp/now24.html', encoding='utf8', errors='ignore').read()
mix = next(b for b in re.findall(r'<script>([\s\S]*?)</script>', html) if '.split' in b)

stmts = []; buf=''; ins=False
for ch in mix:
    if ch=='"':
        ins = not ins
        buf += ch
        continue
    if ch==';' and not ins:
        stmts.append(buf.strip()); buf=''
    else: buf += ch
if buf.strip(): stmts.append(buf.strip())

def eval_v2(expr):
    REV='.split("").reverse().join("")'
    result=[]; idx=0
    while True:
        q1=expr.find('"', idx)
        if q1<0: break
        q2=expr.find('"', q1+1)
        if q2<0: break
        lit=expr[q1+1:q2]
        is_rev=expr[q2+1:q2+1+len(REV)]==REV
        result.append(lit[::-1] if is_rev else lit)
        idx=q2+1+(len(REV) if is_rev else 0)
        while idx<len(expr) and expr[idx] in ' +.srne(-,)':
            idx+=1
    return ''.join(result)

assign={}
for stmt in stmts:
    m=re.match(r'^var\s+(\w+)\s*=\s*("(?:[^"]*)".*)?$', stmt)
    if m:
        name=m.group(1); expr=(m.group(2) or '').strip()
        if not expr: assign[name]=''; continue
        if expr.startswith('"'): assign[name]=eval_v2(expr); continue
        continue
    # 32-hex literal assignment (var or bare)
    m=re.match(r'^(\w+)\s*=\s*"([a-f0-9]{32})"$', stmt)
    if m: assign[m.group(1)]=m.group(2); continue

print('assign:', {k:(v[:30] if len(v)<31 else v[:30]+'..') for k,v in assign.items()})

vals=[v for v in assign.values() if v]
hex32=[v for v in vals if re.match(r'^[a-f0-9]{32}$', v)]
xKey = hex32[0]
oldToken = next((h for h in hex32 if h != xKey), hex32[0])   # 有些页 old==xkey?
newToken = next((v for v in vals if v.startswith('%')), '')
xorConst = re.findall(r'"([a-f0-9]{16})"', html)[0]
print(f'xKey={xKey} old={oldToken} newTok={newToken} c={xorConst}')

def b64(s): return base64.b64decode(s.encode()).decode('latin1')
def decrypt(enc):
    b1 = b64(enc[::-1])
    key = xKey + xorConst
    x=''.join(chr((ord(b1[i]) ^ ord(key[i%len(key)])) & 0xff) for i in range(len(b1)))
    b2 = b64(x)
    return urllib.parse.unquote(b2.replace('token='+oldToken, 'token='+newToken).replace(xKey, ''))

opts = re.findall(r'<option value="([^"]+)"[^>]*>線路\d*', html)
for i, o in enumerate(opts[:2]):
    print(f'L{i+1}:', decrypt(o)[:130])
