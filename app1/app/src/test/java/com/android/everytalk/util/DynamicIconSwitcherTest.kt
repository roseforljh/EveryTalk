package com.android.everytalk.util

import org.junit.Assert.assertEquals
import org.junit.Test

class DynamicIconSwitcherTest {

    @Test
    fun `app launcher always uses white alias`() {
        assertEquals(DynamicIconSwitcher.WHITE_ALIAS, DynamicIconSwitcher.aliasToEnable(isDarkTheme = false))
        assertEquals(DynamicIconSwitcher.WHITE_ALIAS, DynamicIconSwitcher.aliasToEnable(isDarkTheme = true))
    }

    @Test
    fun `old aliases are always disabled`() {
        assertEquals(
            listOf(DynamicIconSwitcher.LIGHT_ALIAS, DynamicIconSwitcher.DARK_ALIAS),
            DynamicIconSwitcher.aliasesToDisable(isDarkTheme = false),
        )
        assertEquals(
            listOf(DynamicIconSwitcher.LIGHT_ALIAS, DynamicIconSwitcher.DARK_ALIAS),
            DynamicIconSwitcher.aliasesToDisable(isDarkTheme = true),
        )
    }
}
