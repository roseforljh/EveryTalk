package com.example.everytalk.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.data.DataClass.Sender
// import com.example.everytalk.ui.components.TableData

/**
 * 完整解决方案集成测试组件
 * 
 * 测试覆盖：
 * 1. 表格渲染稳定性测试
 * 2. 数学公式乱闪测试
 * 3. 内存泄漏监控测试
 * 4. Compose重组性能测试
 * 5. 混合内容渲染测试
 */
@Composable
fun IntegrationTestScreen() {
    var currentTest by remember { mutableIntStateOf(0) }
    var testResults by remember { mutableStateOf<Map<String, TestResult>>(emptyMap()) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "WebView渲染问题解决方案测试",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // 内存状态显示
        MemoryStatusCard()
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // 测试按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { currentTest = 1 },
                modifier = Modifier.weight(1f)
            ) {
                Text("表格测试")
            }
            
            Button(
                onClick = { currentTest = 2 },
                modifier = Modifier.weight(1f)
            ) {
                Text("公式测试")
            }
            
            Button(
                onClick = { currentTest = 3 },
                modifier = Modifier.weight(1f)
            ) {
                Text("混合测试")
            }
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { currentTest = 4 },
                modifier = Modifier.weight(1f)
            ) {
                Text("压力测试")
            }
            
            Button(
                onClick = { 
                    MemoryLeakGuard.performEmergencyCleanup()
                    currentTest = 0
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
            ) {
                Text("紧急清理")
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // 测试内容渲染
        when (currentTest) {
            1 -> TableRenderingTest()
            2 -> TextRenderingTest()
            3 -> MixedContentTest()
            4 -> StressTest()
            else -> {
                Card {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "选择测试用例",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "点击上方按钮开始测试",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray
                        )
                    }
                }
            }
        }
    }
}

/**
 * 内存状态卡片
 */
