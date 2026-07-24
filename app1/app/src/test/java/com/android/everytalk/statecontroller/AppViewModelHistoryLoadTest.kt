package com.android.everytalk.statecontroller

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppViewModelHistoryLoadTest {

    @Test
    fun `skips reloading currently loaded history item with messages`() {
        val result = shouldSkipReloadingLoadedHistory(
            requestedIndex = 3,
            loadedIndex = 3,
            hasLoadedMessages = true,
        )

        assertTrue(result)
    }

    @Test
    fun `does not skip current history item when messages are empty`() {
        val result = shouldSkipReloadingLoadedHistory(
            requestedIndex = 3,
            loadedIndex = 3,
            hasLoadedMessages = false,
        )

        assertFalse(result)
    }

    @Test
    fun `does not skip different history item`() {
        val result = shouldSkipReloadingLoadedHistory(
            requestedIndex = 4,
            loadedIndex = 3,
            hasLoadedMessages = true,
        )

        assertFalse(result)
    }

    @Test
    fun `only latest history load generation may commit state`() {
        assertFalse(isCurrentHistoryLoad(requestGeneration = 7L, currentGeneration = 8L))
        assertTrue(isCurrentHistoryLoad(requestGeneration = 8L, currentGeneration = 8L))
    }

    @Test
    fun `text history load job covers preparation through final commit`() {
        val appViewModelSource = source(
            "src/main/java/com/android/everytalk/statecontroller/viewmodel/AppViewModel.kt",
            "app/src/main/java/com/android/everytalk/statecontroller/viewmodel/AppViewModel.kt",
            "app1/app/src/main/java/com/android/everytalk/statecontroller/viewmodel/AppViewModel.kt",
        ) + source(
            "src/main/java/com/android/everytalk/statecontroller/viewmodel/AppViewModelConversationActions.kt",
            "app/src/main/java/com/android/everytalk/statecontroller/viewmodel/AppViewModelConversationActions.kt",
            "app1/app/src/main/java/com/android/everytalk/statecontroller/viewmodel/AppViewModelConversationActions.kt",
        ) + source(
            "src/main/java/com/android/everytalk/statecontroller/viewmodel/AppViewModelActions.kt",
            "app/src/main/java/com/android/everytalk/statecontroller/viewmodel/AppViewModelActions.kt",
            "app1/app/src/main/java/com/android/everytalk/statecontroller/viewmodel/AppViewModelActions.kt",
        )
        val historyControllerSource = source(
            "src/main/java/com/android/everytalk/statecontroller/controller/conversation/HistoryController.kt",
            "app/src/main/java/com/android/everytalk/statecontroller/controller/conversation/HistoryController.kt",
            "app1/app/src/main/java/com/android/everytalk/statecontroller/controller/conversation/HistoryController.kt",
        )
        val textLoadFunction = historyControllerSource
            .substringAfter("suspend fun loadTextHistory(index: Int)")
            .substringBefore("fun loadImageHistory")

        assertTrue(appViewModelSource.contains("textHistoryLoadJob?.cancel()"))
        assertTrue(appViewModelSource.contains("previousHistoryLoadJob?.cancelAndJoin()"))
        assertTrue(appViewModelSource.contains("historyController.loadTextHistory(resolvedIndex)"))
        assertTrue(
            appViewModelSource
                .substringAfter("fun startNewChat()")
                .substringBefore("fun startNewImageGeneration()")
                .contains("cancelPendingTextHistoryLoad()")
        )
        assertTrue(historyControllerSource.contains("suspend fun loadTextHistory(index: Int)"))
        assertFalse(textLoadFunction.contains("scope.launch"))
    }

    private fun source(vararg candidates: String): String {
        val sourceFile = candidates.asSequence().map(::File).firstOrNull(File::isFile)
        requireNotNull(sourceFile) { "找不到源码文件: ${candidates.joinToString()}" }
        return sourceFile.readText(Charsets.UTF_8)
    }
}
