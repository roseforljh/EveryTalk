package com.android.everytalk.data.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppDatabaseMigrationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        stopKoin()
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(TEST_DB)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(TEST_DB)
        stopKoin()
    }

    @Test
    fun `migration 5 to 6 preserves api configs with openclaw columns`() {
        val createHelper = openHelper(
            version = 5,
            onCreate = { db ->
                createSchemaFromJson(db, 5)
            }
        )
        createHelper.writableDatabase.apply {
            execSQL(
                """
                INSERT INTO api_configs (
                    id, address, key, model, provider, name, channel, isValid,
                    modalityType, temperature, topP, maxTokens, defaultUseWebSearch,
                    imageSize, numInferenceSteps, guidanceScale, toolsJson,
                    enableCodeExecution, openClawAccessMode, openClawBridgeUrl,
                    openClawSessionId, isImageGenConfig
                ) VALUES (
                    'api-1', 'https://example.test', 'secret', 'model-1', 'openclaw',
                    'OpenClaw', 'chat', 1, 'TEXT', 0.7, NULL, NULL, 0,
                    NULL, NULL, NULL, '[]', 0, 'bridge',
                    'http://127.0.0.1:3000', 'session-1', 0
                )
                """.trimIndent()
            )
            close()
        }
        createHelper.close()

        val migrateHelper = openHelper(
            version = 6,
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(5, oldVersion)
                assertEquals(6, newVersion)
                AppDatabase.MIGRATION_5_6.migrate(db)
            }
        )
        val db = migrateHelper.writableDatabase

        val cursor = db.query(
            "SELECT openClawAccessMode, openClawBridgeUrl, openClawSessionId FROM api_configs WHERE id = 'api-1'"
        )
        cursor.use {
            assertTrue(it.moveToFirst())
            assertEquals("bridge", it.getString(0))
            assertEquals("http://127.0.0.1:3000", it.getString(1))
            assertEquals("session-1", it.getString(2))
        }
        db.close()
        migrateHelper.close()
    }

    private fun openHelper(
        version: Int,
        onCreate: (SupportSQLiteDatabase) -> Unit = {},
        onUpgrade: (SupportSQLiteDatabase, Int, Int) -> Unit = { _, _, _ -> },
    ): SupportSQLiteOpenHelper {
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DB)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(version) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            onCreate(db)
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int
                        ) {
                            onUpgrade(db, oldVersion, newVersion)
                        }
                    }
                )
                .build()
        )
    }

    private fun createSchemaFromJson(db: SupportSQLiteDatabase, version: Int) {
        val schema = schemaFile(version).readText(Charsets.UTF_8).removePrefix("\uFEFF")
        val database = Json.parseToJsonElement(schema).jsonObject
            .getValue("database")
            .jsonObject
        database.getValue("entities").jsonArray.forEach { entityElement ->
            val entity = entityElement.jsonObject
            val tableName = entity.getValue("tableName").jsonPrimitive.content
            val createSql = entity.getValue("createSql").jsonPrimitive.content
                .replace("\${TABLE_NAME}", tableName)
            db.execSQL(createSql)
        }
        database.getValue("setupQueries").jsonArray.forEach { query ->
            db.execSQL(query.jsonPrimitive.content)
        }
    }

    private fun schemaFile(version: Int): File {
        val relative = "schemas/com.android.everytalk.data.database.AppDatabase/$version.json"
        val candidates = listOf(
            File(relative),
            File("app/$relative"),
            File("app1/app/$relative"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("Missing schema file: $relative")
    }

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
