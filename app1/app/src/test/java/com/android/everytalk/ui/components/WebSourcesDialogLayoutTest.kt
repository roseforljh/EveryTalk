package com.android.everytalk.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class WebSourcesDialogLayoutTest {

    @Test
    fun `sources dialog keeps eight dp gap from surrounding controls`() {
        assertEquals(8.dp, WebSourcesDialogEdgeGap)
    }
}
