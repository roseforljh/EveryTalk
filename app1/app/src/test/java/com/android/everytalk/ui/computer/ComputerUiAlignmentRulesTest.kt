package com.android.everytalk.ui.computer

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 固定服务器页面与配置页面已经确认的关键视觉和键盘布局规则。 */
class ComputerUiAlignmentRulesTest {
    @Test
    fun `服务器页顶部保持左返回和右侧双按钮胶囊`() {
        val source = sourceFile("ui/screens/computer/ComputerScreen.kt")
            .readText(Charsets.UTF_8)
        val topBar = source.substringAfter(".floatingEdgeGradient(MaterialTheme.colorScheme.background, fromTop = true)")
            .substringBefore("if (showAddCard)")

        assertTrue(topBar.contains("iconRes = R.drawable.ic_arrow_back"))
        assertTrue(topBar.contains("modifier = Modifier.align(Alignment.CenterStart)"))
        assertTrue(topBar.contains(".width(topButtonSize * 2)"))
        assertTrue(topBar.contains("RoundedCornerShape(percent = 50)"))
        assertTrue(topBar.contains("R.drawable.ic_plus"))
        assertTrue(topBar.contains("R.drawable.ic_dots_horizontal"))
        assertTrue(topBar.contains("modifier = Modifier.align(Alignment.CenterEnd)"))
        assertTrue(topBar.contains("SettingsTabMenu("))
        assertFalse("服务器顶栏不应继续显示居中标题", topBar.contains("computer_screen_title"))
    }

    @Test
    fun `服务器三点菜单能够切回目标设置页签`() {
        val computerSource = sourceFile("ui/screens/computer/ComputerScreen.kt")
            .readText(Charsets.UTF_8)
        val settingsSource = sourceFile("ui/screens/settings/SettingsScreen.kt")
            .readText(Charsets.UTF_8)

        assertTrue(computerSource.contains("getBackStackEntry(Screen.SETTINGS_SCREEN)"))
        assertTrue(computerSource.contains("Screen.SETTINGS_TAB_REQUEST_KEY"))
        assertTrue(computerSource.contains("Screen.SETTINGS_IMPORT_EXPORT_REQUEST_KEY"))
        assertTrue(computerSource.contains("popBackStack(Screen.SETTINGS_SCREEN, inclusive = false)"))
        assertTrue(settingsSource.contains("getStateFlow(Screen.SETTINGS_TAB_REQUEST_KEY, -1)"))
        assertTrue(settingsSource.contains("currentTabIndex = requestedTabIndex"))
        assertTrue(settingsSource.contains("showImportExportDialog = true"))
    }

    @Test
    fun `添加服务器对话框复用配置页样式和输入法收缩规则`() {
        val source = sourceFile("ui/screens/computer/ComputerAddCard.kt")
            .readText(Charsets.UTF_8)

        assertTrue(source.contains("usePlatformDefaultWidth = false"))
        assertTrue(source.contains("decorFitsSystemWindows = false"))
        assertTrue(source.contains(".statusBarsPadding()"))
        assertTrue(source.contains(".navigationBarsPadding()"))
        assertTrue(source.contains(".imePadding()"))
        assertTrue(source.contains(".verticalScroll(rememberScrollState())"))
        assertTrue(source.contains(".padding(horizontal = 16.dp)"))
        assertTrue(source.contains(".padding(24.dp)"))
        assertTrue(source.contains("shape = AppDialogShape"))
        assertTrue(source.contains("shape = AppDialogTextFieldShape"))
        assertTrue(source.contains("SettingsFieldLabel(label)"))
        assertTrue(source.contains("OutlinedButton("))
    }

