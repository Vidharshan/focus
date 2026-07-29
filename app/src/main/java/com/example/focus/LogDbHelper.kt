package com.example.focus

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class BlockLog(
    val id: Long,
    val timestamp: Long,
    val url: String,
    val pattern: String
)

class LogDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    companion object {
        private const val DATABASE_NAME = "focus_blocker_logs.db"
        private const val DATABASE_VERSION = 1
        const val TABLE_LOGS = "block_logs"
        const val COLUMN_ID = "id"
        const val COLUMN_TIMESTAMP = "timestamp"
        const val COLUMN_URL = "url"
        const val COLUMN_PATTERN = "pattern"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTable = ("CREATE TABLE $TABLE_LOGS (" +
                "$COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "$COLUMN_TIMESTAMP INTEGER, " +
                "$COLUMN_URL TEXT, " +
                "$COLUMN_PATTERN TEXT)")
        db.execSQL(createTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_LOGS")
        onCreate(db)
    }

    fun logBlockEvent(url: String, pattern: String) {
        try {
            val db = this.writableDatabase
            val values = ContentValues().apply {
                put(COLUMN_TIMESTAMP, System.currentTimeMillis())
                put(COLUMN_URL, url)
                put(COLUMN_PATTERN, pattern)
            }
            db.insert(TABLE_LOGS, null, values)
            db.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getRecentLogs(limit: Int = 100): List<BlockLog> {
        val logsList = ArrayList<BlockLog>()
        try {
            val db = this.readableDatabase
            val selectQuery = "SELECT * FROM $TABLE_LOGS ORDER BY $COLUMN_TIMESTAMP DESC LIMIT $limit"
            val cursor = db.rawQuery(selectQuery, null)
            if (cursor.moveToFirst()) {
                do {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID))
                    val timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP))
                    val url = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_URL))
                    val pattern = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PATTERN))
                    logsList.add(BlockLog(id, timestamp, url, pattern))
                } while (cursor.moveToNext())
            }
            cursor.close()
            db.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return logsList
    }

    fun clearAllLogs() {
        try {
            val db = this.writableDatabase
            db.delete(TABLE_LOGS, null, null)
            db.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
