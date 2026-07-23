package com.android.everytalk.data.network

import com.android.everytalk.data.network.direct.AliyunRealtimeSttClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeAudioSequencerTest {

    @Test
    fun `realtime stt audio queue has a finite capacity`() {
        assertTrue(AliyunRealtimeSttClient.MAX_QUEUED_AUDIO_CHUNKS in 1 until Int.MAX_VALUE)
    }

    @Test
    fun `ready transition queues buffered audio before boundary audio`() = runTest {
        val sequencer = RealtimeAudioSequencer(maxBufferedBytes = 16)
        val sent = mutableListOf<Int>()
        val firstBufferedChunkSent = CompletableDeferred<Unit>()
        val continueFlush = CompletableDeferred<Unit>()

        sequencer.enqueue(byteArrayOf(1)) { sent += it.single().toInt() }
        sequencer.enqueue(byteArrayOf(2)) { sent += it.single().toInt() }

        val readyJob = launch {
            sequencer.flushBeforeReady { chunk ->
                sent += chunk.single().toInt()
                if (chunk.single().toInt() == 1) {
                    firstBufferedChunkSent.complete(Unit)
                    continueFlush.await()
                }
            }
        }
        firstBufferedChunkSent.await()
        assertFalse(sequencer.isReady)

        val boundaryAudioJob = launch {
            sequencer.enqueue(byteArrayOf(3)) { sent += it.single().toInt() }
        }
        runCurrent()
        assertEquals(listOf(1), sent)

        continueFlush.complete(Unit)
        joinAll(readyJob, boundaryAudioJob)

        assertTrue(sequencer.isReady)
        assertEquals(listOf(1, 2, 3), sent)
    }
}
