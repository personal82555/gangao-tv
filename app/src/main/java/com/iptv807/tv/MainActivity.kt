package com.iptv807.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/**
 * 港澳台直播 v7.0 —— 纯原生播放（无网页兜底）
 *
 * · App 本地解密获取新鲜播放源 → ExoPlayer 直接播放（带 Referer/302）
 * （纯原生播放，无网页兜底）
 */
class MainActivity : Activity() {

    private val channels: List<ChannelData.Channel> = ChannelData.load()
    private val groups: List<ChannelData.Group> = ChannelData.groups()
    private val favGroup = ChannelData.Group("❤ 收藏", 0, 0)

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView

    private lateinit var statusText: TextView
    private lateinit var trialText: TextView
    private lateinit var digitText: TextView
    private lateinit var loadingText: TextView
    private lateinit var loadingMask: FrameLayout
    private lateinit var root: FrameLayout
    private var panelLayout: LinearLayout? = null
    private var groupList: ListView? = null
    private var channelList: ListView? = null
    private var curGroupPos = 0
    private var currentChannel = -1
    private var currentLine = 0
    private var mode = MODE_NATIVE
    private val handler = Handler(Looper.getMainLooper())
    private val hidePanelTask = Runnable { hidePanel() }
    private val uiTick = Runnable { onUiTick() }
    private var loadStart = 0L
    private val loadTick = object : Runnable {
        override fun run() {
            if (loadingMask.visibility != View.VISIBLE) return
            val s = ((System.currentTimeMillis() - loadStart) / 1000 + 1).toInt()
            loadingText.text = if (loadChannelName.isEmpty()) "正在加载 $s 秒，精彩继续…"
                               else "正在加载「$loadChannelName」$s 秒…"
            handler.postDelayed(this, 1000)
        }
    }

    private var digitBuf = ""
    private val digitTask = object : CountDownTimer(2000, 2000) {
        override fun onTick(m: Long) {}
        override fun onFinish() { if (digitBuf.isNotEmpty()) { commitDigit(); digitBuf = "" } }
    }

