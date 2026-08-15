package com.android.everytalk.data.computer

@kotlinx.serialization.Serializable
enum class ComputerHostCommandRisk {
    HOST_WRITE,
    PRIVILEGE_ESCALATION,
    SHELL_SYNTAX,
    SENSITIVE_READ,
    UNKNOWN_COMMAND,
}

/** 主机命令的本地风险判断结果。只有明确只读的命令才允许免确认。 */
data class ComputerHostCommandAssessment(
    val requiresConfirmation: Boolean,
    val reason: String? = null,
    val risks: Set<ComputerHostCommandRisk> = emptySet(),
)

/** 输入框确认卡所需的冻结数据。Host 请求不接受环境变量、Secret、stdin 和后台参数。 */
@kotlinx.serialization.Serializable
data class ComputerHostCommandConfirmationRequest(
    val requestId: String,
    val context: ComputerRequestContext,
    val computerName: String,
    val command: String,
    val cwd: String,
    val requestsPrivilege: Boolean,
    val reason: String,
    val risks: Set<ComputerHostCommandRisk>,
    val decisionMode: ComputerApprovalDecisionMode = ComputerApprovalDecisionMode.ALLOW_OR_REJECT,
)

@kotlinx.serialization.Serializable
enum class ComputerApprovalDecisionMode {
    ALLOW_OR_REJECT,
    RETRY_OR_KEEP_UNKNOWN,
}

/**
 * 审批门只决定是否继续执行传入的同一个请求对象。
 * 禁止审批后重新解析模型参数，确保一次允许只对应卡片中显示的完整命令。
 */
suspend fun <T> executeHostCommandWithConfirmation(
    request: ComputerExecRequest,
    permissionMode: ComputerPermissionMode = ComputerPermissionMode.MANUAL,
    askUserApproval: Boolean? = null,
    confirmationRequest: (ComputerHostCommandAssessment) -> ComputerHostCommandConfirmationRequest,
    confirmer: suspend (ComputerHostCommandConfirmationRequest) -> Boolean,
    execute: suspend (ComputerExecRequest) -> T,
): T {
    requireValidComputerExecRequest(request)
    val assessment = ComputerHostCommandPolicy.assess(request)
    val requiresConfirmation = when (permissionMode) {
        ComputerPermissionMode.MANUAL -> assessment.requiresConfirmation
        ComputerPermissionMode.SMART -> askUserApproval
            ?: throw ComputerException(
                ComputerErrorCodes.HOST_COMMAND_REJECTED,
                "智能批准缺少 ask_user_approval 参数",
            )
        ComputerPermissionMode.FULL -> false
    }
    if (requiresConfirmation && !confirmer(confirmationRequest(assessment))) {
        throw ComputerException(
            ComputerErrorCodes.HOST_COMMAND_REJECTED,
            "用户拒绝了本次 VPS 命令",
            action = "CONFIRM_HOST_COMMAND",
        )
    }
    return execute(request)
}

/**
 * 主机执行采用保守白名单。
 * 解析结果只用于决定是否弹确认卡，不负责重写命令，批准后仍执行冻结的原始请求。
 */
object ComputerHostCommandPolicy {
    private val shellSyntaxCharacters = setOf(
        ';', '|', '>', '<', '`', '\'', '"', '\n', '\r', '&', '(', ')', '{', '}', '#',
    )
    private val safePsColumns = setOf(
        "pid", "ppid", "user", "uid", "gid", "comm", "stat", "%cpu", "%mem", "etime", "etimes",
        "lstart", "nlwp", "rss", "vsz", "tty",
    )
    private val safeSsLongOptions = setOf(
        "--numeric", "--listening", "--tcp", "--udp", "--raw", "--unix", "--ipv4", "--ipv6",
        "--processes", "--summary", "--extended", "--memory", "--info", "--options", "--no-header", "--oneline",
    )
    private val safeIpGlobalOptions = setOf(
        "-brief", "-br", "-details", "-d", "-stats", "-s", "-4", "-6", "-json", "-j", "-pretty", "-p",
        "-human", "-h", "-oneline", "-o",
    )

