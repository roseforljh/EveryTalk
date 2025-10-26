package com.android.everytalk.statecontroller.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 管理抽屉相关状态
 */
class DrawerManager {
    private val _isSearchActiveInDrawer = MutableStateFlow(false)
    val isSearchActiveInDrawer: StateFlow<Boolean> = _isSearchActiveInDrawer.asStateFlow()
    
    private val _expandedDrawerItemIndex = MutableStateFlow<Int?>(null)
    val expandedDrawerItemIndex: StateFlow<Int?> = _expandedDrawerItemIndex.asStateFlow()
    
    private val _searchQueryInDrawer = MutableStateFlow("")
    val searchQueryInDrawer: StateFlow<String> = _searchQueryInDrawer.asStateFlow()
    
    fun setSearchActive(isActive: Boolean) {
        _isSearchActiveInDrawer.value = isActive
        if (!isActive) _searchQueryInDrawer.value = ""
    }
    
    fun setExpandedItemIndex(index: Int?) {
        _expandedDrawerItemIndex.value = index
    }
    
    fun onSearchQueryChange(query: String) {
        _searchQueryInDrawer.value = query
    }
}
