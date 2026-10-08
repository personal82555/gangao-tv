package com.iptv807.tv

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 免费时长用完 → 引导购买页
 * 显示卡密商城地址（CardAuth /#/shop），用户点遥控OK打开购买页
 */
class TrialExpiredActivity : Activity() {

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
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(0xFF12141Au.toInt())
            setPadding(96, 64, 96, 64)
        }
        setContentView(root)

        root.addView(TextView(this).apply {
            text = "⏰ 今日免费时长已用完"
            textSize = 30f; setTextColor(0xFFFFFFFFu.toInt())
            gravity = android.view.Gravity.CENTER; setPadding(0, 0, 0, 24)
        })

        root.addView(TextView(this).apply {
            text = "免费用户每天可观看 1 小时\n购买卡密后立即解锁不限时长观看"
            textSize = 17f; setTextColor(0xFFBBBBBBu.toInt())
            gravity = android.view.Gravity.CENTER; setPadding(0, 0, 0, 56)
        })

        root.addView(Button(this).apply {
            text = "购买套餐（OK键）"
            textSize = 19f
            setOnClickListener { startActivity(android.content.Intent(this@TrialExpiredActivity, ShopActivity::class.java)); finish() }
        })

        root.addView(TextView(this).apply {
            text = "或直接访问：http://app.88531.cn:8881/#/shop"
            textSize = 13f; setTextColor(0xFF888888u.toInt())
            gravity = android.view.Gravity.CENTER; setPadding(0, 32, 0, 0)
        })

        root.addView(TextView(this).apply {
            text = "\n明天再来可继续免费观看 1 小时"
            textSize = 14f; setTextColor(0xFF7799AAu.toInt())
            gravity = android.view.Gravity.CENTER; setPadding(0, 24, 0, 0)
        })
    }

    private fun openShop() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://app.88531.cn:8881/#/shop")))
        } catch (e: Exception) { }
    }
}
