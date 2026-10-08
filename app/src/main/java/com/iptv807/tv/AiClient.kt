package com.iptv807.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 通用 AI 客户端 —— 兼容 OpenAI /api/chat/completions 格式
 * 用户在设置里填: BASE_URL / API_KEY / MODEL（任何一个 OpenAI 兼容服务都可用，
 * 如 new-api 一站式、DeepSeek、通义、Kimi、OpenAI、本地Ollama等）
 */
object AiClient {
    private const val PREF = "iptv_ai"

    fun baseUrl(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("base_url", "https://ai.88531.cn/v1") ?: "https://ai.88531.cn/v1"
    fun apiKey(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("api_key", "") ?: ""
    fun model(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("model", "deepseek-v4.1-flash") ?: "deepseek-v4.1-flash"

    fun isConfigured(ctx: Context): Boolean =
        baseUrl(ctx).isNotEmpty() && apiKey(ctx).isNotEmpty() && model(ctx).isNotEmpty()

    fun save(ctx: Context, baseUrl: String, apiKey: String, model: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("base_url", baseUrl.trim().removeSuffix("/"))
            .putString("api_key", apiKey.trim())
            .putString("model", model.trim()).apply()
    }

    /** chat 请求（同步，调用方自行放子线程）返回回复文本，失败抛异常 */
    fun chat(ctx: Context, systemPrompt: String, userPrompt: String, maxTokens: Int = 500): String {
        val url = URL(baseUrl(ctx) + "/chat/completions")
        val body = JSONObject().apply {
            put("model", model(ctx))
            put("max_tokens", maxTokens)
            put("temperature", 0.6)
            put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", systemPrompt))
                .put(JSONObject().put("role", "user").put("content", userPrompt)))
        }
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10000
            readTimeout = 60000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${apiKey(ctx)}")
            doOutput = true
            outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.let { BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) throw RuntimeException("HTTP $code: ${text.take(180)}")
        val j = JSONObject(text)
        return j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content") ?: ""
    }
}
