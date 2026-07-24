package com.android.everytalk.statecontroller

internal fun ViewModelStateHolder.incrementImageGenerationRetryCount() {
    _imageGenerationRetryCount.value += 1
}

internal fun ViewModelStateHolder.resetImageGenerationRetryCount() {
    _imageGenerationRetryCount.value = 0
}

internal fun ViewModelStateHolder.setImageGenerationError(error: String) {
    _imageGenerationError.value = error
}

internal fun ViewModelStateHolder.showImageGenerationErrorDialog(show: Boolean) {
    _shouldShowImageGenerationError.value = show
}

internal fun ViewModelStateHolder.dismissImageGenerationErrorDialog() {
    _shouldShowImageGenerationError.value = false
    _imageGenerationError.value = null
}
