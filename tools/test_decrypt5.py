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

def eval_concat(expr):
    REV = '.split("").reverse().join("")'
    out = []; i = 0
    while i < len(expr):
        if expr[i] == '"':
            close = expr.find('"', i + 1)
            if close < 0: break
            lit = expr[i+1:close]
            is_rev = expr[close+1:close+1+len(REV)].startswith(REV)
            out.append(lit[::-1] if is_rev else lit)
            i = close + 1 + (len(REV) if is_rev else 0)
        else:
            i += 1
    return ''.join(out)

assign = {}
for stmt in stmts:
    m = re.match(r'^var\s+(\w+)\s*=\s*"(.*)"$', stmt)
    if m:
        assign[m.group(1)] = eval_concat(m.group(2))
        continue
    m = re.match(r'^var\s+(\w+)\s*=\s*"(.*)"', stmt)   # no $ : allow trailing join("") suffix
    if m:
        assign[m.group(1)] = eval_concat(m.group(2))
        continue
    m = re.match(r'^var\s+(\w+)\s*=\s*$', stmt)
    if m:
        assign[m.group(1)] = ''
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

print('assign:', {k: (v[:35] + '..' if len(v) > 35 else v) for k, v in assign.items()})

vals = [v for v in assign.values() if v]
xKey = next(v for v in vals if not re.match(r'^[a-f0-9]{32}$', v) and not v.startswith('%'))
# newTok: also starts with % (encoded) — find it
newTok = next(v for v in vals if v.startswith('%'))
oldToken = next(v for v in vals if re.match(r'^[a-f0-9]{32}$', v))
xorConst = re.search(r'"([a-f0-9]{16})"', mix).group(1)
print(f'xKey={xKey}\nnewTok={newTok}\nold={oldToken[:10]}..\nconst={xorConst}')

def b64str(s):
    return base64.b64decode(s.encode()).decode('latin1')

def decrypt(enc):
    b1 = b64str(enc[::-1])
    key = xKey + xorConst
    x = ''.join(chr((ord(b1[i]) ^ ord(key[i % len(key)])) & 0xff) for i in range(len(b1)))
    b2 = b64str(x)
    # NOTE: 服务端只做 token=old→token=new 的字符串替换和 xKey "???"拼接剥离
    return urllib.parse.unquote(b2.replace('token=' + oldToken, 'token=' + newTok).replace(xKey, ''))

opts = re.findall(r'<option value="([^"]+)"', html)
print('line1:', decrypt(opts[0])[:130])
