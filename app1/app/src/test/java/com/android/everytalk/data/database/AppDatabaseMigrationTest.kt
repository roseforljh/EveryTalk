package com.android.everytalk.data.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `migration 6 to 7 preserves common api config data`() {
        val createHelper = openHelper(
            version = 6,
            onCreate = { db ->
                createVersion6ApiConfigsTable(db)
            }
        )
        createHelper.writableDatabase.apply {
            execSQL(
                """
                INSERT INTO api_configs (
                    id, address, key, model, provider, name, channel, isValid,
                    modalityType, temperature, topP, maxTokens, defaultUseWebSearch,
                    imageSize, numInferenceSteps, guidanceScale, toolsJson,
                    enableCodeExecution, isImageGenConfig
                ) VALUES (
                    'api-1', 'https://example.test', 'secret', 'model-1', 'anthropic',
                    'Anthropic', 'Anthropic', 1, 'TEXT', 0.7, NULL, 8192, 0,
                    NULL, NULL, NULL, '[]', 0, 0
                )
                """.trimIndent()
            )
            close()
        }
        createHelper.close()

        val migrateHelper = openHelper(
            version = 7,
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(6, oldVersion)
                assertEquals(7, newVersion)
                AppDatabase.MIGRATION_6_7.migrate(db)
            }
        )
        val db = migrateHelper.writableDatabase

        val cursor = db.query(
            "SELECT provider, channel, maxTokens, toolsJson FROM api_configs WHERE id = 'api-1'"
        )
        cursor.use {
            assertTrue(it.moveToFirst())
            assertEquals("anthropic", it.getString(0))
            assertEquals("Anthropic", it.getString(1))
            assertEquals(8192, it.getInt(2))
            assertEquals("[]", it.getString(3))
        }
        val columns = mutableListOf<String>()
        db.query("PRAGMA table_info(api_configs)").use { tableInfo ->
            while (tableInfo.moveToNext()) columns += tableInfo.getString(1)
        }
        assertFalse(columns.contains("legacyFeatureState"))
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

    private fun createVersion6ApiConfigsTable(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE api_configs (
                id TEXT NOT NULL PRIMARY KEY,
                address TEXT NOT NULL,
                key TEXT NOT NULL,
                model TEXT NOT NULL,
                provider TEXT NOT NULL,
                name TEXT NOT NULL,
                channel TEXT NOT NULL,
                isValid INTEGER NOT NULL,
                modalityType TEXT NOT NULL,
                temperature REAL NOT NULL,
                topP REAL,
                maxTokens INTEGER,
                defaultUseWebSearch INTEGER,
                imageSize TEXT,
                numInferenceSteps INTEGER,
                guidanceScale REAL,
                toolsJson TEXT,
                enableCodeExecution INTEGER,
                legacyFeatureState TEXT,
                isImageGenConfig INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
