package com.alshami.crashalert.service

import java.util.concurrent.ConcurrentHashMap

/**
 * In-process live state of the main phone's service. It reflects what is ACTUALLY
 * running right now — nothing here is set optimistically.
 */
object ServiceState {
    @Volatile var serviceRunning = false
    @Volatile var startedAt = 0L

    @Volatile var monitorRunning = false
    @Volatile var botRunning = false
    @Volatile var botName: String? = null
    @Volatile var botUsername: String? = null
    @Volatile var botError: String? = null
    @Volatile var botLastPoll = 0L

    @Volatile var lastAlertSummary: String? = null
    @Volatile var lastBroadcastResult: String? = null

    class SourceStatus {
        @Volatile var lastOk = 0L
        @Volatile var symbols = 0
        @Volatile var lastError: String? = null
        @Volatile var lastErrorAt = 0L
    }

    val sources = ConcurrentHashMap<String, SourceStatus>()

    fun sourceOk(id: String, t: Long, n: Int) {
        val s = sources.getOrPut(id) { SourceStatus() }
        s.lastOk = t; s.symbols = n; s.lastError = null
    }

    fun sourceError(id: String, msg: String) {
        val s = sources.getOrPut(id) { SourceStatus() }
        s.lastError = msg; s.lastErrorAt = System.currentTimeMillis()
    }

    fun lastPriceUpdate(): Long = sources.values.maxOfOrNull { it.lastOk } ?: 0L

    /** Prices are considered flowing if any source answered within max(3×poll, 30 s). */
    fun pricesFresh(pollSec: Int): Boolean {
        val last = lastPriceUpdate()
        return monitorRunning && last > 0 &&
            System.currentTimeMillis() - last < maxOf(pollSec * 3_000L, 30_000L)
    }
}
