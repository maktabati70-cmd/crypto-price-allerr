package com.alshami.crashalert.data

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Local SQLite on the main phone only — no cloud database.
 * Tables: subscribers, alerts, settings.
 */
class Db private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx, "crash_alert.db", null, 1) {

    companion object {
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db =
            inst ?: synchronized(this) { inst ?: Db(ctx.applicationContext).also { inst = it } }

        const val ST_ACTIVE = "active"     // subscribed
        const val ST_STOPPED = "stopped"   // user sent /stop
        const val ST_BLOCKED = "blocked"   // user blocked the bot / chat gone
    }

    data class AlertRow(
        val id: Long, val symbol: String, val exchange: String, val dropPct: Double,
        val from: Double, val to: Double, val seconds: Int, val at: Long,
        val sent: Int, val failed: Int
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE subscribers(
                chat_id INTEGER PRIMARY KEY,
                username TEXT,
                first_name TEXT,
                chat_type TEXT,
                first_seen INTEGER NOT NULL,
                subscribed_at INTEGER NOT NULL,
                status TEXT NOT NULL DEFAULT 'active',
                status_changed_at INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE alerts(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                symbol TEXT NOT NULL,
                exchange TEXT NOT NULL,
                drop_pct REAL NOT NULL,
                price_from REAL NOT NULL,
                price_to REAL NOT NULL,
                seconds INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                sent INTEGER NOT NULL DEFAULT 0,
                failed INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL("CREATE INDEX idx_alerts_time ON alerts(created_at)")
        db.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ---------- subscribers ----------

    /** Returns true if the chat became active now (new or re-subscribed). */
    @Synchronized
    fun subscribe(chatId: Long, username: String?, firstName: String?, chatType: String?): Boolean {
        val now = System.currentTimeMillis()
        val w = writableDatabase
        val existing = w.rawQuery(
            "SELECT status FROM subscribers WHERE chat_id=?", arrayOf(chatId.toString())
        ).use { if (it.moveToFirst()) it.getString(0) else null }

        val cv = ContentValues().apply {
            put("username", username)
            put("first_name", firstName)
            put("chat_type", chatType)
        }
        return when (existing) {
            null -> {
                cv.put("chat_id", chatId)
                cv.put("first_seen", now)
                cv.put("subscribed_at", now)
                cv.put("status", ST_ACTIVE)
                cv.put("status_changed_at", now)
                w.insert("subscribers", null, cv)
                true
            }
            ST_ACTIVE -> {
                w.update("subscribers", cv, "chat_id=?", arrayOf(chatId.toString()))
                false
            }
            else -> {
                cv.put("subscribed_at", now)
                cv.put("status", ST_ACTIVE)
                cv.put("status_changed_at", now)
                w.update("subscribers", cv, "chat_id=?", arrayOf(chatId.toString()))
                true
            }
        }
    }

    /** Returns true if the status actually changed. */
    @Synchronized
    fun setStatus(chatId: Long, status: String): Boolean {
        val cv = ContentValues().apply {
            put("status", status)
            put("status_changed_at", System.currentTimeMillis())
        }
        return writableDatabase.update(
            "subscribers", cv, "chat_id=? AND status<>?", arrayOf(chatId.toString(), status)
        ) > 0
    }

    fun isActive(chatId: Long): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM subscribers WHERE chat_id=? AND status=?",
            arrayOf(chatId.toString(), ST_ACTIVE)
        ).use { it.moveToFirst() }

    fun activeChatIds(): List<Long> =
        readableDatabase.rawQuery(
            "SELECT chat_id FROM subscribers WHERE status=? ORDER BY subscribed_at", arrayOf(ST_ACTIVE)
        ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }

    fun activeCount(): Long =
        DatabaseUtils.queryNumEntries(readableDatabase, "subscribers", "status=?", arrayOf(ST_ACTIVE))

    fun totalCount(): Long = DatabaseUtils.queryNumEntries(readableDatabase, "subscribers")

    // ---------- alerts ----------

    fun insertAlert(symbol: String, exchange: String, dropPct: Double, from: Double, to: Double, seconds: Int, at: Long): Long {
        val cv = ContentValues().apply {
            put("symbol", symbol); put("exchange", exchange); put("drop_pct", dropPct)
            put("price_from", from); put("price_to", to); put("seconds", seconds); put("created_at", at)
        }
        val id = writableDatabase.insert("alerts", null, cv)
        // keep the table small
        writableDatabase.execSQL(
            "DELETE FROM alerts WHERE id NOT IN (SELECT id FROM alerts ORDER BY id DESC LIMIT 1000)"
        )
        return id
    }

    fun updateAlertDelivery(id: Long, sent: Int, failed: Int) {
        val cv = ContentValues().apply { put("sent", sent); put("failed", failed) }
        writableDatabase.update("alerts", cv, "id=?", arrayOf(id.toString()))
    }

    fun recentAlerts(limit: Int): List<AlertRow> =
        readableDatabase.rawQuery(
            "SELECT id,symbol,exchange,drop_pct,price_from,price_to,seconds,created_at,sent,failed " +
                "FROM alerts ORDER BY id DESC LIMIT $limit", null
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    AlertRow(
                        c.getLong(0), c.getString(1), c.getString(2), c.getDouble(3),
                        c.getDouble(4), c.getDouble(5), c.getInt(6), c.getLong(7),
                        c.getInt(8), c.getInt(9)
                    )
                )
            }
        }

    // ---------- settings ----------

    fun getSetting(key: String, def: String): String =
        readableDatabase.rawQuery("SELECT value FROM settings WHERE key=?", arrayOf(key))
            .use { if (it.moveToFirst()) it.getString(0) else def }

    fun putSetting(key: String, value: String) {
        val cv = ContentValues().apply { put("key", key); put("value", value) }
        writableDatabase.insertWithOnConflict("settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }
}
