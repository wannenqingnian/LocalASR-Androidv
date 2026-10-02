package com.example.localasr.history

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class HistoryDatabase(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at INTEGER NOT NULL,
                ended_at INTEGER NOT NULL,
                transcript TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun beginSession(startedAt: Long): Long {
        val values = ContentValues().apply {
            put("started_at", startedAt)
            put("ended_at", startedAt)
            put("transcript", "")
        }
        return writableDatabase.insertOrThrow("sessions", null, values)
    }

    fun updateSession(id: Long, endedAt: Long, text: String) {
        val values = ContentValues().apply {
            put("ended_at", endedAt)
            put("transcript", text)
        }
        writableDatabase.update("sessions", values, "id = ?", arrayOf(id.toString()))
    }

    fun list(): List<TranscriptSession> {
        val result = mutableListOf<TranscriptSession>()
        readableDatabase.query(
            "sessions",
            arrayOf("id", "started_at", "ended_at", "transcript"),
            "transcript != ?",
            arrayOf(""),
            null,
            null,
            "started_at DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += TranscriptSession(
                    id = cursor.getLong(0),
                    startedAt = cursor.getLong(1),
                    endedAt = cursor.getLong(2),
                    text = cursor.getString(3),
                )
            }
        }
        return result
    }

    fun delete(id: Long) {
        writableDatabase.delete("sessions", "id = ?", arrayOf(id.toString()))
    }

    companion object {
        private const val DATABASE_NAME = "transcripts.db"
        private const val DATABASE_VERSION = 1
    }
}
