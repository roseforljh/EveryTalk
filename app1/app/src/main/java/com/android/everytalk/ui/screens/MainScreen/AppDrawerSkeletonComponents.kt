package com.android.everytalk.ui.screens.MainScreen
import com.android.everytalk.statecontroller.*

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.res.painterResource
import com.android.everytalk.R
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.ui.screens.MainScreen.drawer.* // 导入抽屉子包下的所有内容
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import com.android.everytalk.ui.components.dialog.AppDialogButtonShape
import com.android.everytalk.ui.components.dialog.AppDialogShape
import com.android.everytalk.ui.components.dialog.AppDialogTextFieldShape
import com.android.everytalk.ui.components.dialog.appDialogBorderColor
import com.android.everytalk.ui.components.dialog.appDialogCancelColor
import com.android.everytalk.ui.components.dialog.appDialogContainerColor
import com.android.everytalk.ui.components.dialog.appDialogContentColor
import com.android.everytalk.ui.components.dialog.appDialogTextFieldColors

@Composable
internal fun HistorySkeletonLoading() {
    val isDark = isSystemInDarkTheme()
    val baseColor = if (isDark) Color.White else Color.Black

    val infiniteTransition = rememberInfiniteTransition(label = "skeletonPulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.06f,
        targetValue = 0.14f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, 0, FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeletonAlpha",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(6) { index ->
            SkeletonItem(
                color = baseColor.copy(alpha = alpha),
                widthFraction = when (index % 3) {
                    0 -> 0.85f
                    1 -> 0.65f
                    else -> 0.75f
                },
            )
        }
    }
}

@Composable
internal fun SkeletonItem(
    color: Color,
    widthFraction: Float,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(widthFraction)
                .height(14.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(widthFraction * 0.6f)
                .height(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color),
        )
    }
}
