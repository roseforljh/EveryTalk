package com.android.everytalk.util.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppStorageManagerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun directorySizeAndClear_keepRootButRemoveNestedContent() {
        val root = temporaryFolder.newFolder("cache")
        root.resolve("first.bin").writeBytes(ByteArray(7))
        root.resolve("nested").apply { mkdirs() }.resolve("second.bin").writeBytes(ByteArray(11))

        assertEquals(18L, directorySize(root))

        deleteDirectoryContents(root)

        assertTrue(root.isDirectory)
        assertEquals(0L, directorySize(root))
    }

    @Test
    fun snapshot_dataDetailsMustEqualDataTotal() {
        val details = listOf(
            StorageDetail(StorageDetailType.CONVERSATIONS, 12L),
            StorageDetail(StorageDetailType.OTHER_DATA, 8L),
        )

        val snapshot = AppStorageSnapshot(
            applicationBytes = 30L,
            dataBytes = 20L,
            cacheBytes = 4L,
            details = details,
        )

        assertEquals(snapshot.dataBytes, snapshot.details.sumOf(StorageDetail::bytes))
    }
}
