package com.android.everytalk.ui.screens.computer

import com.android.everytalk.data.computer.AddComputerRequest
import com.android.everytalk.data.computer.Computer
import com.android.everytalk.data.computer.ComputerAuthKind
import com.android.everytalk.data.computer.ComputerCredential
import com.android.everytalk.data.computer.ComputerRunMode
import com.android.everytalk.data.computer.UpdateComputerRequest
import com.android.everytalk.data.computer.ComputerProvider
import java.util.UUID

internal enum class ComputerAddFormError {
    HOST_REQUIRED,
    PORT_INVALID,
    USERNAME_REQUIRED,
    PASSWORD_REQUIRED,
    PRIVATE_KEY_REQUIRED,
}

/** 添加卡片的纯 UI 状态，真正保存时才创建可清零的凭据字符数组。 */
internal data class ComputerAddFormState(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String = "",
    val host: String = "",
    val port: String = "22",
    val username: String = "root",
    val authKind: ComputerAuthKind = ComputerAuthKind.PASSWORD,
    val password: String = "",
    val privateKey: String = "",
    val privateKeyPassphrase: String = "",
    val sudoPassword: String = "",
    val provider: ComputerProvider = ComputerProvider.SSH,
    val cloudflareAuthorized: Boolean = false,
    val cloudflareIdentity: String? = null,
    val cloudflareAccountId: String = "",
    val cloudflareAccountName: String = "",
    val cloudflareScopes: Set<String> = emptySet(),
) {
    fun validationError(reusableAuthKind: ComputerAuthKind? = null): ComputerAddFormError? = when {
        provider == ComputerProvider.CLOUDFLARE -> null
        host.isBlank() -> ComputerAddFormError.HOST_REQUIRED
        port.toIntOrNull()?.let { it in 1..65535 } != true -> ComputerAddFormError.PORT_INVALID
        username.isBlank() -> ComputerAddFormError.USERNAME_REQUIRED
        authKind == ComputerAuthKind.PASSWORD && password.isEmpty() && reusableAuthKind != authKind ->
            ComputerAddFormError.PASSWORD_REQUIRED
        authKind == ComputerAuthKind.PRIVATE_KEY && privateKey.isBlank() && reusableAuthKind != authKind ->
            ComputerAddFormError.PRIVATE_KEY_REQUIRED
        else -> null
    }

    fun prepare(): PreparedComputerAdd {
        check(validationError() == null) { "添加服务器表单尚未通过校验" }
        val credential = when (authKind) {
            ComputerAuthKind.PASSWORD -> ComputerCredential.Password(password.toCharArray())
            ComputerAuthKind.PRIVATE_KEY -> ComputerCredential.PrivateKey(
                privateKey = privateKey.toCharArray(),
                passphrase = privateKeyPassphrase.takeIf(String::isNotEmpty)?.toCharArray(),
            )
        }
        return PreparedComputerAdd(
            request = AddComputerRequest(
                id = id,
                displayName = displayName,
                host = host,
                port = requireNotNull(port.toIntOrNull()),
                username = username,
                credential = credential,
                // 服务器记录只描述 SSH 连接。是否进入沙箱由执行请求在运行时决定。
                runMode = ComputerRunMode.DIRECT,
            ),
            sudoPassword = sudoPassword.takeIf { username.trim() != "root" && it.isNotEmpty() }?.toCharArray(),
        )
    }

    fun prepareUpdate(existing: Computer): PreparedComputerUpdate {
        check(validationError(existing.authKind) == null) { "编辑服务器表单尚未通过校验" }
        val credential = when (authKind) {
            ComputerAuthKind.PASSWORD -> password.takeIf(String::isNotEmpty)?.let {
                ComputerCredential.Password(it.toCharArray())
            }
            ComputerAuthKind.PRIVATE_KEY -> privateKey.takeIf(String::isNotBlank)?.let {
                ComputerCredential.PrivateKey(
                    privateKey = it.toCharArray(),
                    passphrase = privateKeyPassphrase.takeIf(String::isNotEmpty)?.toCharArray(),
                )
            }
        }
        return PreparedComputerUpdate(
            request = UpdateComputerRequest(
                id = existing.id,
                displayName = displayName,
                host = host,
                port = requireNotNull(port.toIntOrNull()),
                username = username,
                // 编辑也保持服务器级别的 SSH 连接语义，不能在这里切换执行沙箱。
                runMode = ComputerRunMode.DIRECT,
                credential = credential,
            ),
            sudoPassword = sudoPassword.takeIf { username.trim() != "root" && it.isNotEmpty() }?.toCharArray(),
            replaceSudoPassword = username.trim() == "root" ||
                username.trim() != existing.username ||
                sudoPassword.isNotEmpty(),
        )
    }
}

/** 编辑时只回填非敏感参数，密码和私钥留空代表沿用本地已保存值。 */
internal fun Computer.toEditFormState(): ComputerAddFormState = ComputerAddFormState(
    id = id,
    displayName = displayName,
    host = host,
    port = port.toString(),
    username = username,
    authKind = authKind,
)

internal data class PreparedComputerAdd(
    val request: AddComputerRequest,
    val sudoPassword: CharArray?,
) {
    fun clear() {
        request.credential.clear()
        sudoPassword?.fill('\u0000')
    }
}

internal data class PreparedComputerUpdate(
    val request: UpdateComputerRequest,
    val sudoPassword: CharArray?,
    val replaceSudoPassword: Boolean,
) {
    fun clear() {
        request.credential?.clear()
        sudoPassword?.fill('\u0000')
    }
}
