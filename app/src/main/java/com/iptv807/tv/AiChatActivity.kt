package com.iptv807.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** AI 助手 —— 超保守实现：静态布局，异常完全不崩 */
class AiChatActivity : Activity() {

    private lateinit var scroll: ScrollView
    private lateinit var chatBox: LinearLayout
    private lateinit var input: EditText
    private lateinit var statusText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var channelsText = ""

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { init() } catch (e: Exception) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.VERTICAL
            val tv = TextView(this)
            tv.text = "AI 加载失败（已恢复）。回按下返回重启"
            tv.setTextColor(Color.RED); tv.textSize = 16f
            r.addView(tv)
            setContentView(r)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun init() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#12141A"))
        root.setPadding(48, 40, 48, 40)
        setContentView(root)

        val title = TextView(this)
        title.text = "🤖 AI 助手"
        title.textSize = 24f; title.setTextColor(Color.WHITE); title.gravity = Gravity.CENTER
        root.addView(title, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 20 })

        // 检测配置
        if (!AiClient.isConfigured(this)) {
            val hint = TextView(this)
            hint.text = "尚未配置 AI。\n请先到 设置 → AI 配置 填写 API 地址 / Key / 模型名。"
            hint.textSize = 15f; hint.setTextColor(Color.LTGRAY); hint.gravity = Gravity.CENTER
            root.addView(hint, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 200 })
            val go = Button(this)
            go.text = "去配置 AI"; go.textSize = 16f
            go.setOnClickListener {
                try { startActivity(Intent(this, AiSettingsActivity::class.java)) } catch (e: Exception) { }
            }
            root.addView(go, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 40 })
            return
        }

        // 拼频道清单
        try {
            channelsText = ChannelData.load().take(160).joinToString("\n") { c -> "${c.num}. ${c.name} [${c.group}]" }
        } catch (e: Exception) { channelsText = "" }

        scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.parseColor("#171A20"))
        chatBox = LinearLayout(this)
        chatBox.orientation = LinearLayout.VERTICAL
        chatBox.setPadding(24, 24, 24, 24)
        scroll.addView(chatBox)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { bottomMargin = 16 })

        // 快捷键按钮（功能入口）
        val quickActions = listOf(
            "📺 当前在播什么" to "@now",
            "🎯 推荐值得看" to "@rec",
            "📊 我的收视报告" to "@report",
        )
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        for ((label, action) in quickActions) {
            val b = Button(this)
            b.text = label.take(3); b.textSize = 11f
            b.setBackgroundColor(Color.parseColor("#2A2F3A"))
            b.setOnClickListener {
                try { ask(action, label) } catch (e: Exception) { statusText.text = "动作失败" }
            }
            row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 8 })
        }
        root.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 16 })

        input = EditText(this)
        input.hint = "问点啥…"
        input.textSize = 16f
        input.setTextColor(Color.WHITE); input.setHintTextColor(Color.GRAY)
        input.setBackgroundColor(Color.parseColor("#23262F"))
        input.setPadding(24, 20, 24, 20)
        input.setSingleLine(true)
        root.addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 16 })

        statusText = TextView(this)
        statusText.textSize = 13f; statusText.setTextColor(Color.LTGRAY); statusText.gravity = Gravity.CENTER
        root.addView(statusText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 12 })

        val send = Button(this)
        send.text = "发送"; send.textSize = 17f
        send.setOnClickListener {
            try {
                val q = input.text?.toString()?.trim().orEmpty()
                if (q.isNotEmpty()) ask(q, null)
                input.setText("")
            } catch (e: Exception) { statusText.text = "发送失败" }
        }
        root.addView(send, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        addBubble("AI 已就绪。可以直接说话，或用上面的快捷键。", Color.parseColor("#2A3038"))
    }

    private fun addBubble(text: String, bg: Int) {
        try {
            val v = TextView(this)
            v.text = text
            v.textSize = 15f; v.setTextColor(Color.WHITE)
            v.setBackgroundColor(bg)
            v.setPadding(20, 16, 20, 16)
            chatBox.addView(v, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 12 })
            scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
        } catch (e: Exception) { }
    }

    private fun ask(action: String, label: String?) {
        val q = when (action) {
            "@now" -> "当前时段值得看的频道有哪些节目"
            "@rec" -> "推荐我现在时期值得看的3-5个频道"
            "@report" -> "请基于我的收视记录给报告"
            else -> action
        }
        addBubble(label ?: q, Color.parseColor("#1B3A5C"))
        statusText.text = "思考中…"
        val ctx = this
        Thread {
            var reply = try {
                val sys = "你是Android TV IPTV应用的AI助手。频道清单(编号. 名称 [组])：\n$channelsText\n简洁回复，直接给频道编号和名字。"
                AiClient.chat(ctx, sys, q, 600)
            } catch (e: Exception) { "AI 调用失败: ${e.message}" }
            handler.post {
                try { statusText.text = "" } catch (e: Exception) { }
                try {
                    // SWITCH:台号 切台
                    if (reply.contains("SWITCH:")) {
                        val target = reply.substringAfter("SWITCH:").substringBefore("\n").trim()
                        val found = ChannelData.load().firstOrNull { ch -> ch.name.contains(target, true) }
                        if (found != null) {
                            MainActivity.playRequested = found.num
                            addBubble("已切到：${found.num} ${found.name}", Color.parseColor("#2A4030"))
                            handler.postDelayed({ try { finish() } catch (e: Exception) { } }, 800)
                            return@post
                        }
                    }
                } catch (e: Exception) { }
                addBubble(reply, Color.parseColor("#2A3038"))
            }
        }.start()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            try { finish() } catch (e: Exception) { }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

private fun String?.orFallback(): String = this ?: ""
