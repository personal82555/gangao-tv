package com.iptv807.tv

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * CardAuth 授权客户端（app.88531.cn:8881）
 *
 * 协议（已实测）：
 *   POST /api/public/verify
 *   Header: X-API-Key: ak_...(项目API密钥)
 *   Body: {"card_key":"...","machine_id":"TV-XXXX","device_info":"AndroidTV"}
 *   未用卡 → 自动激活绑定；已绑 → 校验machine_id一致 + 未过期
 *   响应 data: {valid, type, duration_days, expire_time, is_permanent, remaining_days}
 * 免费用户：无卡/无效卡 → 每天最多1小时（本地计时 + 服务器日期同步）
 */
object LicenseClient {
    private const val BASE = "http://app.88531.cn:8881"
    private const val API_KEY = "ak_563f4768d5d411d381e58bc3f76ba447"

    data class Result(
        val ok: Boolean,          // 通过授权（VIP） 
        val message: String,
        val expireTime: String? = null,
        val isPermanent: Boolean = false,
        val remainingDays: Int? = null,
        val trial: Boolean = false  // 走免费1小时
    )

    /** 测试卡密：输入该卡密直接永久授权（本地判定，不走服务器） */
    private const val TEST_KEY = "88531"

    /** 卡密验证/激活 */
    fun verify(cardKey: String, machineId: String): Result {
        if (cardKey.trim() == TEST_KEY) {
            return Result(true, "测试卡：永久有效", "永久", true, null, false)
        }
        return try {
            val body = JSONObject().apply {
                put("card_key", cardKey)
                put("machine_id", machineId)
                put("device_info", "AndroidTV ${Build.MODEL}")
            }
            val r = post("/api/public/verify", body)
            val data = r.optJSONObject("data") ?: JSONObject()
            when {
                r.optInt("code") == 200 && data.optBoolean("valid") ->
                    Result(true, data.optString("message", "授权有效"),
                        data.optString("expire_time", ""), data.optBoolean("is_permanent"),
                        if (data.has("remaining_days") && !data.isNull("remaining_days")) data.optInt("remaining_days") else null)
                else -> Result(false, data.optString("message", r.optString("message", "授权无效")).ifEmpty { "卡密无效" })
            }
        } catch (e: Exception) {
            Result(false, "网络错误: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val url = URL(BASE + path)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 10000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-API-Key", API_KEY)
            doOutput = true
            outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.let { BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).readText() } ?: ""
        conn.disconnect()
        return JSONObject(text)
    }

    /** 稳定机器码（安装期持久化+硬件融合） */
    fun machineCode(ctx: Context): String {
        val prefs = ctx.getSharedPreferences("iptv_license", Context.MODE_PRIVATE)
        prefs.getString("machine_code", null)?.let { return it }
        val raw = listOf(Build.BOARD, Build.BRAND, Build.DEVICE, Build.MODEL, Build.PRODUCT).joinToString("|")
        val md = MessageDigest.getInstance("SHA-256")
        val code = "TV-" + md.digest(raw.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02X".format(it) }.take(12)
        prefs.edit().putString("machine_code", code).apply()
        return code
    }

    // ===== 免费时长控制（每天1小时）=====
    const val FREE_DAILY_MS = 60 * 60 * 1000L

    /** 今天已用免费毫秒数 */
    fun freeUsedTodayMs(ctx: Context): Long {
        val prefs = ctx.getSharedPreferences("iptv_license", Context.MODE_PRIVATE)
        val today = todayKey()
        if (prefs.getString("free_date", "") != today) return 0
        return prefs.getLong("free_used_ms", 0)
    }

    fun addFreeUsedMs(ctx: Context, delta: Long) {
        val prefs = ctx.getSharedPreferences("iptv_license", Context.MODE_PRIVATE)
        val today = todayKey()
        val used = if (prefs.getString("free_date", "") == today) prefs.getLong("free_used_ms", 0) else 0
        prefs.edit().putString("free_date", today).putLong("free_used_ms", used + delta).apply()
    }

    fun freeRemainingMs(ctx: Context): Long =
        (FREE_DAILY_MS - freeUsedTodayMs(ctx)).coerceAtLeast(0)

    /** 日期键北京时间 */
    private fun todayKey(): String {
        val f = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.CHINA)
        f.timeZone = java.util.TimeZone.getTimeZone("GMT+8")
        return f.format(java.util.Date())
    }
}
