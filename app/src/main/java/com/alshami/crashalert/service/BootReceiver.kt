package com.alshami.crashalert.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.alshami.crashalert.data.Config
import com.alshami.crashalert.data.Db
import com.alshami.crashalert.util.L

/** Restarts the main-phone service after a reboot or app update, if it was enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val ok = intent.action in setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
        )
        if (!ok) return
        val db = Db.get(ctx)
        if (Config.monitorEnabled(db) || Config.botEnabled(db)) {
            try {
                MonitorService.apply(ctx)
            } catch (e: Exception) {
                L.w("Could not restart service after boot", e)
            }
        }
    }
}