    fun assess(request: ComputerExecRequest): ComputerHostCommandAssessment {
        val risks = linkedSetOf<ComputerHostCommandRisk>()
        val command = request.command.trim()
        val words = command.split(' ', '\t').filter(String::isNotEmpty)
        // 反斜杠可改变 shell 分词，简单白名单不能可靠解释，必须确认。

        if (isSafeReadOnlyCommandChain(command) || isSafeReadOnlyPipeline(command)) {
            return ComputerHostCommandAssessment(requiresConfirmation = false)
        }

        if (words.firstOrNull() in setOf("sudo", "su", "doas", "pkexec")) {
            risks += ComputerHostCommandRisk.PRIVILEGE_ESCALATION
        }
        if (request.asRoot) risks += ComputerHostCommandRisk.PRIVILEGE_ESCALATION
        if (words.firstOrNull() == "sudo" && words.drop(1).none { it.startsWith('-') }) {
            when {
                isMutatingSystemctl(words.drop(1)) -> risks += ComputerHostCommandRisk.HOST_WRITE
                isKnownHostWriteCommand(words.drop(1)) -> risks += ComputerHostCommandRisk.HOST_WRITE
            }
        }
        val hasShellSyntax = command.any(shellSyntaxCharacters::contains) || "$(" in command ||
            command.startsWith('!') || '\\' in command || hasShellVariableExpansion(command)
        if (
            hasShellSyntax
        ) {
            risks += ComputerHostCommandRisk.SHELL_SYNTAX
        }
        if (readsSensitivePath(words)) risks += ComputerHostCommandRisk.SENSITIVE_READ
        if (readsPotentiallySensitiveHostData(words)) risks += ComputerHostCommandRisk.SENSITIVE_READ

        when {
            ComputerHostCommandRisk.PRIVILEGE_ESCALATION in risks -> Unit
            ComputerHostCommandRisk.SHELL_SYNTAX in risks -> Unit
            isMutatingDiagnosticCommand(words) -> risks += ComputerHostCommandRisk.HOST_WRITE
            isReadOnlySystemctl(words) -> Unit
            isMutatingSystemctl(words) -> risks += ComputerHostCommandRisk.HOST_WRITE
            isKnownHostWriteCommand(words) -> risks += ComputerHostCommandRisk.HOST_WRITE
            isKnownReadOnlyCommand(words) -> Unit
            else -> risks += ComputerHostCommandRisk.UNKNOWN_COMMAND
        }

        return if (risks.isEmpty()) {
            ComputerHostCommandAssessment(requiresConfirmation = false)
        } else {
            ComputerHostCommandAssessment(
                requiresConfirmation = true,
                reason = risks.first().displayReason(),
                risks = risks,
            )
        }
    }

    private fun isReadOnlySystemctl(words: List<String>): Boolean =
        words.size == 3 && words[0] == "systemctl" &&
            words[1] in setOf("status", "is-active", "is-enabled") && isSafeUnit(words[2])

    private fun isMutatingSystemctl(words: List<String>): Boolean =
        words.size >= 2 && words[0] == "systemctl" && words[1] in setOf(
            "start", "stop", "restart", "reload", "enable", "disable", "mask", "unmask",
        )

    private fun isKnownReadOnlyCommand(words: List<String>): Boolean = when (words.firstOrNull()) {
        "hostname", "uptime" -> words.size == 1
        "uname" -> words.drop(1).all { it in setOf("-a", "-s", "-n", "-r", "-v", "-m", "-p", "-i", "-o") }
        "whoami", "id" -> words.size == 1
        "nproc" -> words.drop(1).all { it == "--all" || it.startsWith("--ignore=") && it.substringAfter('=').all(Char::isDigit) }
        "lscpu", "lsblk" -> words.drop(1).none { it in setOf("--help", "-h") }
        "vmstat" -> words.drop(1).all { it in setOf("-s", "-d", "-D", "-f") }
        "free" -> words.drop(1).all { it in setOf("-b", "-k", "-m", "-g", "-h", "--si", "-t", "-w", "-l", "-v", "--wide", "--lohi") }
        "df" -> words.drop(1).all { it.startsWith('-') || isSafeNonSensitivePath(it) }
        "ps" -> isReadOnlyPs(words)
        "ss" -> isReadOnlySs(words)
        "ip" -> isReadOnlyIp(words)
        "cat" -> words.size == 2 && words[1] in setOf(
            "/etc/os-release", "/etc/debian_version", "/proc/cpuinfo", "/proc/meminfo", "/proc/loadavg",
        )
        "journalctl" -> words.drop(1).all { it in setOf("--disk-usage", "--list-boots", "--list-catalog", "--header", "--verify") }
        "docker" -> isReadOnlyDocker(words)
        else -> false
    }

