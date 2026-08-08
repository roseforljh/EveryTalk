package com.android.everytalk.util.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageHandlingPolicyTest {

    @Test
    fun `图片处理限制在所有链路共享同一组值`() {
        assertEquals(16L * 1024L * 1024L, ImageHandlingLimits.USER_UPLOAD_MAX_BYTES)
        assertEquals(32L * 1024L * 1024L, ImageHandlingLimits.GENERATED_IMAGE_MAX_BYTES)
        assertEquals(40_000_000L, ImageHandlingLimits.MAX_IMAGE_PIXELS)
        assertEquals(16 * 1024, ImageHandlingLimits.MAX_REMOTE_URL_BYTES)
        assertEquals(10_000, ImageHandlingLimits.REMOTE_CONNECT_TIMEOUT_MILLIS)
        assertEquals(60_000, ImageHandlingLimits.REMOTE_DOWNLOAD_TIMEOUT_MILLIS)
    }

    @Test
    fun `用户图片超限提示包含文件名实际大小和最大值`() {
        val actualBytes = (18.4 * 1024.0 * 1024.0).toLong()
        val failure = ImagePersistenceFailure.TooLarge(
            actualBytes = actualBytes,
            limitBytes = ImageHandlingLimits.USER_UPLOAD_MAX_BYTES,
            actualSizeIsExact = true,
        )

        assertEquals(
            "图片“示例.png”大小为 18.4 MiB，超过最大 16 MiB 限制，请选择更小的图片。",
            failure.toUserImageMessage("示例.png"),
        )
    }

    @Test
    fun `流式读取只能确认超限时不伪造精确大小`() {
        val failure = ImagePersistenceFailure.TooLarge(
            actualBytes = ImageHandlingLimits.USER_UPLOAD_MAX_BYTES + 1L,
            limitBytes = ImageHandlingLimits.USER_UPLOAD_MAX_BYTES,
            actualSizeIsExact = false,
        )

        assertEquals(
            "所选图片大小已超过最大 16 MiB 限制，请选择更小的图片。",
            failure.toUserImageMessage(null),
        )
    }

    @Test
    fun `Base64 原始字节大小计算正确处理填充和非法字符`() {
        assertEquals(1L, decodedBase64SizeOrNull("TQ=="))
        assertEquals(2L, decodedBase64SizeOrNull("TWE="))
        assertEquals(3L, decodedBase64SizeOrNull("TWFu"))
        assertNull(decodedBase64SizeOrNull("T中Fu"))
        assertNull(decodedBase64SizeOrNull("T=Fu"))
    }
}
