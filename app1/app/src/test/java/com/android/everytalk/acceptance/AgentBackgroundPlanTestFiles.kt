package com.android.everytalk.acceptance

import java.io.File

/**
 * 后台 Agent 计划验收测试共用的源码定位器。
 *
 * 这些测试既能从 app1 根目录运行，也能从 EveryTalk 根目录运行。
 * 所有读取都限制在项目源码和资源目录，不访问设备、网络或用户文件。
 */
internal object AgentBackgroundPlanTestFiles {
    private val appRoots = buildList {
        System.getProperty("everytalk.project.root")?.let { add(File(it)) }
        add(File("."))
        add(File("app1"))
    }

    fun appFile(relativePath: String): File {
        val root = requireNotNull(appRoots.firstOrNull { File(it, "app/src/main").isDirectory }) {
            "找不到 app1 Android 工程根目录"
        }
        return File(root, relativePath).also { file ->
            require(file.isFile) { "计划要求的文件不存在：${file.path}" }
        }
    }

    fun optionalAppFile(relativePath: String): File? = appRoots
        .asSequence()
        .map { File(it, relativePath) }
        .firstOrNull(File::isFile)

    fun source(relativePath: String): String = appFile(
        "app/src/main/java/com/android/everytalk/$relativePath",
    ).readText(Charsets.UTF_8)

    /**
     * 返回只包含可执行 Kotlin 代码的文本。
     *
     * 源码契约只能匹配真实代码，注释和字符串中的函数名不能作为实现证据。
     * 这里使用逐字符扫描，避免用正则错误处理转义字符串、原始字符串和嵌套块注释。
     */
    fun code(relativePath: String): String = executableCode(source(relativePath))

    fun asset(relativePath: String): String = appFile(
        "app/src/main/assets/computer/$relativePath",
    ).readText(Charsets.UTF_8)

    fun allProductionKotlin(): List<Pair<File, String>> {
        val sourceRoot = requireNotNull(appFile("app/src/main/AndroidManifest.xml").parentFile)
            .resolve("java/com/android/everytalk")
        return sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it to it.readText(Charsets.UTF_8) }
            .toList()
    }

    fun allProductionCode(): List<Pair<File, String>> = allProductionKotlin()
        .map { (file, source) -> file to executableCode(source) }

    /** 返回 marker 后第一个花括号代码块，供契约判断调用是否真的发生在目标生命周期内。 */
    fun codeBlockAfter(source: String, marker: String): String {
        val markerIndex = source.indexOf(marker)
        if (markerIndex < 0) return ""
        val openIndex = source.indexOf('{', markerIndex + marker.length)
        if (openIndex < 0) return ""
        var depth = 0
        for (index in openIndex until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(openIndex, index + 1)
                }
            }
        }
        return ""
    }

    fun codeBlocksAfter(source: String, marker: String): List<String> = buildList {
        var searchFrom = 0
        while (searchFrom < source.length) {
            val markerIndex = source.indexOf(marker, searchFrom)
            if (markerIndex < 0) break
            codeBlockAfter(source.substring(markerIndex), marker).takeIf(String::isNotBlank)?.let(::add)
            searchFrom = markerIndex + marker.length
        }
    }

    fun occurrencesOutsideDefinition(symbol: String, definitionFileSuffix: String): List<File> =
        allProductionKotlin()
            .filterNot { (file, _) -> file.invariantSeparatorsPath.endsWith(definitionFileSuffix) }
            .filter { (_, text) -> text.contains(symbol) }
            .map { (file, _) -> file }

    private enum class ParserState { CODE, LINE_COMMENT, BLOCK_COMMENT, STRING, RAW_STRING, CHARACTER }

    private fun executableCode(source: String): String {
        val result = StringBuilder(source.length)
        var state = ParserState.CODE
        var blockDepth = 0
        var escaped = false
        var index = 0

        fun blank(character: Char) {
            result.append(if (character == '\n' || character == '\r') character else ' ')
        }

        while (index < source.length) {
            val current = source[index]
            val next = source.getOrNull(index + 1)
            val third = source.getOrNull(index + 2)
            when (state) {
                ParserState.CODE -> when {
                    current == '/' && next == '/' -> {
                        blank(current); blank(next)
                        index += 2
                        state = ParserState.LINE_COMMENT
                        continue
                    }
                    current == '/' && next == '*' -> {
                        blank(current); blank(next)
                        index += 2
                        blockDepth = 1
                        state = ParserState.BLOCK_COMMENT
                        continue
                    }
                    current == '"' && next == '"' && third == '"' -> {
                        repeat(3) { blank('"') }
                        index += 3
                        state = ParserState.RAW_STRING
                        continue
                    }
                    current == '"' -> {
                        blank(current)
                        state = ParserState.STRING
                    }
                    current == '\'' -> {
                        blank(current)
                        state = ParserState.CHARACTER
                    }
                    else -> result.append(current)
                }

                ParserState.LINE_COMMENT -> {
                    blank(current)
                    if (current == '\n' || current == '\r') state = ParserState.CODE
                }

                ParserState.BLOCK_COMMENT -> when {
                    current == '/' && next == '*' -> {
                        blank(current); blank(next)
                        index += 2
                        blockDepth++
                        continue
                    }
                    current == '*' && next == '/' -> {
                        blank(current); blank(next)
                        index += 2
                        blockDepth--
                        if (blockDepth == 0) state = ParserState.CODE
                        continue
                    }
                    else -> blank(current)
                }

                ParserState.STRING -> {
                    blank(current)
                    when {
                        escaped -> escaped = false
                        current == '\\' -> escaped = true
                        current == '"' -> state = ParserState.CODE
                    }
                }

                ParserState.RAW_STRING -> {
                    if (current == '"' && next == '"' && third == '"') {
                        repeat(3) { blank('"') }
                        index += 3
                        state = ParserState.CODE
                        continue
                    }
                    blank(current)
                }

                ParserState.CHARACTER -> {
                    blank(current)
                    when {
                        escaped -> escaped = false
                        current == '\\' -> escaped = true
                        current == '\'' -> state = ParserState.CODE
                    }
                }
            }
            index++
        }
        return result.toString()
    }
}
