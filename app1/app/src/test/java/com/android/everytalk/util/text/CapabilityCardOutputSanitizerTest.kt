package com.android.everytalk.util.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityCardOutputSanitizerTest {
    @Test
    fun `removes capability card and keeps following answer`() {
        val text = "局方能力选择：\n\n• general-answer\n\n---\n\n结论：这张卡适合大额消费。"

        val result = CapabilityCardOutputSanitizer.sanitize(text)

        assertEquals("结论：这张卡适合大额消费。", result)
    }

    @Test
    fun `keeps normal mention of capability id`() {
        val text = "文档中提到 general-answer，它是内部能力 ID。"

        val result = CapabilityCardOutputSanitizer.sanitize(text)

        assertEquals(text, result)
    }

    @Test
    fun `streaming detector removes card split across chunks`() {
        val detector = CapabilityCardOutputSanitizer.StreamingDetector()
        detector.enable()

        assertEquals("", detector.appendAndSanitize("局方能力"))
        assertEquals("", detector.appendAndSanitize("选择：\n\n• general-"))
        assertEquals("", detector.appendAndSanitize("answer\n\n---\n\n"))
        val answer = detector.appendAndSanitize("结论：保留正文") + detector.flush()

        assertEquals("结论：保留正文", answer)
    }

    @Test
    fun `streaming detector does not block ordinary answer`() {
        val detector = CapabilityCardOutputSanitizer.StreamingDetector()
        detector.enable()

        val answer = detector.appendAndSanitize("结论：这是正常回答。") + detector.flush()

        assertTrue(answer.contains("正常回答"))
        assertFalse(answer.contains("能力选择"))
    }

    @Test
    fun `streaming detector releases ordinary answer after leading newline`() {
        val detector = CapabilityCardOutputSanitizer.StreamingDetector()
        detector.enable()

        val answer = detector.appendAndSanitize("\n\n结论：这是正常回答。") + detector.flush()

        assertEquals("\n\n结论：这是正常回答。", answer)
    }

    @Test
    fun `streaming detector keeps protocol-shaped text disabled`() {
        val detector = CapabilityCardOutputSanitizer.StreamingDetector()
        val text = "能力选择：\n\n- general-answer\n\n---\n\n这是用户要求解释的示例。"

        assertEquals(text, detector.appendAndSanitize(text))
        assertEquals("", detector.flush())
    }
}