    @Test
    fun `服务器选择浮层使用自适应紧凑名称卡且隐藏连接信息`() {
        val source = sourceFile("ui/screens/MainScreen/chat/text/ui/ChatInputPanels.kt")
            .readText(Charsets.UTF_8)
            .substringAfter("internal fun ComputerSelectionCard(")
            .substringBefore("internal fun computerStatusLabelRes")

        assertFalse("选择浮层不应再显示标题", source.contains("agent_server_picker_title"))
        assertFalse("选择浮层不应再显示说明", source.contains("agent_server_picker_description"))
        assertTrue(source.contains("computers.chunked(3)"))
        assertTrue(source.contains(".wrapContentWidth()"))
        assertTrue(source.contains(".widthIn(min = 72.dp, max = maxItemWidth)"))
        assertTrue(source.contains("1 -> 184.dp"))
        assertTrue(source.contains("2 -> 146.dp"))
        assertTrue(source.contains("else -> 94.dp"))
        assertTrue(source.contains(".height(48.dp)"))
        assertTrue(source.contains("shape = RoundedCornerShape(percent = 50)"))
        assertTrue(source.contains("computer.displayName"))
        assertTrue(source.contains("computerCardAccentColorIndexes(computers)"))
        assertTrue(source.contains("ComputerCardAccentPalette["))
        assertTrue("按压反馈必须由圆角 Surface 裁剪", source.contains("Surface(\n                    onClick ="))
        assertFalse("点击修饰符会产生直角按压底色", source.contains(".height(48.dp)\n                        .clickable"))
        assertFalse("选择浮层不得显示用户名、IP 或端口", source.contains("computer.username"))
        assertFalse("选择浮层不得显示用户名、IP 或端口", source.contains("computer.host"))
        assertFalse("选择浮层不得显示用户名、IP 或端口", source.contains("computer.port"))
        assertFalse("紧凑卡片不再使用大号单选圆圈", source.contains("RadioButton("))
        assertFalse("未选中卡片不再显示冗余状态圆点", source.contains(".size(7.dp)"))
    }

    @Test
    fun `服务器功能统一覆盖默认紫色控件`() {
        val activitySource = sourceFile("statecontroller/activity/MainActivity.kt")
            .readText(Charsets.UTF_8)
        val styleSource = sourceFile("ui/screens/computer/ComputerComponents.kt")
            .readText(Charsets.UTF_8)
        val computerSources = listOf(
            "ui/screens/computer/ComputerScreen.kt",
            "ui/screens/computer/ComputerWorkspaceUi.kt",
            "ui/screens/computer/ComputerPreviewUi.kt",
        ).joinToString("\n") { sourceFile(it).readText(Charsets.UTF_8) }

        assertTrue(activitySource.contains("ComputerNeutralTheme"))
        assertTrue(styleSource.contains("colorScheme.copy("))
        assertTrue(styleSource.contains("primary = controlColor"))
        assertFalse(computerSources.contains("MaterialTheme.colorScheme.primary"))
    }

    @Test
    fun `服务器编辑弹窗统一输入框圆角和主题色`() {
        val dialogSources = listOf(
            "ui/screens/computer/ComputerAddCard.kt",
            "ui/screens/computer/ComputerWorkspaceUi.kt",
            "ui/screens/computer/ComputerPreviewUi.kt",
        ).map { sourceFile(it).readText(Charsets.UTF_8) }

        dialogSources.forEach { source ->
            assertTrue(source.contains("AppDialogTextFieldShape"))
            assertTrue(source.contains("appDialogTextFieldColors()"))
        }
        dialogSources.drop(1).forEach { source ->
            assertTrue(source.contains("containerColor = appDialogContainerColor()"))
        }
        val detailSource = sourceFile("ui/screens/computer/ComputerDetailScreen.kt")
            .readText(Charsets.UTF_8)
        assertTrue(detailSource.contains("ComputerAddCard("))
        assertTrue(detailSource.contains("containerColor = appDialogContainerColor()"))
    }

    @Test
    fun `服务器入口固定跟在第三项MCP之后`() {
        val source = sourceFile("ui/screens/settings/SettingsScreen.kt")
            .readText(Charsets.UTF_8)
            .substringAfter("private fun SettingsTabMenu(")

        val tabsIndex = source.indexOf("tabs.forEachIndexed")
        val serverIndex = source.indexOf("R.string.settings_servers")
        val importExportIndex = source.indexOf("R.string.settings_import_export")

        assertTrue("设置页签必须按声明顺序绘制", tabsIndex >= 0)
        assertTrue("服务器入口必须紧跟设置页签", serverIndex > tabsIndex)
        assertTrue("导入导出入口必须放在服务器之后", importExportIndex > serverIndex)
        assertFalse("菜单不得再按文字长度重排", source.contains("sortedBy { it.second.length }"))
    }

