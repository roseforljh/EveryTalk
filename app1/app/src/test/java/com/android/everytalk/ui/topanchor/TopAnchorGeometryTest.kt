package com.android.everytalk.ui.topanchor

import org.junit.Assert.assertEquals
import org.junit.Test

class TopAnchorGeometryTest {
    @Test
    fun `short anchor uses top`() {
        assertEquals(
            120,
            computeTopAnchorY(120, 80, 200, 96)
        )
    }

    @Test
    fun `tall anchor keeps fixed visible height`() {
        assertEquals(
            504,
            computeTopAnchorY(120, 480, 200, 96)
        )
    }

    @Test
    fun `positive drift means anchor is below target`() {
        assertEquals(24, computeTopAnchorDriftPx(124, 100))
    }
}
