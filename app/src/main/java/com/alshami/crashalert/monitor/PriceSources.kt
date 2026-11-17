package com.alshami.crashalert.monitor

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** A public, key-less spot price feed. Returns base symbol → last price for USDT pairs. */
interface PriceSource {
    val id: String
    val name: String
    fun fetch(client: OkHttpClient): Map<String, Double>
}

private fun httpGet(client: OkHttpClient, url: String): String {
    val req = Request.Builder().url(url).header("User-Agent", "CryptoCrashAlert/2.0").build()
    client.newCall(req).execute().use { r ->
        if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
        return r.body?.string() ?: throw IOException("empty body")
    }
}

private fun putUsdt(out: HashMap<String, Double>, base: String, priceStr: String) {
    if (base.isEmpty()) return
    val p = priceStr.toDoubleOrNull() ?: return
    if (p > 0) out[base] = p
}

object BinanceSource : PriceSource {
    override val id = "binance"
    override val name = "Binance"
    // data-api.binance.vision is Binance's public market-data mirror, used if the main host is blocked.
    private val urls = listOf(
        "https://api.binance.com/api/v3/ticker/price",
        "https://data-api.binance.vision/api/v3/ticker/price",
    )

    override fun fetch(client: OkHttpClient): Map<String, Double> {
        var last: Exception? = null
        for (u in urls) {
            try {
                val arr = JSONArray(httpGet(client, u))
                val out = HashMap<String, Double>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val sym = o.optString("symbol")
                    if (sym.endsWith("USDT")) putUsdt(out, sym.removeSuffix("USDT"), o.optString("price"))
                }
                return out
            } catch (e: Exception) {
                last = e
            }
        }
        throw IOException(last?.message ?: "Binance unavailable")
    }
}

object BybitSource : PriceSource {
    override val id = "bybit"
    override val name = "Bybit"
    override fun fetch(client: OkHttpClient): Map<String, Double> {
        val j = JSONObject(httpGet(client, "https://api.bybit.com/v5/market/tickers?category=spot"))
        if (j.optInt("retCode", -1) != 0) throw IOException("Bybit: ${j.optString("retMsg")}")
        val list = j.getJSONObject("result").getJSONArray("list")
        val out = HashMap<String, Double>(list.length())
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            val sym = o.optString("symbol")
            if (sym.endsWith("USDT")) putUsdt(out, sym.removeSuffix("USDT"), o.optString("lastPrice"))
        }
        return out
    }
}

object OkxSource : PriceSource {
    override val id = "okx"
    override val name = "OKX"
    override fun fetch(client: OkHttpClient): Map<String, Double> {
        val j = JSONObject(httpGet(client, "https://www.okx.com/api/v5/market/tickers?instType=SPOT"))
        if (j.optString("code") != "0") throw IOException("OKX: ${j.optString("msg")}")
        val data = j.getJSONArray("data")
        val out = HashMap<String, Double>(data.length())
        for (i in 0 until data.length()) {
            val o = data.getJSONObject(i)
            val inst = o.optString("instId")
            if (inst.endsWith("-USDT")) putUsdt(out, inst.removeSuffix("-USDT"), o.optString("last"))
        }
        return out
    }
}

object Sources {
    val all: List<PriceSource> = listOf(BinanceSource, BybitSource, OkxSource)
    fun name(id: String) = all.firstOrNull { it.id == id }?.name ?: id
}
