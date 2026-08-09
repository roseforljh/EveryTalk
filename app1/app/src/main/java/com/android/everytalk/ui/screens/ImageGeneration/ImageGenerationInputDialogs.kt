package com.android.everytalk.ui.screens.ImageGeneration
import com.android.everytalk.statecontroller.*

import kotlin.math.max
import android.Manifest
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.android.everytalk.R
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.ImageRatio
import com.android.everytalk.ui.components.modifier.diffuseShadow
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.ui.components.ImageRatioSelector
import com.android.everytalk.ui.components.ImageGenCapabilities
import com.android.everytalk.ui.components.ImageGenCapabilities.ModelFamily
import com.android.everytalk.ui.components.ImageGenCapabilities.QualityTier
import com.android.everytalk.ui.components.dialog.AppDialogShape
import com.android.everytalk.ui.components.dialog.AppDialogTextFieldShape
import com.android.everytalk.ui.components.dialog.appDialogBorderColor
import com.android.everytalk.ui.components.dialog.appDialogContainerColor
import com.android.everytalk.ui.components.dialog.appDialogContentColor
import com.android.everytalk.ui.components.dialog.appDialogTextFieldDefaultBorderColor
import com.android.everytalk.ui.components.dialog.appDialogTextFieldBorderColor
import com.android.everytalk.ui.components.dialog.appDialogTextFieldColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.android.everytalk.config.PerformanceConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID


