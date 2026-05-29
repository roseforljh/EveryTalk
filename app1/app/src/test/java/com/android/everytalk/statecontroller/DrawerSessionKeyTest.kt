package com.android.everytalk.statecontroller

import androidx.compose.material3.DismissibleNavigationDrawer
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@OptIn(ExperimentalMaterial3Api::class)
class DrawerSessionKeyTest {

    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `drawer session key increments when drawer opens from closed`() {
        var observedSessionKey = -1
        lateinit var openDrawer: () -> Unit
        lateinit var closeDrawer: () -> Unit

        composeRule.setContent {
            val drawerState = rememberDrawerState(DrawerValue.Closed)
            val coroutineScope = rememberCoroutineScope()
            openDrawer = { coroutineScope.launch { drawerState.open() } }
            closeDrawer = { coroutineScope.launch { drawerState.close() } }
            observedSessionKey = rememberDrawerSessionKey(drawerState)

            DismissibleNavigationDrawer(
                drawerState = drawerState,
                drawerContent = { Text("Drawer") },
            ) {
                Text("Content")
            }
        }

        composeRule.runOnIdle {
            assertEquals(0, observedSessionKey)
        }

        composeRule.runOnIdle { openDrawer() }
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitUntil { observedSessionKey == 1 }

        composeRule.runOnIdle { closeDrawer() }
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitUntil { observedSessionKey == 1 }

        composeRule.runOnIdle { openDrawer() }
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitUntil { observedSessionKey == 2 }
    }
}
