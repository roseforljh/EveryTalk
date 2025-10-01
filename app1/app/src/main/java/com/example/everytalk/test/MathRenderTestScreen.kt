package com.example.everytalk.test

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.everytalk.ui.components.*
import com.example.everytalk.util.MathRenderMonitor

/**
 * 🚀 数学公式渲染测试页面
 * 
 * 用于测试和演示专业数学公式渲染功能
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MathRenderTestScreen() {
    var selectedTest by remember { mutableStateOf(0) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 测试选择器
        Text(
            text = "🚀 数学公式渲染测试",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        // 测试用例选择
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            testCases.forEachIndexed { index, testCase ->
                FilterChip(
                    onClick = { selectedTest = index },
                    label = { Text(testCase.name) },
                    selected = selectedTest == index
                )
            }
        }
        
        // 性能统计显示
        PerformanceStatsCard()
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // 测试内容渲染
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                val currentTest = testCases[selectedTest]
                
                Text(
                    text = currentTest.description,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                // 渲染数学内容
                IntelligentMarkdownRenderer(
                    parts = currentTest.parts,
                    onRenderComplete = { partId, success ->
                        if (success) {
                            MathRenderMonitor.recordCacheHit()
                        } else {
                            MathRenderMonitor.recordError("render_failure", "Part: $partId")
                        }
                    }
                )
            }
        }
    }
}

/**
 * 性能统计卡片
 */
@Composable
private fun PerformanceStatsCard() {
    val stats = remember { mutableStateOf(MathRenderMonitor.getPerformanceStats()) }
    
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1000)
        stats.value = MathRenderMonitor.getPerformanceStats()
    }
    
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = "📊 渲染性能统计",
                style = MaterialTheme.typography.titleSmall
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("成功率: ${"%.1f".format(stats.value.successRate * 100)}%")
                Text("平均耗时: ${"%.1f".format(stats.value.averageRenderTime)}ms")
                Text("缓存命中率: ${"%.1f".format(stats.value.cacheHitRate * 100)}%")
            }
        }
    }
}

/**
 * 测试用例数据类
 */
private data class MathTestCase(
    val name: String,
    val description: String,
    val parts: List<MarkdownPart>
)

/**
 * 测试用例定义
 */
private val testCases = listOf(
    MathTestCase(
        name = "基础公式",
        description = "测试基础数学公式渲染，包括分数、根号、上下标等",
        parts = listOf(
            MarkdownPart.MathBlock(
                id = "basic_1",
                content = """
                求解方程：x² - 8.6x + 14.5 = 0
                
                第一步：识别系数
                a = 1，b = -8.6，c = 14.5
                
                第二步：计算判别式
                Δ = b² - 4ac = (-8.6)² - 4(1)(14.5) = 73.96 - 58 = 15.96
                
                第三步：应用求根公式
                x = (-b ± √Δ)/(2a) = (8.6 ± √15.96)/2 = (8.6 ± 3.99)/2
                
                第四步：计算具体值
                x₁ = (8.6 + 3.99)/2 = 12.59/2 = 6.295
                x₂ = (8.6 - 3.99)/2 = 4.61/2 = 2.305
                """.trimIndent(),
                renderMode = "professional"
            )
        )
    ),
    
    MathTestCase(
        name = "高级数学",
        description = "测试复杂数学表达式，包括积分、求和、矩阵等",
        parts = listOf(
            MarkdownPart.MathBlock(
                id = "advanced_1",
                content = """
                微积分基本定理：
                ∫[a→b] f'(x) dx = f(b) - f(a)
                
                泰勒级数展开：
                f(x) = Σ[n=0→∞] [f⁽ⁿ⁾(a)/n!](x-a)ⁿ
                
                欧拉公式：
                e^(iπ) + 1 = 0
                """.trimIndent(),
                renderMode = "professional"
            )
        )
    ),
    
    MathTestCase(
        name = "混合内容",
        description = "测试数学公式与普通文本的混合渲染",
        parts = listOf(
            MarkdownPart.Text(
                id = "mixed_text_1",
                content = "在物理学中，能量守恒定律是一个基本原理。"
            ),
            MarkdownPart.MathBlock(
                id = "mixed_math_1",
                content = "E = mc²",
                renderMode = "professional"
            ),
            MarkdownPart.Text(
                id = "mixed_text_2",
                content = "这个公式描述了质量和能量的等价关系，其中 c 是光速常数。"
            ),
            MarkdownPart.MathBlock(
                id = "mixed_math_2",
                content = """
                动能公式：
                K = ½mv²
                
                势能公式：
                U = mgh
                """.trimIndent(),
                renderMode = "professional"
            )
        )
    ),
    
    MathTestCase(
        name = "表格测试",
        description = "测试表格与数学公式的组合渲染",
        parts = listOf(
            MarkdownPart.Table(
                id = "table_1",
                content = """
                | 函数 | 导数 | 积分 |
                | --- | --- | --- |
                | xⁿ | nxⁿ⁻¹ | xⁿ⁺¹/(n+1) |
                | sin x | cos x | -cos x |
                | cos x | -sin x | sin x |
                | eˣ | eˣ | eˣ |
                | ln x | 1/x | x ln x - x |
                """.trimIndent(),
                renderMode = "webview"
            )
        )
    ),
    
    MathTestCase(
        name = "复杂表达式",
        description = "测试极复杂的数学表达式渲染性能",
        parts = listOf(
            MarkdownPart.MathBlock(
                id = "complex_1",
                content = """
                量子力学中的薛定谔方程：
                iℏ ∂/∂t |ψ⟩ = Ĥ|ψ⟩
                
                贝叶斯定理：
                P(A|B) = P(B|A) · P(A) / P(B)
                
                傅里叶变换：
                F[f(t)] = ∫[-∞→∞] f(t) e^(-i2πft) dt
                
                正态分布概率密度函数：
                f(x) = 1/(σ√(2π)) · e^(-((x-μ)²)/(2σ²))
                """.trimIndent(),
                renderMode = "professional"
            )
        )
    )
)