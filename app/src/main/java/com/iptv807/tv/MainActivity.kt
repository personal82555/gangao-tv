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
            loadingText.text = "正在加载 $s 秒，精彩继续…"
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
    private var failureReport = ""
    private var wasStopped = false
    private var resumeBtn: TextView? = null
    // 连续滑动两次退出
    private var lastSwipeTs = 0L
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var exitHint: TextView? = null

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

        // 4) 右上：免费时长 + 模式
        val rightCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        trialText = TextView(this).apply {
            textSize = 14f; setTextColor(Color.WHITE)
            setBackgroundColor(0x88000000u.toInt())
            setPadding(20, 10, 20, 10)
        }
        rightCol.addView(trialText)
        val rcLP = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
        root.addView(rightCol, rcLP)

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
            textSize = 28f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setShadowLayer(6f, 3f, 3f, Color.BLACK)
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
                    Player.STATE_READY -> { setMode(MODE_NATIVE); hideLoading() }
                    Player.STATE_BUFFERING -> { /* 缓冲中，保持计时继续 */ }
                }
            }
            override fun onIsPlayingChanged(p: Boolean) {
                if (p) { hideLoading(); resumeBtn?.visibility = View.GONE }
            }
        })

        // 触屏/遥控 OK 弹面板
        playerView.setOnClickListener { if (!isPanelVisible()) showPanel() else hidePanel() }

        SourceResolver.init(this)
        // 播放期间保持屏幕常亮
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        checkLicense()
        if (savedInstanceState == null && channels.isNotEmpty()) {
            val defIdx = channels.indexOfFirst { it.name.contains("TVB翡翠台") }.let { if (it >= 0) it else 0 }
            playChannel(defIdx)
            // 启动后台预取默认频道所在分组，之后切台秒开
            val g = channels.getOrNull(defIdx)?.group
            val gi = ChannelData.groups().indexOfFirst { it.name == g }
            if (gi >= 0) handler.postDelayed({ prefetchGroup(gi) }, 2500)
        }
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
                if (r.ok) { trialText.visibility = View.GONE; trialText.text = "VIP" } else enterFreeMode()
            }
        }.start()
    }

    private fun enterFreeMode() {
        trialText.visibility = View.VISIBLE
        handler.removeCallbacks(uiTick)
        handler.post(uiTick)
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
        if (gpos >= 0) { curGroupPos = gpos; groupList?.setSelection(gpos + 1); updateChannelListOfGroup(gpos) }
        resetHideTimer()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildPanel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(0xE8141418u.toInt())
        setOnClickListener { hidePanel() }

        groupList = ListView(this@MainActivity).apply {
            setBackgroundColor(Color.TRANSPARENT)
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1,
                listOf("⚙ 设置") + panelGroups().map { "${it.name} (${it.count})" })
            onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
                if (pos == 0) {
                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                    hidePanel(); return@OnItemClickListener
                }
                curGroupPos = pos - 1
                groupList?.setSelection(pos)
                updateChannelListOfGroup(curGroupPos)
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
        prefetchGroup(pos)
        val list = channelsOfGroup(pos)
        channelList?.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1,
            list.map { "%3d  %s".format(it.num, it.name) })
    }

    private fun hidePanel() { handler.removeCallbacks(hidePanelTask); panelLayout?.visibility = View.GONE }

    /** 后台预取某分组所有频道的源，让切台变秒切（每次运行只做一轮） */
    private var prefetchedGroups = HashSet<Int>()
    private fun prefetchGroup(gpos: Int) {
        if (prefetchedGroups.contains(gpos)) return
        prefetchedGroups.add(gpos)
        val list = channelsOfGroup(gpos)
        Thread {
            for (ch in list) {
                try { SourceResolver.resolveLines(ch.tid, ch.id) } catch (e: Exception) { }
                try { Thread.sleep(250) } catch (ie: InterruptedException) { }
            }
        }.start()
    }
    private fun resetHideTimer() {
        handler.removeCallbacks(hidePanelTask)
        handler.postDelayed(hidePanelTask, 5000)
    }

    private fun hideLoading() { loadingMask.visibility = View.GONE; handler.removeCallbacks(loadTick) }

    /** 开始一次新的换台计时（只在切台时调用） */
    private fun beginLoading() {
        loadStart = System.currentTimeMillis()
        loadingText.text = "正在加载 1 秒，精彩继续…"
        loadingMask.visibility = View.VISIBLE
        handler.removeCallbacks(loadTick)
        handler.postDelayed(loadTick, 1000)
    }

    private fun playChannel(idx: Int, line: Int = 0) {
        if (idx >= channels.size) return
        val isSameChannel = (idx == currentChannel)
        currentChannel = idx; currentLine = line
        val ch = channels[idx]
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
            val vn = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
            statusText.text = "⚠ ${ch.num} ${ch.name} 全部线路失败\n$lastPlayError\n解析: ${SourceResolver.lastError}\n$failureReport\n版本: v$vn"
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
        // 「继续播放」按钮可见时，遥控 OK/确认 直接触发
        if (resumeBtn?.visibility == View.VISIBLE &&
            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
             keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_SPACE)) {
            resumePlayback(); return true
        }
        if (isPanelVisible()) resetHideTimer()
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) { onDigit(keyCode - KeyEvent.KEYCODE_0); return true }
        if (keyCode == KeyEvent.KEYCODE_NUMPAD_0) { onDigit(0); return true }
        if (keyCode in KeyEvent.KEYCODE_NUMPAD_1..KeyEvent.KEYCODE_NUMPAD_9) { onDigit(keyCode - KeyEvent.KEYCODE_NUMPAD_0); return true }
        when (keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                if (isPanelVisible()) {
                    val list = channelsOfGroup(curGroupPos)
                    val pos = channelList?.checkedItemPosition ?: -1
                    if (pos in 0 until list.size) {
                        toggleFav(list[pos])
                        updateChannelListOfGroup(curGroupPos)
                        Toast.makeText(this, if (favSet().contains(list[pos].num.toString())) "★ 已收藏 ${list[pos].name}" else "☆ 已取消 ${list[pos].name}", Toast.LENGTH_SHORT).show()
                    }
                    return true
                } else { autoSwitchLine(); return true }
            }
            KeyEvent.KEYCODE_CHANNEL_UP -> { playChannel((currentChannel - 1 + channels.size) % channels.size); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { playChannel((currentChannel + 1) % channels.size); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isPanelVisible()) {
                    curGroupPos = (curGroupPos - 1 + groups.size) % groups.size
                    groupList?.setSelection(curGroupPos + 1); updateChannelListOfGroup(curGroupPos)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isPanelVisible()) {
                    curGroupPos = (curGroupPos + 1) % groups.size
                    groupList?.setSelection(curGroupPos + 1); updateChannelListOfGroup(curGroupPos)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (isPanelVisible()) return super.onKeyDown(keyCode, event)
                playChannel((currentChannel + (if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1) + channels.size) % channels.size); return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (isPanelVisible()) {
                    val list = channelsOfGroup(curGroupPos)
                    val pos = channelList?.checkedItemPosition ?: -1
                    if (pos >= 0 && pos < list.size) { playChannel(channels.indexOf(list[pos])); hidePanel(); return true }
                }
                showPanel(); return true
            }
            KeyEvent.KEYCODE_BACK -> { if (isPanelVisible()) { hidePanel(); return true } }
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

    /** 退出前先弹窗确认 */
    private fun exitApp() {
        runOnUiThread {
            try {
                exitHint?.visibility = View.GONE
                android.app.AlertDialog.Builder(this)
                    .setTitle("退出应用")
                    .setMessage("确定要退出「港澳台直播」吗？")
                    .setPositiveButton("退出") { _, _ -> doExit() }
                    .setNegativeButton("取消", null)
                    .setCancelable(true)
                    .show()
            } catch (e: Exception) {
                doExit()   // 弹窗异常时直接退出，别卡住
            }
        }
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
                handler.removeCallbacks(uiTick)
            }
        }
        // 从桌面/设置返回：先尝试续播，失败则显示「继续播放」按钮
        if (wasStopped) {
            wasStopped = false
            try { player?.play() } catch (e: Exception) { }
            handler.postDelayed({
                // 直播流暂停后常常定格无法续播 → 给用户一个明确的按钮
                if (player?.isPlaying != true) resumeBtn?.visibility = View.VISIBLE
                else resumeBtn?.visibility = View.GONE
            }, 1800)
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
        try { player?.play() } catch (e: Exception) { }
        // 等 1.2 秒看是否已恢复，没恢复就重连
        handler.postDelayed({
            if (player?.isPlaying != true) playChannel(idx)
        }, 1200)
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
        super.onDestroy()
    }
}
