package com.iptv807.tv

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** CardAuth 公开API的轻量封装 */
object CardAuthApi {
    private const val BASE = "http://app.88531.cn:8881"
    private const val API_KEY = "ak_563f4768d5d411d381e58bc3f76ba447"

    fun get(path: String): JSONObject = request("GET", path, null)
    fun post(path: String, body: JSONObject): JSONObject = request("POST", path, body.toString())

    private fun request(method: String, path: String, body: String?): JSONObject {
        val conn = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8000; readTimeout = 10000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-API-Key", API_KEY)
            if (body != null && method == "POST") {
                doOutput = true
                outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.let { BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).readText() } ?: ""
        conn.disconnect()
        if (text.trimStart().startsWith("<")) {
            throw RuntimeException("服务端返回了HTML（路径错误/网关拦截），HTTP $code")
        }
        return JSONObject(text)
    }
}
