s = '"aadf"+"c"'
REV = '.split("").reverse().join("")'
o = []; i = 0
while i < len(s):
    if s[i] == '"':
        c = s.find('"', i + 1)
        if c < 0: break
        lit = s[i+1:c]
        ir = s[c+1:c+1+len(REV)] == REV
        o.append(lit[::-1] if ir else lit)
        print(f'step: i={i} lit={lit!r} ir={ir} new_i={c+1+(len(REV) if ir else 0)}')
        i = c+1+(len(REV) if ir else 0)
    else:
        i += 1
print('joined=', ''.join(o))

# 测试复杂语句
s2 = '"aadf"+"c"+"9c75".split("").reverse().join("")+"b259"'
o2 = []; i = 0
while i < len(s2):
    if s2[i] == '"':
        c = s2.find('"', i + 1)
        if c < 0: break
        lit = s2[i+1:c]
        ir = s2[c+1:c+1+len(REV)] == REV
        o2.append(lit[::-1] if ir else lit)
        print(f'S2 i={i} lit={lit!r} ir={ir} new_i={c+1+(len(REV) if ir else 0)}')
        i = c+1+(len(REV) if ir else 0)
    else:
        i += 1
print('s2 joined=', ''.join(o2))