    /**
     * 查看整机配置通常需要多条诊断命令。这里只允许无引号、无变量、无重定向的简单顺序链，
     * 并逐段复用同一只读白名单；任一片段未知时整条链继续走确认。
     */
    private fun isSafeReadOnlyCommandChain(command: String): Boolean {
        if (
            command.none { it == ';' || it == '\n' || it == '\r' || it == '&' } ||
            command.any { it in setOf('|', '>', '<', '`', '\'', '"', '\\', '(', ')', '{', '}', '#') } ||
            '$' in command || "||" in command || '&' in command && "&&" !in command
        ) {
            return false
        }
        val normalized = command.replace("&&", "\n").replace(';', '\n').replace('\r', '\n')
        val segments = normalized.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        return segments.size > 1 && segments.all { segment ->
            val words = segment.split(' ', '\t').filter(String::isNotEmpty)
            isReadOnlySystemctl(words) || isKnownReadOnlyCommand(words)
        }
    }

    /**
     * 允许常见的只读诊断管道，例如 du 输出交给 sort/head，并允许仅把 stderr 丢到 /dev/null。
     * 解析器只接受有限 shell 语法；变量展开、命令替换、写文件重定向和未知管道程序仍会要求确认。
     */
    private fun isSafeReadOnlyPipeline(command: String): Boolean {
        val stages = splitSafeShellStages(command) ?: return false
        if (stages.size < 2) return false
        return stages.all { stage ->
            val words = tokenizeSafeShellStage(stage) ?: return@all false
            val commandWords = words.filterNot { it == "2>/dev/null" }
            words.count { it == "2>/dev/null" } <= 1 &&
                commandWords.isNotEmpty() &&
                (isReadOnlySystemctl(commandWords) || isKnownReadOnlyCommand(commandWords) ||
                    isReadOnlyPipelineCommand(commandWords))
        }
    }

    /** 分隔符只在引号外生效，拒绝 ||、单独 &、变量展开、反斜杠和写入型重定向。 */
    private fun splitSafeShellStages(command: String): List<String>? {
        if ('$' in command || '`' in command || '\\' in command || '\n' in command || '\r' in command) return null
        val stages = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var index = 0
        while (index < command.length) {
            val character = command[index]
            if (character == '\'' || character == '"') {
                quote = if (quote == null) character else if (quote == character) null else quote
                current.append(character)
                index += 1
                continue
            }
            if (quote == null) {
                val next = command.getOrNull(index + 1)
                if (command.startsWith("2>/dev/null", index)) {
                    current.append("2>/dev/null")
                    index += "2>/dev/null".length
                    continue
                }
                if (character == '|' && next == '|') return null
                if (character == '&' && next != '&') return null
                if (character == '>' || character == '<') {
                    return null
                }
                val separatorLength = when {
                    character == ';' || character == '|' -> 1
                    character == '&' && next == '&' -> 2
                    else -> 0
                }
                if (separatorLength > 0) {
                    val stage = current.toString().trim()
                    if (stage.isEmpty()) return null
                    stages += stage
                    current.clear()
                    index += separatorLength
                    continue
                }
            }
            current.append(character)
            index += 1
        }
        if (quote != null) return null
        val last = current.toString().trim()
        if (last.isEmpty()) return null
        stages += last
        return stages
    }

