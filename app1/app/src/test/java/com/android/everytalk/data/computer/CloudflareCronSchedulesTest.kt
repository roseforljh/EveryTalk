package com.android.everytalk.data.computer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CloudflareCronSchedulesTest {
    @Test
    fun `cron expressions are normalized and encoded as Cloudflare array`() {
        val values = CloudflareCronSchedules.validate(listOf(" 0  * * * * ", "*/15 * * * *"))
        assertEquals(listOf("*/15 * * * *", "0 * * * *"), values)
        assertEquals("0 * * * *", CloudflareCronSchedules.encode(values)[1].jsonObject["cron"]?.toString()?.trim('"'))
    }

    @Test
    fun `malformed cron and duplicate schedules are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { CloudflareCronSchedules.validate(listOf("0 0 * *")) }
        assertThrows(IllegalArgumentException::class.java) { CloudflareCronSchedules.validate(listOf("0 0 * * *", "0 0 * * *")) }
    }

    @Test
    fun `response parser only accepts result schedules`() {
        val response = Json.parseToJsonElement("{\"success\":true,\"result\":{\"schedules\":[{\"cron\":\"0 0 * * *\"}]}}")
            .jsonObject
        assertEquals(listOf("0 0 * * *"), CloudflareCronSchedules.fromResponse(response))
    }
}
