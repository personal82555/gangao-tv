import re, base64, urllib.parse

html = open('/tmp/fresh2.html', encoding='utf8', errors='ignore').read()
blocks = re.findall(r'<script>([\s\S]*?)</script>', html)
mix = next(b for b in blocks if '.split' in b)

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
        while idx < len(expr) and expr[idx] in ' +.srne(-,)':
            idx += 1
    return ''.join(result)

assign = {}
for stmt in stmts:
    m = re.match(r'^var\s+(\w+)\s*=\s*("(?:[^"]*)".*)?$', stmt)
    if m:
        name = m.group(1); expr = (m.group(2) or '').strip()
        if not expr: assign[name] = ''; continue
        if expr.startswith('"'):
            assign[name] = eval_v2(expr)
            continue
        continue
    m = re.match(r'^(\w+)\s*=\s*(\w+)$', stmt)
    if m:
        assign[m.group(1)] = assign.get(m.group(2), '')
        continue
    m = re.match(r'^(\w+)\s*=\s*"([a-f0-9]{32})"$', stmt)
    if m:
        assign[m.group(1)] = m.group(2)
        continue

# 变量含义:
#   xKey  = urdlw后的 assign值 (fkeco) — 32 hex-lookin b8fbfe...? 不，b8fbfe...是 xKey? 长度34？看长度
candidate_vals = {k: v for k, v in assign.items() if v}
for k, v in candidate_vals.items():
    print(f'{k} len={len(v)}: {v[:40]}')

# xKey 从源代码里看是 KEY; 它有 literal "b8fbfe92cc44e64533fcf75e8010097e" — hmm长度=36?  page的 fbjao21拼接变32位hex
# but svg based on原始 code:
#   var xefgv = ""; var fbjao = "..拼接.."; xefgv = fbjao;           (xKey)
#   var qrtkk = ""; var lnzyw = "..拼接.."; qrtkk = lnzyw;           (newToken)
#   ouyit = "32hex" (oldToken)
# 所以 xKey / newTok 都是 mixed literals (not hex32)
# BUT实际assign里 fkeco='b8fbfe92cc44e64533fcf75e801009..'是一个32hex串! 那 xKey 也是hex32? — 注意newTok(last)='%' URL-encode形式。
# 因此爷就取:
xKey = next(v for v in assign.values() if v and re.match(r'^[a-f0-9]{32}$', v) and v != next(u for u in assign.values() if re.match(r'^[a-f0-9]{32}$', u)))

# Hmm confusing; more precisely:
vals = list(assign.values())
hex32s = [v for v in vals if re.match(r'^[a-f0-9]{32}$', v)]
print('hex32s:', hex32s)
# 常识：b8fbfe.. 是 xKey (from urdlw eval), old token '1c2e5965...'  another hex32
# New token = uncmd starting '%'
newTok = next((v for v in vals if v.startswith('%')), '')
oldToken = next((v for v in hex32s if v != 'b8fbfe92cc44e64533fcf75e8010097e'), '')
xKey = 'b8fbfe92cc44e64533fcf75e8010097e'   # hardcoded from first
xorConst = re.findall(r'"([a-f0-9]{16})"', html)[0]
print(f'xKey={xKey}\nnewTok={newTok}\nold={oldToken}\nconst={xorConst}')

def b64str(s): return base64.b64decode(s.encode()).decode('latin1')
def decrypt(enc):
    b1 = b64str(enc[::-1])
    key = xKey + xorConst
    x = ''.join(chr((ord(b1[i]) ^ ord(key[i % len(key)])) & 0xff) for i in range(len(b1)))
    b2 = b64str(x)
    return urllib.parse.unquote(b2.replace('token=' + oldToken, 'token=' + newTok).replace(xKey, ''))

opts = re.findall(r'<option value="([^"]+)"', html)
if opts:
    print('L1:', decrypt(opts[0])[:130])
    print('L2:', decrypt(opts[1])[:130])