    private var isVip = false
    private var lastPlayError = ""
    private var lastSelfTest = ""
    private var loadChannelName = ""
    private var triedFresh = false
    /** true=当前这次"新开播"还没真正开始（避免旧频道的播放回调把加载提示提前隐藏） */
    private var awaitingStart = false
    private var lastPlayedUrl: String? = null
    private var failureReport = ""
    private var wasStopped = false
    private var resumeBtn: TextView? = null
    // 连续滑动两次退出
    private var lastSwipeTs = 0L
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var exitHint: TextView? = null
    private var confirmOverlay: View? = null
    /** 右上角「立即授权」按钮（未授权时显示） */
    private var authBtn: TextView? = null
    /** 右下角「已授权」小字标识（已授权时显示，未授权隐藏） */
    private var authStateText: TextView? = null
    /** 首次启动简易教程遮罩 */
    private var tutorialOverlay: View? = null
    private var tutorialVisible = false
    // 安装统计 + 版本推送
    private var updateOverlay: View? = null
    private var pendingUpdate: AuthApi.UpdateInfo? = null
    private var downloadId = -1L
    private val heartbeatTask = object : Runnable {
        override fun run() {
            Thread {
                try {
                    val card = getSharedPreferences("iptv_license", MODE_PRIVATE).getString("card_key", "") ?: ""
                    AuthApi.heartbeat(LicenseClient.machineCode(this@MainActivity), card, this@MainActivity)
                } catch (e: Exception) { }
            }.start()
            handler.postDelayed(this, 10 * 60 * 1000L)   // 每 10 分钟心跳
        }
    }
    private val downloadReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: android.content.Context, intent: android.content.Intent) {
            if (intent.action != android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id != downloadId) return
            val dm = ctx.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            val uri = runCatching { dm.getUriForDownloadedFile(id) }.getOrNull()
            if (uri != null) runOnUiThread {
                statusText.text = "✓ 新版本下载完成，正在打开安装…"
                try {
                    val i = android.content.Intent(android.content.Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(i)
                } catch (e: Exception) {
                    statusText.text = "已下载到 下载/ 目录，请手动安装"
                }
            }
        }
    }
    private var confirmVisible = false
    private var confirmCancelBtn: TextView? = null
    private var channelAdapter: RowAdapter? = null
    private var groupAdapter: RowAdapter? = null
    private var panelCol = 1          // 0=分组列表 1=频道列表

    companion object {
        const val MODE_NATIVE = 0
        @JvmStatic var playRequested: Int = -1
    }

    @SuppressLint("SetJavaScriptEnabled", "SetTextI18n", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { init(savedInstanceState) } catch (e: Exception) {
            // 保守恢复：显示错误不闪退
            val r = FrameLayout(this)
            r.setBackgroundColor(Color.BLACK)
            val tv = TextView(this)
            tv.text = "启动异常已恢复 (${e.javaClass.simpleName})\n请按返回键重启"
            tv.setTextColor(Color.WHITE); tv.textSize = 18f; tv.gravity = Gravity.CENTER
            r.addView(tv)
            setContentView(r)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun init(savedInstanceState: Bundle?) {
        hideSystemUi()
        root = FrameLayout(this)
        setContentView(root)

        // 1) 原生播放层
        playerView = PlayerView(this).apply { useController = false }
        root.addView(playerView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 3) 左上状态
        statusText = TextView(this).apply {
            textSize = 18f; setTextColor(Color.WHITE)
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
            setPadding(32, 24, 32, 24)
        }
        root.addView(statusText, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 4) 右上：免费时长 + 模式 + 【立即授权】按钮（横排，按钮在倒计时右边）
        val rightCol = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        trialText = TextView(this).apply {
            textSize = 14f; setTextColor(Color.WHITE)
            setBackgroundColor(0x88000000u.toInt())
            setPadding(20, 10, 20, 10)
        }
        rightCol.addView(trialText)

        authBtn = TextView(this).apply {
            text = "🔑 立即授权"
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF1B6EF3.toInt())
            setPadding(26, 10, 26, 10)
            isFocusable = true
            isFocusableInTouchMode = true
            visibility = View.GONE          // 授权成功后隐藏
            // 遥控器聚焦高亮：蓝底加白框
            val pad = (4 * resources.displayMetrics.density).toInt()
            setOnFocusChangeListener { v, has ->
                v.setBackgroundColor(if (has) 0xFF3D8BFF.toInt() else 0xFF1B6EF3.toInt())
                v.setPadding(26, 10, 26, 10)
                if (has) v.alpha = 1f else v.alpha = 0.95f
            }
            setOnClickListener { openAuthPage() }
        }
        rightCol.addView(authBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = 8 })
        val rcLP = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
        root.addView(rightCol, rcLP)

        // 4b) 右下角：已授权标识（小字，不打扰观看；未授权时隐藏）
        authStateText = TextView(this).apply {
            text = "✓ 已授权"
            textSize = 12f
            setTextColor(0xB3FFFFFF.toInt())      // 70% 白，弱化存在感
            setBackgroundColor(0x59000000.toInt()) // 35% 黑底，保证在亮画面上也看得清
            setPadding(14, 6, 14, 6)
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
            visibility = View.GONE
        }
        root.addView(authStateText, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 26, 26) })

        // 5) 数字键大字
        digitText = TextView(this).apply {
            textSize = 56f; setTextColor(Color.WHITE)
            setShadowLayer(6f, 3f, 3f, Color.BLACK)
            setPadding(24, 64, 48, 24)
            visibility = View.GONE
        }
        root.addView(digitText, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))

        // 6) 加载罩
        loadingMask = FrameLayout(this).apply { setBackgroundColor(0xB2000000u.toInt()); visibility = View.GONE }
        loadingText = TextView(this).apply {
            textSize = 16f; setTextColor(0xE6FFFFFF.toInt()); gravity = Gravity.CENTER
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
            setPadding(32, 0, 32, 0)
        }
        loadingMask.addView(loadingText, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(loadingMask, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 8) 「继续播放」按钮（从桌面回来画面卡住时点它重连）
        resumeBtn = TextView(this).apply {
            text = "▶  继续播放"
            textSize = 22f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xE61B6EF3u.toInt())
            setPadding(48, 24, 48, 24)
            gravity = Gravity.CENTER
            isFocusable = true
            visibility = View.GONE
            setOnClickListener { resumePlayback() }
        }
        root.addView(resumeBtn, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        // 7) ExoPlayer 初始化
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(1500, 12000, 500, 1000)
            .build()
        player = ExoPlayer.Builder(this).setLoadControl(loadControl).setMediaSourceFactory(
            DefaultMediaSourceFactory(
                DefaultDataSource.Factory(this,
                    DefaultHttpDataSource.Factory()
                        .setUserAgent("Mozilla/5.0 (Linux; Android 12; SM-S901B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                        .setDefaultRequestProperties(mapOf(
                            "Referer" to "https://m.iptv807.com/",
                            "Origin" to "https://m.iptv807.com"
                        ))
                        .setAllowCrossProtocolRedirects(true)
                        .setConnectTimeoutMs(8000)
                        .setReadTimeoutMs(12000)
                )
            )).build()
        playerView.player = player

        player?.addListener(object : Player.Listener {
            override fun onPlayerError(err: androidx.media3.common.PlaybackException) {
                lastPlayError = "播放错误 code=${err.errorCode}: ${err.message?.take(60) ?: ""}"
                autoSwitchLine()
            }
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> { setMode(MODE_NATIVE); if (awaitingStart) { awaitingStart = false; hideLoading() } }
                    Player.STATE_BUFFERING -> { /* 缓冲中，保持计时继续 */ }
                }
            }
            override fun onIsPlayingChanged(p: Boolean) {
                if (p) {
                    resumeBtn?.visibility = View.GONE
                    // 只有"这次要播的新频道"真正起播了才收起加载提示
                    if (awaitingStart) { awaitingStart = false; hideLoading() }
                }
            }
        })

        // 触屏/遥控 OK 弹面板
        playerView.setOnClickListener { if (!isPanelVisible()) showPanel() else hidePanel() }

        SourceResolver.init(this)
        // 播放期间保持屏幕常亮
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        checkLicense()
        startInstallTracking()   // 安装统计 + 检查新版本
        if (savedInstanceState == null && channels.isNotEmpty()) {
            val defIdx = channels.indexOfFirst { it.name.contains("TVB翡翠台") }.let { if (it >= 0) it else 0 }
            playChannel(defIdx)
            // 启动后预取默认频道的相邻频道（很轻量，1 个一个来）
            handler.postDelayed({ schedulePrefetch(channels.getOrNull(defIdx)) }, 2000)
        }
        // 新安装首次打开 → 简易使用教程（可关闭，只弹一次）
        maybeShowTutorial()
    }

    private fun setMode(m: Int) { mode = m }

    /** 启动自检：解析源 + 实测第一线路连通性，结果在状态栏显示 10 秒 */
    private fun runSelfTest(idx: Int) {
        val ch = channels.getOrNull(idx) ?: return
        Thread {
            val t0 = System.currentTimeMillis()
            val urls = try { SourceResolver.resolve(ch.tid, ch.id) } catch (e: Exception) { null }
            val resolveMs = System.currentTimeMillis() - t0
            val info = StringBuilder()
            if (urls.isNullOrEmpty()) {
                info.append("自检: 解析失败(${SourceResolver.lastError})")
            } else {
                info.append("自检: 解析${urls.size}线/${resolveMs}ms")
                // 实测第一条能否连上
                info.append(" | ").append(SourceResolver.verifyChain(ch.tid, ch.id))
            }
            runOnUiThread {
                statusText.text = info.toString()
                handler.postDelayed({
                    // 10 秒后恢复正常显示
                    val c = channels.getOrNull(currentChannel)
                    if (c != null) statusText.text = "▶ ${c.num} ${c.name}"
                }, 10000)
            }
        }.start()
    }

    private fun freeRemainingMs(): Long = LicenseClient.freeRemainingMs(this)
    private fun checkLicense() {
        val prefs = getSharedPreferences("iptv_license", MODE_PRIVATE)
        val card = prefs.getString("card_key", "") ?: ""
        Thread {
            val r = if (card.isNotEmpty()) LicenseClient.verify(card, LicenseClient.machineCode(this)) else LicenseClient.Result(false, "免费")
            runOnUiThread {
                isVip = r.ok
                prefs.edit().putBoolean("is_vip", r.ok).apply()
                if (r.ok) {
                    trialText.visibility = View.GONE; trialText.text = "VIP"
                    showAuthButton(false)
                    showAuthState(true)          // 右下角显示「✓ 已授权」
                } else enterFreeMode()
            }
        }.start()
    }

    private fun enterFreeMode() {
        trialText.visibility = View.VISIBLE
        showAuthButton(true)
        showAuthState(false)         // 免费模式下不显示右下角标识
        handler.removeCallbacks(uiTick)
        handler.post(uiTick)
    }

    /** 右下角「✓ 已授权」标识 显示/隐藏 */
    private fun showAuthState(show: Boolean) {
        runOnUiThread {
            val t = authStateText ?: return@runOnUiThread
            t.visibility = if (show) View.VISIBLE else View.GONE
            t.text = if (show) "✓ 已授权" else ""
        }
    }

    /** 显示/隐藏右上角「立即授权」按钮 */
    private fun showAuthButton(show: Boolean) {
        runOnUiThread {
            val b = authBtn ?: return@runOnUiThread
            b.visibility = if (show) View.VISIBLE else View.GONE
            if (show) {
                // 首次出现时自动聚焦，遥控器按 OK 直接进授权页
                b.postDelayed({
                    if (b.visibility == View.VISIBLE && !isPanelVisible() && tutorialOverlay?.visibility != View.VISIBLE) {
                        b.requestFocus()
                    }
                }, 600)
            } else {
                b.clearFocus()
            }
        }
    }

    /** 打开授权/激活页面（含卡密激活与购买入口） */
    private fun openAuthPage() {
        try {
            startActivity(Intent(this, SettingsActivity::class.java))
        } catch (e: Exception) {
            statusText.text = "打开授权页失败: ${e.javaClass.simpleName}: ${e.message}"
            android.widget.Toast.makeText(this, "打开授权页失败: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** 首次启动：简易使用教程（可关闭，只弹一次） */
    private fun maybeShowTutorial() {
        try {
            if (getSharedPreferences("iptv_main", MODE_PRIVATE).getBoolean("tutorial_done", false)) return

            val mask = FrameLayout(this).apply {
                setBackgroundColor(0xE6000000.toInt())
                isClickable = true   // 挡住底层点击
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xFF161B26.toInt())
                setPadding(64, 48, 64, 56)
            }
            card.addView(TextView(this).apply {
                text = "📺 欢迎使用「港澳台直播」"
                textSize = 24f; setTextColor(Color.WHITE); setPadding(0, 0, 0, 6)
            })
            card.addView(TextView(this).apply {
                text = "花 30 秒知道怎么用，功能一个不少"
                textSize = 14f; setTextColor(0xFF93A1B8.toInt()); setPadding(0, 0, 0, 26)
            })

            val steps = listOf(
                "1️⃣  按遥控器 OK 键 → 打开选台面板",
                "2️⃣  上下键选频道（选中行蓝底高亮），左右键切换香港 / 澳门 / 台湾分组",
                "3️⃣  直接按数字键 → 快速跳到对应频道（按 9 就是 9 号台）",
                "4️⃣  菜单键 → 收藏 / 取消收藏当前频道",
                "5️⃣  连滑两次 或 按返回键 → 退出应用（都会先弹确认框）",
                "6️⃣  断线自动重连、10 条线路自动切换，画面卡住点「▶ 继续播放」即可"
            )
            for (t in steps) {
                card.addView(TextView(this).apply {
                    text = t; textSize = 16f; setTextColor(0xFFE6ECF7.toInt())
                    setLineSpacing(0f, 1.35f); setPadding(0, 9, 0, 9)
                })
            }

            val okBtn = android.widget.Button(this).apply {
                text = "知道了，开始观看"
                textSize = 17f
                isFocusable = true; isFocusableInTouchMode = true
                setBackgroundColor(0xFF1B6EF3.toInt())
                setTextColor(Color.WHITE)
                setOnClickListener { closeTutorial() }
            }
            card.addView(okBtn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 34 })

            val scroll = android.widget.ScrollView(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                isFillViewport = true
                addView(card)
            }
            mask.addView(scroll, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
            ).apply { setMargins(260, 90, 260, 90) })

            root.addView(mask, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            tutorialOverlay = mask
            tutorialVisible = true
            okBtn.post { okBtn.requestFocus() }   // 遥控器 OK 直接关闭
        } catch (e: Exception) {
            // 教程失败不能影响正常播放
            tutorialVisible = false
        }
    }

    /** 关闭教程并记住（之后不再显示） */
    private fun closeTutorial() {
        tutorialVisible = false
        try {
            getSharedPreferences("iptv_main", MODE_PRIVATE).edit().putBoolean("tutorial_done", true).apply()
        } catch (e: Exception) { }
        root.removeView(tutorialOverlay)
        tutorialOverlay = null
    }

    private fun onUiTick() {
        if (isVip) return
        // 每秒累计一次免费用量，这样右上角倒计时才会真正走动
        if (player?.isPlaying == true) LicenseClient.addFreeUsedMs(this, 1000)
        val r = freeRemainingMs()
        trialText.visibility = View.VISIBLE
        trialText.text = if (r <= 0) "⏳ 今日免費已用完"
                         else "⏳ 今日免費 ${r / 60000}分${((r % 60000) / 1000).toString().padStart(2, '0')}秒"
        handler.postDelayed(uiTick, 1000)
    }

    private fun showTrialExpired() {
        player?.pause()
        trialText.visibility = View.GONE
        statusText.text = "今日免费时长已用完"
        startActivity(Intent(this, TrialExpiredActivity::class.java))
        finish()
    }

    private fun isPanelVisible(): Boolean = panelLayout?.visibility == View.VISIBLE

    /** 面板列表行的适配器：高亮由我们自己控制（row == selected 时蓝色底），不依赖系统焦点 */
    private inner class RowAdapter(private val items: List<String>) : android.widget.BaseAdapter() {
        var selected = 0
            set(value) { field = value; notifyDataSetChanged() }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tv = (convertView as? TextView) ?: TextView(this@MainActivity).apply {
                textSize = 17f
                setTextColor(Color.WHITE)
                setPadding(36, 22, 36, 22)
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            tv.text = items[position]
            val on = position == selected
            tv.setBackgroundColor(if (on) Color.parseColor("#1B6EF3") else Color.TRANSPARENT)
            tv.setTextColor(if (on) Color.WHITE else Color.parseColor("#DDDDDD"))
            return tv
        }
    }

    /** 遥控焦点高亮：方向键经过的行显示蓝色背景 */
    private fun highlightDrawable() = android.graphics.drawable.ColorDrawable(Color.parseColor("#1B6EF3"))

    private fun panelGroups(): List<ChannelData.Group> =
        if (favSet().isNotEmpty()) listOf(favGroup) + groups else groups

    private fun favSet(): MutableSet<String> =
        getSharedPreferences("iptv_fav", MODE_PRIVATE).getStringSet("favs", HashSet<String>())!!.toMutableSet()

    private fun toggleFav(ch: ChannelData.Channel) {
        val s = favSet()
        val n = ch.num.toString()
        if (n in s) s.remove(n) else s.add(n)
        getSharedPreferences("iptv_fav", MODE_PRIVATE).edit().putStringSet("favs", s).apply()
    }

    private fun channelsOfGroup(gpos: Int): List<ChannelData.Channel> {
        val g = panelGroups()[gpos]
        return if (g.name == favGroup.name) {
            favSet().mapNotNull { n -> channels.find { it.num == n.toInt() } }.sortedBy { it.num }
        } else channels.filter { it.group == g.name }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showPanel() {
        if (panelLayout == null) {
            panelLayout = buildPanel()
            root.addView(panelLayout, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        panelLayout?.visibility = View.VISIBLE
        val gpos = panelGroups().indexOfFirst { g ->
            val num = channels.getOrNull(currentChannel)?.num
            if (g.name == favGroup.name) num?.toString() in favSet()
            else g.name == channels.getOrNull(currentChannel)?.group
        }
        if (gpos >= 0) { curGroupPos = gpos; updateChannelListOfGroup(gpos) }
        panelCol = 1
        refreshPanelHighlight()
        resetHideTimer()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildPanel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(0xE8141418u.toInt())
        setOnClickListener { hidePanel() }
        // 自己不要抢焦点，让方向键作用在两个列表上
        isFocusable = false
        isFocusableInTouchMode = false
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        groupList = ListView(this@MainActivity).apply {
            setBackgroundColor(Color.TRANSPARENT)
            val ga = RowAdapter(listOf("⚙ 设置") + panelGroups().map { "${it.name} (${it.count})" })
            groupAdapter = ga
            adapter = ga
            setSelector(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            isFocusable = false
            isFocusableInTouchMode = false
            setItemsCanFocus(false)
            onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
                if (pos == 0) {
                    try { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
                    catch (e: Exception) {
                        statusText.text = "打开设置失败: ${e.javaClass.simpleName}: ${e.message}"
                        android.widget.Toast.makeText(this@MainActivity, "打开设置失败: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                        return@OnItemClickListener
                    }
                    hidePanel(); return@OnItemClickListener
                }
                if (pos == 0) return@OnItemClickListener
                curGroupPos = pos - 1
                updateChannelListOfGroup(curGroupPos)
                panelCol = 1
                refreshPanelHighlight()
            }
            setPadding(8, 24, 8, 24)
            setOnTouchListener { v, ev ->
                if (ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_MOVE) resetHideTimer()
                v.onTouchEvent(ev)
            }
        }
        addView(groupList, LinearLayout.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.2f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))

        channelList = ListView(this@MainActivity).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setSelector(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            isFocusable = false
            isFocusableInTouchMode = false
            setItemsCanFocus(false)
            onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
                val list = channelsOfGroup(curGroupPos)
                if (pos < list.size) { playChannel(channels.indexOf(list[pos])); hidePanel() }
            }
            setPadding(8, 24, 8, 24)
            setOnTouchListener { v, ev ->
                if (ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_MOVE) resetHideTimer()
                v.onTouchEvent(ev)
            }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(v: AbsListView, s: Int) { resetHideTimer() }
                override fun onScroll(v: AbsListView, a: Int, b: Int, c: Int) { resetHideTimer() }
            })
        }
        addView(channelList, LinearLayout.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.38f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun updateChannelListOfGroup(pos: Int) {
        val list = channelsOfGroup(pos)
        val ca = RowAdapter(list.map { "%3d  %s".format(it.num, it.name) })
        channelAdapter = ca
        channelList?.adapter = ca
        // 默认高亮正在播的频道
        val ci = list.indexOfFirst { it.num == channels.getOrNull(currentChannel)?.num }
        ca.selected = if (ci >= 0) ci else 0
    }

    /** 刷新两个列表的高亮（我们自己控制，不依赖系统焦点） */
    private fun refreshPanelHighlight() {
        val gsel = if (panelCol == 0) curGroupPos + 1 else -1
        groupAdapter?.selected = if (gsel >= 0) gsel else -1
        groupList?.setSelection(curGroupPos + 1)
        val cpos = channelAdapter?.selected ?: 0
        channelList?.setSelection(cpos)
    }

    private fun hidePanel() { handler.removeCallbacks(hidePanelTask); panelLayout?.visibility = View.GONE }

    /** 只预取"当前高亮的那一个频道"（1.2 秒去抖动）——源站很脆，不做整组预取 */
    private val prefetchTask = Runnable {
        val ch = prefetchTarget ?: return@Runnable
        if (SourceResolver.hasCache(ch.tid, ch.id)) return@Runnable
        Thread {
            try { SourceResolver.resolveLines(ch.tid, ch.id) } catch (e: Exception) { }
        }.start()
    }
    private var prefetchTarget: ChannelData.Channel? = null
    private fun schedulePrefetch(ch: ChannelData.Channel?) {
        if (ch == null) return
        prefetchTarget = ch
        handler.removeCallbacks(prefetchTask)
        handler.postDelayed(prefetchTask, 1200)
    }
    private fun resetHideTimer() {
        handler.removeCallbacks(hidePanelTask)
        handler.postDelayed(hidePanelTask, 5000)
    }

    private fun hideLoading() { loadingMask.visibility = View.GONE; handler.removeCallbacks(loadTick) }

    /** 开始一次新的换台计时（只在切台时调用） */
    private fun beginLoading() {
        loadStart = System.currentTimeMillis()
        awaitingStart = false
        loadingText.text = "正在加载 1 秒，精彩继续…"
        loadingMask.visibility = View.VISIBLE
        loadingMask.bringToFront()
        handler.removeCallbacks(loadTick)
        handler.postDelayed(loadTick, 1000)
    }

    private fun playChannel(idx: Int, line: Int = 0) {
        if (idx >= channels.size) return
        val isSameChannel = (idx == currentChannel)
        currentChannel = idx; currentLine = line
        val ch = channels[idx]
        if (!isSameChannel) triedFresh = false
        loadChannelName = ch.name
        if (!isSameChannel || loadingMask.visibility != View.VISIBLE) {
            beginLoading()   // 只有换台才重置计时；同台换线路时继续计时
        }
        Thread {
            val urls = try { SourceResolver.resolve(ch.tid, ch.id) } catch (e: Exception) { null }
            if (urls.isNullOrEmpty()) {
                // 自动重试 3 次（间隔 4 秒），期间给友好提示
                var got: List<String>? = null
                for (r in 1..3) {
                    runOnUiThread { statusText.text = "网络波动，正在重试 ($r/3)…"; loadingText.text = "正在加载，精彩继续…" }
                    Thread.sleep(4000)
                    got = try { SourceResolver.resolveLines(ch.tid, ch.id) } catch (e: Exception) { null }
                    if (!got.isNullOrEmpty()) break
                }
                if (got.isNullOrEmpty()) {
                    runOnUiThread {
                        statusText.text = "⚠ ${ch.num} ${ch.name} 源解析失败\n解析: ${SourceResolver.lastError}"
                        failureReport = "解析失败 ${SourceResolver.lastError}"
                        hideLoading()
                    }
                    return@Thread
                }
                val u2 = got!!
                runOnUiThread { playUrl(u2[0], ch) }
                return@Thread
            }
            // 不预探测（那是切台慢的主因）：直接播对应线路，失败由 onPlayerError 自动换下一条
            var chosen = line % urls.size
            val probe = StringBuilder("skip")
            val tok = Regex("token=([^&]+)").find(urls[0])?.groupValues?.get(1)?.take(14) ?: "?"
            failureReport = "探测: $probe\ntoken: $tok"
            lastSelfTest = failureReport
            if (chosen < 0) {
                // 用的是缓存线路且全失效 → 强制刷新一次再试
                runOnUiThread { statusText.text = "线路失效，正在刷新源…" }
                Thread.sleep(2500)
                val fresh = try { SourceResolver.resolveFresh(ch.tid, ch.id) } catch (e: Exception) { null }
                if (!fresh.isNullOrEmpty() && fresh != urls) {
                    for (i in fresh.indices) {
                        val st = probeLine(fresh[i])
                        if (st == "200" || st == "206" || st == "302") { chosen = i; break }
                    }
                    if (chosen >= 0) {
                        runOnUiThread { playUrl(fresh[chosen], ch) }
                        return@Thread
                    }
                }
            }
            runOnUiThread {
                if (chosen < 0) {
                    statusText.text = "⚠ ${ch.num} ${ch.name} 源站拒绝(全部线路)\n$failureReport"
                    hideLoading()
                } else {
                    playUrl(urls[chosen], ch)
                }
            }
        }.start()
    }

    /** 轻量探测一条线路：跟 302 后取 HTTP 状态码 */
    private fun probeLine(url: String): String = try {
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 4000; readTimeout = 4000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12; SM-S901B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
            setRequestProperty("Referer", "https://m.iptv807.com/")
            setRequestProperty("Origin", "https://m.iptv807.com")
            setRequestProperty("Range", "bytes=0-1")
            instanceFollowRedirects = true
        }
        val code = conn.responseCode
        runCatching { conn.inputStream?.close() }
        conn.disconnect()
        code.toString()
    } catch (e: Exception) { "E:${e.javaClass.simpleName}" }

    private fun playUrl(url: String, ch: ChannelData.Channel) {
        val isFlv = url.contains("type=.flv") || url.endsWith(".flv")
        val item = MediaItem.Builder().setUri(Uri.parse(url))
            .setMimeType(if (isFlv) MimeTypes.VIDEO_FLV else MimeTypes.APPLICATION_M3U8)
            .build()
        awaitingStart = true
        lastPlayedUrl = url
        player?.setMediaItem(item)
        player?.prepare()
        player?.playWhenReady = true
        statusText.text = "… ${ch.num} ${ch.name}"
        HistoryRecorder.log(this, ch.num, ch.name)
    }

    @SuppressLint("SetTextI18n")
    private fun autoSwitchLine() {
        val ch = channels.getOrNull(currentChannel) ?: return
        currentLine++
        if (currentLine > 12) {
            // 可能是缓存里的 token 过期了 → 强制刷新一次源再试一轮
            if (!triedFresh) {
                triedFresh = true
                statusText.text = "线路失效，正在刷新源…"
                Thread {
                    val fresh = try { SourceResolver.resolveFresh(ch.tid, ch.id) } catch (e: Exception) { null }
                    runOnUiThread {
                        if (!fresh.isNullOrEmpty()) { currentLine = 0; playChannel(currentChannel, 0) }
                        else {
                            val vn = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
                            statusText.text = "⚠ ${ch.num} ${ch.name} 全部线路失败\n$lastPlayError\n解析: ${SourceResolver.lastError}\n版本: v$vn"
                            hideLoading()
                        }
                    }
                }.start()
                return
            }
            val vn = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
            statusText.text = "⚠ ${ch.num} ${ch.name} 全部线路失败\n$lastPlayError\n解析: ${SourceResolver.lastError}\n版本: v$vn"
            hideLoading()
            return
        }
        playChannel(currentChannel, currentLine)
    }

    private fun onDigit(d: Int) {
        digitBuf += d.toString()
        digitText.text = digitBuf
        digitText.visibility = View.VISIBLE
        val t = digitBuf.toIntOrNull() ?: return
        val idx = channels.indexOfFirst { it.num == t }
        if (idx >= 0) {
            playChannel(idx); digitBuf = ""; digitText.visibility = View.GONE; digitTask.cancel()
        } else { digitTask.cancel(); digitTask.start() }
    }
    private fun commitDigit() {
        val t = digitBuf.toIntOrNull() ?: return
        val idx = channels.indexOfFirst { it.num == t }
        if (idx >= 0) playChannel(idx)
        digitText.visibility = View.GONE
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 首次教程显示时：返回/OK 都可关闭
        if (tutorialVisible) {
            if (keyCode == KeyEvent.KEYCODE_BACK ||
                keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_ENTER) {
                closeTutorial(); return true
            }
            return true
        }
        // 退出确认框显示时：返回=取消，其余交给按钮焦点系统处理（左右切换，OK 确认）
        if (confirmVisible) {
            if (keyCode == KeyEvent.KEYCODE_BACK) { hideExitConfirm(); return true }
            return super.onKeyDown(keyCode, event)
        }
        // 右上角「立即授权」按钮聚焦时：OK 进授权页，返回取消聚焦，方向键清焦点后继续正常换台
        if (authBtn?.visibility == View.VISIBLE && authBtn?.isFocused == true) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    openAuthPage(); return true
                }
                KeyEvent.KEYCODE_BACK -> { authBtn?.clearFocus(); return true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    authBtn?.clearFocus()   // 不 return：落到下面正常换台/开面板逻辑
                }
            }
        }
        // 「继续播放」按钮可见时，遥控 OK/确认 直接触发
        if (resumeBtn?.visibility == View.VISIBLE &&
            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
             keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_SPACE)) {
            resumePlayback(); return true
        }
        // 数字键：直接跳台（面板开着也能用）
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) { onDigit(keyCode - KeyEvent.KEYCODE_0); return true }
        if (keyCode == KeyEvent.KEYCODE_NUMPAD_0) { onDigit(0); return true }
        if (keyCode in KeyEvent.KEYCODE_NUMPAD_1..KeyEvent.KEYCODE_NUMPAD_9) { onDigit(keyCode - KeyEvent.KEYCODE_NUMPAD_0); return true }

        // ★ 面板打开时：方向键/确认键全部由我们自己处理（不依赖系统焦点，电视盒子兼容性最好）
        if (isPanelVisible()) {
            resetHideTimer()
            val chList = channelsOfGroup(curGroupPos)
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> { hidePanel(); return true }

                KeyEvent.KEYCODE_MENU -> {
                    val pos = channelAdapter?.selected ?: -1
                    if (pos in chList.indices) {
                        toggleFav(chList[pos])
                        updateChannelListOfGroup(curGroupPos)
                        channelAdapter?.selected = pos
                        Toast.makeText(this, if (favSet().contains(chList[pos].num.toString())) "★ 已收藏 ${chList[pos].name}" else "☆ 已取消 ${chList[pos].name}", Toast.LENGTH_SHORT).show()
                    }
                    return true
                }

                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (panelCol == 1) { panelCol = 0; refreshPanelHighlight() }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (panelCol == 0) { panelCol = 1; refreshPanelHighlight() }
                    return true
                }

                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val delta = if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                    if (panelCol == 1) {
                        val a = channelAdapter ?: return true
                        if (chList.isEmpty()) return true
                        val np = (a.selected + delta).coerceIn(0, chList.size - 1)
                        a.selected = np
                        channelList?.setSelection(np)
                        schedulePrefetch(chList.getOrNull(np))   // 提前备好源
                    } else {
                        val a = groupAdapter ?: return true
                        val total = (a.count) // 含"⚙ 设置"
                        val cur = curGroupPos + 1
                        val np = (cur + delta).coerceIn(0, total - 1)
                        if (np != cur) {
                            if (np == 0) { a.selected = 0 }                       // 停在"设置"上，确认才进
                            else { curGroupPos = np - 1; a.selected = np; updateChannelListOfGroup(curGroupPos) }
                            groupList?.setSelection(np)
                        }
                    }
                    return true
                }

                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (panelCol == 0) {
                        val np = groupAdapter?.selected ?: 0
                        if (np == 0) {
                            // 保险：打开失败要能在屏幕上看到原因，便于排查
                            try { startActivity(Intent(this, SettingsActivity::class.java)) }
                            catch (e: Exception) {
                                statusText.text = "打开设置失败: ${e.javaClass.simpleName}: ${e.message}"
                                android.widget.Toast.makeText(this@MainActivity, "打开设置失败: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                                return true
                            }
                            hidePanel()
                        }
                        else { curGroupPos = np - 1; updateChannelListOfGroup(curGroupPos); panelCol = 1; refreshPanelHighlight() }
                    } else {
                        val pos = channelAdapter?.selected ?: -1
                        if (pos in chList.indices) { playChannel(channels.indexOf(chList[pos])); hidePanel() }
                    }
                    return true
                }
            }
            return true   // 面板打开时吞掉其它按键，避免误触发换台
        }

        when (keyCode) {
            // ★ 修复：返回键必须弹退出确认，绝不直接 finish（原来漏了这个分支，
            //   导致 BACK 落到 super.onKeyDown() = 系统默认无提示退出）
            KeyEvent.KEYCODE_BACK -> { exitApp(); return true }
            KeyEvent.KEYCODE_MENU -> { autoSwitchLine(); return true }
            KeyEvent.KEYCODE_CHANNEL_UP -> { playChannel((currentChannel - 1 + channels.size) % channels.size); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { playChannel((currentChannel + 1) % channels.size); return true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                playChannel((currentChannel + (if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1) + channels.size) % channels.size); return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { showPanel(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    /** 全局手势：连续滑动两次 → 完全退出 App */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        try {
            if (ev != null) {
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downX = ev.rawX; downY = ev.rawY; downT = System.currentTimeMillis()
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        val dx = ev.rawX - downX
                        val dy = ev.rawY - downY
                        val dist = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
                        val dt = System.currentTimeMillis() - downT
                        // 一次有效滑动：位移够大、时间够短
                        if (dist > 120f && dt < 800) {
                            val now = System.currentTimeMillis()
                            if (now - lastSwipeTs < 1500) { exitApp(); return true }
                            lastSwipeTs = now
                            showExitHint()
                        }
                    }
                }
            }
        } catch (e: Exception) { }
        return super.dispatchTouchEvent(ev)
    }

    /** 第一次滑动后提示"再滑一次退出" */
    private fun showExitHint() {
        runOnUiThread {
            if (exitHint == null) {
                exitHint = TextView(this).apply {
                    textSize = 18f; setTextColor(Color.WHITE)
                    setBackgroundColor(0xB3000000u.toInt())
                    setPadding(40, 20, 40, 20); gravity = Gravity.CENTER
                }
                (window.decorView as android.view.ViewGroup).addView(exitHint,
                    android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT).also {
                        (it as android.widget.FrameLayout.LayoutParams).gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                        it.bottomMargin = 80
                    })
            }
            exitHint?.text = "再滑动一次退出应用"
            exitHint?.visibility = View.VISIBLE
            handler.removeCallbacks(hideExitHintTask)
            handler.postDelayed(hideExitHintTask, 2000)
        }
    }
    private val hideExitHintTask = Runnable { exitHint?.visibility = View.GONE }

    // ===== 安装统计 & 版本推送 =====
    private fun startInstallTracking() {
        val prefs = getSharedPreferences("iptv_license", MODE_PRIVATE)
        val card = prefs.getString("card_key", "") ?: ""
        Thread {
            val mid = LicenseClient.machineCode(this@MainActivity)
            // check-update 一次请求同时完成：安装登记 + 在线上报 + 查新版本
            val info = runCatching {
                AuthApi.checkUpdate(mid, card, this@MainActivity)
            }.getOrDefault(AuthApi.UpdateInfo(msg = AuthApi.lastError))
            runOnUiThread {
                if (info.ok) {
                    val sb = StringBuilder("安装上报 ok #${info.installId} · v${AuthApi.clientVersion(this)}")
                    if (info.updateAvailable) sb.append(" · 发现新版 v${info.latestVersion}")
                    statusText.text = sb.toString()
                    handler.postDelayed({
                        val c = channels.getOrNull(currentChannel)
                        if (c != null) statusText.text = "▶ ${c.num} ${c.name}"
                    }, 8000)
                    if (info.updateAvailable) {
                        pendingUpdate = info
                        showUpdateDialog(info)
                    }
                } else {
                    statusText.text = "安装上报失败: ${info.msg}"
                }
            }
        }.start()
        handler.removeCallbacks(heartbeatTask)
        handler.postDelayed(heartbeatTask, 60 * 1000L)   // 1 分钟后首次心跳
    }

    /** 新版本提示（自绘浮层，电视全屏主题下 AlertDialog 不可靠） */
    private fun showUpdateDialog(info: AuthApi.UpdateInfo) {
        if (updateOverlay == null) {
            val mask = FrameLayout(this).apply { setBackgroundColor(0xCC000000u.toInt()) }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#22262E"))
                setPadding(64, 48, 64, 48)
            }
            card.addView(TextView(this).apply {
                text = "发现新版本"; textSize = 24f; setTextColor(Color.WHITE)
                gravity = Gravity.CENTER; setPadding(0, 0, 0, 12)
            })
            card.addView(TextView(this).apply {
                tag = "ver"; textSize = 40f; setTextColor(Color.parseColor("#4CAF50"))
                gravity = Gravity.CENTER; setPadding(0, 0, 0, 16)
            })
            card.addView(TextView(this).apply {
                tag = "meta"; textSize = 15f; setTextColor(Color.LTGRAY)
                gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f); setPadding(0, 0, 0, 24)
            })
            card.addView(TextView(this).apply {
                tag = "chg"; textSize = 15f; setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#1C2028"))
                setPadding(24, 20, 24, 20); setLineSpacing(0f, 1.25f)
                maxLines = 8; gravity = Gravity.CENTER
            })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, 28, 0, 0) }
            fun mkBtn(label: String, focusable: Boolean, onClick: () -> Unit): TextView =
                TextView(this).apply {
                    text = label; textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                    setPadding(56, 22, 56, 22); isFocusable = focusable; isFocusableInTouchMode = focusable
                    if (focusable) background = android.graphics.drawable.StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_focused),
                            android.graphics.drawable.ColorDrawable(Color.parseColor("#1B6EF3")))
                        addState(intArrayOf(), android.graphics.drawable.ColorDrawable(Color.parseColor("#3A4150")))
                    } else setBackgroundColor(Color.parseColor("#2A2F3A"))
                    setOnClickListener { onClick() }
                }
            val laterBtn = mkBtn("稍后", true) { hideUpdateDialog() }
            val dlBtn = mkBtn("立即更新", false) {
                pendingUpdate?.let { startUpdateDownload(it) }; hideUpdateDialog()
            }
            row.addView(laterBtn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = 32 })
            row.addView(dlBtn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            card.addView(row)
            mask.addView(card, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            root.addView(mask, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            updateOverlay = mask
        }
        val ver = updateOverlay!!.findViewWithTag<TextView>("ver")
        val meta = updateOverlay!!.findViewWithTag<TextView>("meta")
        val chg = updateOverlay!!.findViewWithTag<TextView>("chg")
        ver.text = "v" + info.latestVersion
        val mb = if (info.fileSize > 0) "%.1f MB".format(info.fileSize / 1024.0 / 1024.0) else ""
        val force = info.updateRequired || (info.minClientVersion.isNotEmpty() &&
                compareVersion(AuthApi.clientVersion(this), info.minClientVersion) < 0)
        meta.text = "当前 v${AuthApi.clientVersion(this)}" + (if (mb.isNotEmpty()) "  ·  $mb" else "") +
                (if (force) "\n⚠ 请升级到此版本" else "")
        chg.text = info.changelog.ifEmpty { "本次更新包含功能优化与问题修复" }
        updateOverlay!!.visibility = View.VISIBLE
        // 强制更新时不给"稍后"
        updateOverlay!!.findViewWithTag<TextView>("ver") // touch to ensure rendered
    }

    private fun hideUpdateDialog() { updateOverlay?.visibility = View.GONE }

    /** 比较 "9.10.0" vs "9.3.0" 之类 */
    fun compareVersion(a: String, b: String): Int {
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }; val y = pb.getOrElse(i) { 0 }
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }

    /** 用系统 DownloadManager 下载新版本 APK，完成广播里自动拉起安装 */
    private fun startUpdateDownload(info: AuthApi.UpdateInfo) {
        if (info.downloadUrl.isEmpty()) {
            statusText.text = "暂无下载地址，请到后台发布安装包"
            return
        }
        try {
            val dm = getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            val name = "gangao-tv-${info.latestVersion}.apk"
            val req = android.app.DownloadManager.Request(Uri.parse(info.downloadUrl))
                .setTitle("港澳台直播 v${info.latestVersion}")
                .setDescription("正在下载新版本…")
                .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setMimeType("application/vnd.android.package-archive")
                .setDestinationInExternalFilesDir(this, android.os.Environment.DIRECTORY_DOWNLOADS, name)
            downloadId = dm.enqueue(req)
            try { registerReceiver(downloadReceiver, android.content.IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE)) } catch (e: Exception) { }
            statusText.text = "⬇ 已开始下载 v${info.latestVersion}，完成后会自动提示安装"
        } catch (e: Exception) {
            statusText.text = "下载失败: ${e.message}"
        }
    }

    /** 退出前先弹确认框（自绘，不依赖系统对话框主题） */
    private fun exitApp() {
        runOnUiThread {
            try {
                exitHint?.visibility = View.GONE
                showExitConfirm()
            } catch (e: Exception) {
                doExit()
            }
        }
    }

    /** 自绘退出确认框：上下左右可用遥控，OK 确认 */
    private fun showExitConfirm() {
        if (confirmOverlay == null) {
            val mask = FrameLayout(this).apply { setBackgroundColor(0xCC000000u.toInt()) }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#22262E"))
                setPadding(64, 48, 64, 48)
            }
            card.addView(TextView(this).apply {
                text = "退出应用"
                textSize = 24f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                setPadding(0, 0, 0, 16)
            })
            card.addView(TextView(this).apply {
                text = "确定要退出「港澳台直播」吗？"
                textSize = 16f; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER
                setPadding(0, 0, 0, 36)
            })

            fun mkBtn(label: String, onClick: () -> Unit): TextView {
                val tv = TextView(this).apply {
                    text = label
                    textSize = 18f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setPadding(56, 22, 56, 22)
                }
                // 获得焦点→蓝色，普通→灰色
                val sl = android.graphics.drawable.StateListDrawable()
                sl.addState(intArrayOf(android.R.attr.state_focused),
                    android.graphics.drawable.ColorDrawable(Color.parseColor("#1B6EF3")))
                sl.addState(intArrayOf(),
                    android.graphics.drawable.ColorDrawable(Color.parseColor("#3A4150")))
                tv.background = sl
                tv.setOnClickListener { onClick() }
                return tv
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            val cancelBtn = mkBtn("取消") { hideExitConfirm() }
            val okBtn = mkBtn("退出") {
                hideExitConfirm()
                handler.postDelayed({ doExit() }, 120)
            }
            row.addView(cancelBtn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = 32 })
            row.addView(okBtn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            card.addView(row)

            mask.addView(card, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            root.addView(mask, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            confirmOverlay = mask
            confirmCancelBtn = cancelBtn
        }
        confirmOverlay?.visibility = View.VISIBLE
        confirmVisible = true
        // 默认焦点放在「取消」上，避免误按 OK 直接退出
        confirmCancelBtn?.post { confirmCancelBtn?.requestFocus() }
    }

    private fun hideExitConfirm() {
        confirmVisible = false
        confirmOverlay?.visibility = View.GONE
    }

    /** 真正完全退出 App */
    private fun doExit() {
        try { handler.removeCallbacksAndMessages(null) } catch (e: Exception) { }
        try { player?.release() } catch (e: Exception) { }
        try { finishAndRemoveTask() } catch (e: Exception) { finishAffinity() }
        // 彻底结束进程（电视盒子上保证真的退出）
        handler.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 250)
    }

    override fun onResume() {
        super.onResume()
        // 从设置页输入卡密激活后，回来立即生效
        run {
            val prefs = getSharedPreferences("iptv_license", MODE_PRIVATE)
            val nowVip = prefs.getBoolean("is_vip", false)
            val card = prefs.getString("card_key", "") ?: ""
            if (nowVip && (card.isNotEmpty() || !isVip)) {
                isVip = true
                trialText.visibility = View.GONE
                showAuthButton(false)
                showAuthState(true)          // 右下角显示「✓ 已授权」
                handler.removeCallbacks(uiTick)
            } else if (!nowVip && isVip) {
                // ★ 设置页里撤销了授权 → 回到免费模式（倒计时 + 立即授权按钮都恢复）
                isVip = false
                showAuthState(false)         // 右下角标识隐藏
                enterFreeMode()
            }
        }
        // 从桌面/设置返回：先尝试续播，失败则显示「继续播放」按钮
        if (wasStopped) {
            wasStopped = false
            val idx = if (currentChannel in channels.indices) currentChannel else 0
            val url = lastPlayedUrl
            if (url != null && channels.isNotEmpty()) {
                // 直接重连同一路流（新 HTTP 连接），直播流这样才能真正恢复
                loadChannelName = channels.getOrNull(idx)?.name ?: ""
                beginLoading()
                playUrl(url, channels[idx])
                handler.postDelayed({
                    if (player?.isPlaying != true) resumeBtn?.visibility = View.VISIBLE
                    else resumeBtn?.visibility = View.GONE
                }, 3500)
            } else {
                try { player?.play() } catch (e: Exception) { }
                handler.postDelayed({
                    if (player?.isPlaying != true) resumeBtn?.visibility = View.VISIBLE
                    else resumeBtn?.visibility = View.GONE
                }, 1800)
            }
        }
        if (playRequested > 0) {
            val idx = channels.indexOfFirst { it.num == playRequested }
            playRequested = -1
            if (idx >= 0) playChannel(idx)
        }
    }

    /** 点「继续播放」→ 重新连接当前频道（直播流最稳的恢复方式） */
    private fun resumePlayback() {
        resumeBtn?.visibility = View.GONE
        val idx = if (currentChannel in channels.indices) currentChannel else 0
        val url = lastPlayedUrl
        if (url != null && channels.isNotEmpty()) {
            // 直接重连同一路流：比重新抓源快得多，也最可靠
            loadChannelName = channels[idx].name
            beginLoading()
            playUrl(url, channels[idx])
        } else {
            playChannel(idx)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    override fun onStop() {
        wasStopped = true
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        digitTask.cancel()
        handler.removeCallbacksAndMessages(null)
        playerView.player = null
        player?.release()
        runCatching { unregisterReceiver(downloadReceiver) }
        super.onDestroy()
    }
}
