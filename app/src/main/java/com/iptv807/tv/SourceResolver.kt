package com.iptv807.tv

import android.util.Base64
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * iptv807 播放源本地解密 —— App 内直接解密，完全不经过 VPS
 * 
 * 解密步骤（Python mirror 端到端 verified）:
 *  1. GET https://m.iptv807.com/?act=play&tid=X&id=Y  (UA=Android mobile, Referer=m.iptv807.com)
 *  2. 找 <script>.split("") 混淆块, 逐语句 (quote-aware ; 分割)
 *  3. evalConcat: 双引号对 - 其后紧跟 .split("").reverse().join("") → 取反向值,并跳过整个 suffix(含 "" 内嵌引号不再被当作独立literal)
 *  4. 从 assignments 找:
 *      - 首个 32hex = xKey (加密 key)
 *      - 另一个 32hex = oldToken
 *      - 含 %XX 的长串 = newToken
 *  5. xorConst = html 里最早的 "16hex" 字面量
 *  6. decrypt option:
 *      reversed → base64 → XOR(key=xKey+xorConst) → base64 → eat token 替换 → xKey 剥离 → URLDecode UTF-8
 */
object SourceResolver {

    @Volatile private var cache: MutableMap<String, Cached>? = null
    private data class Cached(val lines: List<String>, val ts: Long)
    private const val TTL = 8 * 60 * 1000L

    private const val BASE = "https://m.iptv807.com/"
    private const val UA = "Mozilla/5.0 (Linux; Android 12; SM-S901B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    @Volatile var lastError: String = ""

    /** 磁盘兜底缓存（App 重启后仍可用上次成功的线路） */
    private var appCtx: android.content.Context? = null
    fun init(ctx: android.content.Context) { appCtx = ctx.applicationContext }

