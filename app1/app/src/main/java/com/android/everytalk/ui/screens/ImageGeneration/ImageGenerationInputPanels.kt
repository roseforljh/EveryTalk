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
internal fun ImageFunctionPanelContent(
    supportsImageEditing: Boolean,
    hasContent: Boolean,
    isQwenEdit: Boolean,
    detectedFamily: ImageGenCapabilities.ModelFamily?,
    onChangeImageSteps: ((Int) -> Unit)?,
    onChangeImageParams: ((Int, Float) -> Unit)?,
    onOpenGallery: () -> Unit,
    onOpenCamera: () -> Unit,
    onShowRatioDialog: () -> Unit,
    onShowStepsDialog: () -> Unit,
    onShowParamsDialog: () -> Unit,
    onClearContent: () -> Unit,
    currentImageSteps: Int?,
    selectedImageRatio: ImageRatio,
    maxHeight: androidx.compose.ui.unit.Dp = 370.dp,
    isGptImage: Boolean = false,
    currentGptImageQuality: ImageGenCapabilities.GptImageQuality = ImageGenCapabilities.GptImageQuality.AUTO,
    onShowQualityDialog: () -> Unit = {}
) {
    val isDark = isSystemInDarkTheme()
    val cardBg = if (isDark) Color(0xFF212121) else Color(0xFFFFFFFF)
    val borderColor = if (isDark) Color.White.copy(alpha = 0.10f) else Color(0xFF0D0D0D).copy(alpha = 0.05f)
    val iconBg = if (isDark) Color(0xFF3B3B3B) else Color(0xFFE8E8E8)
    val textColor = if (isDark) Color.White else Color(0xFF0D0D0D)
    val iconTint = if (isDark) Color.White else Color(0xFF0D0D0D)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .shadow(8.dp, RoundedCornerShape(28.dp))
            .border(1.dp, borderColor, RoundedCornerShape(28.dp)),
        shape = RoundedCornerShape(28.dp),
        color = cardBg
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            if (!isQwenEdit) {
                ImageFunctionPanelRow(
                    iconRes = R.drawable.ic_aspect_ratio,
                    label = "比例: ${selectedImageRatio.displayName}",
                    iconBg = iconBg,
                    iconTint = Color(0xFF66B5FF),
                    textColor = textColor,
                    onClick = onShowRatioDialog
                )
            }
            if (isGptImage) {
                ImageFunctionPanelRow(
                    iconRes = R.drawable.ic_settings_slider,
                    label = "质量: ${currentGptImageQuality.displayName}",
                    iconBg = iconBg,
                    iconTint = Color(0xFF9C27B0),
                    textColor = textColor,
                    onClick = onShowQualityDialog
                )
            }
            if (supportsImageEditing) {
                ImageFunctionPanelRow(
                    iconRes = R.drawable.ic_image_gallery,
                    label = "选择图片",
                    iconBg = iconBg,
                    iconTint = Color(0xff2cb334),
                    textColor = textColor,
                    onClick = onOpenGallery
                )
                ImageFunctionPanelRow(
                    iconRes = R.drawable.ic_camera,
                    label = "拍照",
                    iconBg = iconBg,
                    iconTint = Color(0xFF2196F3),
                    textColor = textColor,
                    onClick = onOpenCamera
                )
            }
            if (detectedFamily == ImageGenCapabilities.ModelFamily.MODAL_Z_IMAGE && onChangeImageSteps != null) {
                ImageFunctionPanelRow(
                    iconRes = R.drawable.ic_tuning,
                    label = "步数: ${currentImageSteps ?: 4}",
                    iconBg = iconBg,
                    iconTint = Color(0xFF66B5FF),
                    textColor = textColor,
                    onClick = onShowStepsDialog
                )
            }
            if (isQwenEdit && onChangeImageParams != null) {
                ImageFunctionPanelRow(
                    iconRes = R.drawable.ic_tuning,
                    label = "参数调节",
                    iconBg = iconBg,
                    iconTint = Color(0xFFFF9800),
                    textColor = textColor,
                    onClick = onShowParamsDialog
                )
            }
            if (hasContent) {
                ImageFunctionPanelRow(
                    iconRes = R.drawable.ic_close,
                    label = "清除内容",
                    iconBg = iconBg,
                    iconTint = iconTint,
                    textColor = textColor,
                    onClick = onClearContent
                )
            }
        }
    }
}

@Composable
internal fun ImageFunctionPanelRow(
    iconRes: Int,
    label: String,
    iconBg: Color,
    iconTint: Color,
    textColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(iconBg, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = label,
            fontSize = 18.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
            color = textColor,
            modifier = Modifier.weight(1f)
        )
    }
}