@Composable
internal fun ImageGenerationInputDialogs(
    showStepsDialog: Boolean,
    onShowStepsDialogChange: (Boolean) -> Unit,
    currentImageSteps: Int?,
    onChangeImageSteps: ((Int) -> Unit)?,
    showParamsDialog: Boolean,
    onShowParamsDialogChange: (Boolean) -> Unit,
    currentImageGuidance: Float?,
    onChangeImageParams: ((Int, Float) -> Unit)?,
    showGptQualityDialog: Boolean,
    onShowGptQualityDialogChange: (Boolean) -> Unit,
    currentGptImageQuality: ImageGenCapabilities.GptImageQuality,
    onGptImageQualityChanged: ((ImageGenCapabilities.GptImageQuality) -> Unit)?,
    showRatioDialog: Boolean,
    onShowRatioDialogChange: (Boolean) -> Unit,
    selectedImageRatio: ImageRatio,
    onImageRatioChanged: (ImageRatio) -> Unit,
    allowedRatioNames: List<String>?,
    detectedFamily: ImageGenCapabilities.ModelFamily?,
    seedreamQuality: ImageGenCapabilities.QualityTier,
    onSeedreamQualityChange: (ImageGenCapabilities.QualityTier) -> Unit,
    selectedApiConfig: ApiConfig?,
    onGeminiImageSizeChanged: ((String) -> Unit)?,
    context: Context,
    tempCameraImageUri: Uri?,
) {
    if (showStepsDialog && onChangeImageSteps != null) {
            var stepsValue by remember(currentImageSteps) { mutableFloatStateOf((currentImageSteps ?: 4).toFloat()) }
            var stepsText by remember(currentImageSteps) { mutableStateOf((currentImageSteps ?: 4).toString()) }
            val textFieldBorderColor = appDialogTextFieldBorderColor()
            val textFieldDefaultBorderColor = appDialogTextFieldDefaultBorderColor()

            AlertDialog(
                onDismissRequest = { onShowStepsDialogChange(false) },
                modifier = Modifier.border(1.dp, appDialogBorderColor(), AppDialogShape),
                shape = AppDialogShape,
                containerColor = appDialogContainerColor(),
                titleContentColor = appDialogContentColor(),
                textContentColor = appDialogContentColor(),
                title = { Text(stringResource(R.string.image_adjust_inference_steps)) },
                text = {
                    Column {
                        Text(
                            text = stringResource(R.string.image_inference_steps_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Slider(
                                value = stepsValue,
                                onValueChange = {
                                    stepsValue = it
                                    stepsText = it.toInt().toString()
                                },
                                valueRange = 1f..20f,
                                steps = 19,
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.White,
                                    activeTrackColor = Color.White,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                                    activeTickColor = Color.Transparent,
                                    inactiveTickColor = Color.Transparent
                                )
                            )
                            
                            Spacer(modifier = Modifier.width(12.dp))
                            
                            OutlinedTextField(
                                value = stepsText,
                                onValueChange = { newValue: String ->
                                    if (newValue.all { char -> char.isDigit() }) {
                                        stepsText = newValue
                                        val num = newValue.toIntOrNull()
                                        if (num != null) {
                                            stepsValue = num.coerceIn(1, 20).toFloat()
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .width(56.dp)
                                    .height(48.dp),
                                singleLine = true,
                                shape = CircleShape,
                                textStyle = LocalTextStyle.current.copy(
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    focusedBorderColor = textFieldBorderColor,
                                    unfocusedBorderColor = textFieldDefaultBorderColor,
                                    disabledBorderColor = textFieldDefaultBorderColor.copy(alpha = 0.5f),
                                    cursorColor = textFieldBorderColor,
                                )
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val finalSteps = stepsValue.toInt().coerceIn(1, 20)
                            onChangeImageSteps(finalSteps)
                            onShowStepsDialogChange(false)
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text(stringResource(R.string.action_confirm))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { onShowStepsDialogChange(false) },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }

    if (showParamsDialog && onChangeImageParams != null) {
        var stepsText by remember(currentImageSteps) { mutableStateOf((currentImageSteps ?: 30).toString()) }
        var guidanceText by remember(currentImageGuidance) { mutableStateOf((currentImageGuidance ?: 7.5f).toString()) }

        AlertDialog(
            onDismissRequest = { onShowParamsDialogChange(false) },
            modifier = Modifier.border(1.dp, appDialogBorderColor(), AppDialogShape),
            shape = AppDialogShape,
            containerColor = appDialogContainerColor(),
            titleContentColor = appDialogContentColor(),
            textContentColor = appDialogContentColor(),
            title = { Text(stringResource(R.string.image_adjust_generation_parameters)) },
            text = {
                Column {
                    // 推理步数
                    Text(
                        text = stringResource(R.string.image_inference_steps_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    OutlinedTextField(
                        value = stepsText,
                        onValueChange = { newValue ->
                            if (newValue.all { it.isDigit() }) {
                                stepsText = newValue
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = AppDialogTextFieldShape,
                        placeholder = { Text(stringResource(R.string.image_steps_recommended_hint)) },
                        colors = appDialogTextFieldColors()
                    )
                    Text(
                        text = stringResource(R.string.image_steps_recommended_range),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                    )

                    // 引导系数
                    Text(
                        text = stringResource(R.string.image_guidance_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    OutlinedTextField(
                        value = guidanceText,
                        onValueChange = { newValue ->
                            if (newValue.all { it.isDigit() || it == '.' }) {
                                guidanceText = newValue
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = AppDialogTextFieldShape,
                        placeholder = { Text(stringResource(R.string.image_guidance_recommended_hint)) },
                        colors = appDialogTextFieldColors()
                    )
                    Text(
                        text = stringResource(R.string.image_guidance_recommended_range),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val finalSteps = stepsText.toIntOrNull()?.coerceIn(1, 50) ?: 30
                        val finalGuidance = guidanceText.toFloatOrNull()?.coerceIn(1f, 10f) ?: 7.5f
                        onChangeImageParams(finalSteps, finalGuidance)
                        onShowParamsDialogChange(false)
                    }
                ) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { onShowParamsDialogChange(false) }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showGptQualityDialog && onGptImageQualityChanged != null) {
        AlertDialog(
            onDismissRequest = { onShowGptQualityDialogChange(false) },
            modifier = Modifier.border(1.dp, appDialogBorderColor(), AppDialogShape),
            shape = AppDialogShape,
            containerColor = appDialogContainerColor(),
            titleContentColor = appDialogContentColor(),
            textContentColor = appDialogContentColor(),
            title = { Text(stringResource(R.string.image_select_quality)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.image_quality_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    ImageGenCapabilities.getGptImageQualities().forEach { quality ->
                        val isSelected = quality == currentGptImageQuality
                        Surface(
                            onClick = {
                                onGptImageQualityChanged(quality)
                                onShowGptQualityDialogChange(false)
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(quality.displayNameRes),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { onShowGptQualityDialogChange(false) }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showRatioDialog) {
        com.android.everytalk.ui.components.ImageRatioSelectionDialog(
            selectedRatio = selectedImageRatio,
            onRatioSelected = onImageRatioChanged,
            onDismiss = { onShowRatioDialogChange(false) },
            allowedRatioNames = allowedRatioNames,
            family = detectedFamily,
            seedreamQuality = seedreamQuality,
            onQualityChange = { onSeedreamQualityChange(it) },
            geminiImageSize = selectedApiConfig?.imageSize,
            onGeminiImageSizeChange = onGeminiImageSizeChanged
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            tempCameraImageUri?.let { uri ->
                safeDeleteTempFile(context, uri)
            }
        }
    }
}
