package com.android.everytalk.ui.screens.settings
import com.android.everytalk.statecontroller.*

import android.util.Log
import android.view.WindowManager
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.Surface
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.android.everytalk.R
import com.android.everytalk.ui.components.dialog.appDialogTextFieldDefaultBorderColor
import com.android.everytalk.ui.components.dialog.appDialogTextFieldBorderColor
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.PopupProperties
import com.android.everytalk.data.DataClass.ModalityType
import com.android.everytalk.data.network.ExternalWebSearchProvider

@get:StringRes
internal val ModalityType.displayNameRes: Int
    get() = when (this) {
        ModalityType.TEXT -> R.string.modality_text
        ModalityType.IMAGE -> R.string.modality_image
        ModalityType.AUDIO -> R.string.modality_audio
        ModalityType.VIDEO -> R.string.modality_video
        ModalityType.MULTIMODAL -> R.string.modality_multimodal
    }

@get:StringRes
internal val ExternalWebSearchProvider.descriptionRes: Int
    get() = when (this) {
        ExternalWebSearchProvider.TAVILY -> R.string.web_provider_tavily_description
        ExternalWebSearchProvider.EXA -> R.string.web_provider_exa_description
        ExternalWebSearchProvider.BOCHA -> R.string.web_provider_bocha_description
        ExternalWebSearchProvider.SERPAPI -> R.string.web_provider_serpapi_description
    }

@Composable
internal fun localizedProviderLabel(provider: String): String = when (provider.trim().lowercase()) {
    "默认", "default", "default_text" -> stringResource(R.string.settings_provider_default)
    "google", "gemini", "谷歌" -> stringResource(R.string.settings_provider_google)
    "硅基流动", "siliconflow" -> stringResource(R.string.settings_provider_siliconflow)
    "阿里云百炼" -> stringResource(R.string.settings_provider_aliyun_bailian)
    "火山引擎" -> stringResource(R.string.settings_provider_volcengine)
    "深度求索", "deepseek" -> stringResource(R.string.settings_provider_deepseek)
    else -> provider
}

@Composable
internal fun localizedChannelLabel(channel: String): String = when (channel.trim().lowercase()) {
    "openai兼容", "openai compatible" -> stringResource(R.string.settings_openai_compatible)
    else -> channel
}

val DialogTextFieldColors
    @Composable get() = run {
        val borderColor = appDialogTextFieldBorderColor()
        val defaultBorderColor = appDialogTextFieldDefaultBorderColor()
        OutlinedTextFieldDefaults.colors(
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            cursorColor = borderColor,
            focusedBorderColor = borderColor,
            unfocusedBorderColor = defaultBorderColor,
            disabledBorderColor = defaultBorderColor.copy(alpha = 0.5f),
            focusedLabelColor = borderColor,
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            disabledLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent
        )
    }
val DialogShape = RoundedCornerShape(16.dp)