@Composable
fun MemoryStatusCard() {
    var memoryStats by remember { mutableStateOf<MemoryStatsSnapshot?>(null) }
    var webViewCount by remember { mutableIntStateOf(0) }
    
    LaunchedEffect(Unit) {
        while (true) {
            memoryStats = MemoryLeakGuard.getMemoryStats()
            webViewCount = 0 // 原生渲染器无需WebView计数
            kotlinx.coroutines.delay(2000)
        }
    }
    
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(
                text = "系统状态监控",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            memoryStats?.let { stats ->
                Text("当前内存: ${stats.formatMemory(stats.currentMemory)}")
                Text("峰值内存: ${stats.formatMemory(stats.maxMemory)}")
                Text("平均内存: ${stats.formatMemory(stats.averageMemory)}")
            }
            
            Text("活跃WebView: $webViewCount 个")
            
            // 内存状态指示器
            val memoryLevel = memoryStats?.let {
                when {
                    it.currentMemory > 120 * 1024 * 1024 -> "紧急"
                    it.currentMemory > 80 * 1024 * 1024 -> "严重"
                    it.currentMemory > 50 * 1024 * 1024 -> "警告"
                    else -> "正常"
                }
            } ?: "未知"
            
            val indicatorColor = when (memoryLevel) {
                "紧急" -> Color.Red
                "严重" -> Color(0xFFFF9800)
                "警告" -> Color.Yellow
                "正常" -> Color.Green
                else -> Color.Gray
            }
            
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("内存状态: ")
                Text(
                    text = memoryLevel,
                    color = indicatorColor,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * 表格渲染测试
 */
@Composable
fun TableRenderingTest() {
    val complexTableMessage = remember {
        Message(
            id = "table_test",
            text = "复杂表格测试",
            sender = Sender.AI,
            parts = listOf(
                MarkdownPart.Text(id = "table_desc", content = "以下是复杂表格渲染测试："),
                // MarkdownPart.Table(...) removed
                MarkdownPart.Text(id = "table_complete", content = "表格渲染完成测试。")
            ),
            timestamp = System.currentTimeMillis()
        )
    }
    
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "表格+公式混合渲染测试",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // 原生渲染器无需特殊组件
            Text("使用原生数学公式渲染器")
        }
    }
}

/**
 * 数学公式测试
 */
@Composable
fun TextRenderingTest() {
    val complexTextMessage = remember {
        Message(
            id = "text_test",
            text = "文本渲染测试",
            sender = Sender.AI,
            parts = listOf(
                MarkdownPart.Text(id = "text_desc", content = "以下是数学公式渲染测试："),
                MarkdownPart.Text(id = "text_content_intro", content = "这是一个行内公式示例："),
                MarkdownPart.MathBlock(id = "math_inline", latex = "E = mc^2", displayMode = false),
                MarkdownPart.Text(id = "text_block_intro", content = "下面是块级公式："),
                MarkdownPart.MathBlock(id = "math_block", latex = "\\int_{-\\infty}^{\\infty} e^{-x^2} \\, dx = \\sqrt{\\pi}", displayMode = true),
                MarkdownPart.Text(id = "text_error_intro", content = "错误/未闭合公式兜底展示："),
                MarkdownPart.MathBlock(id = "math_error", latex = "\\frac{a+b", displayMode = true)
            ),
            timestamp = System.currentTimeMillis()
        )
    }
    
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "数学公式渲染测试",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            EnhancedMarkdownText(
                message = complexTextMessage,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * 混合内容测试
 */
@Composable
fun MixedContentTest() {
    val mixedMessage = remember {
        Message(
            id = "mixed_test",
            text = "混合内容测试",
            sender = Sender.AI,
            parts = listOf(
                MarkdownPart.Text(id = "mixed_intro", content = "这是一个复杂的**混合内容**测试，包含："),
                MarkdownPart.Text(id = "text_styles", content = "1. 普通文本和**粗体**、*斜体*"),
                MarkdownPart.CodeBlock(id = "code_sample", content = "fun test() {\n    println(\"Hello World\")\n}", language = "kotlin"),
                MarkdownPart.Text(id = "plain_text", content = "2. 这是普通文本，包含原始格式：f(x) = x^2 + 2x + 1"),
                MarkdownPart.MathBlock(id = "mixed_math", latex = "\\sum_{k=1}^{n} k = \\frac{n(n+1)}{2}", displayMode = true),
                // MarkdownPart.Table(...) removed
                MarkdownPart.Text(id = "test_complete", content = "3. 测试完成，观察是否有乱闪或卡死现象。")
            ),
            timestamp = System.currentTimeMillis()
        )
    }
    
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "混合内容渲染测试",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // 原生渲染器无需特殊组件
            Text("使用原生数学公式渲染器")
        }
    }
}

/**
 * 压力测试
 */
@Composable
fun StressTest() {
    var testCount by remember { mutableIntStateOf(0) }
    
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "压力测试 (测试次数: $testCount)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Button(
                onClick = { testCount++ }
            ) {
                Text("触发重组测试")
            }
            
            // 多个重复的复杂内容
            repeat(3) { index ->
                key(testCount, index) {
                    val stressMessage = Message(
                        id = "stress_$testCount$index",
                        text = "压力测试 $testCount-$index",
                        sender = Sender.AI,
                        parts = listOf(
                            MarkdownPart.Text(id = "stress_text_$testCount$index", content = "压力测试 #$testCount-$index"),
                            // MarkdownPart.Table(...) removed
                        ),
                        timestamp = System.currentTimeMillis()
                    )
                    
                    // 原生渲染器无需特殊组件
                    Text("使用原生数学公式渲染器")
                }
                
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

/**
 * 测试结果数据类
 */
data class TestResult(
    val testName: String,
    val success: Boolean,
    val duration: Long,
    val memoryUsage: Long,
    val error: String? = null
)