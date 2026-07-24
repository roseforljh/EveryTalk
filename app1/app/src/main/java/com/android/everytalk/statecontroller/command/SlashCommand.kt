package com.android.everytalk.statecontroller

import android.util.Log
import com.android.everytalk.data.DataClass.Sender

sealed interface SlashCommand {
    data class Model(val args: String = "") : SlashCommand
    data class Models(val args: String = "") : SlashCommand
    data object Help : SlashCommand
    data object New : SlashCommand
    data object Reset : SlashCommand
    data class Reasoning(val enabled: Boolean) : SlashCommand
}

fun parseSlashCommand(input: String): SlashCommand? {
    runCatching { Log.d("SlashCommand", "inputChars=${input.length}") }
    val normalized = input.trim()
    if (!normalized.startsWith("/")) return null
    val commandToken = normalized.substringBefore(" ").lowercase()
    val args = normalized.substringAfter(" ", "").trim()
    runCatching { Log.d("SlashCommand", "commandToken=$commandToken argsChars=${args.length}") }

    val resolved = when (commandToken) {
        "/model" -> SlashCommand.Model(args)
        "/models" -> SlashCommand.Models(args)
        "/help" -> SlashCommand.Help
        "/new" -> SlashCommand.New
        "/reset" -> SlashCommand.Reset
        "/reasoning" -> when (args.lowercase()) {
            "on" -> SlashCommand.Reasoning(enabled = true)
            "off" -> SlashCommand.Reasoning(enabled = false)
            else -> null
        }
        else -> null
    }
    runCatching { Log.d("SlashCommand", "resolvedCommand=${resolved?.let { it::class.simpleName }}") }
    return resolved
}

fun shouldHandleOpenClawSlashCommandLocally(
    input: String,
    provider: String,
    channel: String?
): Boolean {
    if (parseSlashCommand(input) == null) return false
    val normalizedProvider = provider.lowercase().trim()
    val normalizedChannel = channel?.lowercase()?.trim().orEmpty()
    return normalizedProvider.contains("openclaw") || normalizedChannel.contains("openclaw")
}

fun localSlashReplySender(command: SlashCommand): Sender {
    return when (command) {
        is SlashCommand.Model,
        is SlashCommand.Models -> Sender.AI
        else -> Sender.System
    }
}

fun formatModelsCommandMessage(
    result: com.android.everytalk.data.network.openclaw.ModelsCatalogQueryResult,
    providerArg: String = ""
): String {
    if (!result.ok) {
        return buildString {
            appendLine("无法获取 OpenClaw 模型目录")
            appendLine()
            appendLine("原因：")
            append(result.errorMessage ?: "models.list 查询失败")
        }.trimEnd()
    }

    val groups = result.providerGroups
    val normalizedProviderArg = providerArg.trim()
    if (normalizedProviderArg.isNotBlank()) {
        val target = groups.firstOrNull { it.provider.equals(normalizedProviderArg, ignoreCase = true) }
            ?: return "未找到 provider: $normalizedProviderArg"
        return buildString {
            appendLine("${target.provider} 可用模型")
            target.models.forEach { appendLine("- $it") }
        }.trimEnd()
    }

    return buildString {
        appendLine("可用模型提供商")
        groups.forEach { group ->
            appendLine("- ${group.provider} (${group.models.size})")
        }
    }.trimEnd()
}

fun formatModelCommandMessage(
    backendReply: String? = null,
    commandExecutionAvailable: Boolean = false
): String {
    val normalizedReply = backendReply?.trim().orEmpty()
    if (normalizedReply.isNotBlank()) {
        return compactModelStatusText(normalizedReply)
    }
    return buildString {
        if (commandExecutionAvailable) {
            append("当前 Gateway 尚未完成 ET 可用的 /model 命令执行通路。")
        } else {
            append("当前 Gateway 暂未暴露可供 ET 使用的结构化模型状态接口；sessions.preview 只能返回会话预览文本，不能作为 /model 的真实结果。")
        }
    }.trimEnd()
}

fun compactModelStatusText(raw: String): String {
    val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
    val currentLine = lines.firstOrNull { it.startsWith("Current:") }
    val defaultLine = lines.firstOrNull { it.startsWith("Default:") }

    if (currentLine == null) {
        return raw.trim()
    }

    return buildString {
        appendLine("当前模型")
        appendLine("- $currentLine")
        defaultLine?.let { appendLine("- $it") }
    }.trimEnd()
}

fun formatModelCommandFailureMessage(error: String): String {
    return "/model 查询失败：${error.ifBlank { "未知错误" }}"
}

fun defaultReasoningBudgetForModel(model: String): Int {
    val modelLower = model.lowercase()
    return when {
        "flash" in modelLower -> 1024
        "pro" in modelLower -> 8192
        else -> 24576
    }
}
