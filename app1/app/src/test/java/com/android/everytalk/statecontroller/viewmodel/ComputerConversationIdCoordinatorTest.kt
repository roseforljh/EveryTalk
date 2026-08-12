package com.android.everytalk.statecontroller.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ComputerConversationIdCoordinatorTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Workspace先创建时迁移等待并搬到稳定会话`() = runTest {
        val coordinator = ComputerConversationIdCoordinator()
        val creationStarted = CompletableDeferred<Unit>()
        val releaseCreation = CompletableDeferred<Unit>()
        val storedIds = mutableSetOf<String>()

        val creation = async {
            coordinator.withCurrentId("draft") { currentId ->
                creationStarted.complete(Unit)
                releaseCreation.await()
                storedIds += currentId
            }
        }
        creationStarted.await()
        val migration = async {
            coordinator.migrate("draft", "stable") { sourceId, targetId ->
                if (storedIds.remove(sourceId)) storedIds += targetId
            }
        }
        runCurrent()
        assertEquals(emptySet<String>(), storedIds)

        releaseCreation.complete(Unit)
        creation.await()
        migration.await()

        assertEquals(setOf("stable"), storedIds)
        assertEquals("stable", coordinator.resolve("draft"))
    }

    @Test
    fun `会话先迁移时后续Workspace直接使用稳定ID`() = runTest {
        val coordinator = ComputerConversationIdCoordinator()
        coordinator.migrate("draft", "stable") { _, _ -> }

        val createdWith = coordinator.withCurrentId("draft") { it }

        assertEquals("stable", createdWith)
    }
}
