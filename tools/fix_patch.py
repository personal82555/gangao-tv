#!/usr/bin/env python3
"""补丁v2: my previous patch added a stray '}\n\n} else {' —— 移除多余的 '}'"""
import sys
P = sys.argv[1]
src = open(P).read()
bad = """                }
            }

            } else {"""
good = """                }
            } else {"""
assert bad in src, 'stray not found'
src = src.replace(bad, good, 1)
open(P, 'w').write(src)
print('FIXED')
