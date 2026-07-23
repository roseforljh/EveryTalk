package com.android.everytalk.data.network.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SttDirectClientFailureTest {

    @Test
    fun `aliyun task failed event remains a failure`() {
        val error = assertThrows(AliyunSttTaskFailedException::class.java) {
            parseAliyunSttEvent(
                """{"header":{"event":"task-failed","error_code":"BadRequest","error_message":"invalid audio"}}""",
            )
        }

        assertEquals("Aliyun STT task failed: [BadRequest] invalid audio", error.message)
    }
}
