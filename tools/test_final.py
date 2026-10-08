import re, base64, urllib.parse

html = open('/tmp/a1.html', encoding='utf8', errors='ignore').read()
mix = next(b for b in re.findall(r'<script>([\s\S]*?)</script>', html) if '.split("")' in b)
stmts = [s.strip() for s in mix.split(';') if s.strip()]

REV = '.split("").reverse().join("")'
def ev(expr):
    out = []; i = 0
    while i < len(expr):
        if expr[i] == '"':
            j = i + 1; lit = ''
            while j < len(expr) and expr[j] != '"':
                lit += expr[j]; j += 1
            if j >= len(expr):
                out.append(lit); break
            rest = expr[j+1:j+1+50]
            if rest.startswith(REV):
                out.append(lit[::-1]); i = j + 1 + len(REV)
            else:
                out.append(lit); i = j + 1
            while i < len(expr) and expr[i] in '+ ':
                i += 1
        else:
            i += 1
    return ''.join(out)

assign = {}
for st in stmts:
    m = re.match(r'^var\s+(\w+)\s*=\s*$', st)
    if m: assign[m.group(1)] = ''; continue
    m = re.match(r'^var\s+(\w+)\s*=\s*"(.*)"$', st)
    if m:
        assign[m.group(1)] = ev(m.group(2)); continue
    m = re.match(r'^var\s+(\w+)\s*=\s*"(.*)"', st)  # no closing
    if m:
        assign[m.group(1)] = ev(m.group(2)); continue
    m = re.match(r'^(\w+)\s*=\s*(\w+)$', st)
    if m:
        assign[m.group(1)] = assign.get(m.group(2), ''); continue
    m = re.match(r'^(\w+)\s*=\s*"([a-f0-9]{32})"$', st)
    if m:
        assign[m.group(1)] = m.group(2); continue
    print('unmet:', st[:50])

print('assign:', {k: (v[:20]+'..' if len(v) > 20 else v) for k, v in assign.items()})

# 按 JS 语言语义变量：
# xKey    = cdgsc (=jvfon_eval)
# oldToken= jbdjv (裸 32hex)
# newToken= xptcr (=xcyvc_eval)
vals = [v for v in assign.values() if v]
hex32 = [v for v in vals if re.match(r'^[a-f0-9]{32}$', v)]
xKey = next(v for v in vals if re.match(r'^[a-f0-9]{32}$', v) and not v.startswith('%'))
# 修复: xKey应该是 jvfon_eval (32hex) — 从var(以 "(.*)" 成)来的
# oldToken = jbdjv bare(32hex) — 第二个 hex32
oldToken = next((h for h in hex32 if h != xKey), hex32[0])
newToken = next((v for v in vals if re.search(r'%[0-9A-Fa-f]{2}', v) and not re.match(r'^[a-f0-9]{32}$', v) and not re.match(r'^[a-f0-9]{16}$', v)), '')
print(f'xKey={xKey[:24]}...\noldTok={oldToken[:24]}...\nnewTok={newToken[:24]}...\nconst={re.findall(chr(34)+"([a-f0-9]{16})"+chr(34), html)[0]}')
xorConst = re.findall(r'"([a-f0-9]{16})"', html)[0]

def b64(s): return base64.b64decode(s.encode()).decode('latin1')
def dec(e):
    b1 = b64(e[::-1])
    k = xKey + xorConst
    x = ''.join(chr((ord(b1[i]) ^ ord(k[i % len(k)])) & 0xff) for i in range(len(b1)))
    b2 = b64(x)
    return urllib.parse.unquote(b2.replace('token=' + oldToken, 'token=' + newToken).replace(xKey, ''))

opts = re.findall(r'<option value="([^"]+)"[^>]*>', html)
print('L1:', dec(opts[0])[:135])
print('L2:', dec(opts[1])[:135])
