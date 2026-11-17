package com.alshami.crashalert.monitor

import com.alshami.crashalert.data.MonitorSettings

data class CrashEvent(
    val base: String,
    val exchangeId: String,
    val exchangeName: String,
    val fromPrice: Double,
    val toPrice: Double,
    val dropPct: Double,
    /** Seconds between the window high and the current price. */
    val elapsedSec: Int,
    val windowSec: Int,
    val atMs: Long,
)

/**
 * Rolling-window crash detection, per exchange + symbol:
 * drop = (highest price seen in the last `windowSec` − current) / highest × 100.
 * Fires when drop ≥ dropPercent. One alert per coin per cooldown period
 * (across all exchanges, so the same crash isn't sent three times).
 */
class CrashDetector {
    private class Sample(val t: Long, val p: Double)

    private val series = HashMap<String, ArrayDeque<Sample>>()
    private val lastAlert = HashMap<String, Long>()

    @Synchronized
    fun reset() {
        series.clear()
    }

    @Synchronized
    fun sample(exId: String, exName: String, base: String, price: Double, now: Long, s: MonitorSettings): CrashEvent? {
        val key = "$exId:$base"
        val dq = series.getOrPut(key) { ArrayDeque() }
        val windowMs = s.windowSec * 1000L
        while (dq.isNotEmpty() && now - dq.first().t > windowMs) dq.removeFirst()

        var high: Sample? = null
        for (x in dq) if (high == null || x.p > high.p) high = x
        dq.addLast(Sample(now, price))

        val h = high ?: return null
        if (h.p <= 0.0) return null
        val drop = (h.p - price) / h.p * 100.0
        if (drop < s.dropPercent) return null

        val last = lastAlert[base]
        if (last != null && now - last < s.cooldownMin * 60_000L) return null
        lastAlert[base] = now

        // Re-baseline after an alert so a continuing slide is measured from here.
        dq.clear()
        dq.addLast(Sample(now, price))

        return CrashEvent(
            base = base, exchangeId = exId, exchangeName = exName,
            fromPrice = h.p, toPrice = price, dropPct = drop,
            elapsedSec = ((now - h.t) / 1000).toInt(), windowSec = s.windowSec, atMs = now,
        )
    }
}
