package com.iptv807.tv

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 安装统计 + 版本推送 客户端（auth.88531.cn）
 *
 * 接口文档：https://auth.88531.cn/#/docs
 *   POST /api/public/installations/check-update   安装登记 + 检查更新（启动时调用，一石二鸟）
 *   POST /api/public/installations/heartbeat      仅更新在线时间（周期心跳）
 *   Header: X-Api-Key + Content-Type: application/json
 *   响应：{ code, message, data }
 *
 * 文档建议：
 *   启动/周期心跳时调用 check-update，同时完成安装登记与在线上报；
 *   verify / check-update / heartbeat 成功都会更新"最后上线"。
 */
object AuthApi {

    private const val BASE = "https://auth.88531.cn"
    private const val API_KEY = "ak_563f4768d5d411d381e58bc3f76ba447"

    @Volatile var lastError: String = ""

    /** 检查更新 + 安装登记 的结果 */
    data class UpdateInfo(
        val ok: Boolean = false,
        val msg: String = "",
        val updateAvailable: Boolean = false,
        val updateRequired: Boolean = false,      // 后台开了强制推送
        val currentVersion: String = "",
        val latestVersion: String = "",
        val downloadUrl: String = "",
        val fileSize: Long = 0,
        val checksum: String = "",
        val changelog: String = "",
        val minClientVersion: String = "",
        val isForce: Int = 0,
        val installId: Long = 0
    )

    /** 当前应用版本号（用于 client_version 与版本比较） */
    fun clientVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0.0.0"
    } catch (e: Exception) { "0.0.0" }

    /** 完整下载地址（接口返回的是相对路径） */
    fun absDownloadUrl(rel: String): String = when {
        rel.isEmpty() -> ""
        rel.startsWith("http") -> rel
        else -> BASE + if (rel.startsWith("/")) rel else "/$rel"
    }

    /** 检查更新 + 登记安装（网络请求，必须放后台线程） */
    @JvmOverloads
    fun checkUpdate(machineId: String, cardKey: String = "", ctx: Context? = null): UpdateInfo {
        val body = JSONObject().apply {
            put("machine_id", machineId)
            put("client_version", if (ctx != null) clientVersion(ctx) else "0.0.0")
            put("device_info", "AndroidTV ${android.os.Build.MODEL}")
            if (cardKey.isNotEmpty()) put("card_key", cardKey)
        }
        val r = post("/api/public/installations/check-update", body)
        val code = r.optInt("code", 0)
        if (code != 200) {
            lastError = "code=$code ${r.optString("message")}"
            return UpdateInfo(ok = false, msg = lastError)
        }
        val d = r.optJSONObject("data") ?: JSONObject()
        val ver = d.optString("version").ifEmpty { d.optString("latest_version") }
        val info = UpdateInfo(
            ok = true,
            msg = "ok",
            updateAvailable = d.optBoolean("update_available", false),
            updateRequired = d.optBoolean("update_required", false) || d.optInt("is_force", 0) == 1,
            currentVersion = d.optString("current_version"),
            latestVersion = ver,
            downloadUrl = absDownloadUrl(d.optString("download_url")),
            fileSize = d.optLong("file_size", 0),
            checksum = d.optString("checksum"),
            changelog = d.optString("changelog"),
            minClientVersion = d.optString("min_client_version"),
            isForce = d.optInt("is_force", 0),
            installId = d.optLong("install_id", 0)
        )
        lastError = "ok install=${info.installId}"
        return info
    }

    /** 心跳：仅更新在线时间（网络请求，必须放后台线程） */
    @JvmOverloads
    fun heartbeat(machineId: String, cardKey: String = "", ctx: Context? = null): Boolean {
        val body = JSONObject().apply {
            put("machine_id", machineId)
            put("client_version", if (ctx != null) clientVersion(ctx) else "0.0.0")
            if (cardKey.isNotEmpty()) put("card_key", cardKey)
        }
        val r = post("/api/public/installations/heartbeat", body)
        val ok = r.optInt("code", 0) == 200
        lastError = if (ok) "hb ok" else "hb ${r.optString("message")}"
        return ok
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        return try {
            val conn = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12000
                readTimeout = 15000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Api-Key", API_KEY)
                setRequestProperty("Accept", "application/json")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = try {
                stream?.let { BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).readText() } ?: ""
            } finally { runCatching { stream?.close(); conn.disconnect() } }
            if (text.isEmpty()) JSONObject().put("code", code)
            else JSONObject(text)
        } catch (e: Exception) {
            JSONObject().put("code", 0).put("message", e.javaClass.simpleName + ": " + (e.message ?: "").take(80))
        }
    }
}
