package com.android.everytalk.ui.screens.settings

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.navigation.Screen
import com.android.everytalk.statecontroller.AppViewModel
import com.android.everytalk.statecontroller.viewmodel.SettingsExportRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** 使用真实菜单、弹窗、导航和 Activity Result 注册器，仅替换存储及系统文件选择器。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "en")
class SettingsImportExportInteractionTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** 拦截系统文件选择，并允许把取消结果交回真实的 Compose launcher。 */
    private class FilePickerRegistry : ActivityResultRegistry() {
        var launchCount = 0
        var requestCode = 0
        var action: String? = null

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            this.requestCode = requestCode
            action = contract.createIntent(org.robolectric.RuntimeEnvironment.getApplication(), input).action
            launchCount++
        }

        fun cancel() {
            assertTrue(dispatchResult(requestCode, Activity.RESULT_CANCELED, null))
        }

        fun select(file: File) {
            assertTrue(dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(Uri.fromFile(file))))
        }
    }

    @Test
    fun `所有菜单打开关闭和取消文件选择都保留原页面与导航栈`() {
        val model = mockk<AppViewModel>(relaxed = true)
        every { model.getApplication<Application>() } returns org.robolectric.RuntimeEnvironment.getApplication()
        val settingsController = model.settingsController
        val requests = MutableSharedFlow<SettingsExportRequest>(extraBufferCapacity = 1)
        val importedText = AtomicReference<String?>(null)
        every { settingsController.importSettings(any()) } answers { importedText.set(firstArg()) }
        var pendingExport: SettingsExportRequest? = null
        every { model.settingsExportRequest } returns requests
        every { model.pendingSettingsExport } answers { pendingExport }
        every { model.pendingSettingsExport = any() } answers { pendingExport = firstArg() }
        every { model.apiConfigs } returns MutableStateFlow(listOf(
            ApiConfig("https://example.com", "test", "model", "OpenAI", name = "测试配置")
        ))
        every { model.imageGenApiConfigs } returns MutableStateFlow(emptyList())
        every { model.historicalConversations } returns MutableStateFlow(emptyList())
        every { model.imageGenerationHistoricalConversations } returns MutableStateFlow(emptyList())

        val picker = FilePickerRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = picker
        }
        lateinit var navController: NavHostController
        val pages = listOf("platforms", "search", "mcp", "computers", "skills", "image")
        composeRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                MaterialTheme {
                    navController = rememberNavController()
                    var visible by rememberSaveable { mutableStateOf(false) }
                    Box(Modifier.fillMaxSize()) {
                        NavHost(navController, startDestination = "home") {
                            composable("home") { Text("Home") }
                            pages.forEachIndexed { index, page ->
                                composable(page) {
                                    var menu by remember { mutableStateOf(false) }
                                    Button(onClick = { menu = true }, modifier = Modifier.testTag("menu")) {
                                        Text(page)
                                    }
                                    SettingsTabMenu(
                                        expanded = menu,
                                        tabs = if (page == "image") emptyList() else listOf("Platforms", "Search", "MCP"),
                                        currentTabIndex = if (index < 3) index else -1,
                                        onTabSelected = { error("导入导出不得切换页签") },
                                        onImportExport = { visible = true },
                                        onOpenComputers = { error("导入导出不得导航") },
                                        onOpenSkills = { error("导入导出不得导航") },
                                        onOpenAccount = { error("导入导出不得导航") },
                                        isComputerSelected = page == "computers",
                                        isSkillSelected = page == "skills",
                                        onDismiss = { menu = false },
                                    )
                                }
                            }
                        }
                        SettingsImportExportHost(model, visible) { visible = false }
                    }
                }
            }
        }

        pages.flatMap { page -> listOf(page to false, page to true) }.forEach { (page, hasSettingsEntry) ->
            // 同时覆盖聊天直接进入，以及已有设置页历史记录两种导航栈。
            composeRule.runOnIdle {
                if (hasSettingsEntry) navController.navigate("platforms") { popUpTo("home") }
                navController.navigate(page) { if (!hasSettingsEntry) popUpTo("home") }
            }
            composeRule.waitForIdle()
            val originalStack = navController.currentBackStack.value.map { it.id }
            fun assertOriginalPage() {
                composeRule.runOnIdle {
                    assertEquals(page, navController.currentDestination?.route)
                    assertEquals(originalStack, navController.currentBackStack.value.map { it.id })
                }
            }
            fun openDialog() {
                composeRule.onNodeWithTag("menu").performClick()
                composeRule.onNodeWithText("Import / Export").performClick()
                composeRule.onNodeWithText("Import").assertIsDisplayed()
                assertOriginalPage()
            }

            openDialog()
            composeRule.runOnIdle { ShadowDialog.getLatestDialog().onBackPressed() }
            composeRule.onNodeWithText("Import").assertDoesNotExist()
            assertOriginalPage()

            openDialog()
            composeRule.onNodeWithText("Import").performClick()
            composeRule.runOnIdle {
                assertEquals(Intent.ACTION_GET_CONTENT, picker.action)
                picker.cancel()
            }
            composeRule.onNodeWithText("Import").assertDoesNotExist()
            assertOriginalPage()

            openDialog()
            composeRule.onNodeWithText("Export").performClick()
            verify { settingsController.exportSettings(false) }
            // 模拟备份在弹窗关闭之后才生成，接收者仍必须存在且只打开一次文件选择器。
            val file = File.createTempFile("settings-navigation-", ".json").apply { writeText("{}") }
            try {
                val launchesBeforeExport = picker.launchCount
                composeRule.runOnIdle { assertTrue(requests.tryEmit(SettingsExportRequest("backup.json", file))) }
                composeRule.waitForIdle()
                composeRule.runOnIdle {
                    assertEquals(launchesBeforeExport + 1, picker.launchCount)
                    assertEquals(Intent.ACTION_CREATE_DOCUMENT, picker.action)
                    picker.cancel()
                    assertNull(pendingExport)
                    assertFalse(file.exists())
                }
                assertOriginalPage()
            } finally {
                file.delete()
            }
        }

        // 实际写入文件，验证覆盖旧内容、清理缓存，以及成功后仍留在原页面。
        val originalStack = navController.currentBackStack.value.map { it.id }
        val source = File.createTempFile("settings-source-", ".json").apply { writeText("{}") }
        val target = File.createTempFile("settings-target-", ".json").apply { writeText("old-long-content") }
        try {
            composeRule.runOnIdle { assertTrue(requests.tryEmit(SettingsExportRequest("backup.json", source))) }
            composeRule.waitForIdle()
            composeRule.runOnIdle { picker.select(target) }
            composeRule.waitUntil(timeoutMillis = 5_000) { !source.exists() }
            assertEquals("{}", target.readText())
            assertNull(pendingExport)
            composeRule.runOnIdle {
                assertEquals(originalStack, navController.currentBackStack.value.map { it.id })
            }

            composeRule.onNodeWithTag("menu").performClick()
            composeRule.onNodeWithText("Import / Export").performClick()
            composeRule.onNodeWithText("Import").performClick()
            composeRule.runOnIdle { picker.select(target) }
            // 这里只验证选择结果被完整交给已有导入控制器，不写入真实配置或历史记录。
            composeRule.waitUntil(timeoutMillis = 5_000) { importedText.get() == "{}" }
            verify(exactly = 1) { settingsController.importSettings("{}") }
            composeRule.runOnIdle {
                assertEquals(originalStack, navController.currentBackStack.value.map { it.id })
            }
        } finally {
            source.delete()
            target.delete()
        }
    }

    @Test
    fun `设置菜单路由所有双向组合使用同一动画且不影响外部页面`() {
        val routes = listOf(Screen.SETTINGS_SCREEN, Screen.COMPUTER_SCREEN, Screen.SKILL_SCREEN, Screen.ACCOUNT_SCREEN)
        routes.forEach { from ->
            routes.forEach { to -> assertTrue(isSettingsMenuTransition(from, to)) }
            listOf(null, Screen.CHAT_SCREEN, Screen.COMPUTER_DETAIL_SCREEN, Screen.SKILL_DETAIL_SCREEN).forEach { other ->
                assertFalse(isSettingsMenuTransition(from, other))
                assertFalse(isSettingsMenuTransition(other, from))
            }
        }
    }
}
