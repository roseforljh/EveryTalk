package com.android.everytalk.statecontroller

import androidx.compose.material3.DismissibleNavigationDrawer
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
// 只验证侧栏界面，不启动真实应用的后台同步任务。
@Config(sdk = [34], application = android.app.Application::class)
@OptIn(ExperimentalMaterial3Api::class)
class DrawerSessionKeyTest {

    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `关闭的抽屉不创建内容且打开后关闭仍保留组件`() {
        var mounts = 0
        var disposals = 0
        lateinit var openDrawer: () -> Unit
        lateinit var closeDrawer: () -> Unit
        composeRule.setContent {
            val state = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            openDrawer = { scope.launch { state.open() } }
            closeDrawer = { scope.launch { state.close() } }
            DismissibleNavigationDrawer(drawerState = state, drawerContent = {
                DeferredDrawerContent(state) {
                    DisposableEffect(Unit) {
                        mounts++
                        onDispose { disposals++ }
                    }
                    Text("历史")
                }
            }) { Text("聊天") }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(0, mounts); openDrawer() }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(1, mounts); closeDrawer() }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(1, mounts)
            assertEquals(0, disposals)
        }
    }

    @Test
    fun `首次右滑能够挂载抽屉且到达打开状态`() {
        var mounts = 0
        lateinit var drawerState: DrawerState
        composeRule.setContent {
            drawerState = rememberDrawerState(DrawerValue.Closed)
            DismissibleNavigationDrawer(
                drawerState = drawerState,
                modifier = Modifier.fillMaxSize().testTag("drawer-host"),
                drawerContent = {
                    DeferredDrawerContent(drawerState) {
                        DisposableEffect(Unit) {
                            mounts++
                            onDispose { }
                        }
                        Box(Modifier.width(320.dp).fillMaxHeight()) { Text("历史") }
                    }
                },
            ) { Text("聊天") }
        }
        composeRule.runOnIdle { assertEquals(0, mounts) }
        composeRule.onNodeWithTag("drawer-host").performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(1, mounts)
            assertEquals(DrawerValue.Open, drawerState.currentValue)
        }
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
