package com.android.everytalk.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class WebSourcesDialogLayoutTest {

    @Test
    fun `sources dialog keeps eight dp gap from surrounding controls`() {
        assertEquals(8.dp, WebSourcesDialogEdgeGap)
    }

    @Test
    fun `source url and preview text have constrained single and double line display`() {
        assertEquals(360.dp, WebSourcesDialogUrlMaxWidth)
        assertEquals(1, WebSourcesDialogUrlMaxLines)
        assertEquals(2, WebSourcesDialogSnippetMaxLines)
    }
}
