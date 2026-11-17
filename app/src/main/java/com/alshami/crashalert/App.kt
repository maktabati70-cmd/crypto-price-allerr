package com.alshami.crashalert

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.alshami.crashalert.monitor.CrashEvent
import com.alshami.crashalert.ui.MainActivity
import java.util.Locale

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifs.createChannels(this)
    }
}

object Notifs {
    const val CH_SERVICE = "service"
    const val CH_ALERTS = "alerts"
    const val ID_SERVICE = 1001

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "خدمة المراقبة (الهاتف الرئيسي)", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "تنبيهات الهبوط", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
            }
        )
    }

    private fun openApp(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun service(ctx: Context, text: String): Notification =
        NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle("Crypto Crash Alert — الهاتف الرئيسي")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    private fun canNotify(ctx: Context) =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun updateService(ctx: Context, text: String) {
        if (!canNotify(ctx)) return
        try { NotificationManagerCompat.from(ctx).notify(ID_SERVICE, service(ctx, text)) } catch (_: SecurityException) {}
    }

    /** Local alert on the main phone itself (in addition to Telegram). */
    fun crash(ctx: Context, e: CrashEvent) {
        if (!canNotify(ctx)) return
        val text = String.format(Locale.US, "%s/USDT -%.2f%% خلال %d ث على %s", e.base, e.dropPct, e.elapsedSec, e.exchangeName)
        val n = NotificationCompat.Builder(ctx, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle("🚨 هبوط حاد: ${e.base}")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(2000 + (e.base.hashCode() and 0xFFFF), n)
        } catch (_: SecurityException) {}
    }
}
