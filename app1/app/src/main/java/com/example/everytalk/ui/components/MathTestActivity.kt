package com.example.everytalk.ui.components

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 数学公式组件测试活动
 */
class MathTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MathTestScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MathTestScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "数学公式组件测试",
            style = MaterialTheme.typography.headlineMedium
        )
        
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("KaTeX数学公式测试:", style = MaterialTheme.typography.titleMedium)
                
                // 根据主题设置数学公式的正确颜色
                val mathTextColor = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) {
                    Color.Black // 浅色主题使用纯黑色
                } else {
                    Color.White // 深色主题使用纯白色
                }
                
                // 基本符号
                MathView(
                    latex = "\\alpha + \\beta = \\gamma",
                    isDisplay = false,
                    textColor = mathTextColor,
                    textSize = 16.sp
                )
                
                // 分数和根号
                MathView(
                    latex = "x = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}",
                    isDisplay = true,
                    textColor = mathTextColor,
                    textSize = 18.sp
                )
                
                // 积分
                MathView(
                    latex = "\\int_0^{\\infty} e^{-x^2} dx = \\frac{\\sqrt{\\pi}}{2}",
                    isDisplay = true,
                    textColor = mathTextColor,
                    textSize = 18.sp
                )
                
                // 矩阵
                MathView(
                    latex = "\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}",
                    isDisplay = true,
                    textColor = mathTextColor,
                    textSize = 18.sp
                )
                
                // 欧拉公式
                MathView(
                    latex = "e^{i\\pi} + 1 = 0",
                    isDisplay = true,
                    textColor = mathTextColor,
                    textSize = 20.sp
                )
            }
        }
        
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("SimpleMathView测试:", style = MaterialTheme.typography.titleMedium)
                
                SimpleMathView(
                    expression = "x^2 + y^2 = z^2",
                    textColor = MaterialTheme.colorScheme.primary,
                    textSize = 14.sp
                )
                
                SimpleMathView(
                    expression = "E = mc^2",
                    textColor = MaterialTheme.colorScheme.secondary,
                    textSize = 16.sp
                )
            }
        }
        
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("SmartMathView测试:", style = MaterialTheme.typography.titleMedium)
                
                // 根据主题设置数学公式的正确颜色
                val mathTextColor = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) {
                    Color.Black // 浅色主题使用纯黑色
                } else {
                    Color.White // 深色主题使用纯白色
                }
                
                SmartMathView(
                    expression = "\\int_0^\\infty e^{-x} dx = 1",
                    textColor = mathTextColor,
                    textSize = 16.sp,
                    isDisplay = true
                )
                
                SmartMathView(
                    expression = "\\sqrt{a^2 + b^2}",
                    textColor = mathTextColor,
                    textSize = 14.sp
                )
            }
        }
        
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("WebMathView (向后兼容)测试:", style = MaterialTheme.typography.titleMedium)
                
                // 根据主题设置数学公式的正确颜色
                val mathTextColor = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) {
                    Color.Black // 浅色主题使用纯黑色
                } else {
                    Color.White // 深色主题使用纯白色
                }
                
                WebMathView(
                    latex = "\\theta = \\arctan(\\frac{y}{x})",
                    isDisplay = false,
                    textColor = mathTextColor
                )
            }
        }
    }
}