    @Test
    fun `服务器列表刷新由ViewModel生命周期执行`() {
        val screenSource = sourceFile("ui/screens/computer/ComputerScreen.kt")
            .readText(Charsets.UTF_8)
        val actionSource = sourceFile("statecontroller/viewmodel/AppViewModelActions.kt")
            .readText(Charsets.UTF_8)
        val repositorySource = sourceFile("data/computer/ComputerRepository.kt")
            .readText(Charsets.UTF_8)

        assertTrue(screenSource.contains("onRefresh = { viewModel.refreshComputerFromList(computer.id) }"))
        assertTrue(actionSource.contains("internal fun AppViewModel.refreshComputerFromList"))
        assertTrue(actionSource.substringAfter("internal fun AppViewModel.refreshComputerFromList").contains("viewModelScope.launch(Dispatchers.IO)"))
        assertTrue(repositorySource.contains("withContext(NonCancellable)"))
        assertTrue(repositorySource.contains("recoverInterruptedComputerOperations(COMPUTER_BOOTSTRAP_VERSION)"))
    }

    @Test
    fun `服务器列表顶部和系统返回都固定回聊天首页`() {
        val source = sourceFile("ui/screens/computer/ComputerScreen.kt")
            .readText(Charsets.UTF_8)

        assertTrue(source.contains("fun returnToChatHome()"))
        assertTrue(source.contains("BackHandler(onBack = ::returnToChatHome)"))
        assertTrue(source.contains("onClick = ::returnToChatHome"))
        assertTrue(source.contains("popBackStack(Screen.CHAT_SCREEN, inclusive = false)"))
    }

    @Test
    fun `服务器卡片强调色按随机ID分配并在当前列表避让`() {
        val screenSource = sourceFile("ui/screens/computer/ComputerScreen.kt")
            .readText(Charsets.UTF_8)
        val componentSource = sourceFile("ui/screens/computer/ComputerComponents.kt")
            .readText(Charsets.UTF_8)

        assertTrue(screenSource.contains("computerCardAccentColors(computers)"))
        assertTrue(screenSource.contains("accentColor = cardAccentColors.getValue(computer.id)"))
        assertTrue(componentSource.contains("internal fun computerCardAccentColors"))
        assertTrue(componentSource.contains("usedColorIndexes"))
        assertTrue(componentSource.contains("accentColor: Color"))
        assertFalse("服务器卡片不得继续共用 Agent 强调色", componentSource.contains("ChatAgentColor"))
    }

    @Test
    fun `修复Container显示真实阶段且只保留一个加载圈`() {
        val dialogSource = sourceFile("ui/screens/computer/ComputerDetailScreen.kt")
            .readText(Charsets.UTF_8)
            .substringAfter("private fun ComputerContainerRepairDialog(")
            .substringBefore("private fun ComputerReplacementHostKeyDialog(")

        assertTrue("修复过程必须显示真实阶段", dialogSource.contains("setupStage.labelRes()"))
        assertTrue("修复过程必须显示阶段说明", dialogSource.contains("setupStage.detailRes()"))
        assertTrue("修复过程必须显示加载时间", dialogSource.contains("EveryTalkTimedLoadingStatus("))
        assertTrue("修复过程上方不显示加载圈", dialogSource.contains("showIndicator = false"))
        assertTrue("按钮文字必须保留布局宽度", dialogSource.contains(".alpha(if (isBusy) 0f else 1f)"))
        assertTrue("修复按钮忙碌时显示加载圈", dialogSource.contains("CircularProgressIndicator("))
        assertFalse("修复按钮不得显示弹跳文案", dialogSource.contains("ComputerWorkingLabel"))
    }

