package com.alshami.crashalert.data

/** Monitoring settings, persisted in the SQLite `settings` table. */
data class MonitorSettings(
    val dropPercent: Double = 5.0,
    val windowSec: Int = 60,
    val cooldownMin: Int = 15,
    val pollSec: Int = 5,
    /** Empty set = all USDT spot pairs. */
    val symbols: Set<String> = Config.DEFAULT_SYMBOLS,
    val exchanges: Set<String> = setOf("binance", "bybit", "okx"),
)

object Config {
    val DEFAULT_SYMBOLS: Set<String> = linkedSetOf(
        "BTC", "ETH", "BNB", "SOL", "XRP", "DOGE", "ADA", "TRX", "AVAX", "LINK",
        "TON", "DOT", "LTC", "BCH", "SHIB", "NEAR", "APT", "SUI", "PEPE", "ARB"
    )

    private const val K_MONITOR = "monitor_enabled"
    private const val K_BOT = "bot_enabled"
    private const val K_DROP = "drop_percent"
    private const val K_WINDOW = "window_sec"
    private const val K_COOLDOWN = "cooldown_min"
    private const val K_POLL = "poll_sec"
    private const val K_SYMBOLS = "symbols"
    private const val K_EXCH = "exchanges"
    const val K_TG_OFFSET = "tg_offset"
    const val K_BOT_NAME = "bot_name"
    const val K_BOT_USER = "bot_username"

    fun monitorEnabled(db: Db) = db.getSetting(K_MONITOR, "0") == "1"
    fun setMonitorEnabled(db: Db, on: Boolean) = db.putSetting(K_MONITOR, if (on) "1" else "0")
    fun botEnabled(db: Db) = db.getSetting(K_BOT, "0") == "1"
    fun setBotEnabled(db: Db, on: Boolean) = db.putSetting(K_BOT, if (on) "1" else "0")

    fun load(db: Db): MonitorSettings {
        val d = MonitorSettings()
        val symRaw = db.getSetting(K_SYMBOLS, d.symbols.joinToString(","))
        return MonitorSettings(
            dropPercent = db.getSetting(K_DROP, d.dropPercent.toString()).toDoubleOrNull() ?: d.dropPercent,
            windowSec = db.getSetting(K_WINDOW, d.windowSec.toString()).toIntOrNull() ?: d.windowSec,
            cooldownMin = db.getSetting(K_COOLDOWN, d.cooldownMin.toString()).toIntOrNull() ?: d.cooldownMin,
            pollSec = db.getSetting(K_POLL, d.pollSec.toString()).toIntOrNull() ?: d.pollSec,
            symbols = parseSymbols(symRaw),
            exchanges = db.getSetting(K_EXCH, d.exchanges.joinToString(","))
                .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                .ifEmpty { d.exchanges },
        )
    }

    fun save(db: Db, s: MonitorSettings) {
        db.putSetting(K_DROP, s.dropPercent.toString())
        db.putSetting(K_WINDOW, s.windowSec.toString())
        db.putSetting(K_COOLDOWN, s.cooldownMin.toString())
        db.putSetting(K_POLL, s.pollSec.toString())
        db.putSetting(K_SYMBOLS, if (s.symbols.isEmpty()) "ALL" else s.symbols.joinToString(","))
        db.putSetting(K_EXCH, s.exchanges.joinToString(","))
    }

    /** "ALL" or empty → empty set (= every USDT pair). */
    fun parseSymbols(raw: String): Set<String> {
        val parts = raw.uppercase().split(Regex("[,\\s،]+")).map { it.trim().removeSuffix("USDT") }
            .filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.contains("ALL")) return emptySet()
        return LinkedHashSet(parts)
    }

    fun symbolsLabel(s: MonitorSettings) =
        if (s.symbols.isEmpty()) "كل أزواج USDT" else "${s.symbols.size} عملة"
}
