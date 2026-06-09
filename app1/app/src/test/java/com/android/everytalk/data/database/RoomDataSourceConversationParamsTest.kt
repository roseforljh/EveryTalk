package com.android.everytalk.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.DataClass.GenerationConfig
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoomDataSourceConversationParamsTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        stopKoin()
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        mockkObject(AppDatabase.Companion)
        every { AppDatabase.getDatabase(any()) } returns database
    }

    @After
    fun tearDown() {
        unmockkObject(AppDatabase.Companion)
        database.close()
        stopKoin()
    }

    @Test
    fun `saving conversation parameters replaces removed entries`() = runBlocking {
        val dataSource = RoomDataSource(ApplicationProvider.getApplicationContext())
        val oldParams = mapOf(
            "keep" to GenerationConfig(temperature = 0.4f),
            "stale" to GenerationConfig(temperature = 0.9f),
        )
        val newParams = mapOf(
            "keep" to GenerationConfig(temperature = 0.2f),
        )

        dataSource.saveConversationParameters(oldParams)
        dataSource.saveConversationParameters(newParams)

        assertEquals(newParams, dataSource.loadConversationParameters())
    }

    @Test
    fun `saving empty conversation parameters clears all entries`() = runBlocking {
        val dataSource = RoomDataSource(ApplicationProvider.getApplicationContext())
        val oldParams = mapOf(
            "one" to GenerationConfig(temperature = 0.4f),
            "two" to GenerationConfig(temperature = 0.9f),
        )

        dataSource.saveConversationParameters(oldParams)
        dataSource.saveConversationParameters(emptyMap())

        assertEquals(emptyMap<String, GenerationConfig>(), dataSource.loadConversationParameters())
    }
}
