package com.alshami.crashalert.util

import android.util.Log
import java.math.BigDecimal
import java.math.MathContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Removes any Telegram bot token from text before it is logged or shown. */
object Redact {
    private val botUrl = Regex("bot\\d+:[A-Za-z0-9_-]+")
    private val bare = Regex("\\d{5,}:[A-Za-z0-9_-]{25,}")
    fun s(msg: String?): String =
        (msg ?: "").replace(botUrl, "bot<hidden>").replace(bare, "<hidden>")
}

/** Logger that always redacts and never prints stack traces (they may contain URLs). */
object L {
    private const val TAG = "CrashAlert"
    fun i(m: String) { Log.i(TAG, Redact.s(m)) }
    fun w(m: String, t: Throwable? = null) {
        val extra = t?.let { ": " + (it.message ?: it.javaClass.simpleName) } ?: ""
        Log.w(TAG, Redact.s(m + extra))
    }
}

object Fmt {
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun time(ms: Long): String = synchronized(timeFmt) { timeFmt.format(Date(ms)) }

    fun ago(ms: Long): String {
        if (ms <= 0L) return "لم يحدث بعد"
        val s = (System.currentTimeMillis() - ms) / 1000
        return when {
            s < 60 -> "منذ $s ث"
            s < 3600 -> "منذ ${s / 60} د"
            else -> "منذ ${s / 3600} س"
        }
    }

    fun duration(ms: Long): String {
        val s = ms / 1000
        val d = s / 86400; val h = (s % 86400) / 3600; val m = (s % 3600) / 60
        return when {
            d > 0 -> "${d} يوم ${h} س"
            h > 0 -> "${h} س ${m} د"
            else -> "${m} د ${s % 60} ث"
        }
    }

    fun price(p: Double): String = when {
        p >= 1000 -> String.format(Locale.US, "%,.2f", p)
        p >= 1 -> String.format(Locale.US, "%.4f", p)
        else -> BigDecimal(p).round(MathContext(6)).stripTrailingZeros().toPlainString()
    }

    fun html(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
