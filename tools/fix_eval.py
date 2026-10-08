import re

# Check Kotlin source resolver for same bug + apply v4-style fix:
src_file = '/vol1/1000/HD1/APP/iptv-tv-app/app/src/main/java/com/iptv807/tv/SourceResolver.kt'
s = open(src_file).read()

# 拿出当前 evalConcat 部分
anchor = '''            if (s[i] == '"') {                       // 处理字符串字面量
                        val close = s.indexOf('"', i + 1)
                        if (close < 0) break
                        val lit = s.substring(i + 1, close)
                        val tail = s.substring((close + 1).coerceAtMost(s.length))
                        val isRev = tail.startsWith(".split(\\"\\").reverse().join(\\"\\")")
                        sb.append(if (isRev) lit.reversed() else lit)
                        i = if (isRev) close + 1 + ".split(\\"\\").reverse().join(\\"\\")".length else close + 1
                    } else i++
                }'''

new = '''                    if (s[i] == '"') {
                        var j = i + 1; var lit = ""
                        while (j < s.length && s[j] != '"') { lit += s[j]; j++ }
                        if (j >= s.length) {
                            sb.append(lit)
                            i = s.length
                        } else {
                            val REV = ".split(\\"\\").reverse().join(\\"\\")"
                            val tail = s.substring((j+1).coerceAtMost(s.length))
                            if (tail.startsWith(REV)) {
                                sb.append(lit.reversed())
                                i = j + 1 + REV.length
                            } else {
                                sb.append(lit)
                                i = j + 1
                            }
                            while (i < s.length && (s[i] == '+' || s[i] == ' ')) i++
                        }
                    } else i++'''
assert anchor in s, 'anchor missing'
s = s.replace(anchor, new, 1)
open(src_file, 'w').write(s)
print('kotlin eval fixed')
