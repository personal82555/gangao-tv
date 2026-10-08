import re, base64, urllib.parse

html = open('/tmp/android_proxy.html', encoding='utf8', errors='ignore').read()
mix = next(b for b in re.findall(r'<script>([\s\S]*?)</script>', html) if '.split("")' in b)

# 修正版 eval：游标从 expr 起点扫描，遇 quote 开literal，遇REV tag 反转
def eval_v3(expr):
    REV = '.split("").reverse().join("")'
    frags = []
    i = 0
    while i < len(expr):
        if expr[i] == '"':
            j = i + 1; lit = ''
            while j < len(expr) and expr[j] != '"':
                lit += expr[j]; j += 1
            if j >= len(expr):
                frags.append(lit); break   # unterminated - take rest
            rest = expr[j+1:j+1+50]
            is_rev = rest.startswith(REV)
            frags.append(lit[::-1] if is_rev else lit)
            i = j + 1 + (len(REV) if is_rev else 0)
            while i < len(expr) and expr[i] == '+':
                i += 1
        else:
            i += 1
    return ''.join(frags)

assert eval_v3('"b8"+"fb"+"f".split("").reverse().join("")+"c29e"') == 'b8fbfe92c', "test failed"
print('v3 test OK')

assign = {}
for st in [s.strip() for s in mix.split(';') if s.strip()]:
    m = re.match(r'^var\s+(\w+)\s*=\s*(.*)$', st)
    if m:
        name = m.group(1); rhs = m.group(2).strip()
        if not rhs or rhs == '"':
            assign[name] = ''; continue
        # rhs 开头是 "，保留整段从quote开始
        if rhs.startswith('"'):
            assign[name] = eval_v3(rhs)
            continue
        m2 = re.match(r'^"([a-f0-9]{32})"$', rhs)
        if m2:
            assign[name] = m2.group(1); continue
        continue
    m = re.match(r'^(\w+)\s*=\s*(\w+)$', st)
    if m:
        assign[m.group(1)] = assign.get(m.group(2), '')
        continue
    m = re.match(r'^(\w+)\s*=\s*"([a-f0-9]{32})"$', st)
    if m:
        assign[m.group(1)] = m.group(2)
        continue
    print('un:', st[:40])

print('assign:', {k: (v[:25]+'. .' if len(v) > 25 else v) for k, v in assign.items()})

vals = [v for v in assign.values() if v]
hex32 = [v for v in vals if re.match(r'^[a-f0-9]{32}$', v)]
xKey = hex32[0]
oldToken = next(h for h in hex32 if h != xKey)
newToken = next(v for v in vals if re.search(r'%[0-9A-Fa-f]{2}', v) and not re.match(r'^[a-f0-9]{32}$', v) and not re.match(r'^[a-f0-9]{16}$', v) and v != xKey)
xorConst = re.findall(r'"([a-f0-9]{16})"', html)[0]
print(f'xKey={xKey}\nnewTok={newToken[:30]}..\nold={oldToken[:10]}..\nc={xorConst}')

def b64(s): return base64.b64decode(s.encode()).decode('latin1')
def dec(e):
    b1 = b64(e[::-1])
    k = xKey + xorConst
    x = ''.join(chr((ord(b1[i]) ^ ord(k[i % len(k)])) & 0xff) for i in range(len(b1)))
    b2 = b64(x)
    return urllib.parse.unquote(b2.replace('token=' + oldToken, 'token=' + newToken).replace(xKey, ''))

opts = re.findall(r'<option value="([^"]+)"[^>]*>', html)
print('L1:', dec(opts[0])[:130])
