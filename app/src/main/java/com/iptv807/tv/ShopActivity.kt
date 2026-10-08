package com.iptv807.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import org.json.JSONArray

/**
 * 套餐商店 —— App 内直接购买
 * 流程: GET card-types → 用户选定套餐+支付方式 → POST /api/public/orders
 *      (contact_info=MACHINE:TV-xxx) → 拉起浏览器 pay_url 扫码
 *      → 轮询 /api/public/orders/query?order_no= → paid → 查询卡号 → 自动verify → 进主界面
 */
class ShopActivity : Activity() {

    private val plans = mutableListOf<Plan>()

    data class Plan(val id: Int, val name: String, val price: String, val days: Int)

    private lateinit var list: ListView
    private var rowAdapterRef: RowAdapter? = null
    private lateinit var alipay: TextView
    private lateinit var wxpay: TextView
    private var shopZone = 0     // 0=套餐列表 1=支付方式
    private lateinit var statusText: TextView
    private var payType = "alipay"
    private var orderNo = ""
    private var machineId = ""
    private val handler = Handler(Looper.getMainLooper())

    /** 高亮自控的行适配器 */
    private inner class RowAdapter(private var items: List<String>) : android.widget.BaseAdapter() {
        var selected = 0
            set(value) { field = value; notifyDataSetChanged() }
        fun setItems(v: List<String>) { items = v; notifyDataSetChanged() }
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tv = (convertView as? TextView) ?: TextView(this@ShopActivity).apply {
                textSize = 18f; setPadding(36, 26, 36, 26)
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            tv.text = items[position]
            val on = position == selected
            tv.setBackgroundColor(if (on) Color.parseColor("#1B6EF3") else Color.parseColor("#1C1F26"))
            tv.setTextColor(Color.WHITE)
            return tv
        }
    }

