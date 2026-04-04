package com.android.everytalk.ui.components.table

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TableAwareTextRoutingTest {

    @Test
    fun `fenced code text should never use native markdown shortcut`() {
        assertFalse(
            shouldRenderTrailingStreamingTextWithMarkdown(
                """
                ```powershell
                irm https://openclaw.ai/install.ps1 | iex
                ```
                """.trimIndent()
            )
        )

        assertFalse(
            shouldRenderTrailingStreamingTextWithMarkdown(
                """
                ~~~bash
                docker compose up -d
                ~~~
                """.trimIndent()
            )
        )
    }

    @Test
    fun `fenced code detector should cover both backtick and tilde fences`() {
        assertTrue(containsFencedCodeSyntax("```bash\ncurl -fsSL https://get.docker.com | bash\n```"))
        assertTrue(containsFencedCodeSyntax("~~~powershell\nirm https://openclaw.ai/install.ps1 | iex\n~~~"))
        assertFalse(containsFencedCodeSyntax("普通文本，没有代码围栏"))
    }
}
