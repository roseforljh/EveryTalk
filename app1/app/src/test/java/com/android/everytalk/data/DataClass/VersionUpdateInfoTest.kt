package com.android.everytalk.data.DataClass

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionUpdateInfoTest {
    @Test
    fun `major version upgrade is force update`() {
        assertTrue(VersionUpdateInfo.shouldForceUpdate("1.2.0", "2.2.0"))
    }

    @Test
    fun `older latest version is not force update`() {
        assertFalse(VersionUpdateInfo.shouldForceUpdate("2.2.0", "1.9.0"))
    }

    @Test
    fun `minor gap of at least three is force update`() {
        assertTrue(VersionUpdateInfo.shouldForceUpdate("1.2.0", "1.5.0"))
    }
}
