package com.android.everytalk.ui.screens.MainScreen.chat.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import com.android.everytalk.data.DataClass.ApiConfig
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectionBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    availableModels: List<ApiConfig>,
    selectedApiConfig: ApiConfig?,
    onModelSelected: (ApiConfig) -> Unit,
    allApiConfigs: List<ApiConfig>,
    onPlatformSelected: (ApiConfig) -> Unit
) {
    var searchText by remember { mutableStateOf("") }
    var showPlatformDialog by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    
    // 滑动状态管理
    var isScrolling by remember { mutableStateOf(false) }
    var lastScrollTime by remember { mutableStateOf(0L) }
    var consecutiveScrollCount by remember { mutableStateOf(0) }
    var lastScrollDirection by remember { mutableStateOf(0f) }
    var scrollVelocityBuffer by remember { mutableStateOf(mutableListOf<Float>()) }
    
    // 检查列表是否在顶部
    val isAtTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= 3
        }
    }
    
    // 检查列表是否可以滚动
    val canScrollVertically by remember {
        derivedStateOf {
            listState.canScrollForward || listState.canScrollBackward
        }
    }
    
    val platforms = allApiConfigs.map { it.provider }.distinct()

    // 🆕 过滤并排序模型：默认配置的模型置顶
    val filteredModels = availableModels
        .filter {
            it.name.contains(searchText, ignoreCase = true) || it.model.contains(searchText, ignoreCase = true)
        }
        .sortedWith(compareBy(
            { config ->
                // 默认配置排在最前面（返回0），其他配置返回1
                if (config.provider.trim().lowercase() in listOf("默认", "default")) 0 else 1
            },
            { it.name } // 同类配置内部按名称排序
        ))
    
    // 检查是否有足够的内容需要滚动
    val hasScrollableContent by remember {
        derivedStateOf {
            filteredModels.size > 3 // 如果模型数量大于3个，认为需要滚动
        }
    }
    
    // 平衡的 NestedScrollConnection 实现 - 智能拦截
    val nestedScrollConnection = remember(filteredModels.size) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val currentTime = System.currentTimeMillis()
                
                // 防抖机制
                if (currentTime - lastScrollTime < 16) { // 60fps间隔
                    return Offset.Zero
                }
                
                // 检测连续快速滑动
                if (currentTime - lastScrollTime < 100) {
                    consecutiveScrollCount++
                } else {
                    consecutiveScrollCount = 0
                    scrollVelocityBuffer.clear()
                }
                
                // 记录滑动速度
                if (scrollVelocityBuffer.size > 5) {
                    scrollVelocityBuffer.removeAt(0)
                }
                scrollVelocityBuffer.add(available.y)
                
                lastScrollTime = currentTime
                lastScrollDirection = available.y
                isScrolling = true
                
                return Offset.Zero
            }
            
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                // 只有在特定条件下才拦截向下滑动
                if (filteredModels.size > 3 && available.y > 0 && isAtTop) {
                    // 计算平均滑动速度
                    val avgVelocity = if (scrollVelocityBuffer.isNotEmpty()) {
                        scrollVelocityBuffer.average().toFloat()
                    } else 0f
                    
                    // 只有在快速连续滑动时才拦截
                    if (consecutiveScrollCount > 2 && abs(avgVelocity) > 8f) {
                        return Offset(x = 0f, y = available.y * 0.8f) // 部分消耗
                    }
                    
                    // 或者滑动距离很小时拦截（防止误触）
                    if (abs(available.y) < 5f) {
                        return Offset(x = 0f, y = available.y)
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                // 延迟重置滑动状态
                coroutineScope.launch {
                    kotlinx.coroutines.delay(200)
                    isScrolling = false
                    consecutiveScrollCount = 0
                    scrollVelocityBuffer.clear()
                }
                
                // 只有在快速连续滑动且速度很高时才拦截
                if (filteredModels.size > 3 && available.y > 0 && isAtTop) {
                    if (consecutiveScrollCount > 2 && abs(available.y) > 1000f) {
                        return Velocity(x = 0f, y = available.y * 0.7f) // 部分消耗
                    }
                }
                return Velocity.Zero
            }
        }
    }

    if (showPlatformDialog) {
        PlatformSelectionDialog(
            onDismissRequest = { showPlatformDialog = false },
            platforms = platforms,
            currentPlatform = selectedApiConfig?.provider,
            onConfirm = { provider ->
                allApiConfigs.firstOrNull { it.provider == provider }?.let {
                    onPlatformSelected(it)
                }
                showPlatformDialog = false
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .padding(bottom = 12.dp)
                .height(configuration.screenHeightDp.dp * 0.5f)
                .nestedScroll(nestedScrollConnection)
        ) {
            val borderColor = if (isSystemInDarkTheme()) Color.White.copy(alpha = 0.2f) else Color.Black.copy(alpha = 0.15f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                borderColor,
                                Color.Transparent
                            )
                        )
                    )
            )
            // 搜索和平台切换
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 搜索框背景根据主题自适应
                val isDark = isSystemInDarkTheme()
                val searchBarColor = if (isDark) Color(0xFF1F1F1F) else Color(0xFFF5F5F5)
                BasicTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    modifier = Modifier
                        .weight(1f),
                    singleLine = true,
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp
                    ),
                    decorationBox = { innerTextField ->
                        Row(
                            modifier = Modifier
                                .height(36.dp)
                                .shadow(8.dp, CircleShape, clip = false)
                                .background(searchBarColor, RoundedCornerShape(32.dp))
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.size(8.dp))
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                if (searchText.isEmpty()) {
                                    Text(
                                        "搜索模型...",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 14.sp
                                    )
                                }
                                innerTextField()
                            }
                        }
                    }
                )

                Spacer(modifier = Modifier.size(12.dp))

                // 切换平台按钮背景根据主题自适应
                val buttonBgColor = if (isDark) Color(0xFF1F1F1F) else Color(0xFFF5F5F5)
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .shadow(6.dp, RoundedCornerShape(32.dp), clip = false)
                        .clip(RoundedCornerShape(32.dp))
                        .background(buttonBgColor)
                        .clickable { showPlatformDialog = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Loop,
                        contentDescription = "切换平台",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // 标题部分
            Row(
                modifier = Modifier.padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 8.dp
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = "密钥图标",
                    tint = Color(0xff7bc047),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "当前密钥下的模型",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            // 列表部分 - 移除过度的触摸拦截
            Box(modifier = Modifier.weight(1f)) {
                if (filteredModels.isEmpty()) {
                    Text(
                        "没有可用的模型配置。",
                        modifier = Modifier
                            .padding(16.dp)
                            .align(Alignment.Center),
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 0.dp) // 列表本身的垂直内边距设为0，使列表项更紧凑
                    ) {
                        items(items = filteredModels, key = { it.id }) { modelConfig ->
                            val alpha = remember { Animatable(0f) }
                            val translationY = remember { Animatable(50f) }

                            LaunchedEffect(modelConfig.id) {
                                launch {
                                    alpha.animateTo(1f, animationSpec = tween(durationMillis = 300))
                                }
                                launch {
                                    translationY.animateTo(0f, animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing))
                                }
                            }

                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = modelConfig.name.ifEmpty { modelConfig.model },
                                        fontSize = 14.sp, // 较小的模型名称字体
                                        color = Color(0xff778899)  // 模型名称颜色为灰色
                                    )
                                },
                                supportingContent = {
                                    if (modelConfig.name.isNotEmpty() && modelConfig.model.isNotEmpty() && modelConfig.name != modelConfig.model) {
                                        Text(
                                            modelConfig.model,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontSize = 11.sp, // supporting text 字体
                                            color = Color.DarkGray
                                        )
                                    }
                                },
                                trailingContent = {
                                    if (modelConfig.id == selectedApiConfig?.id) {
                                        Icon(
                                            Icons.Filled.Done,
                                            contentDescription = "当前选中",
                                            tint = Color(0xff778899),
                                            modifier = Modifier.size(20.dp) // 较小的勾选图标
                                        )
                                    } else {
                                        Spacer(Modifier.size(20.dp)) // 保持对齐的占位符
                                    }
                                },
                                modifier = Modifier
                                    .graphicsLayer {
                                        this.alpha = alpha.value
                                        this.translationY = translationY.value
                                    }
                                    .clickable {
                                        onModelSelected(modelConfig)
                                    },
                                colors = ListItemDefaults.colors(
                                    containerColor = Color.Transparent // 保持背景透明
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PlatformSelectionDialog(
    onDismissRequest: () -> Unit,
    platforms: List<String>,
    currentPlatform: String?,
    onConfirm: (String) -> Unit
) {
    // 🆕 将"默认"平台置顶
    val sortedPlatforms = platforms.sortedWith(compareBy { platform ->
        if (platform.trim().lowercase() in listOf("默认", "default")) 0 else 1
    })
    
    var tempSelectedPlatform by remember { mutableStateOf(currentPlatform) }

    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(0.8f) }

    LaunchedEffect(Unit) {
        launch {
            alpha.animateTo(1f, animationSpec = tween(durationMillis = 300))
        }
        launch {
            scale.animateTo(1f, animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing))
        }
    }

    val isDarkTheme = isSystemInDarkTheme()
    val cancelButtonColor = if (isDarkTheme) Color(0xFFFF5252) else Color(0xFFD32F2F)
    val confirmButtonColor = if (isDarkTheme) Color.White else Color(0xFF212121)
    val confirmButtonTextColor = if (isDarkTheme) Color.Black else Color.White

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .graphicsLayer {
                    this.alpha = alpha.value
                    this.scaleX = scale.value
                    this.scaleY = scale.value
                },
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "切换平台",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.Start)
                )
                
                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(
                    modifier = Modifier.height(300.dp)
                ) {
                    items(sortedPlatforms) { platform ->
                        ListItem(
                            headlineContent = { Text(platform, color = MaterialTheme.colorScheme.onSurface) },
                            modifier = Modifier.clickable { tempSelectedPlatform = platform },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            trailingContent = {
                                if (tempSelectedPlatform == platform) {
                                    Icon(
                                        imageVector = Icons.Filled.CheckCircle,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Filled.RadioButtonUnchecked,
                                        contentDescription = "Unselected",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 取消按钮
                    OutlinedButton(
                        onClick = onDismissRequest,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = cancelButtonColor
                        ),
                        border = BorderStroke(1.dp, cancelButtonColor)
                    ) {
                        Text(
                            text = "取消",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }

                    // 确定按钮
                    Button(
                        onClick = { tempSelectedPlatform?.let { onConfirm(it) } },
                        enabled = tempSelectedPlatform != null && tempSelectedPlatform != currentPlatform,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = confirmButtonColor,
                            contentColor = confirmButtonTextColor,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    ) {
                        Text(
                            text = "确定",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }
    }
}