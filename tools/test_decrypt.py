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
            is_rev = expr[close+1:close+1+len(REV)+3] .startswith(REV)
            out.append(lit[::-1] if is_rev else lit)
            i = close + 1 + (len(REV) if is_rev else 0)
        else:
            i += 1
    return ''.join(out)

# 保护引号、按分号拆
stmts = re.split(r';(?=(?:[^"]*"[^"]*")*[^"]*$)', mix)
stmts = [s.strip() for s in stmts if s.strip()]

assign = {}
for stmt in stmts:
    m = re.match(r'^var\s+(\w+)\s*=\s*"([^"]*)"$', stmt)
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
    print("UNMATCHED:", stmt[:80])

print('assign:', assign)
