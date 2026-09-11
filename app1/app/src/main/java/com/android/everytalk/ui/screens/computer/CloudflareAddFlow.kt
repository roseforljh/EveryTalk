package com.android.everytalk.ui.screens.computer

/** Cloudflare 添加草稿的明确状态，取消或 OAuth 失败都可以回到 AUTH_REQUIRED 重试。 */
internal enum class CloudflareAddState { AUTH_REQUIRED, ACCOUNT_SELECTION_REQUIRED, READY }

internal fun cloudflareAddState(form: ComputerAddFormState): CloudflareAddState = when {
    !form.cloudflareAuthorized -> CloudflareAddState.AUTH_REQUIRED
    form.cloudflareAccountId.isBlank() -> CloudflareAddState.ACCOUNT_SELECTION_REQUIRED
    else -> CloudflareAddState.READY
}

internal fun cloudflareAddError(form: ComputerAddFormState): String? = when (cloudflareAddState(form)) {
    CloudflareAddState.AUTH_REQUIRED -> "请先登录 Cloudflare"
    CloudflareAddState.ACCOUNT_SELECTION_REQUIRED -> "请选择一个 Cloudflare Account"
    CloudflareAddState.READY -> form.displayName.trim().takeIf { it.isBlank() }?.let { "请输入名称" }
}
