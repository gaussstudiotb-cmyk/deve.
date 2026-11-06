
package com.deve.assistant

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ConversationManager(context: Context) {

    data class Message(
        val id: Long,
        val role: String,
        val content: String,
        val timestamp: Long
    )

    private class Database(context: Context) :
        SQLiteOpenHelper(context, "deve_conversations.db", null, 1) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    timestamp INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }

        override fun onUpgrade(
            db: SQLiteDatabase,
            oldVersion: Int,
            newVersion: Int
        ) {
            // Préserver les données lors des futures migrations.
        }
    }

    private val database = Database(context.applicationContext)

    @Synchronized
    fun addMessage(role: String, content: String) {
        if (content.isBlank()) return
        require(role == "user" || role == "assistant")

        val values = ContentValues().apply {
            put("role", role)
            put("content", content)
            put("timestamp", System.currentTimeMillis())
        }

        database.writableDatabase.insertOrThrow(
            "messages", null, values
        )
    }

    @Synchronized
    fun getRecentMessages(limit: Int = 20): List<Message> {
        val safeLimit = limit.coerceIn(1, 100)

        val messages = mutableListOf<Message>()

        database.readableDatabase.rawQuery(
            """
            SELECT id, role, content, timestamp
            FROM messages
            ORDER BY id DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(safeLimit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                messages.add(
                    Message(
                        id = cursor.getLong(0),
                        role = cursor.getString(1),
                        content = cursor.getString(2),
                        timestamp = cursor.getLong(3)
                    )
                )
            }
        }

        return messages.reversed()
    }

    @Synchronized
    fun clearHistory() {
        database.writableDatabase.delete(
            "messages", null, null
        )
    }

    fun close() {
        database.close()
    }
}
