package com.android.everytalk.ui.screens.computer

/** Cloudflare 添加草稿的明确状态，取消或 OAuth 失败都可以回到 AUTH_REQUIRED 重试。 */
internal enum class CloudflareAddState { AUTH_REQUIRED, ACCOUNT_SELECTION_REQUIRED, READY }

internal fun cloudflareAddState(form: ComputerAddFormState): CloudflareAddState = when {
    !form.cloudflareAuthorized -> CloudflareAddState.AUTH_REQUIRED
    form.cloudflareAccountId.isBlank() -> CloudflareAddState.ACCOUNT_SELECTION_REQUIRED
    else -> CloudflareAddState.READY
}

@androidx.annotation.StringRes
internal fun cloudflareAddError(form: ComputerAddFormState): Int? = when (cloudflareAddState(form)) {
    CloudflareAddState.AUTH_REQUIRED -> com.android.everytalk.R.string.cloudflare_sign_in_first
    CloudflareAddState.ACCOUNT_SELECTION_REQUIRED -> com.android.everytalk.R.string.cloudflare_select_account_required
    CloudflareAddState.READY -> form.displayName.trim().takeIf { it.isBlank() }?.let { com.android.everytalk.R.string.cloudflare_name_required }
}
