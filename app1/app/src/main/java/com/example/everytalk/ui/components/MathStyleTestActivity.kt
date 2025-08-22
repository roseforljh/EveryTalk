package com.example.everytalk.ui.components

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * 数学公式样式测试活动
 * 用于验证数学公式在不同主题下的样式显示
 */
class MathStyleTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MathStyleTestScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MathStyleTestScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "数学公式样式测试",
            style = MaterialTheme.typography.headlineMedium
        )
        
        Text(
            text = "测试不同主题下数学公式的背景色和文本颜色",
            style = MaterialTheme.typography.bodyMedium
        )
        
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Enhanced Markdown 测试:", style = MaterialTheme.typography.titleMedium)
                
                val testMarkdown = """
                    这里有一个内联数学公式：${'$'}E = mc^2${'$'}，它应该根据主题显示不同的背景色。
                    
                    另一个内联公式：${'$'}\pi \approx 3.14159${'$'}，用于测试希腊字母的显示。
                    
                    欧拉公式：
                    ${'$'}${'$'}e^{i\pi} + 1 = 0${'$'}${'$'}
                    
                    二次方程求根公式：
                    ${'$'}${'$'}x = \frac{-b \pm \sqrt{b^2 - 4ac}}{2a}${'$'}${'$'}
                    
                    在这段文字中，我们有内联公式 ${'$'}\alpha + \beta = \gamma${'$'}，然后是普通文本。
                """.trimIndent()
                
                EnhancedMarkdownText(
                    markdown = testMarkdown,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("直接 MathView 测试:", style = MaterialTheme.typography.titleMedium)
                
                // 根据主题设置数学公式的正确颜色
                val mathTextColor = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) {
                    Color.Black // 浅色主题使用纯黑色
                } else {
                    Color.White // 深色主题使用纯白色
                }
                
                Text("内联数学公式:", style = MaterialTheme.typography.bodyMedium)
                MathView(
                    latex = "E = mc^2",
                    isDisplay = false,
                    textColor = mathTextColor
                )
                
                Text("块级数学公式:", style = MaterialTheme.typography.bodyMedium)
                MathView(
                    latex = "\\int_0^{\\infty} e^{-x^2} dx = \\frac{\\sqrt{\\pi}}{2}",
                    isDisplay = true,
                    textColor = mathTextColor
                )
            }
        }
        
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("样式要求:", style = MaterialTheme.typography.titleMedium)
                Text("• 夜间模式：背景色 #1a1a1a，文本纯白 #ffffff")
                Text("• 白天模式：背景色纯白 #ffffff，文本纯黑 #000000")
            }
        }
    }
}