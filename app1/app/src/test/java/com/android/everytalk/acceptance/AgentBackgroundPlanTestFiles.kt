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

    fun occurrencesOutsideDefinition(symbol: String, definitionFileSuffix: String): List<File> =
        allProductionKotlin()
            .filterNot { (file, _) -> file.invariantSeparatorsPath.endsWith(definitionFileSuffix) }
            .filter { (_, text) -> text.contains(symbol) }
            .map { (file, _) -> file }
}
