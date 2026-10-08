package com.iptv807.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机自动启动（用户在设置里打开开关才启用） */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val i = Intent(context, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        }
    }
}