    @Test
    fun `修复弹窗运行时背景操作卡不重复显示加载圈`() {
        val source = sourceFile("ui/screens/computer/ComputerDetailScreen.kt")
            .readText(Charsets.UTF_8)
            .substringAfter("private fun ComputerMoreSettingsCard(")
            .substringBefore("private fun ComputerSettingsGroup(")

        assertTrue(source.contains("busyAction != null && busyAction != \"repair\""))
        assertTrue(source.contains("CircularProgressIndicator("))
    }

    @Test
    fun `服务器详情默认只展示主要信息且低频操作折叠`() {
        val detailSource = sourceFile("ui/screens/computer/ComputerDetailScreen.kt")
            .readText(Charsets.UTF_8)
        val workspaceSource = sourceFile("ui/screens/computer/ComputerWorkspaceUi.kt")
            .readText(Charsets.UTF_8)

        assertTrue(detailSource.contains("ComputerAgentUseCard(computer)"))
        assertTrue(detailSource.contains("var moreSettingsExpanded"))
        assertTrue(detailSource.contains("ComputerMoreSettingsCard("))
        assertTrue(detailSource.contains("if (expanded)"))
        assertTrue(detailSource.contains("computer_more_settings"))
        assertTrue(detailSource.contains("ComputerPermissionSettingsCard("))
        assertTrue(detailSource.contains("ComputerMaintenanceCard("))
        assertTrue(detailSource.contains("computer_workspace_container_count"))
        assertTrue(detailSource.contains("val containerCount = remember(workspaces)"))
        assertTrue(detailSource.contains("workspace.runMode == ComputerRunMode.CONTAINER"))
        val moreSettingsSource = detailSource.substringAfter("private fun ComputerMoreSettingsCard(")
            .substringBefore("private fun ComputerSettingsGroup(")
        assertTrue(moreSettingsSource.contains("computer_settings_security"))
        assertFalse("常用权限不得继续藏在更多设置", moreSettingsSource.contains("ComputerPermissionModeSelector("))
        assertFalse("维护操作不得继续藏在更多设置", moreSettingsSource.contains("computer_settings_maintenance"))
        assertTrue(detailSource.indexOf("ComputerWorkspaceCard(") < detailSource.indexOf("ComputerMoreSettingsCard(", detailSource.indexOf("ComputerWorkspaceCard(")))
        assertTrue(workspaceSource.contains("var expanded by remember(workspace.id)"))
        assertTrue(workspaceSource.contains("text = displayName"))
        assertTrue(workspaceSource.contains("if (expanded)"))
    }

    @Test
    fun `服务器编辑表单按基本信息和登录信息分组`() {
        val source = sourceFile("ui/screens/computer/ComputerAddCard.kt")
            .readText(Charsets.UTF_8)

        assertTrue(source.contains("computer_form_basic"))
        assertTrue(source.contains("computer_form_login"))
        assertTrue(source.contains("ComputerFormSectionTitle("))
        assertTrue(source.contains("if (!keepCredentialHint)"))
    }

    @Test
    fun `服务器详情复用添加表单编辑且修复不再重复输入密码`() {
        val source = sourceFile("ui/screens/computer/ComputerDetailScreen.kt")
            .readText(Charsets.UTF_8)
        val repairDialog = source.substringAfter("private fun ComputerContainerRepairDialog(")
            .substringBefore("private fun ComputerReplacementHostKeyDialog(")

        assertTrue(source.contains("ComputerAddCard("))
        assertTrue(source.contains("computer_action_edit"))
        assertTrue(source.contains("probeUpdatedComputerHostKey"))
        assertTrue(source.contains("allowBusyDismiss = true"))
        assertFalse(repairDialog.contains("OutlinedTextField("))
        assertFalse(repairDialog.contains("sudoPassword"))
    }

