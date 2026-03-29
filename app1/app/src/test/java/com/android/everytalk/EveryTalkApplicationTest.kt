package com.android.everytalk

import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.ui.components.markdown.NativeLatexSupport
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EveryTalkApplicationTest {

    @Before
    fun setUp() {
        stopKoin()
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `application startup should initialize native latex support`() {
        val application = ApplicationProvider.getApplicationContext<EveryTalkApplication>()

        NativeLatexSupport.ensureInitialized(application)
        val icon = NativeLatexSupport.buildDisplayIcon(
            latex = """$$ x^2 + y^2 $$""",
            textSizePx = 48f,
            colorArgb = Color.WHITE,
        )

        assertTrue(icon.iconWidth > 0)
        assertTrue(icon.iconHeight > 0)
    }
}
