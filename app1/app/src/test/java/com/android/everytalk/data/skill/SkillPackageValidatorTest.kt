package com.android.everytalk.data.skill

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SkillPackageValidatorTest {
    @Test
    fun `同一文件树哈希稳定且内容变化后更新`() {
        val root = Files.createTempDirectory("skill-validator").toFile()
        try {
            root.resolve("SKILL.md").writeText("---\nname: demo\ndescription: demo skill\n---\n规则")
            val first = SkillPackageValidator.validate(root)
            val second = SkillPackageValidator.validate(root)
            assertEquals(first.contentHash, second.contentHash)

            root.resolve("SKILL.md").appendText("\n新规则")
            assertNotEquals(first.contentHash, SkillPackageValidator.validate(root).contentHash)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `disable-model-invocation 转成仅手动调用`() {
        val root = Files.createTempDirectory("skill-manual").toFile()
        try {
            root.resolve("SKILL.md").writeText(
                "---\nname: manual\ndescription: manual skill\ndisable-model-invocation: true\n---\n规则",
            )
            assertEquals(SkillInvocationMode.MANUAL_ONLY, SkillPackageValidator.validate(root).invocationMode)
        } finally {
            root.deleteRecursively()
        }
    }
}
