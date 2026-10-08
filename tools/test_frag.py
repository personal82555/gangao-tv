import re, base64, urllib.parse

html = open('/tmp/android_proxy.html', encoding='utf8', errors='ignore').read()
mix = next(b for b in re.findall(r'<script>([\s\S]*?)</script>', html) if '.split("")' in b)

def eval_frags(expr):
    REVr = '.split("").reverse().join("")'
    out = []; i = 0
    while i < len(expr):
        m = re.match(r'"([^"]*)"(\.split\(""\)\.reverse\(\)\.join\(""\))?', expr[i:])
        if m:
            lit = m.group(1)
            is_rev = m.group(2) is not None
            out.append(lit[::-1] if is_rev else lit)
            i += m.end()
            while i < len(expr) and expr[i] in ' +':
                i += 1
        else:
            i += 1
    return ''.join(out)

# 真实数据验证：
assign = {}
for st in [s.strip() for s in mix.split(';') if s.strip()]:
    m = re.match(r'^var\s+(\w+)\s*=\s*(.*)$', st)
    if not m:
        m = re.match(r'^(\w+)\s*=\s*(.*)$', st)
        if m:
            name = m.group(1); rhs = m.group(2).strip().rstrip(')').strip()
            if rhs in assign: assign[name] = assign[rhs]
            continue
    else:
        name = m.group(1); rhs = m.group(2).strip().rstrip(')').strip()
        if not rhs: assign[name] = ''; continue
        eval_body = rhs
        if eval_body.startswith('"'): eval_body = eval_body[1:]  # strip leading quote (assignment's opening)
        # 还有末尾可能结尾在 .join("") 之后的 )

        assign[name] = eval_frags(eval_body)
        continue

print('assign:', {k: (v[:25]+'..' if len(v) > 25 else v) for k, v in assign.items()})

vals = [v for v in assign.values() if v]
hex32 = [v for v in vals if re.match(r'^[a-f0-9]{32}$', v)]
xKey = hex32[0]
oldToken = next(h for h in hex32 if h != xKey)
newToken = next(v for v in vals if re.search(r'%[0-9A-Fa-f]{2}', v) and not re.match(r'^[a-f0-9]{32}$', v) and not re.match(r'^[a-f0-9]{16}$', v) and v != xKey)
xorConst = re.findall(r'"([a-f0-9]{16})"', html)[0]
print(f'xKey={xKey} new={newToken[:26]} old={oldToken[:10]}.. c={xorConst}')

def b64(s): return base64.b64decode(s.encode()).decode('latin1')
def dec(e):
    b1 = b64(e[::-1])
    k = xKey + xorConst
    x = ''.join(chr((ord(b1[i]) ^ ord(k[i % len(k)])) & 0xff) for i in range(len(b1)))
    b2 = b64(x)
    return urllib.parse.unquote(b2.replace('token=' + oldToken, 'token=' + newToken).replace(xKey, ''))

opts = re.findall(r'<option value="([^"]+)"[^>]*>', html)
print('L1:', dec(opts[0])[:130])
