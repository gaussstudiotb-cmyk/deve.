package com.deve.assistant

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Base locale : historique des conversations (partagé entre Gemini et Claude) et journal des actions. */
class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "deve.db", null, 1) {

    companion object {
        @Volatile
        private var inst: Db? = null

        fun get(ctx: Context): Db =
            inst ?: synchronized(this) { inst ?: Db(ctx).also { inst = it } }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, " +
                "provider TEXT, user_text TEXT NOT NULL, reply TEXT NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE actions (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, " +
                "name TEXT NOT NULL, detail TEXT, ok INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ---------------------------------------------------------- historique

    fun addMessage(provider: String, user: String, reply: String) {
        val v = ContentValues().apply {
            put("ts", System.currentTimeMillis())
            put("provider", provider)
            put("user_text", user)
            put("reply", reply)
        }
        val db = writableDatabase
        db.insert("messages", null, v)
        db.execSQL("DELETE FROM messages WHERE id NOT IN (SELECT id FROM messages ORDER BY id DESC LIMIT 500)")
    }

    /** Les derniers échanges (du plus ancien au plus récent) depuis une date. */
    fun recent(sinceMs: Long, limit: Int): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        readableDatabase.rawQuery(
            "SELECT user_text, reply FROM messages WHERE ts >= ? ORDER BY id DESC LIMIT ?",
            arrayOf(sinceMs.toString(), limit.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0) to c.getString(1))
        }
        out.reverse()
        return out
    }

    fun countMessages(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM messages", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    fun clearMessages() {
        writableDatabase.delete("messages", null, null)
    }

    // ---------------------------------------------------------- journal des actions

    fun logAction(name: String, detail: String, ok: Boolean) {
        val v = ContentValues().apply {
            put("ts", System.currentTimeMillis())
            put("name", name)
            put("detail", detail)
            put("ok", if (ok) 1 else 0)
        }
        val db = writableDatabase
        db.insert("actions", null, v)
        db.execSQL("DELETE FROM actions WHERE id NOT IN (SELECT id FROM actions ORDER BY id DESC LIMIT 200)")
    }

    fun recentActions(limit: Int): List<String> {
        val out = ArrayList<String>()
        val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE)
        readableDatabase.rawQuery(
            "SELECT ts, name, detail, ok FROM actions ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val mark = if (c.getInt(3) == 1) "✓" else "✗"
                out.add("${fmt.format(Date(c.getLong(0)))} $mark ${c.getString(1)} : ${c.getString(2) ?: ""}")
            }
        }
        return out
    }
}
