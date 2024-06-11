package com.bargarapp.testserver

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "server_logs.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_LOGS = "logs"
        private const val COLUMN_ID = "_id"
        private const val COLUMN_TIMESTAMP = "timestamp"
        private const val COLUMN_MESSAGE = "message"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTableQuery = "CREATE TABLE $TABLE_LOGS ($COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT, $COLUMN_TIMESTAMP INTEGER, $COLUMN_MESSAGE TEXT)"
        db.execSQL(createTableQuery)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_LOGS")
        onCreate(db)
    }

    fun insertLog(timestamp: Long, message: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_TIMESTAMP, timestamp)
            put(COLUMN_MESSAGE, message)
        }
        db.insert(TABLE_LOGS, null, values)
        db.close()
    }

    fun getAllLogs(): List<LogEntry> {
        val logs = mutableListOf<LogEntry>()
        val db = readableDatabase
        val cursor = db.query(TABLE_LOGS, null, null, null, null, null, null)
        with(cursor) {
            while (moveToNext()) {
                val id = getLong(getColumnIndexOrThrow(COLUMN_ID))
                val timestamp = getLong(getColumnIndexOrThrow(COLUMN_TIMESTAMP))
                val message = getString(getColumnIndexOrThrow(COLUMN_MESSAGE))
                logs.add(LogEntry(id, timestamp, message))
            }
            close()
        }
        db.close()
        return logs
    }

    data class LogEntry(val id: Long, val timestamp: Long, val message: String)
}