# 修 Kotlin evalConcat — 用 Python 里的 v4 版本的精确 Kotlin 翻译替换
src_file = '/vol1/1000/HD1/APP/iptv-tv-app/app/src/main/java/com/iptv807/tv/SourceResolver.kt'
s = open(src_file).read()

old_fn = '''            fun evalConcat(s: String): String = StringBuilder().also { sb ->
                var i = 0
                while (i < s.length) {
                    if (s[i] == '"') {                       // 处理字符串字面量
                        val close = s.indexOf('"', i + 1)
                        if (close < 0) break
                        val lit = s.substring(i + 1, close)
                        val tail = s.substring((close + 1).coerceAtMost(s.length))
                        val isRev = tail.startsWith(".split(\\\\"\\\\").reverse().join(\\\\"\\\\")")
                        sb.append(if (isRev) lit.reversed() else lit)
                        i = if (isRev) close + 1 + ".split(\\\\"\\\\").reverse().join(\\\\"\\\\")".length else close + 1
                    } else i++
                }
            }.toString()'''

new_fn = '''            fun evalConcat(s: String): String = StringBuilder().also { sb ->
                val REV = ".split(\\\\"\\\\").reverse().join(\\\\"\\\\")"
                var i = 0
                while (i < s.length) {
                    if (s[i] == '"') {
                        var j = i + 1
                        var lit = ""
                        while (j < s.length && s[j] != '"') { lit += s[j]; j++ }
                        if (j >= s.length) {
                            sb.append(lit)
                            i = s.length
                        } else {
                            val tail = s.substring((j + 1).coerceAtMost(s.length))
                            if (tail.startsWith(REV)) {
                                sb.append(lit.reversed())
                                i = j + 1 + REV.length
                            } else {
                                sb.append(lit)
                                i = j + 1
                            }
                            while (i < s.length && (s[i] == '+' || s[i] == ' ')) i++
                        }
                    } else i++
                }
            }.toString()'''

if old_fn in s:
    s = s.replace(old_fn, new_fn, 1)
    open(src_file, 'w').write(s)
    print('REPLACED OK')
else:
    print('old_fn NOT FOUND, doing alternative approach')
    # Fallback: locate the evalConcat definition and replace all lines
    import re
    m = re.search(r'fun evalConcat\(s: String\): String = StringBuilder\(\)\.also \{ sb ->[\s\S]*?\}\.toString\(\)', s)
    if m:
        print('found block len:', len(m.group(0)))
        s = s.replace(m.group(0), new_fn.strip('            '))
        open(src_file, 'w').write(s)
        print('REPLACED via regex')
