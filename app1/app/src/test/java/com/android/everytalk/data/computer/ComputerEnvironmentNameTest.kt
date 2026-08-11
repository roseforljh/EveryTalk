package com.android.everytalk.data.computer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerEnvironmentNameTest {
    @Test
    fun `environment names follow portable shell rules`() {
        assertEquals("API_KEY", ComputerEnvironmentName.requireValid("API_KEY"))
        assertEquals("_TOKEN2", ComputerEnvironmentName.requireValid("_TOKEN2"))

        listOf("", "2TOKEN", "A-B", "A.B", "A B", "密钥").forEach { name ->
            assertTrue(runCatching { ComputerEnvironmentName.requireValid(name) }.exceptionOrNull() is ComputerException)
        }
    }
}
