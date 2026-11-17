package com.alshami.crashalert.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.alshami.crashalert.data.Config
import com.alshami.crashalert.data.Db
import com.alshami.crashalert.data.MonitorSettings
import com.alshami.crashalert.databinding.ActivityMainBinding
import com.alshami.crashalert.monitor.Sources
import com.alshami.crashalert.security.TokenStore
import com.alshami.crashalert.service.MonitorService
import com.alshami.crashalert.service.ServiceState
import com.alshami.crashalert.telegram.TelegramApi
import com.alshami.crashalert.util.Fmt
import com.alshami.crashalert.util.Redact
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var db: Db
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var updatingSwitches = false

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        db = Db.get(this)

        loadSettingsIntoForm()
        wireTelegram()
        wireMonitoring()
        wireReliability()
        askNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        ui.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    /** Start the service if anything is enabled, otherwise stop it. */
    private fun applyService() {
        if (Config.monitorEnabled(db) || Config.botEnabled(db)) MonitorService.apply(this)
        else MonitorService.stop(this)
    }

    // ---------------- Telegram ----------------

    private fun wireTelegram() {
        b.btnSaveToken.setOnClickListener {
            val t = b.edtToken.text?.toString()?.trim().orEmpty()
            if (!TokenStore.isValidFormat(t)) {
                toast("صيغة التوكن غير صحيحة. انسخه كما هو من BotFather.")
                return@setOnClickListener
            }
            io.execute {
                val err = try { TokenStore.save(this, t); null } catch (e: Exception) { Redact.s(e.message) }
                runOnUiThread {
                    if (err != null) toast("فشل الحفظ: $err")
                    else {
                        b.edtToken.setText("")
                        toast("تم حفظ التوكن مشفّراً على هذا الهاتف")
                        if (Config.botEnabled(db)) MonitorService.apply(this) // restart bot with new token
                        testConnection(null)
                    }
                }
            }
        }

        b.btnTestBot.setOnClickListener {
            val typed = b.edtToken.text?.toString()?.trim().orEmpty()
            if (typed.isNotEmpty() && !TokenStore.isValidFormat(typed)) {
                toast("صيغة التوكن غير صحيحة.")
                return@setOnClickListener
            }
            testConnection(typed.ifEmpty { null })
        }

        b.btnClearToken.setOnClickListener {
            TokenStore.clear(this)
            Config.setBotEnabled(db, false)
            db.putSetting(Config.K_BOT_NAME, "")
            db.putSetting(Config.K_BOT_USER, "")
            applyService()
            toast("تم حذف التوكن وإيقاف البوت")
            refresh()
        }

        b.swBot.setOnCheckedChangeListener { _, on ->
            if (updatingSwitches) return@setOnCheckedChangeListener
            if (on && !TokenStore.has(this)) {
                toast("أدخل Bot Token واحفظه أولاً")
                setSwitch(b.swBot, false)
                return@setOnCheckedChangeListener
            }
            Config.setBotEnabled(db, on)
            applyService()
        }

        b.btnTestBroadcast.setOnClickListener {
            if (!ServiceState.botRunning) {
                toast("البوت غير متصل حالياً")
                return@setOnClickListener
            }
            MonitorService.testBroadcast(this)
        }
    }

    /** getMe only — never calls getUpdates, so it never competes with the running bot. */
    private fun testConnection(typed: String?) {
        b.txtBotName.text = "جارٍ الاختبار…"
        io.execute {
            val token = typed ?: TokenStore.load(this)
            val text = if (token == null) {
                "لا يوجد Token محفوظ"
            } else try {
                val me = TelegramApi(token).getMe()
                if (typed == null) {
                    db.putSetting(Config.K_BOT_NAME, me.firstName)
                    db.putSetting(Config.K_BOT_USER, me.username)
                }
                "✅ متصل: ${me.firstName} (@${me.username})"
            } catch (e: TelegramApi.ApiException) {
                if (e.code == 401 || e.code == 404) "❌ التوكن غير صالح" else "❌ خطأ ${e.code}: ${Redact.s(e.message)}"
            } catch (e: Exception) {
                "❌ تعذّر الاتصال بـ Telegram: ${Redact.s(e.message)}"
            }
            runOnUiThread { b.txtBotName.text = text }
        }
    }

    // ---------------- Monitoring ----------------

    private fun loadSettingsIntoForm() {
        val s = Config.load(db)
        b.edtDrop.setText(trimNum(s.dropPercent))
        b.edtWindow.setText(s.windowSec.toString())
        b.edtCooldown.setText(s.cooldownMin.toString())
        b.edtPoll.setText(s.pollSec.toString())
        b.edtSymbols.setText(if (s.symbols.isEmpty()) "ALL" else s.symbols.joinToString(", "))
        b.chkBinance.isChecked = "binance" in s.exchanges
        b.chkBybit.isChecked = "bybit" in s.exchanges
        b.chkOkx.isChecked = "okx" in s.exchanges
    }

    private fun trimNum(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()

    private fun wireMonitoring() {
        b.swMonitor.setOnCheckedChangeListener { _, on ->
            if (updatingSwitches) return@setOnCheckedChangeListener
            Config.setMonitorEnabled(db, on)
            applyService()
        }

        b.btnSaveSettings.setOnClickListener {
            val drop = b.edtDrop.text?.toString()?.toDoubleOrNull()
            val window = b.edtWindow.text?.toString()?.toIntOrNull()
            val cooldown = b.edtCooldown.text?.toString()?.toIntOrNull()
            val poll = b.edtPoll.text?.toString()?.toIntOrNull()
            val ex = buildSet {
                if (b.chkBinance.isChecked) add("binance")
                if (b.chkBybit.isChecked) add("bybit")
                if (b.chkOkx.isChecked) add("okx")
            }
            when {
                drop == null || drop < 0.5 || drop > 50 -> toast("نسبة الهبوط بين 0.5 و 50")
                window == null || window < 10 || window > 3600 -> toast("المدة بين 10 و 3600 ثانية")
                cooldown == null || cooldown < 1 || cooldown > 1440 -> toast("التهدئة بين 1 و 1440 دقيقة")
                poll == null || poll < 2 || poll > 60 -> toast("التحديث بين 2 و 60 ثانية")
                poll * 2 > window -> toast("يجب أن يكون التحديث أسرع بكثير من مدة النافذة (أقل من نصفها)")
                ex.isEmpty() -> toast("اختر منصة واحدة على الأقل")
                else -> {
                    val s = MonitorSettings(
                        dropPercent = drop, windowSec = window, cooldownMin = cooldown, pollSec = poll,
                        symbols = Config.parseSymbols(b.edtSymbols.text?.toString().orEmpty()),
                        exchanges = ex,
                    )
                    Config.save(db, s)
                    loadSettingsIntoForm()
                    if (Config.monitorEnabled(db)) MonitorService.apply(this)
                    toast("تم حفظ الإعدادات")
                }
            }
        }
    }

    // ---------------- Reliability ----------------

    @SuppressLint("BatteryLife")
    private fun wireReliability() {
        b.btnBattery.setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName"))
                )
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        b.btnNotifPerm.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
            } else {
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                )
            }
        }
    }

    private fun askNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && !notifGranted()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
    }

    private fun notifGranted() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun batteryExempt(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    // ---------------- Live status ----------------

    private fun setSwitch(sw: com.google.android.material.materialswitch.MaterialSwitch, v: Boolean) {
        updatingSwitches = true
        sw.isChecked = v
        updatingSwitches = false
    }

    private fun refresh() {
        val s = Config.load(db)
        setSwitch(b.swBot, Config.botEnabled(db))
        setSwitch(b.swMonitor, Config.monitorEnabled(db))

        // Overall state — only what is really running.
        val fresh = ServiceState.pricesFresh(s.pollSec)
        val overall = when {
            !ServiceState.serviceRunning -> "⛔ الخدمة متوقفة — لا مراقبة ولا تنبيهات"
            ServiceState.monitorRunning && fresh && ServiceState.botRunning ->
                "✅ النظام يعمل: المراقبة والبوت فعّالان (منذ ${Fmt.duration(System.currentTimeMillis() - ServiceState.startedAt)})"
            ServiceState.monitorRunning && !fresh -> "⚠️ المراقبة مشغّلة لكن لا تصل أسعار — تحقق من الإنترنت"
            !ServiceState.monitorRunning -> "⚠️ الخدمة تعمل لكن مراقبة العملات متوقفة — لن تصدر تنبيهات"
            else -> "⚠️ المراقبة تعمل لكن البوت غير متصل — التنبيهات لن تصل للمشتركين"
        }
        b.txtServiceState.text = overall

        // Telegram
        val hasToken = TokenStore.has(this)
        b.txtTokenState.text = if (hasToken) "🔒 التوكن محفوظ ومشفّر على هذا الهاتف (لا يُعرض ولا يُسجَّل)" else "لا يوجد توكن محفوظ"
        val shown = b.txtBotName.text?.toString().orEmpty()
        if (shown.isEmpty() || shown.startsWith("اسم البوت")) {
            val name = ServiceState.botName ?: db.getSetting(Config.K_BOT_NAME, "")
            val user = ServiceState.botUsername ?: db.getSetting(Config.K_BOT_USER, "")
            b.txtBotName.text = if (name.isNotEmpty()) "اسم البوت: $name (@$user)" else "اسم البوت: —"
        }
        b.txtSubscribers.text = "المشتركون الفعّالون: ${db.activeCount()}  (الإجمالي المسجّل: ${db.totalCount()})"
        b.txtBotState.text = when {
            !Config.botEnabled(db) -> "البوت متوقف"
            ServiceState.botRunning -> "متصل ويستقبل الأوامر • آخر استقبال ${Fmt.ago(ServiceState.botLastPoll)}"
            else -> "غير متصل: ${ServiceState.botError ?: "جارٍ الاتصال…"}"
        }
        b.txtBroadcast.text = ServiceState.lastBroadcastResult ?: ""

        // Sources
        b.txtSources.text = if (!ServiceState.monitorRunning) "المراقبة متوقفة" else buildString {
            append("الشرط: هبوط ≥ ${trimNum(s.dropPercent)}% خلال ${s.windowSec} ث • ${Config.symbolsLabel(s)}\n")
            Sources.all.filter { it.id in s.exchanges }.forEach { src ->
                val st = ServiceState.sources[src.id]
                append("• ${src.name}: ")
                when {
                    st == null -> append("جارٍ الاتصال…")
                    st.lastError != null -> append("❌ ${st.lastError} (آخر نجاح ${Fmt.ago(st.lastOk)})")
                    else -> append("✅ ${st.symbols} عملة • ${Fmt.ago(st.lastOk)}")
                }
                append('\n')
            }
        }.trimEnd()

        // Reliability
        b.txtReliability.text = buildString {
            append(if (batteryExempt()) "✅ مستثنى من تحسين البطارية\n" else "⚠️ غير مستثنى من تحسين البطارية — قد يوقف النظام الخدمة\n")
            append(if (notifGranted()) "✅ الإشعارات مسموحة" else "⚠️ الإشعارات غير مسموحة")
        }

        // Alerts
        val rows = db.recentAlerts(20)
        b.txtAlerts.text = if (rows.isEmpty()) "لا توجد تنبيهات بعد" else rows.joinToString("\n") { a ->
            String.format(
                Locale.US, "%s  %s  -%.2f%%  %s ➜ %s  (%s) • أُرسل %d / فشل %d",
                Fmt.time(a.at), a.symbol, a.dropPct, Fmt.price(a.from), Fmt.price(a.to), a.exchange, a.sent, a.failed
            )
        }
    }
}
