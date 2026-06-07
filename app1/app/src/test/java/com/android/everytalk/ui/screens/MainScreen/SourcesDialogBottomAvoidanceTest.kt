package com.android.everytalk.ui.screens.MainScreen

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class SourcesDialogBottomAvoidanceTest {

    @Test
    fun `uses input content height plus eight dp gap when input height includes system inset`() {
        val result = calculateSourcesDialogBottomAvoidance(
            inputAreaHeight = 220.dp,
            inputBottomInset = 48.dp
        )

        assertEquals(180.dp, result)
    }

    @Test
    fun `keeps bottom avoidance non negative when inset exceeds measured input height`() {
        val result = calculateSourcesDialogBottomAvoidance(
            inputAreaHeight = 30.dp,
            inputBottomInset = 80.dp
        )

        assertEquals(8.dp, result)
    }

    @Test
    fun `uses controls bottom plus eight dp gap for top avoidance after status bar padding`() {
        val result = calculateSourcesDialogTopAvoidance(
            topControlsBottom = 90.dp,
            statusBarHeight = 24.dp
        )

        assertEquals(74.dp, result)
    }

    @Test
    fun `keeps top avoidance non negative when controls are inside status bar`() {
        val result = calculateSourcesDialogTopAvoidance(
            topControlsBottom = 12.dp,
            statusBarHeight = 24.dp
        )

        assertEquals(8.dp, result)
    }
}
