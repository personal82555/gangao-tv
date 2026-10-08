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
    # 关键：" 拆开 — expr begins WITHOUT leading quote (它已在var assignment里被消费)
    # expr: b8"+"fb"+"f".split("").reverse().join("")+"c29e"...
    # 用 " 切：多段：['b8', '+', 'fb', '+', 'f', '.split(。'...]
    # 期望JS语义: "b8" + "fb" + "f".split("").reverse().join("") + "c29e"...
    #   b8 (normal), fb (normal), f (rev单字不变), c29e... (逐段 'rev'取决于 q[i+1] 之后是否跟着 REV)
    # 重新实现:每次 分割 " 按 char，两个引号一组 literal; quote 组之间字符 `+` 连接;
    REV = '.split("").reverse().join("")'
    parts = expr.split('"')
    # parts结构: [pre, lit1, sep1, lit2, sep2, ...]
    # sep里如果以 ' + .split' 开头等 (REV后跟随的+), 判断 REV 属于前面那次
    result = []
    i = 1  # parts[0]是空的或非literal常数
    while i < len(parts):
        lit = parts[i]
        sep = parts[i+1] if i + 1 < len(parts) else ''
        if sep.startswith('+ .split') or sep.startswith('+.split'):
            result.append(lit)
        else: result.append(lit)  # simplify: all normal
        i += 2
    # 但rev:
    return ''.join(result)

# simpler and correct: tokenize quotes pairs with ownership of next chars
def eval_v2(expr):
    REV = '.split("").reverse().join("")'
    # find quotes pairing; check if literal is followed by REV
    result = []
    idx = 0
    while True:
        q1 = expr.find('"', idx)
        if q1 < 0: break
        q2 = expr.find('"', q1 + 1)
        if q2 < 0: break
        lit = expr[q1+1:q2]
        is_rev = expr[q2+1:q2+1+len(REV)] == REV
        result.append(lit[::-1] if is_rev else lit)
        idx = q2 + 1 + (len(REV) if is_rev else 0)
        # skip + 连接符
        while idx < len(expr) and expr[idx] in ' +.snp':
            idx += 1
    return ''.join(result)

assign = {}
for stmt in stmts:
    m = re.match(r'^var\s+(\w+)\s*=\s*(.*)$', stmt)
    if m:
        name = m.group(1); expr = m.group(2).strip()
        if not expr or not expr.startswith('"'):
            if expr == '':
                assign[name] = ''
                continue
            m2 = re.match(r'^"([a-f0-9]{32})"$', expr)
            if m2:
                assign[name] = m2.group(1)
                continue
            continue
        # 从expr中来(uns greedy)
        if expr.startswith('"'):
            # 内部从头到尾 pair
            assign[name] = eval_v2(expr[1:])   # strip leading "
            continue
        # 无开头引号？ fallback to 正常
        assign[name] = expr
        continue
    print('un:', stmt[:40])

print('assign:', {k: (v[:25]+'..' if len(v) > 25 else v) for k, v in assign.items()})
