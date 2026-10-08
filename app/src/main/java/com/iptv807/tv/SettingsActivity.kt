package com.iptv807.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/**
 * 设置页：
 * - 版本号显示
 * - 开机自启动开关（BOOT_COMPLETED receiver）
 * - 会员状态显示（已是会员显示有效期；非会员显示成为会员按钮→跳购买）
 */
class SettingsActivity : Activity() {

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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#12141A"))
            setPadding(96, 64, 96, 64)
        }
        setContentView(root)

        root.addView(TextView(this).apply {
            text = "设置"
            textSize = 28f; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER; setPadding(0, 0, 0, 48)
        })

        // ===== 版本信息 =====
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (e: Exception) { "1.0" }

        val prefs = getSharedPreferences("iptv_license", MODE_PRIVATE)
        val isVip = prefs.getBoolean("is_vip", false)
        val expire = prefs.getString("vip_expire", "")
        val permanent = prefs.getBoolean("is_permanent", false)
        val cardKey = prefs.getString("card_key", "")

        root.addView(TextView(this).apply {
            text = "版本信息"
            textSize = 15f; setTextColor(Color.parseColor("#7AA7D9"))
            setPadding(0, 0, 0, 8)
        })
        root.addView(TextView(this).apply {
            text = "App 版本：$versionName\n授权系统：CardAuth（app.88531.cn）\n机器码：${LicenseClient.machineCode(this@SettingsActivity)}"
            textSize = 15f; setTextColor(Color.WHITE)
            setLineSpacing(0f, 1.15f)
            setBackgroundColor(Color.parseColor("#1C2028"))
            setPadding(24, 20, 24, 20)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 40 })

        // ===== 开机自启动 =====
        root.addView(TextView(this).apply {
            text = "通用设置"
            textSize = 15f; setTextColor(Color.parseColor("#7AA7D9"))
            setPadding(0, 0, 0, 8)
        })
        val bootSwitch = Switch(this).apply {
            text = "开机自动启动"; textSize = 17f
            setTextColor(Color.WHITE)
            isChecked = isBootReceiverEnabled()
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
            setBackgroundColor(Color.parseColor("#1C2028"))
        }
        bootSwitch.setOnCheckedChangeListener { _, checked ->
            val pm = packageManager
            val comp = ComponentName(this, BootReceiver::class.java)
            pm.setComponentEnabledSetting(
                comp,
                if (checked) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
        root.addView(bootSwitch, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 40 })

        // ===== 会员状态 =====
        root.addView(TextView(this).apply {
            text = "会员状态"
            textSize = 15f; setTextColor(Color.parseColor("#7AA7D9"))
            setPadding(0, 0, 0, 8)
        })
        statusText = TextView(this).apply {
            textSize = 15f; setLineSpacing(0f, 1.15f)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1C2028"))
            setPadding(24, 20, 24, 20)
            text = if (isVip) {
                "✓ 已是会员\n${if (permanent) "永久有效" else "有效期至：$expire"}${if (cardKey?.isNotEmpty() == true) "\n卡号：$cardKey" else ""}"
            } else {
                "当前为免费用户（每天 1 小时）\n尚未成为会员"
            }
        }
        root.addView(statusText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 24 })


        if (isVip) {
            root.addView(TextView(this).apply {
                text = "✓ 已经是会员"
                textSize = 18f; setTextColor(Color.parseColor("#4CAF50"))
                gravity = Gravity.CENTER; setPadding(0, 24, 0, 24)
            })
        } else {
            val btn = Button(this).apply {
                text = "支付成为会员（月/季/年）"
                textSize = 17f
                setBackgroundColor(Color.parseColor("#1B6EF3"))
                setOnClickListener { startActivity(Intent(this@SettingsActivity, ShopActivity::class.java)) }
            }
            root.addView(btn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun isBootReceiverEnabled(): Boolean =
        packageManager.getComponentEnabledSetting(ComponentName(this, BootReceiver::class.java)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }
}
