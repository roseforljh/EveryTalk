package com.android.everytalk.ui.screens.BubbleMain.Main

import androidx.compose.ui.Alignment
import com.android.everytalk.data.DataClass.Sender
import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentLayoutRulesTest {
    @Test
    fun `user image attachments are anchored to the right`() {
        assertEquals(Alignment.End, attachmentStripHorizontalAlignment(Sender.User))
    }

    @Test
    fun `ai image attachments keep left alignment`() {
        assertEquals(Alignment.Start, attachmentStripHorizontalAlignment(Sender.AI))
    }
}