    /** 只做安全判断所需的最小分词，移除成对引号，不解释任何 shell 展开。 */
    private fun tokenizeSafeShellStage(stage: String): List<String>? {
        val words = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        stage.forEach { character ->
            if (character == '\'' || character == '"') {
                quote = if (quote == null) character else if (quote == character) null else quote
            } else if (character.isWhitespace() && quote == null) {
                if (current.isNotEmpty()) {
                    words += current.toString()
                    current.clear()
                }
            } else {
                current.append(character)
            }
        }
        if (quote != null) return null
        if (current.isNotEmpty()) words += current.toString()
        return words
    }

    private fun isReadOnlyPipelineCommand(words: List<String>): Boolean = when (words.firstOrNull()) {
        "echo" -> true
        "du" -> isReadOnlyDu(words.drop(1))
        "sort" -> words.drop(1).all { option ->
            option.startsWith('-') && option.drop(1).all { it in "bdfghinrRsuVz" }
        }
        "head" -> words.size == 1 || words.size == 3 && words[1] == "-n" && words[2].all(Char::isDigit) ||
            words.size == 2 && words[1].startsWith('-') && words[1].drop(1).all(Char::isDigit)
        else -> false
    }

    private fun isReadOnlyDu(arguments: List<String>): Boolean {
        var expectsDepth = false
        for (argument in arguments) {
            if (expectsDepth) {
                if (!argument.all(Char::isDigit)) return false
                expectsDepth = false
                continue
            }
            when {
                argument == "-d" || argument == "--max-depth" -> expectsDepth = true
                argument.startsWith("--max-depth=") -> if (!argument.substringAfter('=').all(Char::isDigit)) return false
                argument.startsWith("--") -> if (argument !in setOf("--all", "--human-readable", "--one-file-system", "--summarize")) return false
                argument.startsWith('-') -> if (argument.drop(1).any { it !in "ahxsd" }) return false
                !isSafeNonSensitivePath(argument) -> return false
            }
        }
        return !expectsDepth
    }

    /** ps 只有显式选择安全列时才免确认，避免把进程参数中的 Token 发送给模型。 */
    private fun isReadOnlyPs(words: List<String>): Boolean {
        val arguments = words.drop(1)
        if (arguments.isEmpty()) return true
        var hasSafeFormat = false
        var index = 0
        while (index < arguments.size) {
            val argument = arguments[index]
            when {
                argument in setOf("-e", "-A", "--no-headers") -> Unit
                argument == "-o" || argument == "-eo" -> {
                    val format = arguments.getOrNull(++index) ?: return false
                    if (!isSafePsFieldList(format)) return false
                    hasSafeFormat = true
                }
                argument.startsWith("-eo") && argument.length > 3 -> {
                    if (!isSafePsFieldList(argument.substring(3))) return false
                    hasSafeFormat = true
                }
                argument.startsWith("-o") && argument.length > 2 -> {
                    if (!isSafePsFieldList(argument.substring(2))) return false
                    hasSafeFormat = true
                }
                argument.startsWith("--sort=") -> if (!isSafePsSort(argument.substringAfter('='))) return false
                else -> return false
            }
            index += 1
        }
        return hasSafeFormat
    }

    private fun isSafePsFieldList(value: String): Boolean = value.split(',').all { field ->
        field.substringBefore('=').trim() in safePsColumns
    }

    private fun isSafePsSort(value: String): Boolean = value.split(',').all { field ->
        field.trim().trimStart('+', '-') in safePsColumns
    }

    /** ss 的事件流与删除连接参数不在白名单内，防止自动命令长期阻塞或改变连接。 */
    private fun isReadOnlySs(words: List<String>): Boolean = words.drop(1).all { argument ->
        when {
            argument in safeSsLongOptions -> true
            argument.startsWith("--") -> false
            argument.startsWith('-') && argument.length > 1 -> argument.drop(1).all { it in "Hnltuwx46psemiOar" }
            else -> false
        }
    }

    /** ip monitor、netns exec 和所有修改子命令都需要确认。 */
    private fun isReadOnlyIp(words: List<String>): Boolean {
        val arguments = words.drop(1)
        val objectIndex = arguments.indexOfFirst { !it.startsWith('-') }
        if (objectIndex < 0 || arguments.take(objectIndex).any { it !in safeIpGlobalOptions }) return false
        val objectName = arguments[objectIndex]
        val tail = arguments.drop(objectIndex + 1)
        if (objectName !in setOf("address", "addr", "link", "route", "rule", "neigh")) return false
        return tail.isEmpty() || tail.size == 1 && tail[0] in setOf("show", "list")
    }

