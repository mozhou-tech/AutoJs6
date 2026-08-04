package org.autojs.autojs.mcp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PhoneMcpBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_USER_UNLOCKED, Intent.ACTION_MY_PACKAGE_REPLACED) &&
            PhoneMcpPreferences.isEnabled(context)
        ) {
            PhoneMcpService.start(context)
        }
    }
}
