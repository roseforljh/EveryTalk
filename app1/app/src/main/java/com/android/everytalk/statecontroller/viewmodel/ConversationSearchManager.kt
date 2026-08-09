package com.android.everytalk.statecontroller.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 管理主页面会话搜索模式及输入内容。 */
class ConversationSearchManager {
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    fun setActive(isActive: Boolean) {
        _isActive.value = isActive
        if (!isActive) _query.value = ""
    }

    fun onQueryChange(query: String) {
        _query.value = query.take(MAX_QUERY_CHARS)
    }

    companion object {
        const val MAX_QUERY_CHARS = 120
    }
}
