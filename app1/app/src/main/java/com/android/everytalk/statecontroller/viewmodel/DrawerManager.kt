package com.android.everytalk.statecontroller.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 管理抽屉相关状态。 */
class DrawerManager {
    private val _expandedDrawerItemIndex = MutableStateFlow<Int?>(null)
    val expandedDrawerItemIndex: StateFlow<Int?> = _expandedDrawerItemIndex.asStateFlow()

    fun setExpandedItemIndex(index: Int?) {
        _expandedDrawerItemIndex.value = index
    }
}