    private fun focusBg(normal: Int, focused: Int): android.graphics.drawable.StateListDrawable {
        val sl = android.graphics.drawable.StateListDrawable()
        sl.addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.ColorDrawable(focused))
        sl.addState(intArrayOf(), android.graphics.drawable.ColorDrawable(normal))
        return sl
    }
    private var pollTask: Runnable? = null
    private var pollCount = 0

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
            setPadding(64, 48, 64, 48)
        }
        setContentView(root)
        root.gravity = Gravity.CENTER

        machineId = LicenseClient.machineCode(this)

        root.addView(TextView(this).apply {
            text = "IPTV · 选择套餐"
            textSize = 26f; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER; setPadding(0, 0, 0, 12)
        })
        root.addView(TextView(this).apply {
            text = "支付成功自动激活本机（无需卡密）"
            textSize = 14f; setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER; setPadding(0, 0, 0, 32)
        })

        list = ListView(this).apply {
            setBackgroundColor(Color.parseColor("#1C1F26"))
            setSelector(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            isFocusable = false
            isFocusableInTouchMode = false
            setItemsCanFocus(false)
        }
        root.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { bottomMargin = 24 })

        statusText = TextView(this).apply {
            textSize = 14f; setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER; setPadding(0, 0, 0, 24)
        }
        root.addView(statusText)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun mkPay(label: String, t: String) = TextView(this).apply {
            text = label; textSize = 16f
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(24, 26, 24, 26)
            isFocusable = true; isFocusableInTouchMode = true
            background = focusBg(0xFF2A2F3A.toInt(), 0xFF1B6EF3.toInt())
            setOnClickListener { payType = t; refreshPayBtns() }
        }
        alipay = mkPay("支付宝", "alipay")
        wxpay = mkPay("微信", "wxpay")
        row.addView(alipay, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 16 })
        row.addView(wxpay, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)

        list.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
            if (pos < plans.size) createOrder(plans[pos])
        }
        refreshPayBtns()

        loadPlans()
    }

    /** 当前支付方式：亮蓝 + 焦点时更亮 */
    private fun refreshPayBtns() {
        val a = payType == "alipay"
        alipay.setBackgroundColor(Color.parseColor(if (a) "#1B6EF3" else "#2A2F3A"))
        wxpay.setBackgroundColor(Color.parseColor(if (a) "#2A2F3A" else "#1B6EF3"))
        alipay.alpha = if (a) 1f else 0.75f
        wxpay.alpha = if (a) 0.75f else 1f
    }

    @SuppressLint("SetTextI18n")
    private fun loadPlans() {
        statusText.text = "加载套餐…"
        Thread {
            try {
                val r = CardAuthApi.get("/api/public/projects/2/card-types")
                val arr = r.optJSONArray("data") ?: JSONArray()
                plans.clear()
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i)
                    // 接口不返回 status 字段时视为启用（默认 1），只有显式 status=0 才跳过
                    val st = if (p.has("status")) p.optInt("status", 1) else 1
                    if (st == 1) {
                        val days = p.optInt("duration_days", 0)
                        plans.add(Plan(p.optInt("id"), p.optString("name"), p.optString("price"), days))
                    }
                }
                if (plans.isEmpty()) {
                    runOnUiThread { statusText.text = "暂无可用套餐（请稍后重试）" }
                }
                runOnUiThread {
                    plans.sortBy { it.days }
                    val ra = RowAdapter(plans.map {
                        val d = it.days
                        "📱 ${it.name}   ¥${it.price}   ${if (d >= 999) "永久" else "${d}天"}"
                    })
                    rowAdapterRef = ra
                    list.adapter = ra
                    statusText.text = "上下选择套餐，OK 购买；左右可切支付方式"
                }
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "加载失败: ${e.javaClass.simpleName}: ${e.message?.take(150)}" }
            }
        }.start()
    }

    private fun createOrder(plan: Plan) {
        statusText.text = "创建订单…"
        Thread {
            try {
                val body = org.json.JSONObject().apply {
                    put("project_id", 2)
                    put("card_type_id", plan.id)
                    put("pay_type", payType)
                    put("contact_info", "MACHINE:$machineId")
                }
                val r = CardAuthApi.post("/api/public/orders", body)
                val d = r.optJSONObject("data")
                val url = d?.optString("pay_url") ?: ""
                runOnUiThread {
                    if (url.isNotEmpty()) {
                        orderNo = d!!.optString("order_no", "")
                        statusText.text = "订单已创建，正在打开支付页…支付成功后自动激活"
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        startPolling()
                    } else {
                        statusText.text = "下单失败: ${r.optString("message")}"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "下单错误: ${e.message}" }
            }
        }.start()
    }

    private fun startPolling() {
        pollCount = 0
        pollTask = object : Runnable {
            override fun run() {
                pollCount++
                if (pollCount > 60) {   // 最多3分钟
                    handler.post { statusText.text = "等待支付超时，可重新下单" }
                    return
                }
                Thread {
                    var paid = false
                    var cardKey = ""
                    try {
                        val r = CardAuthApi.get("/api/public/orders/query?order_no=$orderNo")
                        val d = r.optJSONObject("data") ?: org.json.JSONObject()
                        paid = d.optString("status") == "paid"
                        cardKey = d.optString("card_key", "")
                    } catch (e: Exception) { }
                    runOnUiThread {
                        if (paid) {
                            statusText.text = "✓ 支付成功！正在激活本机…"
                            if (cardKey.isNotEmpty()) activateAndEnter(cardKey)
                            else {                                   // 兜底: 直接用verify免卡路径
                                handler.postDelayed({
                                    getSharedPreferences("iptv_license", MODE_PRIVATE)
                                        .edit().putBoolean("is_vip", true).apply()
                                    enterMain()
                                }, 500)
                            }
                        } else {
                            statusText.text = "等待支付…（${pollCount * 3}秒）"
                            handler.postDelayed(this, 3000)
                        }
                    }
                }.start()
            }
        }
        handler.postDelayed(pollTask!!, 3000)
    }

    private fun activateAndEnter(cardKey: String) {
        Thread {
            val r = LicenseClient.verify(cardKey, machineId)
            runOnUiThread {
                getSharedPreferences("iptv_license", MODE_PRIVATE).edit()
                    .putBoolean("is_vip", r.ok)
                    .putString("card_key", if (r.ok) cardKey else "")
                    .putString("vip_expire", r.expireTime)
                    .putBoolean("is_permanent", r.isPermanent)
                    .apply()
                if (r.ok) {
                    statusText.text = "✓ 激活成功，开始播放"
                    enterMain()
                } else {
                    statusText.text = "激活校验失败: ${r.message}（可直接输入卡密重试）"
                }
            }
        }.start()
    }

    private fun enterMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 支付中：回归普通处理
        if (pollTask != null) {
            if (keyCode == KeyEvent.KEYCODE_BACK) { handler.removeCallbacks(pollTask!!); pollTask = null }
            return super.onKeyDown(keyCode, event)
        }
        val ra = rowAdapterRef
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val delta = if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                if (shopZone == 0 && ra != null && ra.count > 0) {
                    val np = ra.selected + delta
                    if (np < 0) { shopZone = 1; refreshPayBtns(); alipay.requestFocus() }
                    else { ra.selected = np.coerceAtMost(ra.count - 1); list.setSelection(ra.selected) }
                } else if (shopZone == 1) {
                    if (delta > 0) { } else { shopZone = 0; ra?.let { list.setSelection(it.selected) } }
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (shopZone == 1) { payType = if (payType == "alipay") "wxpay" else "alipay"; refreshPayBtns() }
                else if (ra != null && ra.count > 0) {
                    // 列表里左右也可切支付方式，方便一步到位
                    payType = if (payType == "alipay") "wxpay" else "alipay"; refreshPayBtns()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (shopZone == 1) { payType = if (payType == "alipay") "wxpay" else "alipay"; refreshPayBtns() }
                else {
                    val pos = ra?.selected ?: -1
                    if (pos in plans.indices) createOrder(plans[pos])
                }
                return true
            }
            KeyEvent.KEYCODE_BACK -> { finish(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
}
