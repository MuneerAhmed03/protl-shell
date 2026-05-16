package com.portl.shell

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class DatabaseManager(context: Context) {
    private val helper = object : SQLiteOpenHelper(context, "androlocal.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {

        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {

        }
    }

    @Synchronized
    fun query(sql: String, paramsJson: String?): String {
        return try {
            val params = parseParams(paramsJson)
            val statementType = sql.trim().substringBefore(" ", "").uppercase(Locale.US)

            when (statementType) {
                "SELECT" -> executeSelect(sql, params)
                "INSERT", "UPDATE", "DELETE" -> executeWrite(sql, params, statementType)
                else -> executeStatement(sql, params)
            }
        } catch (e: Exception) {
            JSONObject()
                .put("error", e.message ?: "unknown error")
                .toString()
        }
    }

    private fun executeSelect(sql: String, params: List<String?>): String {
        val args = params.map { it ?: "" }.toTypedArray()
        helper.readableDatabase.rawQuery(sql, args).use { cursor ->
            val rows = JSONArray()
            while (cursor.moveToNext()) {
                val row = JSONObject()
                for (columnIndex in 0 until cursor.columnCount) {
                    val key = cursor.getColumnName(columnIndex)
                    row.put(key, cursorValue(cursor, columnIndex))
                }
                rows.put(row)
            }
            return rows.toString()
        }
    }

    private fun executeWrite(sql: String, params: List<String?>, statementType: String): String {
        val db = helper.writableDatabase
        val statement = db.compileStatement(sql)
        try {
            bindStatementParams(statement, params)
            return if (statementType == "INSERT") {
                val rowId = statement.executeInsert()
                val changes = if (rowId >= 0) 1 else 0
                JSONObject()
                    .put("changes", changes)
                    .put("lastInsertRowid", if (rowId >= 0) rowId else 0)
                    .toString()
            } else {
                val changes = statement.executeUpdateDelete()
                JSONObject()
                    .put("changes", changes)
                    .put("lastInsertRowid", 0)
                    .toString()
            }
        } finally {
            statement.close()
        }
    }

    private fun executeStatement(sql: String, params: List<String?>): String {
        if (params.isEmpty()) {
            helper.writableDatabase.execSQL(sql)
        } else {
            val bindArgs = params.toTypedArray()
            helper.writableDatabase.execSQL(sql, bindArgs)
        }
        return JSONObject()
            .put("changes", 0)
            .put("lastInsertRowid", 0)
            .toString()
    }

    private fun parseParams(paramsJson: String?): List<String?> {
        if (paramsJson.isNullOrBlank()) {
            return emptyList()
        }

        val parsed = JSONArray(paramsJson)
        return buildList(parsed.length()) {
            for (i in 0 until parsed.length()) {
                if (parsed.isNull(i)) {
                    add(null)
                } else {
                    add(parsed.get(i).toString())
                }
            }
        }
    }

    private fun bindStatementParams(
        statement: android.database.sqlite.SQLiteStatement,
        params: List<String?>
    ) {
        params.forEachIndexed { index, value ->
            val bindIndex = index + 1
            if (value == null) {
                statement.bindNull(bindIndex)
            } else {
                statement.bindString(bindIndex, value)
            }
        }
    }

    private fun cursorValue(cursor: Cursor, columnIndex: Int): Any {
        return when (cursor.getType(columnIndex)) {
            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(columnIndex)
            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(columnIndex)
            Cursor.FIELD_TYPE_BLOB -> Base64.encodeToString(cursor.getBlob(columnIndex), Base64.NO_WRAP)
            else -> cursor.getString(columnIndex)
        }
    }
}
