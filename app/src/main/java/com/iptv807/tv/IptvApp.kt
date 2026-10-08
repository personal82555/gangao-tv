package com.iptv807.tv

import android.app.Application

/** 应用级 crash 防护：把异常写标记后照常崩溃（不吞什），下次启动 reset license 缓存 */
class IptvApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                getSharedPreferences("iptv_crash", MODE_PRIVATE)
                    .edit().putBoolean("crashed", true)
                    .putString("msg", "${e.javaClass.simpleName}: ${e.message?.take(200)}").commit()
            } catch (ex: Exception) { }
            default?.uncaughtException(t, e)
        }
    }
}
