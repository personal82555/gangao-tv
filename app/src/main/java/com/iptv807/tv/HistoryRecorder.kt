package com.iptv807.tv

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 收视历史（供 AI 推荐/报告用） */
object HistoryRecorder {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("iptv_history", Context.MODE_PRIVATE)
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

    fun log(ctx: Context, chanNum: Int, name: String) {
        val now = fmt.format(Date())
        val p = prefs(ctx)
        var count = p.getInt("count_${chanNum}", 0)
        count++
        p.edit().putInt("count_${chanNum}", count)
            .putString("last_" + chanNum, "$now $name").apply()
        // 只保留最近 90 days（清理老数据）
        if (p.all.size > 200) {
            val sorted = p.all.keys.sorted()
            p.edit().remove(sorted.take(50).toString()).apply()
        }
    }

    fun topChannels(ctx: Context, n: Int = 5): List<Pair<Int, String>> {
        val p = prefs(ctx)
        return p.all.entries
            .filter { it.key.startsWith("count_") }
            .sortedByDescending { it.value as Int }
            .take(n)
            .map { it.key.removePrefix("count_").toInt() }
            .mapNotNull { num -> ChannelData.load().find { it.num == num }?.let { num to it.name } }
    }
}
