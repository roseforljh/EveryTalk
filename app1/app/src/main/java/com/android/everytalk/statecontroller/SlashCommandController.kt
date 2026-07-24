package com.android.everytalk.statecontroller

import android.app.Application
import android.util.Log
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.GenerationConfig
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.DataClass.ThinkingConfig
import com.android.everytalk.data.network.WebSearchSupport
import com.android.everytalk.data.network.openclaw.ModelsCatalogQueryResult
import com.android.everytalk.data.network.openclaw.OpenClawRuntimeStatusService
import com.android.everytalk.ui.screens.viewmodel.HistoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class SlashCommandController(
    private val stateHolder: ViewModelStateHolder,
    private val scope: CoroutineScope,
    private val historyManager: HistoryManager,
    private val openClawRuntimeStatusService: OpenClawRuntimeStatusService,
    private val application: Application,
    private val triggerScrollToBottom: () -> Unit,
    private val saveCurrentChat: () -> Unit,
    private val startNewChat: () -> Unit,
) {
    fun handleIfNeeded(input: String): Boolean {
        val command = parseSlashCommand(input) ?: return false
        val config = stateHolder._selectedApiConfig.value ?: return false
        if (!shouldHandleOpenClawSlashCommandLocally(input, config.provider, config.channel)) {
            return false
        }
        addLocalSlashUserMessage(input)
        handleSlashCommand(command)
        return true
    }

    private fun handleSlashCommand(command: SlashCommand) {
        when (command) {
            SlashCommand.Help -> addLocalSlashReply(buildSlashHelpMessage(), command)
            is SlashCommand.Model -> handleModelCommand(command)
            is SlashCommand.Models -> handleModelsCommand(command)
            SlashCommand.New -> startNewChatWithSystemMessage()
            SlashCommand.Reset -> resetConversationOverrides()
            is SlashCommand.Reasoning -> updateReasoningState(command.enabled)
        }
    }

    private fun addLocalSlashUserMessage(text: String) {
        stateHolder.addMessage(Message(text = text, sender = Sender.User))
        triggerScrollToBottom()
        saveCurrentChat()
    }

    private fun addLocalSlashReply(text: String, command: SlashCommand) {
        addLocalMessage(text = text, sender = localSlashReplySender(command))
    }

    private fun addLocalSlashReplyWithLoading(command: SlashCommand, loader: suspend () -> String) {
        val sender = localSlashReplySender(command)
        if (sender != Sender.AI) {
            scope.launch {
                addLocalMessage(loader(), sender)
            }
            return
        }

        val placeholderId = "slash_ai_§{java.util.UUID.randomUUID()}"
        stateHolder.addMessage(
            Message(
                id = placeholderId,
                text = "",
                sender = Sender.AI,
                contentStarted = false
            )
        )
        stateHolder.startLocalSlashLoading(placeholderId)
        triggerScrollToBottom()
        saveCurrentChat()

        scope.launch {
            val finalText = runCatchingPreservingCancellation { loader() }
                .getOrElse { it.message ?: "命令执行失败" }
            Log.d("SlashCommand", "addLocalSlashReplyWithLoading finalTextChars=§{finalText.length}")
            stateHolder.finishLocalSlashLoading(placeholderId, finalText)
            triggerScrollToBottom()
            saveCurrentChat()
        }
    }

    private fun addSystemMessage(text: String) {
        addLocalMessage(text = text, sender = Sender.System)
    }

    private fun addLocalMessage(text: String, sender: Sender) {
        stateHolder.addMessage(Message(text = text, sender = sender))
        triggerScrollToBottom()
        saveCurrentChat()
    }

    private fun startNewChatWithSystemMessage() {
        startNewChat()
        scope.launch {
            withContext(Dispatchers.Main.immediate) {
                addSystemMessage("已开始新聊天。")
            }
        }
    }

    private fun buildSlashHelpMessage(): String {
        return listOf(
            "支持的本地命令：",
            "/help - 显示命令帮助",
            "/model - 查询 OpenClaw 后端当前 session 状态",
            "/models - 查询 OpenClaw 模型目录",
            "/new - 开始新聊天",
            "/reset - 重置当前会话参数覆盖",
            "/reasoning on - 开启推理输出",
            "/reasoning off - 关闭推理输出"
        ).joinToString(separator = "\n")
    }

    private fun handleModelsCommand(command: SlashCommand.Models) {
        Log.d("SlashCommand", "handleModelsCommand")
        val config = stateHolder._selectedApiConfig.value
        if (config == null) {
            addSystemMessage("当前未选择 OpenClaw 配置。")
            return
        }

        val isOpenClaw = config.channel.contains("openclaw", ignoreCase = true) ||
            config.provider.contains("openclaw", ignoreCase = true) ||
            config.model.contains("openclaw", ignoreCase = true)
        if (!isOpenClaw) {
            addSystemMessage("当前会话不是 OpenClaw 配置，无法获取 provider 列表预览。")
            return
        }

        val request = ChatRequest(
            messages = listOf(SimpleTextApiMessage(role = "user", content = "/models §{command.args}".trim())),
            provider = config.provider,
            channel = config.channel,
            apiAddress = config.address,
            apiKey = config.key,
            model = config.model,
            deviceId = com.android.everytalk.util.DeviceIdManager.getDeviceId(application),
            conversationId = stateHolder._currentConversationId.value,
            openClawSessionId = stateHolder._currentOpenClawSessionId.value
        )

        addLocalSlashReplyWithLoading(command) {
            val result = withContext(Dispatchers.IO) {
                openClawRuntimeStatusService.queryModelsCatalog(request, command.args.takeIf { it.isNotBlank() })
            }
            formatModelsCommandMessage(result, command.args)
        }
    }

    private fun handleModelCommand(command: SlashCommand.Model) {
        Log.d("SlashCommand", "handleModelCommand -> proxy /model status")
        val config = stateHolder._selectedApiConfig.value
        if (config == null) {
            addSystemMessage("当前未选择 OpenClaw 配置。")
            return
        }

        val isOpenClaw = config.channel.contains("openclaw", ignoreCase = true) ||
            config.provider.contains("openclaw", ignoreCase = true) ||
            config.model.contains("openclaw", ignoreCase = true)
        if (!isOpenClaw) {
            addSystemMessage("当前会话不是 OpenClaw 配置，无法查询后端真实模型状态。")
            return
        }

        val sessionKey = com.android.everytalk.data.network.openclaw.OpenClawGatewayClient.resolveSessionKey(
            ChatRequest(
                messages = emptyList(),
                provider = config.provider,
                channel = config.channel,
                apiAddress = config.address,
                apiKey = config.key,
                model = config.model,
                deviceId = com.android.everytalk.util.DeviceIdManager.getDeviceId(application),
                conversationId = stateHolder._currentConversationId.value,
                openClawSessionId = stateHolder._currentOpenClawSessionId.value
            )
        )

        addLocalSlashReplyWithLoading(command) {
            runCatchingPreservingCancellation {
                val proxyRequest = ChatRequest(
                    messages = listOf(SimpleTextApiMessage(role = "user", content = "/model status")),
                    provider = config.provider,
                    channel = config.channel,
                    apiAddress = config.address,
                    apiKey = config.key,
                    model = config.model,
                    deviceId = com.android.everytalk.util.DeviceIdManager.getDeviceId(application),
                    conversationId = stateHolder._currentConversationId.value,
                    openClawSessionId = stateHolder._currentOpenClawSessionId.value
                )
                Log.d("SlashCommand", "proxying backend command: /model status, sessionKey=§{sessionKey}")
                openClawRuntimeStatusService.proxyModelStatusCommand(proxyRequest)
            }.fold(
                onSuccess = { backendReply ->
                    Log.d("SlashCommand", "backend /model status result=§{backendReply}")
                    formatModelCommandMessage(backendReply = backendReply)
                },
                onFailure = { error ->
                    val message = error.message ?: "未知错误"
                    Log.d("SlashCommand", "backend /model status result=§{message}")
                    formatModelCommandFailureMessage(message)
                }
            )
        }
    }

    private fun resetConversationOverrides() {
        val config = stateHolder._selectedApiConfig.value
        if (config == null) {
            addSystemMessage("当前未选择 API 配置，无法重置会话参数。")
            return
        }

        persistConversationConfig(
            GenerationConfig(
                temperature = config.temperature,
                topP = config.topP,
                maxOutputTokens = null,
                thinkingConfig = null
            )
        )
        addLocalSlashReply("已重置当前会话参数覆盖，恢复为当前配置默认值。", SlashCommand.Reset)
    }

    private fun updateReasoningState(enabled: Boolean) {
        val currentConfig = stateHolder._selectedApiConfig.value
        if (currentConfig == null) {
            addSystemMessage("当前未选择 API 配置，无法更新 reasoning 状态。")
            return
        }

        val baseConfig = stateHolder.getCurrentConversationConfig() ?: GenerationConfig(
            temperature = currentConfig.temperature,
            topP = currentConfig.topP,
            maxOutputTokens = null,
            thinkingConfig = null
        )
        val thinkingConfig = if (enabled) {
            ThinkingConfig(
                includeThoughts = true,
                thinkingBudget = if (WebSearchSupport.isGeminiModel(currentConfig)) {
                    defaultReasoningBudgetForModel(currentConfig.model)
                } else {
                    null
                }
            )
        } else {
            ThinkingConfig(includeThoughts = false, thinkingBudget = 0)
        }
        persistConversationConfig(baseConfig.copy(thinkingConfig = thinkingConfig))
        addLocalSlashReply(
            if (enabled) "已开启 reasoning。" else "已关闭 reasoning。",
            SlashCommand.Reasoning(enabled)
        )
    }

    private fun persistConversationConfig(config: GenerationConfig) {
        stateHolder.updateCurrentConversationConfig(config)
        scope.launch(Dispatchers.IO) {
            try {
                historyManager.saveCurrentChatToHistoryIfNeeded(
                    forceSave = true,
                    isImageGeneration = false
                )
            } catch (_: Exception) {
            }
        }
    }
}

