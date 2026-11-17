package com.alshami.crashalert.telegram

import com.alshami.crashalert.data.Config
import com.alshami.crashalert.data.Db
import com.alshami.crashalert.data.MonitorSettings
import com.alshami.crashalert.monitor.CrashEvent
import com.alshami.crashalert.monitor.Sources
import com.alshami.crashalert.service.ServiceState
import com.alshami.crashalert.util.Fmt
import java.util.Locale

/** All texts the bot sends. Honest about the single-main-phone dependency. */
object Messages {

    const val SHORT_DESCRIPTION =
        "تنبيهات هبوط العملات الرقمية. تصدر من هاتف رئيسي واحد؛ إن لم يرد البوت على /status فالخدمة متوقفة حالياً."

    private const val DEPENDENCY_NOTE =
        "ℹ️ <b>مهم:</b> جميع التنبيهات تصدر من <b>هاتف رئيسي واحد</b>. " +
            "إذا كان هذا الهاتف مغلقاً أو بلا إنترنت أو توقفت الخدمة عليه، فلن تصل أي تنبيهات " +
            "ولن يرد البوت على الأوامر. أرسل /status للتحقق — إن لم يصلك رد فالخدمة متوقفة الآن."

    private fun pct(d: Double) = String.format(Locale.US, "%.2f", d)
    private fun num(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else pct(d)

    fun welcome(newly: Boolean, s: MonitorSettings): String {
        val head = if (newly) "✅ <b>تم تفعيل اشتراكك</b> في تنبيهات Crypto Crash Alert."
        else "✅ أنت مشترك بالفعل في تنبيهات Crypto Crash Alert."
        return "$head\n\n" +
            "ستصلك رسالة عند هبوط أي عملة مراقَبة بنسبة <b>${num(s.dropPercent)}%</b> أو أكثر " +
            "خلال <b>${s.windowSec} ثانية</b> على Binance أو Bybit أو OKX " +
            "(${Config.symbolsLabel(s)}).\n\n" +
            "لا تحتاج إلى أي Token أو مفاتيح API.\n\n" +
            "$DEPENDENCY_NOTE\n\n" +
            "الأوامر: /status حالة الخدمة • /stop إلغاء الاشتراك"
    }

    fun stopped(changed: Boolean) =
        if (changed) "🛑 تم إلغاء اشتراكك. لن تصلك تنبيهات بعد الآن.\nللعودة أرسل /start"
        else "أنت غير مشترك حالياً. للاشتراك أرسل /start"

    fun help() =
        "أهلاً 👋\n/start الاشتراك في تنبيهات الهبوط\n/stop إلغاء الاشتراك\n/status حالة الخدمة"

    fun status(db: Db, subscribed: Boolean): String {
        val s = Config.load(db)
        val sb = StringBuilder("📊 <b>حالة الخدمة</b>\n\n")
        sb.append("• الهاتف الرئيسي: متصل الآن (هذا الرد صادر منه)\n")

        val monitoringOn = ServiceState.monitorRunning
        val fresh = ServiceState.pricesFresh(s.pollSec)
        sb.append("• مراقبة الأسعار: ")
        sb.append(
            when {
                !monitoringOn -> "⛔ متوقفة — لن تصدر تنبيهات"
                fresh -> "✅ تعمل"
                else -> "⚠️ قيد التشغيل لكن لا تصل أسعار حالياً — التنبيهات لن تعمل حتى يعود الاتصال"
            }
        ).append('\n')

        if (monitoringOn) {
            sb.append("• آخر تحديث للأسعار: ${Fmt.ago(ServiceState.lastPriceUpdate())}\n")
            val parts = Sources.all.filter { it.id in s.exchanges }.map { src ->
                val st = ServiceState.sources[src.id]
                val ok = st != null && st.lastOk > 0 &&
                    System.currentTimeMillis() - st.lastOk < maxOf(s.pollSec * 3_000L, 30_000L)
                "${src.name} ${if (ok) "✓" else "✗"}"
            }
            sb.append("• المصادر: ${parts.joinToString(" | ")}\n")
        }
        sb.append("• شرط التنبيه: هبوط ≥ ${num(s.dropPercent)}% خلال ${s.windowSec} ث\n")
        sb.append("• العملات: ${Config.symbolsLabel(s)}\n")
        sb.append("• عدد المشتركين: ${db.activeCount()}\n")
        sb.append("• اشتراكك: ${if (subscribed) "فعّال ✅" else "غير مشترك — أرسل /start"}\n")
        if (ServiceState.startedAt > 0) {
            sb.append("• مدة التشغيل: ${Fmt.duration(System.currentTimeMillis() - ServiceState.startedAt)}\n")
        }
        sb.append("\nإذا توقف الهاتف الرئيسي تتوقف المراقبة والتنبيهات فوراً.")
        return sb.toString()
    }

    fun crash(e: CrashEvent): String =
        "🚨 <b>تنبيه هبوط حاد</b>\n\n" +
            "🪙 العملة: <b>${Fmt.html(e.base)}/USDT</b>\n" +
            "🏦 المنصة: ${Fmt.html(e.exchangeName)}\n" +
            "📉 الهبوط: <b>-${pct(e.dropPct)}%</b> خلال ${e.elapsedSec.coerceAtLeast(1)} ثانية\n" +
            "💵 من ${Fmt.price(e.fromPrice)} ➜ إلى ${Fmt.price(e.toPrice)}\n" +
            "🕒 ${Fmt.time(e.atMs)}\n\n" +
            "<i>للمعلومة فقط وليست نصيحة استثمارية.</i>"

    fun test(): String =
        "🧪 رسالة تجريبية من Crypto Crash Alert.\nالبوت يعمل على الهاتف الرئيسي ويصل إلى المشتركين.\n🕒 " +
            Fmt.time(System.currentTimeMillis())
}
