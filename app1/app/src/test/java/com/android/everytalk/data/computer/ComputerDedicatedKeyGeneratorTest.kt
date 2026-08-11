package com.android.everytalk.data.computer

import net.schmizz.sshj.SSHClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerDedicatedKeyGeneratorTest {
    @Test
    fun `dedicated key is ed25519 and can be parsed by sshj`() {
        val generated = ComputerDedicatedKeyGenerator.generate("computer_1")

        assertTrue(generated.authorizedKeyLine.startsWith("ssh-ed25519 "))
        assertTrue(generated.authorizedKeyLine.endsWith(" everytalk:computer_1"))
        SSHClient().use { client ->
            val keyText = String(generated.credential.privateKey)
            val provider = client.loadKeys(keyText, null, null)
            assertEquals("ssh-ed25519", provider.type.toString())
            assertTrue(provider.private != null)
            assertTrue(provider.public != null)
        }

        generated.credential.clear()
    }
}
