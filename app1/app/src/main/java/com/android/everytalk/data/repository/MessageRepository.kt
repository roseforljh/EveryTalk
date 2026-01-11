package com.android.everytalk.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.android.everytalk.data.database.daos.ChatDao
import com.android.everytalk.data.database.entities.MessageEntity
import kotlinx.coroutines.flow.Flow

class MessageRepository(
    private val chatDao: ChatDao
) {
    companion object {
        private const val PAGE_SIZE = 30
        private const val PREFETCH_DISTANCE = 10
    }
    
    fun getMessagesPaged(sessionId: String): Flow<PagingData<MessageEntity>> {
        return Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                prefetchDistance = PREFETCH_DISTANCE,
                enablePlaceholders = false,
                initialLoadSize = PAGE_SIZE * 2
            ),
            pagingSourceFactory = { chatDao.getMessagesForSessionPaged(sessionId) }
        ).flow
    }
    
    suspend fun getMessageCount(sessionId: String): Int {
        return chatDao.getMessageCountForSession(sessionId)
    }
    
    suspend fun getAllMessages(sessionId: String): List<MessageEntity> {
        return chatDao.getMessagesForSession(sessionId)
    }
}
