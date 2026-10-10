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
import android.widget.EditText
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
    private lateinit var statusText2: TextView

    /** 焦点高亮背景：获得焦点→蓝色，普通→深灰 */
    private fun focusBg(normal: Int = 0xFF2A2F3A.toInt(), focused: Int = 0xFF1B6EF3.toInt())
            : android.graphics.drawable.StateListDrawable {
        val sl = android.graphics.drawable.StateListDrawable()
        sl.addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.ColorDrawable(focused))
        sl.addState(intArrayOf(), android.graphics.drawable.ColorDrawable(normal))
        return sl
    }

    /** 可聚焦的操作行（代替固定背景的 Button，保证遥控器上能看到选中） */
    private fun mkAction(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 17f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(24, 26, 24, 26)
            isFocusable = true
            isFocusableInTouchMode = true
            background = focusBg()
            setOnClickListener { onClick() }
        }

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
        val scroll = android.widget.ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#12141A"))
            isFillViewport = true
        }
        scroll.addView(root)
        setContentView(scroll)

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
            isFocusable = true
            isFocusableInTouchMode = true
            background = focusBg(normal = 0xFF1C2028.toInt())
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


        // ===== 安装统计 & 版本更新 =====
        root.addView(TextView(this).apply {
            text = "版本更新"
            textSize = 15f; setTextColor(Color.parseColor("#7AA7D9"))
            setPadding(0, 8, 0, 8)
        })
        statusText2 = TextView(this).apply {
            textSize = 15f; setLineSpacing(0f, 1.15f)
            setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#1C2028"))
            setPadding(24, 20, 24, 20)
            text = "点击右侧按钮检查是否有新版本\n（安装统计会在启动时自动上报）"
        }
        root.addView(statusText2, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 12 })

        val checkBtn = mkAction("检查更新") { doCheckUpdate() }
        root.addView(checkBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 40 })

        // ===== 卡密激活 =====
        root.addView(TextView(this).apply {
            text = "卡密激活"
            textSize = 15f; setTextColor(Color.parseColor("#7AA7D9"))
            setPadding(0, 8, 0, 8)
        })
        val cardInput = EditText(this).apply {
            hint = "请输入卡密"
            textSize = 16f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#888888"))
            setPadding(24, 20, 24, 20)
            isSingleLine = true
            isFocusable = true
            isFocusableInTouchMode = true
            background = focusBg(normal = 0xFF1C2028.toInt())
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        root.addView(cardInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 12 })

        val actBtn = mkAction("激活") { doActivate(cardInput.text.toString().trim()) }
        root.addView(actBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 40 })

        if (isVip) {
            root.addView(TextView(this).apply {
                text = "✓ 已经是会员"
                textSize = 18f; setTextColor(Color.parseColor("#4CAF50"))
                gravity = Gravity.CENTER; setPadding(0, 24, 0, 12)
            })
            // ★ 新增：撤销本机授权（换机 / 换卡密前先解绑）
            val revokeBtn = mkAction("撤销本机授权（可重新激活 / 换卡密）") { doRevoke() }
            root.addView(revokeBtn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 20 })
        } else {
            val btn = mkAction("支付成为会员（月/季/年）") {
                startActivity(Intent(this@SettingsActivity, ShopActivity::class.java))
            }
            root.addView(btn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    /** 撤销本机授权：清除本地授权信息 → 恢复免费模式，可重新输入卡密或换机 */
    private fun doRevoke() {
        try {
            getSharedPreferences("iptv_license", MODE_PRIVATE).edit()
                .putBoolean("is_vip", false)
                .putString("card_key", "")
                .putString("vip_expire", "")
                .putBoolean("is_permanent", false)
                .remove("token")
                .apply()
        } catch (e: Exception) { }
        statusText.text = "✓ 已撤销本机授权\n当前为免费用户（每天 1 小时）\n可在下方重新输入卡密激活"
        android.widget.Toast.makeText(this, "已撤销授权，可重新激活", android.widget.Toast.LENGTH_LONG).show()
        // 重开本页以刷新界面状态（会员按钮 ↔ 支付按钮）
        try {
            startActivity(Intent(this@SettingsActivity, SettingsActivity::class.java))
            finish()
        } catch (e: Exception) { }
    }

    /** 输入卡密激活 */
    private fun doActivate(key: String) {
        if (key.isEmpty()) { statusText.text = "请先输入卡密"; return }
        statusText.text = "正在验证卡密…"
        Thread {
            val r = try {
                LicenseClient.verify(key, LicenseClient.machineCode(this))
            } catch (e: Exception) {
                LicenseClient.Result(false, "网络错误: ${e.javaClass.simpleName}")
            }
            runOnUiThread {
                val prefs = getSharedPreferences("iptv_license", MODE_PRIVATE)
                prefs.edit()
                    .putBoolean("is_vip", r.ok)
                    .putString("card_key", if (r.ok) key else "")
                    .putString("vip_expire", r.expireTime)
                    .putBoolean("is_permanent", r.isPermanent)
                    .apply()
                statusText.text = if (r.ok) {
                    "✓ 激活成功！\n${if (r.isPermanent) "永久有效" else "有效期至：${r.expireTime}"}\n卡号：$key"
                } else {
                    "✗ 激活失败：${r.message}\n请检查卡密是否正确 / 是否已被使用"
                }
                android.widget.Toast.makeText(this@SettingsActivity,
                    if (r.ok) "激活成功" else "激活失败：${r.message}",
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    /** 检查新版本（安装登记 + 在线上报 会一并完成） */
    private fun doCheckUpdate() {
        statusText2.text = "正在检查…"
        Thread {
            val mid = LicenseClient.machineCode(this)
            val card = getSharedPreferences("iptv_license", MODE_PRIVATE).getString("card_key", "") ?: ""
            val info = AuthApi.checkUpdate(mid, card, this)
            runOnUiThread {
                if (!info.ok) {
                    statusText2.text = "检查失败：${info.msg}"
                    return@runOnUiThread
                }
                val head = "安装上报成功  #${info.installId}\n当前 v${AuthApi.clientVersion(this@SettingsActivity)}"
                statusText2.text = if (info.updateAvailable) {
                    "$head\n✅ 有新版本：v${info.latestVersion}" +
                    (if (info.changelog.isNotEmpty()) "\n${info.changelog}" else "")
                } else {
                    "$head\n✅ 已是最新版本"
                }
                if (info.updateAvailable) {
                    android.widget.Toast.makeText(this@SettingsActivity,
                        "发现新版本 v${info.latestVersion}，返回主界面下载", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun isBootReceiverEnabled(): Boolean =
        packageManager.getComponentEnabledSetting(ComponentName(this, BootReceiver::class.java)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }
}
