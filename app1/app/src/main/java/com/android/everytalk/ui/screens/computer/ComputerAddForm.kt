package com.android.everytalk.ui.screens.computer

import com.android.everytalk.data.computer.AddComputerRequest
import com.android.everytalk.data.computer.ComputerAuthKind
import com.android.everytalk.data.computer.ComputerCredential
import com.android.everytalk.data.computer.ComputerRunMode
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
    val runMode: ComputerRunMode = ComputerRunMode.CONTAINER,
    val sudoPassword: String = "",
) {
    fun validationError(): ComputerAddFormError? = when {
        host.isBlank() -> ComputerAddFormError.HOST_REQUIRED
        port.toIntOrNull()?.let { it in 1..65535 } != true -> ComputerAddFormError.PORT_INVALID
        username.isBlank() -> ComputerAddFormError.USERNAME_REQUIRED
        authKind == ComputerAuthKind.PASSWORD && password.isEmpty() -> ComputerAddFormError.PASSWORD_REQUIRED
        authKind == ComputerAuthKind.PRIVATE_KEY && privateKey.isBlank() -> ComputerAddFormError.PRIVATE_KEY_REQUIRED
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
                runMode = runMode,
            ),
            sudoPassword = sudoPassword.takeIf {
                runMode == ComputerRunMode.CONTAINER && username.trim() != "root" && it.isNotEmpty()
            }?.toCharArray(),
        )
    }
}

internal data class PreparedComputerAdd(
    val request: AddComputerRequest,
    val sudoPassword: CharArray?,
) {
    fun clear() {
        request.credential.clear()
        sudoPassword?.fill('\u0000')
    }
}
