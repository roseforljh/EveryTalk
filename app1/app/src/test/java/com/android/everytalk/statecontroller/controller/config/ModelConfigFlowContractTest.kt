package com.android.everytalk.statecontroller.controller.config

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelConfigFlowContractTest {

    @Test
    fun `auto fetch passes channel without shared loading wait`() {
        val source = sourceFile("statecontroller/AppViewModel.kt")

        assertTrue(source.contains("fetchModels(params.address, params.key, params.channel)"))
        assertFalse(source.contains("isFetchingModels"))
        assertFalse(source.contains("flatMapLatest { fetching"))
    }

    @Test
    fun `manual input stays in view model and preserves configuration parameters`() {
        val viewModel = sourceFile("statecontroller/AppViewModel.kt")
        val settings = sourceFile("ui/screens/settings/SettingsScreen.kt")
        val imageSettings = sourceFile("ui/screens/ImageGeneration/ImageGenerationSettingsScreen.kt")

        assertTrue(viewModel.contains("fun submitManualModel(modelName: String)"))
        assertTrue(viewModel.contains("enableCodeExecution = params.enableCodeExecution"))
        assertTrue(viewModel.contains("toolsJson = params.toolsJson"))
        assertTrue(viewModel.contains("imageSize = params.imageSize"))
        assertTrue(viewModel.contains("numInferenceSteps = params.numInferenceSteps"))
        assertTrue(viewModel.contains("guidanceScale = params.guidanceScale"))
        assertTrue(settings.contains("viewModel.submitManualModel(modelName)"))
        assertTrue(settings.contains("viewModel.dismissManualModelInput()"))
        assertTrue(imageSettings.contains("viewModel.submitManualModel(modelName)"))
        assertTrue(imageSettings.contains("viewModel.dismissManualModelInput()"))
    }

    @Test
    fun `image flow and selection dialog keep their complete contract`() {
        val imageSettings = sourceFile("ui/screens/ImageGeneration/ImageGenerationSettingsScreen.kt")
        val selectionDialog = sourceFile("ui/screens/settings/dialogs/ModelSelectionDialog.kt")
        val settingsContent = sourceFile("ui/screens/settings/SettingsScreenContent.kt")

        assertTrue(imageSettings.contains("imageSize = imageSize"))
        assertTrue(imageSettings.contains("numInferenceSteps = numInferenceSteps"))
        assertTrue(imageSettings.contains("guidanceScale = guidanceScale"))
        assertTrue(selectionDialog.contains("LaunchedEffect(showDialog, models)"))
        assertTrue(selectionDialog.contains("Text(\"手动输入模型\""))
        assertTrue(settingsContent.contains("modelConfigGroupId(configsForKeyAndModality.first())"))
    }

    private fun sourceFile(relativePath: String): String {
        return sequenceOf(
            File("src/main/java/com/android/everytalk/$relativePath"),
            File("app/src/main/java/com/android/everytalk/$relativePath"),
            File("app1/app/src/main/java/com/android/everytalk/$relativePath"),
        ).first { it.isFile }.readText(Charsets.UTF_8)
    }
}