    @Test
    fun `修复任务允许停止并在返回时断开SSH`() {
        val detailSource = sourceFile("ui/screens/computer/ComputerDetailScreen.kt")
            .readText(Charsets.UTF_8)
        val repositorySource = sourceFile("data/computer/ComputerRepository.kt")
            .readText(Charsets.UTF_8)

        assertTrue(detailSource.contains("BackHandler(onBack = ::navigateBack)"))
        assertTrue(detailSource.contains("viewModel.cancelComputerOperation(computerId)"))
        assertTrue(detailSource.contains("R.string.action_stop"))
        assertTrue(detailSource.contains("onProgress = { stage ->"))
        assertTrue(repositorySource.contains("catch (error: CancellationException)"))
        assertTrue(repositorySource.contains("ComputerStatus.CONFIGURATION_REQUIRED.name"))
    }

    @Test
    fun `首次添加逐阶段展示SSH与Container配置进度`() {
        val screenSource = sourceFile("ui/screens/computer/ComputerScreen.kt")
            .readText(Charsets.UTF_8)
        val dialogSource = sourceFile("ui/screens/computer/ComputerAddCard.kt")
            .readText(Charsets.UTF_8)
        val provisionerSource = sourceFile("data/computer/ComputerProvisioner.kt")
            .readText(Charsets.UTF_8)

        assertTrue(screenSource.contains("ComputerSetupStage.READING_HOST_KEY"))
        assertTrue(screenSource.contains("ComputerSetupStage.INSPECTING_VPS"))
        assertTrue(screenSource.contains("ComputerSetupStage.BUILDING_IMAGE"))
        assertTrue(screenSource.contains("progressDetailText"))
        assertTrue(provisionerSource.contains("onProgress(ComputerSetupStage.PREPARING_DOCKER)"))
        assertTrue(provisionerSource.contains("onProgress(ComputerSetupStage.BUILDING_IMAGE)"))
        assertTrue(provisionerSource.contains("onProgress(ComputerSetupStage.CONFIGURING_NETWORK)"))
        assertTrue(dialogSource.contains("EveryTalkTimedLoadingStatus("))
        assertTrue(dialogSource.contains("showIndicator = false"))
        assertTrue("添加按钮必须保留加载圈", dialogSource.contains("CircularProgressIndicator("))
        assertFalse("添加按钮不得显示弹跳文案", dialogSource.contains("ComputerWorkingLabel"))
    }

    @Test
    fun `主机命令确认卡固定在输入框上方并只提供本次允许和拒绝`() {
        val inputSource = sourceFile("ui/screens/MainScreen/chat/text/ui/ChatInputArea.kt")
            .readText(Charsets.UTF_8)
        val panelSource = sourceFile("ui/screens/MainScreen/chat/text/ui/ChatInputPanels.kt")
            .readText(Charsets.UTF_8)
        val chatScreenSource = sourceFile("ui/screens/MainScreen/chat/ChatScreen.kt")
            .readText(Charsets.UTF_8)
        val confirmationIndex = inputSource.indexOf("ComputerHostCommandConfirmationCard(")
        val inputFieldIndex = inputSource.indexOf("BasicTextField(", confirmationIndex)

        assertTrue(confirmationIndex >= 0)
        assertTrue(inputFieldIndex > confirmationIndex)
        assertTrue(chatScreenSource.contains("suppressScrollButtonForHostCard"))
        assertTrue(chatScreenSource.contains("suppressed = suppressScrollButtonForHostCard"))
        assertTrue(chatScreenSource.contains("wasAtBottomBeforeHostCommand"))
        assertTrue(chatScreenSource.contains("snapshotFlow { !listState.canScrollForward }"))
        assertTrue(chatScreenSource.contains("scrollStateManager.pinToRealBottomUntilUserScroll()"))
        assertTrue(panelSource.contains("agent_host_command_allow_once"))
        assertTrue(panelSource.contains("agent_host_command_reject"))
        assertFalse(panelSource.contains("agent_host_command_always"))
        assertTrue(panelSource.contains("AppFloatingCardScaffold("))
        assertTrue(panelSource.contains("header = {"))
        assertTrue(panelSource.contains("content = {"))
        assertTrue(panelSource.contains("footer = {"))
        val footerSource = panelSource.substringAfter("footer = {")
            .substringBefore("internal fun computerStatusLabelRes")
        assertTrue(footerSource.contains(".fillMaxWidth()"))
        assertTrue(panelSource.contains("currentRequest.requestId"))
    }

