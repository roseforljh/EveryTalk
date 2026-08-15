package com.android.everytalk.data.skill

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SkillSecretSessionStoreTest {
    @Test
    fun `一次性密钥按 Run 隔离并可清除`() {
        val value = "secret-value".toCharArray()
        SkillSecretSessionStore.put("run-a", "TOKEN", value)
        val loaded = requireNotNull(SkillSecretSessionStore.loadSelected("run-a", listOf("TOKEN"))["TOKEN"])
        assertArrayEquals(value, loaded)
        loaded.fill('\u0000')

        assertFalse(SkillSecretSessionStore.loadSelected("run-b", listOf("TOKEN")).containsKey("TOKEN"))
        SkillSecretSessionStore.clear("run-a")
        assertFalse(SkillSecretSessionStore.contains("run-a", "TOKEN"))
    }
}
