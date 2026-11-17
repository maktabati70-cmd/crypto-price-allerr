package com.alshami.crashalert.telegram

import com.alshami.crashalert.util.Redact
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Minimal Telegram Bot API client. The token only appears inside the request URL;
 * every error message passes through Redact, and no HTTP logging interceptor is used.
 */
class TelegramApi(private val token: String) {

    class ApiException(val code: Int, msg: String, val retryAfter: Int = 0) : IOException(msg)

    data class BotInfo(val id: Long, val firstName: String, val username: String)

    sealed class SendResult {
        object Ok : SendResult()
        object Blocked : SendResult()
        data class RetryAfter(val sec: Int) : SendResult()
        data class Failed(val msg: String) : SendResult()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)   // > getUpdates long-poll timeout
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()

    private fun call(method: String, params: JSONObject = JSONObject()): JSONObject {
        val req = Request.Builder()
            .url("https://api.telegram.org/bot$token/$method")
            .post(params.toString().toRequestBody(json))
            .build()
        try {
            client.newCall(req).execute().use { r ->
                val txt = r.body?.string() ?: ""
                val j = try { JSONObject(txt) } catch (_: Exception) {
                    throw ApiException(r.code, "HTTP ${r.code}")
                }
                if (!j.optBoolean("ok")) {
                    val retry = j.optJSONObject("parameters")?.optInt("retry_after", 0) ?: 0
                    throw ApiException(j.optInt("error_code", r.code), Redact.s(j.optString("description")), retry)
                }
                return j
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: IOException) {
            throw IOException(Redact.s(e.message ?: e.javaClass.simpleName))
        }
    }

    fun getMe(): BotInfo {
        val r = call("getMe").getJSONObject("result")
        return BotInfo(r.getLong("id"), r.optString("first_name"), r.optString("username"))
    }

    /** getUpdates cannot work while a webhook is set. */
    fun deleteWebhook() {
        call("deleteWebhook", JSONObject().put("drop_pending_updates", false))
    }

    fun getUpdates(offset: Long, timeoutSec: Int): JSONArray =
        call(
            "getUpdates",
            JSONObject()
                .put("offset", offset)
                .put("timeout", timeoutSec)
                .put("allowed_updates", JSONArray().put("message").put("my_chat_member"))
        ).getJSONArray("result")

    fun sendMessage(chatId: Long, html: String): SendResult = try {
        call(
            "sendMessage",
            JSONObject()
                .put("chat_id", chatId)
                .put("text", html)
                .put("parse_mode", "HTML")
                .put("disable_web_page_preview", true)
        )
        SendResult.Ok
    } catch (e: ApiException) {
        val d = (e.message ?: "").lowercase()
        when {
            e.code == 403 -> SendResult.Blocked
            e.code == 400 && ("chat not found" in d || "user is deactivated" in d) -> SendResult.Blocked
            e.code == 429 -> SendResult.RetryAfter(e.retryAfter.coerceAtLeast(1))
            else -> SendResult.Failed("${e.code}: ${e.message}")
        }
    } catch (e: IOException) {
        SendResult.Failed(e.message ?: "network")
    }

    fun setMyCommands() {
        val cmds = JSONArray()
            .put(JSONObject().put("command", "start").put("description", "الاشتراك في تنبيهات الهبوط"))
            .put(JSONObject().put("command", "stop").put("description", "إلغاء الاشتراك"))
            .put(JSONObject().put("command", "status").put("description", "حالة الخدمة"))
        call("setMyCommands", JSONObject().put("commands", cmds))
    }

    fun setShortDescription(text: String) {
        call("setMyShortDescription", JSONObject().put("short_description", text))
    }

    /** Cancels in-flight calls (e.g. a pending long-poll) so the bot can stop immediately. */
    fun shutdown() {
        client.dispatcher.cancelAll()
    }
}
