package com.android.everytalk.ui.computer

import com.android.everytalk.data.computer.ComputerProvider
import com.android.everytalk.ui.screens.computer.CloudflareAddState
import com.android.everytalk.ui.screens.computer.ComputerAddFormState
import com.android.everytalk.ui.screens.computer.cloudflareAddState
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudflareAddFlowTest {
    @Test
    fun `Cloudflare 草稿必须先授权再选择 Account`() {
        val base = ComputerAddFormState(provider = ComputerProvider.CLOUDFLARE)
        assertEquals(CloudflareAddState.AUTH_REQUIRED, cloudflareAddState(base))
        assertEquals(CloudflareAddState.ACCOUNT_SELECTION_REQUIRED, cloudflareAddState(base.copy(cloudflareAuthorized = true)))
        assertEquals(
            CloudflareAddState.READY,
            cloudflareAddState(base.copy(cloudflareAuthorized = true, cloudflareAccountId = "a1")),
        )
    }
}
