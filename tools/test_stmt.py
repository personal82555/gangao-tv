import re, base64, urllib.parse

# 测试有 trailing join 的真实语句（android_proxy.html 实际数据）：
html = open('/tmp/android_proxy.html', encoding='utf8', errors='ignore').read()
blocks = re.findall(r'<script>([\s\S]*?)</script>', html)
mixBlock = next(b for b in blocks if '.split("")' in b)
stmts = [s.strip() for s in mixBlock.split(';') if s.strip()]
print('stmt[1] (dfuts):')
print(repr(stmts[1]))
print()

def eval_concat(expr):
    REV = '.split("").reverse().join("")'
    out = []; i = 0
    while i < len(expr):
        if expr[i] == '"':
            close = expr.find('"', i+1)
            if close < 0: break
            lit = expr[i+1:close]
            is_rev = expr[close+1:close+1+len(REV)] == REV
            out.append(lit[::-1] if is_rev else lit)
            i = close+1+(len(REV) if is_rev else 0)
        else:
            i += 1
    return ''.join(out)

# 正确assignment解析（Python顺利）：
assign = {}
for st in stmts:
    m = re.match(r'^var\s+(\w+)\s*=\s*"(.*)"$', st)
    if m:
        assign[m.group(1)] = eval_concat(m.group(2)); continue
    m = re.match(r'^var\s+(\w+)\s*=\s*(.*)"\)$', st)
    if m:
        assign[m.group(1)] = eval_concat(m.group(2)); continue
    m = re.match(r'^var\s+(\w+)\s*=\s*(.*)"$'.rstrip('"$')+'("\\)$)?$', st)
    if m and not m.group(0).endswith('\\') : pass
    m = re.match(r'^var\s+(\w+)\s*=\s*$', st)
    if m:
        assign[m.group(1)] = ''; continue
    m = re.match(r'^(\w+)\s*=\s*(\w+)$', st)
    if m:
        assign[m.group(1)] = assign.get(m.group(2), ''); continue
    m = re.match(r'^(\w+)\s*=\s*"([a-f0-9]{32})"$', st)
    if m:
        assign[m.group(1)] = m.group(2); continue

print('assign:', {k: (v[:20]+'..' if len(v) > 20 else v) for k, v in assign.items()})
