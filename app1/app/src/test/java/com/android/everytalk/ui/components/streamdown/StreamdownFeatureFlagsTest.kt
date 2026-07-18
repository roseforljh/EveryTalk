package com.android.everytalk.ui.components.streamdown

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamdownFeatureFlagsTest {
    @Test
    fun `feature flag defaults keep new renderer hidden while telemetry is enabled`() {
        assertFalse(StreamdownFeatureFlags.enabled)
        assertTrue(StreamdownFeatureFlags.doubleParseTelemetry)
    }

    @Test
    fun `streamdown parity docs are allowed by gitignore`() {
        val repoRoot = repoRoot()
        val gitignore = File(repoRoot, ".gitignore").readText(Charsets.UTF_8)

        assertTrue(gitignore.contains("!docs/streamdown-parity/"))
        assertTrue(gitignore.contains("!docs/streamdown-parity/**"))
    }

    private fun repoRoot(): File {
        var dir: File? = File(checkNotNull(System.getProperty("user.dir"))).absoluteFile
        while (dir != null) {
            if (File(dir, ".git").exists()) {
                return dir
            }
            dir = dir.parentFile
        }
        error("Repository root not found")
    }
}
