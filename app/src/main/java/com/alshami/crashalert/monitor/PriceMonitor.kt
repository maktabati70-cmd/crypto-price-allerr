package com.alshami.crashalert.monitor

import com.alshami.crashalert.data.MonitorSettings
import com.alshami.crashalert.service.ServiceState
import com.alshami.crashalert.util.L
import com.alshami.crashalert.util.Redact
import okhttp3.OkHttpClient
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Polls Binance, Bybit and OKX in parallel and feeds the crash detector. */
class PriceMonitor(private val onCrash: (CrashEvent) -> Unit) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val detector = CrashDetector()
    private var exec: ScheduledExecutorService? = null
    @Volatile private var settings: MonitorSettings? = null

    @Synchronized
    fun start(s: MonitorSettings) {
        if (exec != null && s == settings) return  // already running with same settings
        stopInternal()
        settings = s
        detector.reset()
        ServiceState.sources.clear()
        val active = Sources.all.filter { it.id in s.exchanges }
        val e = Executors.newScheduledThreadPool(active.size.coerceAtLeast(1))
        active.forEach { src ->
            e.scheduleWithFixedDelay({ poll(src) }, 0, s.pollSec.toLong(), TimeUnit.SECONDS)
        }
        exec = e
        ServiceState.monitorRunning = true
        L.i("Price monitor started: ${active.joinToString { it.name }}")
    }

    @Synchronized
    fun stop() {
        stopInternal()
        settings = null
    }

    private fun stopInternal() {
        exec?.shutdownNow()
        exec = null
        ServiceState.monitorRunning = false
    }

    private fun poll(src: PriceSource) {
        val s = settings ?: return
        try {
            val prices = src.fetch(client)
            val now = System.currentTimeMillis()
            val watched = if (s.symbols.isEmpty()) prices else prices.filterKeys { it in s.symbols }
            for ((base, p) in watched) {
                val ev = detector.sample(src.id, src.name, base, p, now, s) ?: continue
                try { onCrash(ev) } catch (t: Throwable) { L.w("onCrash failed", t) }
            }
            ServiceState.sourceOk(src.id, now, watched.size)
        } catch (t: Throwable) {
            ServiceState.sourceError(src.id, Redact.s(t.message ?: t.javaClass.simpleName))
        }
    }
}
