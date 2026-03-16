package com.android.everytalk.data.network.openclaw

import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.network.AppStreamEvent
import io.ktor.client.HttpClient
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OpenClawRuntimeStatusServiceTest {

    @Test
    fun `final text should win even if cancellation-like error comes later`() = runBlocking {
        val service = object : OpenClawRuntimeStatusService(
            context = mockk(relaxed = true),
            httpClient = mockk<HttpClient>(relaxed = true),
            json = Json { ignoreUnknownKeys = true }
        ) {
            override suspend fun streamModelStatusCommand(request: ChatRequest): Flow<AppStreamEvent> = flow {
                emit(AppStreamEvent.ContentFinal("Current: custom-12newapi/gpt-5.4"))
                emit(AppStreamEvent.Error("ScopeCoroutine was cancelled"))
            }
        }

        val result = service.proxyModelStatusCommand(
            ChatRequest(
                messages = emptyList(),
                provider = "openclaw",
                channel = "openclaw",
                apiAddress = "wss://24claw.everytalk.cc",
                apiKey = "token",
                model = "main"
            )
        )

        assertEquals("Current: custom-12newapi/gpt-5.4", result)
    }

    @Test
    fun `error should still fail when final text was never received`() = runBlocking {
        val service = object : OpenClawRuntimeStatusService(
            context = mockk(relaxed = true),
            httpClient = mockk<HttpClient>(relaxed = true),
            json = Json { ignoreUnknownKeys = true }
        ) {
            override suspend fun streamModelStatusCommand(request: ChatRequest): Flow<AppStreamEvent> = flow {
                emit(AppStreamEvent.Error("gateway failed"))
            }
        }

        try {
            service.proxyModelStatusCommand(
                ChatRequest(
                    messages = emptyList(),
                    provider = "openclaw",
                    channel = "openclaw",
                    apiAddress = "wss://24claw.everytalk.cc",
                    apiKey = "token",
                    model = "main"
                )
            )
            fail("Expected IllegalStateException")
        } catch (error: IllegalStateException) {
            assertEquals("gateway failed", error.message)
        }
    }

    @Test
    fun `matching chat final should resolve immediately and ignore later events`() = runBlocking {
        val service = object : OpenClawRuntimeStatusService(
            context = mockk(relaxed = true),
            httpClient = mockk<HttpClient>(relaxed = true),
            json = Json { ignoreUnknownKeys = true }
        ) {
            override suspend fun streamModelStatusCommand(request: ChatRequest): Flow<AppStreamEvent> = flow {
                emit(AppStreamEvent.StatusUpdate("chat_run:run-123"))
                emit(AppStreamEvent.OpenClawRuntimeFinal(runId = "run-123", state = "final", text = "Current: main/gpt-5.4"))
                emit(AppStreamEvent.StatusUpdate("health:ok"))
                emit(AppStreamEvent.Error("tick should be ignored"))
            }
        }

        val result = service.proxyModelStatusCommand(
            ChatRequest(
                messages = emptyList(),
                provider = "openclaw",
                channel = "openclaw",
                apiAddress = "wss://24claw.everytalk.cc",
                apiKey = "token",
                model = "main"
            )
        )

        assertEquals("Current: main/gpt-5.4", result)
    }

    @Test
    fun `non matching chat final should be ignored until current run final arrives`() = runBlocking {
        val service = object : OpenClawRuntimeStatusService(
            context = mockk(relaxed = true),
            httpClient = mockk<HttpClient>(relaxed = true),
            json = Json { ignoreUnknownKeys = true }
        ) {
            override suspend fun streamModelStatusCommand(request: ChatRequest): Flow<AppStreamEvent> = flow {
                emit(AppStreamEvent.StatusUpdate("chat_run:run-123"))
                emit(AppStreamEvent.OpenClawRuntimeFinal(runId = "run-other", state = "final", text = "wrong"))
                emit(AppStreamEvent.OpenClawRuntimeFinal(runId = "run-123", state = "final", text = "Current: right"))
            }
        }

        val result = service.proxyModelStatusCommand(
            ChatRequest(
                messages = emptyList(),
                provider = "openclaw",
                channel = "openclaw",
                apiAddress = "wss://24claw.everytalk.cc",
                apiKey = "token",
                model = "main"
            )
        )

        assertEquals("Current: right", result)
    }

    @Test
    fun `matching final should return without waiting for trailing stream events`() = runBlocking {
        val service = object : OpenClawRuntimeStatusService(
            context = mockk(relaxed = true),
            httpClient = mockk<HttpClient>(relaxed = true),
            json = Json { ignoreUnknownKeys = true }
        ) {
            override suspend fun streamModelStatusCommand(request: ChatRequest): Flow<AppStreamEvent> = flow {
                emit(AppStreamEvent.StatusUpdate("chat_run:run-123"))
                emit(AppStreamEvent.OpenClawRuntimeFinal(runId = "run-123", state = "final", text = "Current: immediate"))
                delay(500)
                emit(AppStreamEvent.StatusUpdate("tick"))
            }
        }

        val startAt = System.nanoTime()
        val result = withTimeout(200) {
            service.proxyModelStatusCommand(
                ChatRequest(
                    messages = emptyList(),
                    provider = "openclaw",
                    channel = "openclaw",
                    apiAddress = "wss://24claw.everytalk.cc",
                    apiKey = "token",
                    model = "main"
                )
            )
        }
        val elapsedMs = (System.nanoTime() - startAt) / 1_000_000

        assertEquals("Current: immediate", result)
        assertTrue("Expected immediate return, actual=${elapsedMs}ms", elapsedMs < 200)
    }
}
