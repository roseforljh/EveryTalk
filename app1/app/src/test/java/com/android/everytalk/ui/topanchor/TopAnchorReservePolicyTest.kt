package com.android.everytalk.ui.topanchor

import org.junit.Assert.assertEquals
import org.junit.Test

class TopAnchorReservePolicyTest {
    @Test
    fun `reserve grows by missing scroll`() {
        assertEquals(
            180,
            computeTopAnchorReservePx(
                viewportHeightPx = 900,
                driftPx = 300,
                scrollConsumedPx = 120,
                currentReservePx = 0
            )
        )
    }

    @Test
    fun `reserve caps at viewport height`() {
        assertEquals(
            900,
            computeTopAnchorReservePx(
                viewportHeightPx = 900,
                driftPx = 1400,
                scrollConsumedPx = 0,
                currentReservePx = 0
            )
        )
    }

    @Test
    fun `negative drift does not grow bottom reserve`() {
        assertEquals(
            50,
            computeTopAnchorReservePx(
                viewportHeightPx = 900,
                driftPx = -120,
                scrollConsumedPx = 0,
                currentReservePx = 50
            )
        )
    }

    @Test
    fun `reserve shrinks to visible gap`() {
        assertEquals(120, shrinkTopAnchorReservePx(500, 120))
    }
}
