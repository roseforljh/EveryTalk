package com.android.everytalk.data.computer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerProbeParserTest {
    @Test
    fun `probe parser reads linux capabilities without applying resource quotas`() {
        val output = """
            unrelated login banner
            __ET_PROBE_OS_RELEASE=NAME="Ubuntu"|ID=ubuntu|VERSION_ID="24.04"|
            __ET_PROBE_KERNEL=Linux 6.8.0
            __ET_PROBE_ARCH=x86_64
            __ET_PROBE_USER=ubuntu
            __ET_PROBE_SHELL=/bin/bash
            __ET_PROBE_CPU_COUNT=2
            __ET_PROBE_MEMORY_KIB=1024000
            __ET_PROBE_DISK=/dev/vda1 20480000 1024000 19456000 5% /home/ubuntu
            __ET_PROBE_LOAD=0.01 0.03 0.05 1/100 321
            __ET_PROBE_DOCKER=1
            __ET_PROBE_SUDO=1
        """.trimIndent()

        val result = ComputerProbeParser.parse(output)

        assertEquals("ubuntu", result.osId)
        assertEquals("24.04", result.osVersion)
        assertEquals("Linux 6.8.0", result.kernel)
        assertEquals("x86_64", result.architecture)
        assertEquals("ubuntu", result.remoteUser)
        assertEquals("/bin/bash", result.shell)
        assertEquals(2, result.cpuCount)
        assertEquals(1_048_576_000L, result.memoryBytes)
        assertEquals(19_922_944_000L, result.diskAvailableBytes)
        assertTrue(result.dockerAvailable)
        assertTrue(result.sudoAvailable)
        assertFalse(result.sftpAvailable)
        assertNull(result.memoryBytes?.takeIf { it < 0 })
    }
}
