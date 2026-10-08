# 修正 Kotlin 字符串引号转义 (REV 应该是 ".split(\"\").reverse().join(\"\")")
src_file = '/vol1/1000/HD1/APP/iptv-tv-app/app/src/main/java/com/iptv807/tv/SourceResolver.kt'
s = open(src_file).read()

wrong = 'val REV = ".split(\\\\"\\\\").reverse().join(\\\\"\\\\")"'
right = 'val REV = ".split(\\"\\").reverse().join(\\"\\")"'
s = s.replace(wrong, right)
open(src_file, 'w').write(s)
print('fixed REV string literal')
