package com.android.everytalk.data.computer

/** 受保护服务器环境变量写入的本地参数与脚本构造，绝不记录 Secret 值。 */
internal object ComputerSecretEnvWriter {
    /** AI 可以选择目标环境中的相对或绝对配置文件路径，不再限定为 .env。 */
    fun requireTargetPath(path: String): String {
        val value = path.trim()
        require(value.isNotEmpty() && value.length <= 4096 && '\u0000' !in value && '\n' !in value && '\r' !in value) {
            "环境文件路径无效"
        }
        require(value.split('/', '\\').none { it == ".." }) { "环境文件路径不能包含父目录" }
        return value
    }

    /** 从 stdin 写入指定文件（相对路径基于 SSH 用户 Home），Secret 不会出现在命令行或日志中。 */
    fun buildUpsertCommand(path: String, name: String): String {
        val safePath = resolveTargetPath(path)
        ComputerEnvironmentName.requireValid(name)
        val safeName = shellQuote(name)
        return "set -eu; umask 077; f=$safePath; parent=\$(dirname -- \"${'$'}f\"); " +
            "test -d \"${'$'}parent\" || exit 3; if test -L \"${'$'}f\"; then exit 4; fi; n=$safeName; d=\"${'$'}f.tmp.${'$'}${'$'}\"; " +
            "v=\"\$(cat)\"; " +
            // awk 在没有保留行时仍返回 0，避免 grep 的“无匹配”状态触发 set -e。
            // 文件读取/重定向失败仍返回非零，不能静默覆盖原文件。
            "if test -f \"${'$'}f\"; then awk -v key=\"${'$'}n\" '${'$'}0 !~ \"^[[:space:]]*\" key \"=\" { print }' \"${'$'}f\" > \"${'$'}d\"; else : > \"${'$'}d\"; fi; " +
            "printf '%s=%s\\n' \"${'$'}n\" \"${'$'}v\" >> \"${'$'}d\"; " +
            "chmod 600 \"${'$'}d\"; mv -f \"${'$'}d\" \"${'$'}f\""
    }

    /** SERVER_ENV 明确表示 SSH 宿主机文件；宿主机目标必须使用绝对路径，避免歧义。 */
    fun buildHostUpsertCommand(path: String, name: String): String {
        val target = requireTargetPath(path)
        require(target.startsWith('/') || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(target)) {
            "SERVER_ENV 的目标路径必须是宿主机绝对路径"
        }
        return buildUpsertCommand(target, name)
    }

    private fun resolveTargetPath(path: String): String {
        val target = requireTargetPath(path)
        return if (target.startsWith('/') || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(target)) {
            shellQuote(target)
        } else {
            "\"\$HOME\"/${shellQuote(target.removePrefix("~/"))}"
        }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    /** 只根据 SSH 退出事实判定结果；stderr 可能含旧配置中的密钥，因此不对外传递。 */
    internal fun requireSuccessfulWrite(result: ComputerSshCommandResult) {
        if (result.timedOut || result.exitCode == null) {
            throw ComputerException(ComputerErrorCodes.EXECUTION_UNKNOWN, "服务器环境变量更新结果无法确认")
        }
        if (result.exitCode != 0) {
            throw ComputerException(
                ComputerErrorCodes.EXECUTION_FAILED,
                "服务器环境变量更新失败（退出码=${result.exitCode}）",
            )
        }
    }
}
