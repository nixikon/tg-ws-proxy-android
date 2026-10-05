package com.nixikon.tgwsproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Android counterpart of the desktop "Start on system boot" option.
 *
 * Some vendors block background starts from BOOT_COMPLETED; [ProxyService.start]
 * swallows that failure so a blocked autostart never crashes the app.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            -> {
                val cfg = ConfigStore.load(context)
                if (cfg.autostart) {
                    ProxyService.start(context)
                }
            }
        }
    }
}