    private fun saveDisk(key: String, lines: List<String>) {
        runCatching {
            appCtx?.getSharedPreferences("iptv_lines", android.content.Context.MODE_PRIVATE)?.edit()
                ?.putString(key, org.json.JSONArray(lines).toString())
                ?.putLong("ts_$key", System.currentTimeMillis())
                ?.apply()
        }
    }
    private fun loadDisk(key: String): List<String>? = runCatching {
        val j = appCtx?.getSharedPreferences("iptv_lines", android.content.Context.MODE_PRIVATE)?.getString(key, null)
            ?: return null
        val arr = org.json.JSONArray(j)
        (0 until arr.length()).map { arr.getString(it) }.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** 兼容之前的 method signature */
    fun resolve(tid: String, id: String): List<String>? = resolveLines(tid, id)

    /** 强制丢弃缓存重新抓源（用于"缓存线路全失效"时） */
    fun resolveFresh(tid: String, id: String): List<String>? {
        cache?.remove("$tid-$id")
        return resolveLines(tid, id)
    }

    fun resolveLines(tid: String, id: String): List<String>? {
        val key = "$tid-$id"
        cache?.get(key)?.let { if (System.currentTimeMillis() - it.ts < TTL) return it.lines }

        synchronized(this) {
            // double-check
            cache?.get(key)?.let { if (System.currentTimeMillis() - it.ts < TTL) return it.lines }
            try {
                val url = "$BASE?act=play&tid=$tid&id=$id"
                val html = httpGet(url, BASE)

                // 1) script blocks - find mix
                val blocks = Regex("<script>([\\s\\S]*?)</script>").findAll(html).map { it.groupValues[1] }.toList()
                val mixBlock = blocks.firstOrNull { it.contains(".split(\"\")") } ?: run {
                    lastError = "no-mix-block"; return null
                }

                // 2) quote-aware split by ;
                val stmts = StringBuilder()
                var inStr = false
                for (c in mixBlock) {
                    when {
                        c == '"' -> { inStr = !inStr; stmts.append(c) }
                        c == ';' && !inStr -> { stmts.append('\n'); }
                        else -> stmts.append(c)
                    }
                }
                val statements = stmts.toString().split('\n').map { it.trim() }.filter { it.isNotEmpty() }

                // 3) extensions
                val REV = ".split(\"\").reverse().join(\"\")"
                fun evalConcat(s: String): String = buildString {
                    var i = 0
                    while (i < s.length) {
                        if (s[i] == '"') {
                            var j = i + 1; var lit = ""
                            while (j < s.length && s[j] != '"') { lit += s[j]; j++ }
                            if (j >= s.length) { append(lit); i = s.length; break }
                            val tail = s.substring((j + 1).coerceAtMost(s.length))
                            if (tail.startsWith(REV)) {
                                append(lit.reversed())
                                i = j + 1 + REV.length
                            } else {
                                append(lit)
                                i = j + 1
                            }
                            while (i < s.length && (s[i] == '+' || s[i] == ' ')) i++
                        } else i++
                    }
                }

                // 关键：rhs 原样交给 evalConcat（不要剥前导引号/尾部括号，那会破坏字面量配对）
                val assign = LinkedHashMap<String, String>()
                for (st in statements) {
                    if (st.isEmpty()) continue
                    val mVar = Regex("^var\\s+(\\w+)\\s*=\\s*(.*)$").find(st)
                    if (mVar != null) {
                        val name = mVar.groupValues[1]
                        val rhs = mVar.groupValues[2].trim()
                        assign[name] = when {
                            rhs.isEmpty() || rhs == "\"\"" -> ""
                            Regex("^\"[a-f0-9]{32}\"$").matches(rhs) -> rhs.substring(1, rhs.length - 1)
                            else -> evalConcat(rhs)
                        }
                        continue
                    }
                    val mCopy = Regex("^(\\w+)\\s*=\\s*(\\w+)$").find(st)
                    if (mCopy != null) {
                        assign[mCopy.groupValues[1]] = assign[mCopy.groupValues[2]] ?: ""
                        continue
                    }
                    val mHex = Regex("^(\\w+)\\s*=\\s*\"([a-f0-9]{32})\"$").find(st)
                    if (mHex != null) { assign[mHex.groupValues[1]] = mHex.groupValues[2]; continue }
                }

                val vals = assign.values.filter { it.isNotEmpty() }
                val hex32 = vals.filter { Regex("^[a-f0-9]{32}$").matches(it) }
                if (hex32.isEmpty()) { lastError = "no-hex32"; return null }
                val xKey = hex32[0]
                // ★★ 核心修复2：混淆块里常有 "xx = yy" 复制语句 → hex32[1] 可能 == xKey，
                //    那样 replace("token=$oldToken") 就匹配不到 → URL 保留过期旧 token → 全部 404。
                //    必须取"第一个不等于 xKey"的 32hex 才是真正的 oldToken。
                val oldToken = hex32.firstOrNull { it != xKey } ?: xKey
                val newToken = vals.firstOrNull {
                    it.contains(Regex("%[0-9A-Fa-f]{2}")) &&
                    !Regex("^[a-f0-9]{32}$").matches(it) &&
                    !Regex("^[a-f0-9]{16}$").matches(it) &&
                    it != xKey
                } ?: run { lastError = "no-newToken"; return null }

                val xorConst = Regex("\"([a-f0-9]{16})\"").findAll(html).map { it.groupValues[1] }.firstOrNull() ?: run {
                    lastError = "no-xorConst"; return null
                }

                // 4) decrypt options
                fun decrypt(encrypted: String): String? = try {
                    val rev = encrypted.reversed()
                    val b1 = String(Base64.decode(rev, Base64.DEFAULT), charset("ISO-8859-1"))
                    val key = xKey + xorConst
                    val sb = StringBuilder()
                    for (i in b1.indices) {
                        sb.append(((b1[i].toInt() and 0xFF) xor (key[i % key.length].toInt() and 0xFF)).toChar())
                    }
                    val b2raw = sb.toString()
                    val b2 = String(Base64.decode(b2raw, Base64.DEFAULT), charset("ISO-8859-1"))
                    var u = b2.replace("token=$oldToken", "token=$newToken")
                    u = u.replace(xKey, "")
                    // 兜底：若 token= 后仍是纯32位hex（旧token没替换成功），强制换成新 token
                    u = Regex("token=[a-f0-9]{32}").replace(u, "token=$newToken")
                    // ★★ 核心修复：b2 里 token 已是 %2B 编码形态，绝对不能再 URLDecoder（会把 %2B 解成 '+' 被服务端当空格 → 404）
                    u
                } catch (e: Exception) { null }

                val lines = Regex("<option value=\"([^\"]+)\"[^>]*>", RegexOption.IGNORE_CASE)
                    .findAll(html)
                    .mapNotNull { decrypt(it.groupValues[1]) }
                    .toList()
                if (lines.isEmpty()) { lastError = "decode-empty"; return null }

                if (cache == null) cache = ConcurrentHashMap()
                cache!![key] = Cached(lines, System.currentTimeMillis())
                saveDisk(key, lines)
                lastError = "ok ${lines.size} lines"
                return lines
            } catch (e: Exception) {
                // 网络波动时：用该频道最近一次成功的线路兜底（即使已过期）
                cache?.get(key)?.let { c ->
                    lastError = "网络波动，用上次缓存"
                    return c.lines
                }
                loadDisk(key)?.let { d ->
                    lastError = "网络波动，用磁盘缓存"
                    return d
                }
                lastError = "exception: ${e.javaClass.simpleName}: ${e.message?.take(120)}"
                return null
            }
        }
    }

    /** 验完整链：先 resolver，再对 line[0] HEAD拉流确认 */
    fun verifyChain(tid: String, id: String): String {
        val lines = resolveLines(tid, id) ?: return "resolve 错: $lastError"
        val u = lines.firstOrNull() ?: return "no lines"
        // HEAD / small byte read to verify FLV
        return try {
            val conn = (URL(u).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 10000
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Referer", BASE)
                setRequestProperty("Range", "bytes=0-63")
                setRequestProperty("Origin", "https://m.iptv807.com")
                setInstanceFollowRedirects(true)
            }
            val code = conn.responseCode
            val hdr = conn.getHeaderField("Location") ?: ""
            val magic = runCatching {
                val inn = conn.inputStream
                val bs = ByteArray(3); var n = 0
                while (n < 3) { val v = inn.read(); if (v < 0) break; bs[n++] = v.toByte() }
                String(bs)
            }.getOrElse { "" }
            conn.disconnect()
            when {
                code == 302 && hdr.startsWith("https://t") -> "ok 302"
                magic.startsWith("FLV") -> "ok direct FLV"
                else -> "direct code=$code magic=$magic"
            }
        } catch (e: Exception) { "stream err: ${e.message}" }
    }

    private fun httpGet(urlStr: String, referer: String?): String {
        var lastEx: Exception? = null
        for (attempt in 0 until 3) {
            try {
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 20000
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Accept", "text/html,*/*")
                    setRequestProperty("Connection", "close")
                    if (referer != null) setRequestProperty("Referer", referer)
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = try {
                    stream?.let { BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).readText() } ?: ""
                } finally { runCatching { stream?.close(); conn.disconnect() } }
                if (code !in 200..299) throw RuntimeException("HTTP $code")
                if (text.isNotEmpty()) return text
                lastEx = RuntimeException("empty body")
            } catch (e: Exception) {
                lastEx = e
            }
            try { Thread.sleep(700L * (attempt + 1)) } catch (ie: InterruptedException) {}
        }
        throw (lastEx ?: RuntimeException("unknown"))
    }
}
