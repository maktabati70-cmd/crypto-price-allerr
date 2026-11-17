package com.alshami.crashalert.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.alshami.crashalert.Notifs
import com.alshami.crashalert.data.Config
import com.alshami.crashalert.data.Db
import com.alshami.crashalert.monitor.CrashEvent
import com.alshami.crashalert.monitor.PriceMonitor
import com.alshami.crashalert.security.TokenStore
import com.alshami.crashalert.telegram.Messages
import com.alshami.crashalert.telegram.TelegramBot
import com.alshami.crashalert.util.L

/**
 * Foreground service on the MAIN phone. Hosts both:
 *  - the price monitor (Binance / Bybit / OKX, crash = −X% within N seconds), and
 *  - the Telegram bot (single getUpdates consumer + broadcasts to all subscribers).
 * Keeps running with the screen off (partial wake lock + Wi-Fi lock), restarts after reboot.
 */
class MonitorService : Service() {

    companion object {
        const val ACTION_APPLY = "com.alshami.crashalert.APPLY"
        const val ACTION_TEST = "com.alshami.crashalert.TEST_BROADCAST"

        /** Start/refresh according to the toggles stored in SQLite. */
        fun apply(ctx: Context) {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, MonitorService::class.java).setAction(ACTION_APPLY)
            )
        }

        fun testBroadcast(ctx: Context) {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, MonitorService::class.java).setAction(ACTION_TEST)
            )
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, MonitorService::class.java))
        }
    }

    private var monitor: PriceMonitor? = null
    private var bot: TelegramBot? = null
    private var botToken: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val main = Handler(Looper.getMainLooper())

    private val notifTicker = object : Runnable {
        override fun run() {
            Notifs.updateService(this@MonitorService, summary())
            main.postDelayed(this, 30_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        applyConfig()
        if (intent?.action == ACTION_TEST) runTestBroadcast()
        return START_STICKY
    }

    private fun startInForeground() {
        val n = Notifs.service(this, summary())
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifs.ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifs.ID_SERVICE, n)
        }
    }

    @Synchronized
    private fun applyConfig() {
        val db = Db.get(this)
        val monOn = Config.monitorEnabled(db)
        val botOn = Config.botEnabled(db)

        if (!monOn && !botOn) {
            shutdown()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        acquireLocks()
        if (!ServiceState.serviceRunning) {
            ServiceState.serviceRunning = true
            ServiceState.startedAt = System.currentTimeMillis()
        }

        // ---- Telegram bot (exactly one instance) ----
        val token = if (botOn) TokenStore.load(this) else null
        if (botOn && token != null) {
            if (bot == null || token != botToken) {
                bot?.stop()
                bot = TelegramBot(this, token).also { it.start() }
                botToken = token
            }
        } else {
            bot?.stop(); bot = null; botToken = null
            ServiceState.botRunning = false
            ServiceState.botError = if (botOn) "لا يوجد Token محفوظ" else null
        }

        // ---- Price monitor ----
        if (monOn) {
            val m = monitor ?: PriceMonitor(::onCrash).also { monitor = it }
            m.start(Config.load(db)) // no-op if already running with the same settings
        } else {
            monitor?.stop(); monitor = null
        }

        main.removeCallbacks(notifTicker)
        main.post(notifTicker)
    }

    /** Called on a monitor thread. */
    private fun onCrash(e: CrashEvent) {
        val db = Db.get(this)
        val id = db.insertAlert(e.base, e.exchangeName, e.dropPct, e.fromPrice, e.toPrice, e.elapsedSec, e.atMs)
        ServiceState.lastAlertSummary =
            "${e.base} -${"%.2f".format(e.dropPct)}% (${e.exchangeName}) ${com.alshami.crashalert.util.Fmt.time(e.atMs)}"
        L.i("CRASH ${e.base} on ${e.exchangeName}")
        Notifs.crash(this, e)

        val b = bot
        if (b != null) {
            b.broadcast(Messages.crash(e)) { sent, failed ->
                db.updateAlertDelivery(id, sent, failed)
                ServiceState.lastBroadcastResult = "تنبيه ${e.base}: أُرسل إلى $sent، فشل $failed"
            }
        } else {
            ServiceState.lastBroadcastResult = "تنبيه ${e.base}: لم يُرسل إلى Telegram (البوت متوقف)"
        }
    }

    private fun runTestBroadcast() {
        val b = bot
        if (b == null) {
            ServiceState.lastBroadcastResult = "رسالة تجريبية: البوت غير مشغّل"
            return
        }
        ServiceState.lastBroadcastResult = "رسالة تجريبية: جارٍ الإرسال…"
        b.broadcast(Messages.test()) { sent, failed ->
            ServiceState.lastBroadcastResult = "رسالة تجريبية: أُرسلت إلى $sent، فشل $failed"
        }
    }

    private fun summary(): String {
        val db = Db.get(this)
        val mon = when {
            !ServiceState.monitorRunning -> "متوقفة"
            ServiceState.pricesFresh(Config.load(db).pollSec) -> "تعمل"
            else -> "لا تصل أسعار!"
        }
        val tg = if (ServiceState.botRunning) "يعمل" else "متوقف"
        return "المراقبة: $mon • البوت: $tg • المشتركون: ${db.activeCount()}"
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wakeLock?.isHeld != true) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CrashAlert::Monitor").apply {
                setReferenceCounted(false); acquire()
            }
        }
        if (wifiLock?.isHeld != true) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "CrashAlert::Wifi").apply {
                setReferenceCounted(false); acquire()
            }
        }
    }

    private fun shutdown() {
        main.removeCallbacks(notifTicker)
        bot?.stop(); bot = null; botToken = null
        monitor?.stop(); monitor = null
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
        wakeLock = null; wifiLock = null
        ServiceState.serviceRunning = false
        ServiceState.monitorRunning = false
        ServiceState.botRunning = false
        ServiceState.startedAt = 0L
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }
}
