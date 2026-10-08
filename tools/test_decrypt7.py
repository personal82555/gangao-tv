import re, base64, urllib.parse

html = open('/tmp/fresh2.html', encoding='utf8', errors='ignore').read()
mix = next(b for b in re.findall(r'<script>([\s\S]*?)</script>', html) if '.split' in b)

stmts = []
buf = ''; inside = False
for ch in mix:
    if ch == '"':
        inside = not inside
        buf += ch
        continue
    if ch == ';' and not inside:
        stmts.append(buf.strip())
        buf = ''
        continue
    buf += ch
if buf.strip():
    stmts.append(buf.strip())

def eval_v2(expr):
    REV = '.split("").reverse().join("")'
    result = []; idx = 0
    while True:
        q1 = expr.find('"', idx)
        if q1 < 0: break
        q2 = expr.find('"', q1 + 1)
        if q2 < 0: break
        lit = expr[q1+1:q2]
        is_rev = expr[q2+1:q2+1+len(REV)] == REV
        result.append(lit[::-1] if is_rev else lit)
        idx = q2 + 1 + (len(REV) if is_rev else 0)
        while idx < len(expr) and expr[idx] in ' +.srne(--,)':
            idx += 1
    return ''.join(result)

assign = {}
for stmt in stmts:
    m = re.match(r'^var\s+(\w+)\s*=\s*("(?:[^"]*)".*)?$', stmt)
    if m:
        name = m.group(1); expr = (m.group(2) or '').strip()
        if not expr:
            assign[name] = ''; continue
        if expr.startswith('"'):
            assign[name] = eval_v2(expr)
            continue
        # 字面量 var xx = "32hex"
        m2 = re.match(r'^"([a-f0-9]{32})"$', expr)
        if m2:
            assign[name] = m2.group(1); continue
        continue
    m = re.match(r'^(\w+)\s*=\s*(\w+)$', stmt)
    if m:
        assign[m.group(1)] = assign.get(m.group(2), '')
        continue
    m = re.match(r'^(\w+)\s*=\s*"([a-f0-9]{32})"$', stmt)
    if m:
        assign[m.group(1)] = m.group(2)
        continue
    print('UNMATCHED:', stmt[:60])

print('assign:', {k: (v[:30] + '..' if len(v) > 30 else v) for k, v in assign.items()})

vals = [v for v in assign.values() if v]
xKey = next((v for v in vals if not re.match(r'^[a-f0-9]{32}$', v) and not v.startswith('%')), '')
newTok = next((v for v in vals if v.startswith('%')), '')
oldToken = next((v for v in vals if re.match(r'^[a-f0-9]{32}$', v)), '')
xorConst = re.search(r'"([a-f0-9]{16})"', mix).group(1)
print(f'xKey={xKey}\nnewTok={newTok}\nold={oldToken[:10]}.. const={xorConst}')

def b64str(s):
    return base64.b64decode(s.encode()).decode('latin1')

def decrypt(enc):
    b1 = b64str(enc[::-1])
    key = xKey + xorConst
    x = ''.join(chr((ord(b1[i]) ^ ord(key[i % len(key)])) & 0xff) for i in range(len(b1)))
    b2 = b64str(x)
    return urllib.parse.unquote(b2.replace('token=' + oldToken, 'token=' + newTok).replace(xKey, ''))

opts = re.findall(r'<option value="([^"]+)"', html)
if opts:
    print('line1:', decrypt(opts[0])[:130])
    print('line2:', decrypt(opts[1])[:130])
