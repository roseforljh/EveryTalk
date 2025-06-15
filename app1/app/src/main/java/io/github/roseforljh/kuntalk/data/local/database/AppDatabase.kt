package io.github.roseforljh.kuntalk.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.roseforljh.kuntalk.data.DataClass.Message
import io.github.roseforljh.kuntalk.data.local.SharedPreferencesDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

@Database(entities = [ConversationEntity::class, MessageEntity::class], version = 1, exportSchema = false)
@TypeConverters(AppDatabase.Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    object Converters {
        @JvmStatic
        @TypeConverter
        fun fromSender(sender: io.github.roseforljh.kuntalk.data.DataClass.Sender): String {
            return sender.name
        }

        @JvmStatic
        @TypeConverter
        fun toSender(name: String): io.github.roseforljh.kuntalk.data.DataClass.Sender {
            return io.github.roseforljh.kuntalk.data.DataClass.Sender.valueOf(name)
        }

        @JvmStatic
        @TypeConverter
        fun fromSelectedMediaItemList(attachments: List<io.github.roseforljh.kuntalk.model.SelectedMediaItem>?): String? {
            return attachments?.let {
                val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                val serializableList = it.filter { item -> item !is io.github.roseforljh.kuntalk.model.SelectedMediaItem.ImageFromBitmap }
                json.encodeToString(kotlinx.serialization.builtins.ListSerializer(io.github.roseforljh.kuntalk.model.SelectedMediaItem.serializer()), serializableList)
            }
        }

        @JvmStatic
        @TypeConverter
        fun toSelectedMediaItemList(jsonString: String?): List<io.github.roseforljh.kuntalk.model.SelectedMediaItem>? {
            return jsonString?.let {
                val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(io.github.roseforljh.kuntalk.model.SelectedMediaItem.serializer()), it)
            }
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context, scope: CoroutineScope): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "kuntalk_database"
                )
                .addCallback(AppDatabaseCallback(context, scope))
                .build()
                INSTANCE = instance
                instance
            }
        }

        private class AppDatabaseCallback(
            private val context: Context,
            private val scope: CoroutineScope
        ) : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                INSTANCE?.let { database ->
                    scope.launch(Dispatchers.IO) {
                        populateDatabase(context, database.chatDao())
                    }
                }
            }
        }

        suspend fun populateDatabase(context: Context, chatDao: ChatDao) {
            val sharedPrefsDataSource = SharedPreferencesDataSource(context)
            val legacyHistory = sharedPrefsDataSource.loadChatHistory()

            if (legacyHistory.isNotEmpty()) {
                legacyHistory.forEach { conversationMessages ->
                    if (conversationMessages.isNotEmpty()) {
                        val conversationId = conversationMessages.first().id.split("_").firstOrNull() ?: UUID.randomUUID().toString()
                        chatDao.insertConversation(ConversationEntity(conversationId))
                        val messageEntities = conversationMessages.map { message ->
                            MessageEntity(
                                messageId = message.id,
                                conversationId = conversationId,
                                sender = message.sender,
                                text = message.text,
                                attachments = message.attachments?.let { Converters.fromSelectedMediaItemList(it) },
                                timestamp = message.timestamp,
                                isError = message.isError,
                                reasoning = message.reasoning,
                                contentStarted = message.contentStarted,
                                isPlaceholderName = message.isPlaceholderName
                            )
                        }
                        chatDao.insertMessages(messageEntities)
                    }
                }
                // Optional: Clear the old data after migration
                sharedPrefsDataSource.clearChatHistory()
            }
        }
    }
}