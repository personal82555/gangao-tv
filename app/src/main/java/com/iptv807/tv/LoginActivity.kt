package com.iptv807.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * CardAuth 授权登录页 —— 应用第一屏：
 * 有效卡本地缓存 → 直接进播放；否则显示卡密输入
 * CardAuth 无用户体系：一卡一机绑定，验证即在服务端注册到期时间
 */
class LoginActivity : Activity() {

    private lateinit var cardEdit: EditText
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            // crashGuard: 若上次有崩溃标记，先清before检查
            getSharedPreferences("iptv_crash", MODE_PRIVATE).let {
                if (it.getBoolean("crashed", false)) {
                    it.edit().putBoolean("crashed", false).apply()
                    // 清理license cache以防被污染
                    getSharedPreferences("iptv_license", MODE_PRIVATE).edit().remove("token").remove("card_key").apply()
                }
            }
        } catch (e: Exception) { }

        val prefs = getSharedPreferences("iptv_license", MODE_PRIVATE)
        val machine = LicenseClient.machineCode(this)

        // 本地已授权缓存 → 快速放行（在线重验在主界面做，避免卡启动）
        if (prefs.getBoolean("is_vip", false)) {
            startMain(); return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(Color.parseColor("#12141A"))
            setPadding(96, 64, 96, 64)
        }
        setContentView(root)

        root.addView(TextView(this).apply {
            text = "港澳台直播"
            textSize = 30f; setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER; setPadding(0, 0, 0, 16)
        })
        root.addView(TextView(this).apply {
            text = "免费用户每天可看 1 小时 · 输入卡密解锁全天观看"
            textSize = 15f; setTextColor(Color.LTGRAY)
            gravity = android.view.Gravity.CENTER; setPadding(0, 0, 0, 48)
        })

        cardEdit = EditText(this).apply {
            hint = "卡密（如 8914-XXXX-XXXX）"
            setSingleLine(true)
            textSize = 18f
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.parseColor("#23262F"))
            setPadding(24, 24, 24, 24)
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        root.addView(cardEdit, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 32 })

        val btn = Button(this).apply {
            text = "激活 / 验证"; textSize = 19f
            setOnClickListener { doVerify() }
        }
        root.addView(btn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        statusText = TextView(this).apply {
            textSize = 14f; setTextColor(Color.LTGRAY)
            gravity = android.view.Gravity.CENTER; setPadding(0, 40, 0, 0)
            text = "已有卡密可直接激活；或购买套餐自动绑定本机"
        }
        root.addView(statusText)

        val shopBtn = Button(this).apply {
            text = "购买套餐（支付宝/微信）"; textSize = 15f
            setBackgroundColor(Color.parseColor("#1B6EF3"))
            setOnClickListener { startActivity(android.content.Intent(this@LoginActivity, ShopActivity::class.java)) }
        }
        root.addView(shopBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 24 })

        val freeBtn = Button(this).apply {
            text = "先看一会（免费）"; textSize = 14f
            setBackgroundColor(Color.parseColor("#2A3344"))
            setOnClickListener {
                getSharedPreferences("iptv_license", MODE_PRIVATE).edit().putBoolean("is_vip", false).apply()
                startMain()
            }
        }
        root.addView(freeBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 24 })

        cardEdit.setOnEditorActionListener { _,_,_ -> doVerify(); true }
    }

    private fun doVerify() {
        val card = cardEdit.text.toString().trim()
        if (card.isEmpty()) { statusText.text = "请输入卡密"; return }
        statusText.text = "验证中…"
        val machine = LicenseClient.machineCode(this)
        Thread {
            val r = LicenseClient.verify(card, machine)
            runOnUiThread {
                if (r.ok) {
                    getSharedPreferences("iptv_license", MODE_PRIVATE).edit()
                        .putBoolean("is_vip", true)
                        .putString("card_key", card)
                        .putString("vip_expire", r.expireTime)
                        .putBoolean("is_permanent", r.isPermanent)
                        .apply()
                    Toast.makeText(this, "✓ ${r.message}${if (!r.isPermanent && r.expireTime != null) "\n到期: ${r.expireTime}" else ""}", Toast.LENGTH_LONG).show()
                    startMain()
                } else {
                    statusText.text = r.message
                }
            }
        }.start()
    }

    private fun startMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
