import re, base64, urllib.parse

html = open('/tmp/fresh2.html', encoding='utf8', errors='ignore').read()
mix = next(b for b in re.findall(r'<script>([\s\S]*?)</script>', html) if '.split("")' in b)

def eval_concat(expr):
    out = []; i = 0; REV = '.split("").reverse().join("")'
    while i < len(expr):
        if expr[i] == '"':
            close = expr.find('"', i + 1)
            if close < 0: break
            lit = expr[i+1:close]
            is_rev = expr[close+1:close+1+len(REV)+3].startswith(REV)
            out.append(lit[::-1] if is_rev else lit)
            i = close + 1 + (len(REV) if is_rev else 0)
        else:
            i += 1
    return ''.join(out)

# 手写分号分割（考虑引号）
stmts = []
buf = ''
in_str = False
for ch in mix:
    if ch == '"':
        in_str = not in_str
    if ch == ';' and not in_str:
        stmts.append(buf.strip()); buf = ''
    else:
        buf += ch
if buf.strip():
    stmts.append(buf.strip())

assign = {}
for stmt in stmts:
    m = re.match(r'^var\s+(\w+)\s*=\s*"(.*)"$', stmt)
    if m:
        assign[m.group(1)] = eval_concat(m.group(2))
        continue
    m = re.match(r'^var\s+(\w+)\s*=\s*"(.*)$', stmt)  # 末尾可能无close quote
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

print('assign:', {k: (v[:40] + '..' if len(v) > 40 else v) for k, v in assign.items()})

xKey = assign.get('fkeco') or ''
newTok = assign.get('uncmd') or ''
oldToken = next((v for v in assign.values() if re.match(r'^[a-f0-9]{32}$', v)), '')
xorConst = re.search(r'"([a-f0-9]{16})"', mix).group(1)

def b64str(s):
    return base64.b64decode(s.encode()).decode('latin1')

def decrypt(enc):
    b1 = b64str(enc[::-1])
    key = xKey + xorConst
    xored = ''.join(chr((ord(b1[i]) ^ ord(key[i % len(key)])) & 0xff) for i in range(len(b1)))
    b2 = b64str(xored)
    # token替换 + 去掉key字符串
    return urllib.parse.unquote(b2.replace('token=' + oldToken, 'token=' + newTok).replace(xKey, ''))

opts = re.findall(r'<option value="([^"]+)"', html)
print('line1:', decrypt(opts[0])[:130])