    @Test
    fun `服务器对话框按钮统一使用完整圆角按压区域`() {
        val addSource = sourceFile("ui/screens/computer/ComputerAddCard.kt")
            .readText(Charsets.UTF_8)
        val previewSource = sourceFile("ui/screens/computer/ComputerPreviewUi.kt")
            .readText(Charsets.UTF_8)
        val hostKeyDialog = addSource.substringAfter("internal fun ComputerHostKeyDialog(")
        val previewCreateDialog = previewSource.substringAfter("internal fun ComputerPreviewCreateDialog(")
            .substringBefore("internal fun ComputerPreviewList(")
        val publicPreviewDialog = previewSource.substringAfter("fun ComputerPublicPreviewConfirmationDialog(")

        listOf(hostKeyDialog, previewCreateDialog, publicPreviewDialog).forEach { dialogSource ->
            assertTrue(dialogSource.contains("modifier = Modifier.height(48.dp)"))
            assertTrue(dialogSource.contains("shape = AppDialogButtonShape"))
        }
    }

    @Test
    fun `详情页主要交互使用完整圆角按压区域`() {
        val detailSource = sourceFile("ui/screens/computer/ComputerDetailScreen.kt")
            .readText(Charsets.UTF_8)
        val workspaceSource = sourceFile("ui/screens/computer/ComputerWorkspaceUi.kt")
            .readText(Charsets.UTF_8)

        val permissionSource = detailSource.substringAfter("private fun ComputerPermissionModeSelector(")
            .substringBefore("private fun ComputerPermissionMode.titleResource")
        assertTrue(permissionSource.contains("Surface("))
        assertTrue(permissionSource.contains("shape = shape"))
        assertFalse(permissionSource.contains(".clickable("))

        val moreSettingsSource = detailSource.substringAfter("private fun ComputerMoreSettingsCard(")
            .substringBefore("private fun ComputerSettingsGroup(")
        assertTrue(moreSettingsSource.contains("Surface("))
        assertTrue(moreSettingsSource.contains("shape = RoundedCornerShape(22.dp)"))

        val workspaceHeader = workspaceSource.substringAfter("Card(\n                onClick = { expanded = !expanded }")
            .substringBefore("if (expanded)")
        assertTrue(workspaceHeader.contains("shape = RoundedCornerShape(18.dp)"))
        assertFalse(workspaceHeader.contains(".clickable("))
    }

    @Test
    fun `服务器详情提供三档权限并在完全批准前警告`() {
        val source = sourceFile("ui/screens/computer/ComputerDetailScreen.kt").readText(Charsets.UTF_8)

        assertTrue(source.contains("ComputerPermissionModeSelector("))
        assertTrue(source.contains("ComputerPermissionMode.MANUAL"))
        assertTrue(source.contains("ComputerPermissionMode.SMART"))
        assertTrue(source.contains("ComputerPermissionMode.FULL"))
        assertTrue(source.contains("ComputerFullApprovalWarningDialog("))
        assertTrue(source.contains("computer_permission_full_warning_body"))
        val warningDialog = source.substringAfter("private fun ComputerFullApprovalWarningDialog(")
            .substringBefore("private fun ComputerReplacementHostKeyDialog(")
        assertTrue(warningDialog.contains("shape = AppDialogButtonShape"))
        assertTrue(warningDialog.contains("ButtonDefaults.textButtonColors("))
        assertTrue(warningDialog.contains("contentColor = appDialogContentColor()"))
    }

    private fun sourceFile(relativePath: String): File {
        val roots = listOf(
            File("src/main/java/com/android/everytalk"),
            File("app/src/main/java/com/android/everytalk"),
            File("app1/app/src/main/java/com/android/everytalk"),
        )
        val root = requireNotNull(roots.firstOrNull(File::isDirectory)) { "找不到主源码目录" }
        return File(root, relativePath)
    }
}
