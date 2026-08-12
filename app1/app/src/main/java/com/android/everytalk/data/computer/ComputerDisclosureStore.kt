package com.android.everytalk.data.computer

import android.content.Context

/** Agent 首次启用时需要分层确认的本地风险说明。 */
enum class ComputerDisclosureKind {
    MODEL_DATA_FLOW,
    DIRECT_SSH_PERMISSION,
    ROOT_SSH_PERMISSION,
}

/** 统一模式只在每次主机高风险命令前确认，首次开启这里只说明模型数据流。 */
internal object ComputerDisclosurePolicy {
    fun requiredFor(computer: Computer): Set<ComputerDisclosureKind> = buildSet {
        add(ComputerDisclosureKind.MODEL_DATA_FLOW)
    }
}

/**
 * 风险确认只记录布尔状态，不保存服务器凭据。
 * root 权限按服务器分别确认，避免新增 root 服务器沿用旧确认。
 */
class ComputerDisclosureStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun missingFor(computer: Computer): Set<ComputerDisclosureKind> =
        ComputerDisclosurePolicy.requiredFor(computer).filterNotTo(linkedSetOf()) { kind ->
            preferences.getBoolean(key(kind, computer.id), false)
        }

    fun accept(computer: Computer, disclosures: Set<ComputerDisclosureKind>) {
        if (disclosures.isEmpty()) return
        preferences.edit().apply {
            disclosures.forEach { kind -> putBoolean(key(kind, computer.id), true) }
        }.apply()
    }

    private fun key(kind: ComputerDisclosureKind, computerId: String): String = when (kind) {
        ComputerDisclosureKind.MODEL_DATA_FLOW -> "model_data_flow_v1"
        ComputerDisclosureKind.DIRECT_SSH_PERMISSION -> "direct_ssh_permission_v1"
        ComputerDisclosureKind.ROOT_SSH_PERMISSION -> "root_ssh_permission_v1:$computerId"
    }

    private companion object {
        const val PREFERENCES_NAME = "computer_disclosures"
    }
}
