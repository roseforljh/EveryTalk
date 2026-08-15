package com.android.everytalk.data.skill

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillServerSyncTest {
    @Test
    fun `服务器路径不包含 Skill 原始 ID`() {
        val path = skillServerDirectory("remote:https://github.com/a/b#../../escape", "a".repeat(64))
        assertTrue(path.startsWith(".everytalk/skills/"))
        assertFalse(path.contains("escape"))
        assertFalse(path.contains(".."))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `服务器路径拒绝非本地内容哈希`() {
        skillServerDirectory("skill", "../../bad")
    }
}
