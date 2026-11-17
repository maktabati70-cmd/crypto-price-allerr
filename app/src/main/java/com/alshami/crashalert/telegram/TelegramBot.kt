package com.alshami.crashalert.telegram

import android.content.Context
import com.alshami.crashalert.data.Config
import com.alshami.crashalert.data.Db
import com.alshami.crashalert.service.ServiceState
import com.alshami.crashalert.util.L
import com.alshami.crashalert.util.Redact
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Telegram bot hosted on the main phone.
 *
 * Single consumer of getUpdates:
 *  - exactly one long-poll thread per instance;
 *  - a process-wide guard ensures only one instance is ever polling;
 *  - the webhook is deleted on start; a 409 Conflict (another device/server polling
 *    the same token) is reported in the UI instead of being silently ignored.
 *
 * Broadcasts go through one sender thread, throttled under Telegram's ~30 msg/s limit.
 */
class TelegramBot(ctx: Context, token: String) {

    companion object {
        private val guard = Any()
        private var current: TelegramBot? = null
        private const val LONG_POLL_SEC = 25
        private const val SEND_GAP_MS = 40L
    }

    private val app = ctx.applicationContext
    private val api = TelegramApi(token)
    private val db = Db.get(app)
    @Volatile private var running = false
    private var thread: Thread? = null
    private val sender: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tg-sender").apply { isDaemon = true }
    }

    fun start() {
        synchronized(guard) {
            current?.takeIf { it !== this }?.stop()
            current = this
        }
        if (running) return
        running = true
        thread = Thread({ loop() }, "tg-getUpdates").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        api.shutdown()
        thread?.interrupt()
        thread = null
        sender.shutdownNow()
        synchronized(guard) { if (current === this) current = null }
        ServiceState.botRunning = false
    }

    private fun pause(ms: Long): Boolean = try {
        Thread.sleep(ms); true
    } catch (_: InterruptedException) {
        false
    }

    private fun loop() {
        var initialized = false
        var backoff = 2_000L
        while (running) {
            try {
                if (!initialized) {
                    val me = api.getMe()
                    ServiceState.botName = me.firstName
                    ServiceState.botUsername = me.username
                    db.putSetting(Config.K_BOT_NAME, me.firstName)
                    db.putSetting(Config.K_BOT_USER, me.username)
                    api.deleteWebhook()
                    try {
                        api.setMyCommands()
                        api.setShortDescription(Messages.SHORT_DESCRIPTION)
                    } catch (e: IOException) {
                        L.w("Bot metadata update failed", e)
                    }
                    initialized = true
                    ServiceState.botRunning = true
                    ServiceState.botError = null
                    L.i("Bot online as @${me.username}")
                }

                var offset = db.getSetting(Config.K_TG_OFFSET, "0").toLongOrNull() ?: 0L
                val updates = api.getUpdates(offset, LONG_POLL_SEC)
                for (i in 0 until updates.length()) {
                    val u = updates.getJSONObject(i)
                    val id = u.getLong("update_id")
                    try { handle(u) } catch (e: Exception) { L.w("update handling failed", e) }
                    offset = id + 1
                    db.putSetting(Config.K_TG_OFFSET, offset.toString())
                }
                ServiceState.botLastPoll = System.currentTimeMillis()
                ServiceState.botRunning = true
                ServiceState.botError = null
                backoff = 2_000L
            } catch (e: TelegramApi.ApiException) {
                if (!running) break
                when (e.code) {
                    401, 404 -> {
                        ServiceState.botRunning = false
                        ServiceState.botError = "التوكن غير صالح أو أُلغي من BotFather"
                        initialized = false
                        if (!pause(60_000)) break
                    }
                    409 -> {
                        ServiceState.botRunning = false
                        ServiceState.botError =
                            "تعارض (409): جهاز أو خادم آخر يستقبل تحديثات هذا البوت. أوقفه — يجب وجود مستهلك واحد فقط."
                        initialized = false
                        if (!pause(15_000)) break
                    }
                    429 -> if (!pause(e.retryAfter.coerceAtLeast(1) * 1000L)) break
                    else -> {
                        ServiceState.botError = "خطأ من Telegram: ${e.code} ${Redact.s(e.message)}"
                        if (!pause(backoff)) break
                        backoff = (backoff * 2).coerceAtMost(60_000)
                    }
                }
            } catch (e: IOException) {
                if (!running) break
                ServiceState.botRunning = false
                ServiceState.botError = "لا يوجد اتصال بـ Telegram: ${Redact.s(e.message)}"
                if (!pause(backoff)) break
                backoff = (backoff * 2).coerceAtMost(60_000)
            } catch (e: Exception) {
                if (!running) break
                L.w("bot loop error", e)
                if (!pause(backoff)) break
            }
        }
        ServiceState.botRunning = false
    }

    private fun handle(u: JSONObject) {
        // Bot removed / blocked by a user or group.
        u.optJSONObject("my_chat_member")?.let { m ->
            val chatId = m.getJSONObject("chat").getLong("id")
            val st = m.getJSONObject("new_chat_member").optString("status")
            if (st == "kicked" || st == "left") db.setStatus(chatId, Db.ST_BLOCKED)
            return
        }

        val msg = u.optJSONObject("message") ?: return
        val text = msg.optString("text", "").trim()
        if (text.isEmpty()) return
        val chat = msg.getJSONObject("chat")
        val chatId = chat.getLong("id")
        val chatType = chat.optString("type")
        val from = msg.optJSONObject("from")
        val cmd = text.split(Regex("\\s+"))[0].substringBefore('@').lowercase()

        when (cmd) {
            "/start" -> {
                val newly = db.subscribe(
                    chatId,
                    from?.optString("username")?.takeIf { it.isNotBlank() },
                    from?.optString("first_name")?.takeIf { it.isNotBlank() },
                    chatType
                )
                reply(chatId, Messages.welcome(newly, Config.load(db)))
            }
            "/stop" -> {
                val changed = db.setStatus(chatId, Db.ST_STOPPED)
                reply(chatId, Messages.stopped(changed))
            }
            "/status" -> reply(chatId, Messages.status(db, db.isActive(chatId)))
            else -> if (chatType == "private") reply(chatId, Messages.help())
        }
    }

    private fun reply(chatId: Long, html: String) {
        repeat(3) {
            when (val r = api.sendMessage(chatId, html)) {
                is TelegramApi.SendResult.RetryAfter -> if (!pause(r.sec * 1000L + 250)) return
                is TelegramApi.SendResult.Blocked -> { db.setStatus(chatId, Db.ST_BLOCKED); return }
                else -> return
            }
        }
    }

    /** Sends [html] to every active subscriber. [onDone] gets (sent, failed). */
    fun broadcast(html: String, onDone: (Int, Int) -> Unit) {
        try {
            sender.execute {
                var sent = 0
                var failed = 0
                for (chatId in db.activeChatIds()) {
                    if (!running) break
                    var done = false
                    var attempts = 0
                    while (!done && attempts < 3) {
                        attempts++
                        when (val r = api.sendMessage(chatId, html)) {
                            is TelegramApi.SendResult.Ok -> { sent++; done = true }
                            is TelegramApi.SendResult.Blocked -> {
                                db.setStatus(chatId, Db.ST_BLOCKED); failed++; done = true
                            }
                            is TelegramApi.SendResult.RetryAfter -> if (!pause(r.sec * 1000L + 250)) return@execute
                            is TelegramApi.SendResult.Failed -> {
                                if (attempts >= 3) { failed++; done = true } else if (!pause(1_000)) return@execute
                            }
                        }
                    }
                    if (!done) failed++
                    if (!pause(SEND_GAP_MS)) return@execute
                }
                try { onDone(sent, failed) } catch (e: Exception) { L.w("onDone failed", e) }
            }
        } catch (_: RejectedExecutionException) {
            onDone(0, 0)
        }
    }
}
