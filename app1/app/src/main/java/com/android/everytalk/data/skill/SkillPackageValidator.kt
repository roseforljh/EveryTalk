package com.android.everytalk.data.skill

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

internal const val MAX_SKILL_BYTES = 100L * 1024L * 1024L
internal const val MAX_SKILL_FILES = 1_000

data class ValidatedSkillPackage(
    val skillMarkdown: String,
    val name: String,
    val description: String,
    val invocationMode: SkillInvocationMode,
    val contentHash: String,
    val manifest: List<SkillFileManifestEntry>,
    val frontmatter: Map<String, String>,
)

/**
 * 校验一个已经解压的 Skill 目录。
 * 这里是文件信任边界，任何路径逃逸、符号链接或超限都直接拒绝。
 */
object SkillPackageValidator {
    fun validate(root: File): ValidatedSkillPackage {
        require(root.isDirectory) { "Skill 目录不存在" }
        val canonicalRoot = root.canonicalFile
        val files = root.walkTopDown().filter(File::isFile).toList()
        require(files.size <= MAX_SKILL_FILES) { "Skill 文件数超过 $MAX_SKILL_FILES" }
        require(files.none { Files.isSymbolicLink(it.toPath()) }) { "Skill 禁止包含符号链接" }

        var totalBytes = 0L
        val manifest = files.map { file ->
            val canonical = file.canonicalFile
            require(canonical.toPath().startsWith(canonicalRoot.toPath())) { "Skill 文件路径越界" }
            totalBytes += file.length()
            require(totalBytes <= MAX_SKILL_BYTES) { "Skill 解压后超过 100 MB" }
            val relative = canonicalRoot.toPath().relativize(canonical.toPath()).toString().replace('\\', '/')
            require(relative.isNotBlank() && !relative.startsWith('/') && ".." !in relative.split('/')) {
                "Skill 文件路径无效"
            }
            SkillFileManifestEntry(
                path = relative,
                size = file.length(),
                sha256 = file.sha256(),
                text = file.isTextFile(),
            )
        }.sortedBy(SkillFileManifestEntry::path)

        val skillFile = File(canonicalRoot, "SKILL.md")
        require(skillFile.isFile) { "Skill 根目录缺少 SKILL.md" }
        val markdown = skillFile.readText(Charsets.UTF_8)
        val frontmatter = parseSkillFrontmatter(markdown)
        val name = frontmatter["name"]?.trim()?.takeIf(String::isNotBlank)
            ?: root.name.takeIf(String::isNotBlank)
            ?: error("Skill 缺少名称")
        val description = frontmatter["description"]?.trim()?.takeIf(String::isNotBlank)
            ?: markdown.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()
            ?: "用户添加的 Skill"
        val treeHash = MessageDigest.getInstance("SHA-256").run {
            manifest.forEach { entry ->
                update(entry.path.toByteArray(Charsets.UTF_8))
                update(0)
                update(entry.sha256.toByteArray(Charsets.US_ASCII))
                update(0)
            }
            digest().toHex()
        }
        return ValidatedSkillPackage(
            skillMarkdown = markdown,
            name = name,
            description = description,
            invocationMode = if (frontmatter["disable-model-invocation"].toBoolean()) {
                SkillInvocationMode.MANUAL_ONLY
            } else {
                SkillInvocationMode.AUTO
            },
            contentHash = treeHash,
            manifest = manifest,
            frontmatter = frontmatter,
        )
    }
}

internal fun parseSkillFrontmatter(markdown: String): Map<String, String> {
    val lines = markdown.lineSequence().toList()
    if (lines.firstOrNull()?.trim() != "---") return emptyMap()
    val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
    if (end < 0) return emptyMap()
    val result = linkedMapOf<String, String>()
    var activeKey: String? = null
    for (line in lines.subList(1, end + 1)) {
        val separator = line.indexOf(':')
        if (separator > 0 && !line.first().isWhitespace()) {
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim().trim('"', '\'')
            activeKey = key
            result[key] = if (value == ">" || value == "|") "" else value
        } else if (activeKey != null && line.isNotBlank()) {
            result[activeKey] = listOf(result[activeKey].orEmpty(), line.trim()).filter(String::isNotBlank).joinToString(" ")
        }
    }
    return result
}

internal fun File.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(readBytes())
    .toHex()

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun File.isTextFile(): Boolean = extension.lowercase() in setOf(
    "md", "txt", "json", "yaml", "yml", "toml", "xml", "csv", "tsv",
    "kt", "kts", "java", "js", "ts", "tsx", "jsx", "py", "rb", "sh", "ps1",
    "html", "css", "sql", "ini", "cfg", "conf", "properties",
)
