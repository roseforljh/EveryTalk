package com.android.everytalk.data.computer

/** 受保护服务器环境变量写入的本地参数与脚本构造，绝不记录 Secret 值。 */
internal object ComputerSecretEnvWriter {
    /** AI 可以选择 Workspace 内任意相对文件名，不再限定为 .env。 */
    fun requireWorkspaceRelativePath(path: String): String {
        val value = path.trim()
        require(value.isNotEmpty() && value.length <= 4096 && '\u0000' !in value && '\n' !in value && '\r' !in value) {
            "环境文件路径无效"
        }
        require(!value.startsWith('/') && !value.startsWith('\\') && !Regex("^[A-Za-z]:").containsMatchIn(value)) { "只能使用 Workspace 内相对路径" }
        require(value.split('/', '\\').none { it.isEmpty() || it == "." || it == ".." }) { "环境文件路径越界" }
        return value
    }

    /** 从 stdin 写入 Workspace 内指定文件，Secret 不会出现在命令行或日志中。 */
    fun buildUpsertCommand(workspaceRoot: String, relativePath: String, name: String): String {
        val safeRoot = shellQuote(workspaceRoot.trimEnd('/', '\\'))
        val safeRelativePath = shellQuote(requireWorkspaceRelativePath(relativePath))
        ComputerEnvironmentName.requireValid(name)
        val safeName = shellQuote(name)
        return "set -eu; umask 077; root=\$(cd -- $safeRoot && pwd -P); rel=$safeRelativePath; " +
            "case \"${'$'}rel\" in /*|../*|*/../*|*/..|.|*/.) exit 2;; esac; " +
            "parent=\$(dirname -- \"${'$'}root/${'$'}rel\"); " +
            "[ -d \"${'$'}parent\" ] || exit 3; parent=\$(cd -- \"${'$'}parent\" && pwd -P); " +
            "case \"${'$'}parent/\" in \"${'$'}root/\"*) ;; *) exit 4;; esac; " +
            "f=\"${'$'}root/${'$'}rel\"; [ ! -L \"${'$'}f\" ]; n=$safeName; d=\"${'$'}f.tmp.${'$'}${'$'}\"; " +
            "v=\"\$(cat)\"; " +
            "if [ -f \"${'$'}f\" ]; then grep -v -E \"^[[:space:]]*\${'$'}n=\" \"${'$'}f\" > \"${'$'}d\"; else : > \"${'$'}d\"; fi; " +
            "printf '%s=%s\\n' \"${'$'}n\" \"${'$'}v\" >> \"${'$'}d\"; " +
            "chmod 600 \"${'$'}d\"; mv -f \"${'$'}d\" \"${'$'}f\""
    }

    /**
     * Container 模式使用容器内固定的 `/workspace` 根目录。
     * Secret 仍从最外层 SSH stdin 传入 docker exec，不拼进命令行。
     */
    fun buildContainerUpsertCommand(containerName: String, path: String, name: String): String {
        require(containerName.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"))) { "Container 名称无效" }
        val relativePath = requireWorkspaceRelativePath(path)
        val inner = buildUpsertCommand("/workspace", relativePath, name)
        return "docker exec -i ${shellQuote(containerName)} sh -c ${shellQuote(inner)}"
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
}
