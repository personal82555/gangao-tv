package com.iptv807.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** 用户自定义 AI 服务配置（OpenAI兼容格式） */
class AiSettingsActivity : Activity() {

    private lateinit var baseEdit: EditText
    private lateinit var keyEdit: EditText
    private lateinit var modelEdit: EditText
    private lateinit var statusText: TextView

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#12141A"))
            setPadding(80, 56, 80, 56)
        }
        setContentView(root)

        root.addView(TextView(this).apply {
            text = "AI 功能配置"
            textSize = 26f; setTextColor(Color.WHITE); gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 12)
        })
        root.addView(TextView(this).apply {
            text = "兼容 OpenAI 格式。支持：DeepSeek、通义、Kimi、OpenAI、本地Ollama、NewAPI 中转等。填你的 key 即可。"
            textSize = 13f; setTextColor(Color.LTGRAY); gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 32)
        })

        fun mkEdit(hint: String, v: String = "") = EditText(this).apply {
            this.hint = hint
            setText(v)
            setSingleLine(true)
            textSize = 15f
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.parseColor("#23262F"))
            setPadding(24, 20, 24, 20)
        }

        baseEdit = mkEdit("https://ai.88531.cn/v1（可改自己的）", AiClient.baseUrl(this))
        keyEdit  = mkEdit("API Key（sk-…）", AiClient.apiKey(this))
        modelEdit = mkEdit("deepseek-v4.1-flash（可改）", AiClient.model(this))
        listOf(baseEdit, keyEdit, modelEdit).forEach {
            root.addView(it, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 24 })
        }

        val saveBtn = Button(this).apply { text = "保存"; textSize = 17f }
        val testBtn = Button(this).apply { text = "测试连接"; textSize = 17f }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(saveBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 16 })
        row.addView(testBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)

        statusText = TextView(this).apply {
            textSize = 14f; setTextColor(Color.LTGRAY); gravity = android.view.Gravity.CENTER
            setPadding(0, 32, 0, 0)
            text = "AI 功能：语音搜台 · 节目解读 · 故障诊断 · 智能推荐 · 找片 · 收视报告"
        }
        root.addView(statusText)

        saveBtn.setOnClickListener {
            if (baseEdit.text.trim().isEmpty() || keyEdit.text.trim().isEmpty() || modelEdit.text.trim().isEmpty()) {
                statusText.text = "三项都要填"; return@setOnClickListener
            }
            AiClient.save(this, baseEdit.text.toString(), keyEdit.text.toString(), modelEdit.text.toString())
            statusText.setTextColor(Color.parseColor("#4CAF50"))
            statusText.text = "✓ 已保存"
        }
        testBtn.setOnClickListener {
            if (!AiClient.isConfigured(this)) {
                statusText.setTextColor(Color.RED); statusText.text = "先填写并保存三项"; return@setOnClickListener
            }
            statusText.setTextColor(Color.LTGRAY)
            statusText.text = "测试中…"
            Thread {
                try {
                    val r = AiClient.chat(this, "你是测试助手", "回复：OK")
                    runOnUiThread { statusText.setTextColor(Color.parseColor("#4CAF50")); statusText.text = "✓ 连通：$r" }
                } catch (e: Exception) {
                    runOnUiThread { statusText.setTextColor(Color.RED); statusText.text = "✗ ${e.message}" }
                }
            }.start()
        }
    }
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }
}
