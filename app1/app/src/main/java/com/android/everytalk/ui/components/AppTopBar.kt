package com.android.everytalk.ui.components
 
 import androidx.compose.animation.animateColorAsState
 import androidx.compose.animation.core.Animatable
 import androidx.compose.animation.core.FastOutSlowInEasing
 import androidx.compose.animation.core.RepeatMode
 import androidx.compose.animation.core.infiniteRepeatable
 import androidx.compose.animation.core.tween
 import androidx.compose.foundation.background
 import androidx.compose.foundation.border
 import androidx.compose.foundation.clickable
 import androidx.compose.foundation.interaction.MutableInteractionSource
 import androidx.compose.foundation.layout.*
 import androidx.compose.foundation.isSystemInDarkTheme
 import androidx.compose.foundation.shape.CircleShape
 import androidx.compose.material.icons.Icons
 import androidx.compose.material.icons.filled.Menu
 import androidx.compose.material.icons.filled.Settings
 import androidx.compose.material3.*
 import androidx.compose.runtime.Composable
 import androidx.compose.runtime.LaunchedEffect
 import androidx.compose.runtime.getValue
 import androidx.compose.runtime.remember
 import androidx.compose.runtime.rememberCoroutineScope
 import androidx.compose.ui.Alignment
 import androidx.compose.ui.Modifier
 import androidx.compose.ui.draw.clip
 import androidx.compose.ui.draw.scale
 import androidx.compose.ui.graphics.Color
 import androidx.compose.ui.text.font.FontWeight
 import androidx.compose.ui.text.style.TextAlign
 import androidx.compose.ui.text.style.TextOverflow
 import androidx.compose.ui.unit.Dp
 import androidx.compose.ui.unit.TextUnit
 import androidx.compose.ui.unit.dp
 import androidx.compose.ui.unit.sp
 import com.android.everytalk.ui.theme.DarkGreen
 import kotlinx.coroutines.launch
 
 @Composable
 fun AppTopBar(
     selectedConfigName: String,
     onMenuClick: () -> Unit,
     onSettingsClick: () -> Unit,
     onTitleClick: () -> Unit,
     onSystemPromptClick: () -> Unit,
     systemPrompt: String,
     isSystemPromptExpanded: Boolean,
     // 新增：系统提示接入开关与回调（有默认值，避免现有调用方编译失败）
     isSystemPromptEngaged: Boolean = false,
     onToggleSystemPromptEngaged: () -> Unit = {},
     modifier: Modifier = Modifier,
     // 恢复首页顶栏原始高度与控件尺寸
     barHeight: Dp = 85.dp,
     contentPaddingHorizontal: Dp = 8.dp,
     bottomAlignPadding: Dp = 12.dp,
     titleFontSize: TextUnit = 12.sp,
     iconButtonSize: Dp = 36.dp,
     iconSize: Dp = 22.dp
 ) {
     val coroutineScope = rememberCoroutineScope()
     Surface(
         modifier = modifier
             .fillMaxWidth()
             .height(barHeight)
             .background(MaterialTheme.colorScheme.background),
 
         color = MaterialTheme.colorScheme.background,
     ) {
         Row(
             modifier = Modifier
                 .fillMaxSize()
                 .padding(horizontal = contentPaddingHorizontal),
             horizontalArrangement = Arrangement.SpaceBetween,
             verticalAlignment = Alignment.Bottom
         ) {
             Box(
                 modifier = Modifier
                     .size(iconButtonSize)
                     .padding(bottom = bottomAlignPadding),
                 contentAlignment = Alignment.Center
             ) {
                 IconButton(onClick = onMenuClick) {
                     Icon(
                         imageVector = Icons.Filled.Menu,
                         contentDescription = "打开导航菜单",
                         tint = MaterialTheme.colorScheme.onSurface,
                         modifier = Modifier.size(iconSize)
                     )
                 }
             }
 
             Box(
                 modifier = Modifier
                     .weight(1f)
                     .padding(bottom = bottomAlignPadding - 4.dp),
                 contentAlignment = Alignment.Center
             ) {
                  Row(
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.Center,
                      modifier = Modifier.fillMaxWidth().offset(x = 15.dp)
                  ) {
                      // 胶囊 - 添加与代码块一样的边框
                      val isDark = isSystemInDarkTheme()
                      val borderColor = if (isDark) Color(0xFF3E3E42) else Color(0xFFD0D7DE)
                      
                      Surface(
                          shape = CircleShape,
                          color = if (isDark) Color.Black else MaterialTheme.colorScheme.surfaceDim,
                          modifier = Modifier
                              .height(28.dp)
                              .wrapContentWidth(unbounded = true)
                              .widthIn(max = 200.dp) // 限制最大宽度
                              .border(1.dp, borderColor, CircleShape)
                              .clip(CircleShape)
                              .clickable(onClick = onTitleClick)
                      ) {
                          Text(
                              text = selectedConfigName,
                              color = MaterialTheme.colorScheme.onSurfaceVariant,
                              fontSize = titleFontSize,
                              fontWeight = FontWeight.Medium,
                              textAlign = TextAlign.Center,
                              maxLines = 1,
                              overflow = TextOverflow.Ellipsis,
                              modifier = Modifier
                                  .padding(horizontal = 12.dp, vertical = 4.dp)
                                  .offset(y = (-1.8).dp)
                          )
                      }
 
                      // 圆点（点击进入系统提示页面；开始接入时播放动画）
                      val clickAnimationScale = remember { Animatable(1f) }
                      Box(
                          modifier = Modifier
                              .size(iconButtonSize)
                              .clickable(
                                  onClick = {
                                      onSystemPromptClick()
                                      coroutineScope.launch {
                                          clickAnimationScale.animateTo(1.2f, animationSpec = tween(100))
                                          clickAnimationScale.animateTo(1f, animationSpec = tween(100))
                                      }
                                  },
                                  indication = null,
                                  interactionSource = remember { MutableInteractionSource() }
                              ),
                          contentAlignment = Alignment.Center
                      ) {
                          val scale = remember { Animatable(1f) }
                          // 仅在“接入开启”时变为深绿；暂停或展开时使用主题主色
                          val targetColor = if (isSystemPromptEngaged && !isSystemPromptExpanded) DarkGreen else MaterialTheme.colorScheme.primary
                          val color by animateColorAsState(
                              targetValue = targetColor,
                              animationSpec = tween(durationMillis = 1200, easing = FastOutSlowInEasing), label = ""
                          )

                          // 动画仅在“接入开启”时播放；暂停或展开时不播放
                          LaunchedEffect(isSystemPromptExpanded, isSystemPromptEngaged) {
                              scale.stop()
                              when {
                                  isSystemPromptExpanded -> scale.animateTo(1.25f, tween(300, easing = FastOutSlowInEasing))
                                  isSystemPromptEngaged -> {
                                      scale.snapTo(1.0f)
                                      scale.animateTo(1.25f, infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse))
                                  }
                                  else -> scale.animateTo(1.0f, tween(300, easing = FastOutSlowInEasing))
                              }
                          }

                          Box(
                              modifier = Modifier
                                  .size(8.dp)
                                  .scale(scale.value * clickAnimationScale.value)
                                  .background(color, CircleShape)
                          )
                      }
                  }
             }
 
             Box(
                 modifier = Modifier
                     .size(iconButtonSize)
                     .padding(bottom = bottomAlignPadding),
                 contentAlignment = Alignment.Center
             ) {
                 IconButton(onClick = onSettingsClick) {
                     Icon(
                         imageVector = Icons.Filled.Settings,
                         contentDescription = "设置",
                         tint = MaterialTheme.colorScheme.onSurface,
                         modifier = Modifier.size(iconSize)
                     )
                 }
             }
         }
     }
 }