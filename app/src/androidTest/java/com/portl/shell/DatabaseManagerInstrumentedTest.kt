package com.portl.shell

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseManagerInstrumentedTest {
    private lateinit var manager: DatabaseManager

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("androlocal.db")
        manager = DatabaseManager(context)
        manager.query(
            """
            CREATE TABLE typed_values (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                label TEXT,
                whole INTEGER,
                amount REAL,
                visible INTEGER,
                optional TEXT
            )
            """.trimIndent(),
            null
        )
    }

    @After
    fun tearDown() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("androlocal.db")
    }

    @Test
    fun queryRoundTripsPrimitiveParamTypes() {
        val insert = JSONObject(
            manager.query(
                "INSERT INTO typed_values (label, whole, amount, visible, optional) VALUES (?, ?, ?, ?, ?)",
                """["espresso", 42, 12.5, true, null]"""
            )
        )
        assertEquals(1, insert.getInt("changes"))

        val rows = JSONArray(
            manager.query(
                "SELECT label, whole, amount, visible, optional FROM typed_values WHERE optional IS ?",
                "[null]"
            )
        )
        assertEquals(1, rows.length())

        val row = rows.getJSONObject(0)
        assertEquals("espresso", row.getString("label"))
        assertEquals(42L, row.getLong("whole"))
        assertEquals(12.5, row.getDouble("amount"), 0.0)
        assertEquals(1L, row.getLong("visible"))
        assertTrue(row.isNull("optional"))
    }

    @Test
    fun queryBindsNumericAndNullParamsWithSqliteTypes() {
        val rows = JSONArray(
            manager.query(
                """
                SELECT
                    typeof(?) AS int_type,
                    typeof(?) AS real_type,
                    typeof(?) AS bool_type,
                    typeof(?) AS null_type,
                    typeof(?) AS text_type
                """.trimIndent(),
                """[7, 3.25, false, null, "seven"]"""
            )
        )
        val row = rows.getJSONObject(0)

        assertEquals("integer", row.getString("int_type"))
        assertEquals("real", row.getString("real_type"))
        assertEquals("integer", row.getString("bool_type"))
        assertEquals("null", row.getString("null_type"))
        assertEquals("text", row.getString("text_type"))
    }

    @Test
    fun queryRawKeepsDrizzleRowShapesWithTypedParams() {
        val run = JSONObject(
            manager.queryRaw(
                "INSERT INTO typed_values (label, whole, amount, visible, optional) VALUES (?, ?, ?, ?, ?)",
                """["raw", 9, 1.5, false, null]""",
                "run"
            )
        )
        assertEquals(0, run.getJSONArray("rows").length())

        val allRows = JSONObject(
            manager.queryRaw(
                "SELECT label, whole, amount, visible, optional FROM typed_values WHERE label = ?",
                """["raw"]""",
                "all"
            )
        ).getJSONArray("rows")
        assertEquals(1, allRows.length())
        val allRow = allRows.getJSONArray(0)
        assertEquals("raw", allRow.getString(0))
        assertEquals(9L, allRow.getLong(1))
        assertEquals(1.5, allRow.getDouble(2), 0.0)
        assertEquals(0L, allRow.getLong(3))
        assertTrue(allRow.isNull(4))

        val getRow = JSONObject(
            manager.queryRaw(
                "SELECT typeof(whole), typeof(amount), typeof(visible), typeof(optional) FROM typed_values WHERE label = ?",
                """["raw"]""",
                "get"
            )
        ).getJSONArray("rows")
        assertEquals("integer", getRow.getString(0))
        assertEquals("real", getRow.getString(1))
        assertEquals("integer", getRow.getString(2))
        assertEquals("null", getRow.getString(3))
    }

    @Test
    fun unsupportedArrayOrObjectParamsReturnErrorPayload() {
        val objectError = JSONObject(manager.query("SELECT ?", "[{}]")).getString("error")
        assertTrue(objectError.contains("Unsupported SQL parameter type at index 0"))

        val arrayError = JSONObject(manager.query("SELECT ?", "[[1]]")).getString("error")
        assertTrue(arrayError.contains("Unsupported SQL parameter type at index 0"))
    }
}
