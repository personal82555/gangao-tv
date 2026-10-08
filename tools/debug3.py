import re
t = '"b8"+"fb"+"f".split("").reverse().join("")+"c29e".split("").reverse().join("")+"6e44c"'
REV = '.split("").reverse().join("")'
for i, m in enumerate(re.finditer(r'"([^"]*)"', t)):
    after = t[m.end():m.end()+len(REV)]
    print(f'{i}: lit={m.group(1)!r} after={after[:40]!r} rev={after.startswith(REV)}')