    /** docker stats 必须显式使用单次快照，其他自动放行项本身都会结束。 */
    private fun isReadOnlyDocker(words: List<String>): Boolean = when (words.getOrNull(1)) {
        "ps", "info", "version", "port" -> true
        "stats" -> words.drop(2).any { it == "--no-stream" || it == "--no-stream=true" }
        else -> false
    }

    private fun isKnownHostWriteCommand(words: List<String>): Boolean = when (words.firstOrNull()) {
        "apt", "apt-get", "dnf", "yum", "apk", "pacman", "zypper" -> true
        "docker" -> words.size >= 2 && words[1] !in setOf("ps", "info", "version", "stats", "inspect", "logs", "top", "port")
        "rm", "mv", "cp", "install", "chmod", "chown", "kill", "pkill", "reboot", "shutdown", "mount", "umount" -> true
        else -> false
    }

    private fun isMutatingDiagnosticCommand(words: List<String>): Boolean = when (words.firstOrNull()) {
        "ip" -> words.getOrNull(1) in setOf("netns", "vrf") && words.getOrNull(2) == "exec" ||
            words.getOrNull(1) in setOf("link", "address", "addr", "route", "rule", "neigh") &&
            words.any { it in setOf("set", "add", "del", "delete", "replace", "flush", "change") }
        "ss" -> words.any { it == "-K" || it == "--kill" }
        "journalctl" -> words.any { it.startsWith("--vacuum-") || it.startsWith("--rotate") || it == "--flush" || it == "--sync" }
        else -> false
    }

    private fun readsPotentiallySensitiveHostData(words: List<String>): Boolean =
        words.firstOrNull() == "docker" && words.getOrNull(1) in setOf("inspect", "logs", "top") ||
            words.firstOrNull() == "journalctl" && words.drop(1).any { it !in setOf("--disk-usage", "--list-boots", "--list-catalog", "--header", "--verify") } ||
            words.firstOrNull() == "ps" && words.drop(1).any { value ->
                value == "e" || value == "-e" || value == "eww" || value == "-eww" ||
                    "args" in value || "command" in value || "cmd" in value
            }

    private fun readsSensitivePath(words: List<String>): Boolean = words.any { word ->
        val normalized = word.lowercase()
        normalized == "/etc/shadow" || normalized == "/etc/gshadow" ||
            "/.ssh/" in normalized || normalized.startsWith("~/.ssh/") ||
            normalized.endsWith("/.env") || normalized.endsWith("/credentials") ||
            normalized.endsWith(".pem") || normalized.endsWith(".key")
    }

    private fun looksLikePath(value: String): Boolean = value.startsWith('/') || value.startsWith('~')

    private fun isSafeNonSensitivePath(value: String): Boolean =
        looksLikePath(value) && !readsSensitivePath(listOf(value))

    private fun isSafeUnit(value: String): Boolean = value.isNotBlank() &&
        value.all { character -> character.isLetterOrDigit() || character in setOf('_', '-', '.', '@') }

    private fun hasShellVariableExpansion(command: String): Boolean = command.indices.any { index ->
        command[index] == '$' && command.getOrNull(index + 1)?.let { next ->
            next == '{' || next == '_' || next.isLetter()
        } == true
    }

    private fun ComputerHostCommandRisk.displayReason(): String = when (this) {
        ComputerHostCommandRisk.HOST_WRITE -> "该命令会修改 VPS 状态"
        ComputerHostCommandRisk.PRIVILEGE_ESCALATION -> "该命令请求提升 VPS 权限"
        ComputerHostCommandRisk.SHELL_SYNTAX -> "该命令包含组合、重定向或后台 shell 语法"
        ComputerHostCommandRisk.SENSITIVE_READ -> "该命令可能读取 VPS 敏感信息"
        ComputerHostCommandRisk.UNKNOWN_COMMAND -> "无法确认该命令只读取 VPS 状态"
    }
}
