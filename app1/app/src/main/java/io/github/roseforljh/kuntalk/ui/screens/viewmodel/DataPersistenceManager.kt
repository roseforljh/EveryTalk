package io.github.roseforljh.kuntalk.ui.screens.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import io.github.roseforljh.kuntalk.data.DataClass.ApiConfig
import io.github.roseforljh.kuntalk.data.DataClass.Message
import io.github.roseforljh.kuntalk.data.local.SharedPreferencesDataSource
import io.github.roseforljh.kuntalk.data.local.database.AppDatabase
import io.github.roseforljh.kuntalk.data.local.database.ConversationEntity
import io.github.roseforljh.kuntalk.data.local.database.MessageEntity
import io.github.roseforljh.kuntalk.model.SelectedMediaItem
import io.github.roseforljh.kuntalk.StateControler.ViewModelStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

class DataPersistenceManager(
    private val context: Context,
    private val sharedPrefsDataSource: SharedPreferencesDataSource,
    private val db: AppDatabase,
    private val stateHolder: ViewModelStateHolder,
    private val viewModelScope: CoroutineScope
) {
    private val tag = "PersistenceManager"
    private val json = Json { ignoreUnknownKeys = true }

    private fun getConversationId(messages: List<Message>): String {
        return messages.firstOrNull()?.id?.let { it.substringBeforeLast('_') } ?: UUID.randomUUID().toString()
    }

    private fun deleteAttachmentFiles(messages: List<Message>) {
        val attachmentDir = File(context.filesDir, "chat_attachments")
        if (!attachmentDir.exists() || !attachmentDir.isDirectory) return

        messages.forEach { message ->
            message.attachments?.forEach { attachment ->
                val uriToDelete: Uri? = when (attachment) {
                    is SelectedMediaItem.ImageFromUri -> attachment.uri
                    is SelectedMediaItem.GenericFile -> attachment.uri
                    else -> null
                }

                uriToDelete?.let { uri ->
                    try {
                        if (uri.scheme == "content" && uri.authority == "${context.packageName}.provider") {
                            val fileName = uri.lastPathSegment
                            if (fileName != null) {
                                val fileToDelete = File(attachmentDir, fileName)
                                if (fileToDelete.exists() && fileToDelete.isFile) {
                                    if (fileToDelete.delete()) {
                                        Log.d(tag, "Deleted attachment file: ${fileToDelete.absolutePath}")
                                    } else {
                                        Log.w(tag, "Failed to delete attachment file: ${fileToDelete.absolutePath}")
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Error deleting attachment file for URI: $uri", e)
                    }
                }
            }
        }
    }

    private fun Message.toEntity(conversationId: String): MessageEntity {
        val attachmentsJson = this.attachments?.let {
            json.encodeToString(ListSerializer(SelectedMediaItem.serializer()), it)
        }
        return MessageEntity(
            messageId = this.id,
            conversationId = conversationId,
            sender = this.sender,
            text = this.text,
            attachments = attachmentsJson,
            timestamp = this.timestamp,
            isError = this.isError,
            reasoning = this.reasoning,
            contentStarted = this.contentStarted,
            isPlaceholderName = this.isPlaceholderName
        )
    }

    private fun MessageEntity.toMessage(): Message {
        val attachmentsList = this.attachments?.let {
            json.decodeFromString(ListSerializer(SelectedMediaItem.serializer()), it)
        }
        return Message(
            id = this.messageId,
            sender = this.sender,
            text = this.text,
            attachments = attachmentsList,
            timestamp = this.timestamp,
            isError = this.isError,
            reasoning = this.reasoning,
            contentStarted = this.contentStarted,
            isPlaceholderName = this.isPlaceholderName
        )
    }

    fun loadInitialData(
        loadLastChat: Boolean = true,
        onLoadingComplete: (initialConfigPresent: Boolean, initialHistoryPresent: Boolean) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            var initialConfigPresent = false
            var initialHistoryPresent = false

            try {
                val loadedConfigs: List<ApiConfig> = sharedPrefsDataSource.loadApiConfigs()
                initialConfigPresent = loadedConfigs.isNotEmpty()

                val selectedConfigId: String? = sharedPrefsDataSource.loadSelectedConfigId()
                var finalSelectedConfig = loadedConfigs.find { it.id == selectedConfigId }
                if (finalSelectedConfig == null && loadedConfigs.isNotEmpty()) {
                    finalSelectedConfig = loadedConfigs.first()
                    sharedPrefsDataSource.saveSelectedConfigId(finalSelectedConfig.id)
                }

                val loadedHistory = db.chatDao().getAllConversationsWithMessages().map { conversationWithMessages ->
                    conversationWithMessages.messages.map { it.toMessage() }
                }
                initialHistoryPresent = loadedHistory.isNotEmpty()

                val lastOpenChatMessages: List<Message> = if (loadLastChat) {
                    sharedPrefsDataSource.loadLastOpenChatInternal()
                } else {
                    emptyList()
                }

                withContext(Dispatchers.Main.immediate) {
                    stateHolder._apiConfigs.value = loadedConfigs
                    stateHolder._selectedApiConfig.value = finalSelectedConfig
                    stateHolder._historicalConversations.value = loadedHistory
                    if (loadLastChat) {
                        stateHolder.messages.clear()
                        stateHolder.messages.addAll(lastOpenChatMessages)
                        lastOpenChatMessages.forEach { msg ->
                            if (msg.contentStarted || msg.isError) {
                                stateHolder.messageAnimationStates[msg.id] = true
                            }
                        }
                    }
                    onLoadingComplete(initialConfigPresent, initialHistoryPresent)
                }
            } catch (e: Exception) {
                Log.e(tag, "Error loading initial data", e)
                withContext(Dispatchers.Main.immediate) {
                    onLoadingComplete(false, false)
                }
            }
        }
    }

    suspend fun saveLastOpenChat(messages: List<Message>) {
        withContext(Dispatchers.IO) {
            sharedPrefsDataSource.saveLastOpenChatInternal(messages)
        }
    }

    suspend fun clearAllChatHistory() {
        withContext(Dispatchers.IO) {
            val allConversations = db.chatDao().getAllConversationsWithMessages()
            allConversations.forEach { conversationWithMessages ->
                deleteAttachmentFiles(conversationWithMessages.messages.map { it.toMessage() })
            }
            db.chatDao().clearAllConversations()
            Log.d(tag, "Cleared all attachment files and database entries.")
        }
    }

    suspend fun saveApiConfigs(configsToSave: List<ApiConfig>) {
        withContext(Dispatchers.IO) {
            sharedPrefsDataSource.saveApiConfigs(configsToSave)
        }
    }

    suspend fun saveChatHistory(historyToSave: List<List<Message>>) {
        withContext(Dispatchers.IO) {
            val allConversationsInDb = db.chatDao().getAllConversationsWithMessages()
            val dbConversationMap = allConversationsInDb.associateBy { it.conversation.conversationId }
            val historyConversationMap = historyToSave.associateBy(::getConversationId)

            val dbIds = dbConversationMap.keys
            val historyIds = historyConversationMap.keys

            // Delete conversations from DB that are not in the new history
            dbIds.minus(historyIds).forEach { idToDelete ->
                dbConversationMap[idToDelete]?.let { conversationToDelete ->
                    deleteAttachmentFiles(conversationToDelete.messages.map { it.toMessage() })
                }
                db.chatDao().deleteConversation(idToDelete)
            }

            // Insert or update conversations from the new history
            historyConversationMap.forEach { (id, messages) ->
                val existingConversation = dbConversationMap[id]
                if (existingConversation == null || existingConversation.messages.size != messages.size ||
                    !existingConversation.messages.map { it.messageId }.containsAll(messages.map { it.id })) {
                    db.chatDao().insertConversation(ConversationEntity(id))
                    db.chatDao().insertMessages(messages.map { it.toEntity(id) })
                }
            }
        }
    }

    suspend fun saveSelectedConfigIdentifier(configId: String?) {
        withContext(Dispatchers.IO) {
            sharedPrefsDataSource.saveSelectedConfigId(configId)
        }
    }

    suspend fun clearAllApiConfigData() {
        withContext(Dispatchers.IO) {
            sharedPrefsDataSource.clearApiConfigs()
            sharedPrefsDataSource.saveSelectedConfigId(null)
        }
    }
}
