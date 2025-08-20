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
import androidx.compose.ui.unit.dp

/**
 * 测试表格渲染闪白问题的Activity
 */
class TableRenderingTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TableRenderingTestScreen()
        }
    }
}

@Composable
fun TableRenderingTestScreen() {
    var currentMarkdown by remember { mutableStateOf(simpleTable) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "表格渲染测试",
            style = MaterialTheme.typography.headlineMedium
        )
        
        // 切换按钮
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { currentMarkdown = simpleTable }
            ) {
                Text("简单表格")
            }
            
            Button(
                onClick = { currentMarkdown = complexTable }
            ) {
                Text("复杂表格")
            }
            
            Button(
                onClick = { currentMarkdown = largeTable }
            ) {
                Text("大表格")
            }
        }
        
        Divider()
        
        // 渲染表格
        Card {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "当前表格:",
                    style = MaterialTheme.typography.titleMedium
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                EnhancedMarkdownText(
                    markdown = currentMarkdown,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

// 测试用的表格数据
val simpleTable = """
| 姓名 | 年龄 | 城市 |
|------|------|------|
| 张三 | 25   | 北京 |
| 李四 | 30   | 上海 |
| 王五 | 28   | 广州 |
""".trimIndent()

val complexTable = """
| 产品名称 | 价格 | 库存 | 状态 | 描述 |
|----------|------|------|------|------|
| iPhone 15 Pro | ¥8999 | 50 | 有货 | 最新款苹果手机，配备A17 Pro芯片 |
| MacBook Air M2 | ¥8999 | 30 | 有货 | 轻薄便携，适合办公和学习 |
| iPad Pro 12.9 | ¥6999 | 20 | 缺货 | 专业级平板，支持Apple Pencil |
| AirPods Pro 2 | ¥1899 | 100 | 有货 | 主动降噪耳机，音质出色 |
""".trimIndent()

val largeTable = """
| ID | 用户名 | 邮箱 | 注册时间 | 最后登录 | 状态 | VIP等级 | 积分 |
|----|--------|------|----------|----------|------|---------|------|
| 001 | user001 | user001@example.com | 2023-01-15 | 2024-01-20 | 活跃 | 黄金 | 1250 |
| 002 | user002 | user002@example.com | 2023-02-20 | 2024-01-19 | 活跃 | 白银 | 890 |
| 003 | user003 | user003@example.com | 2023-03-10 | 2024-01-18 | 活跃 | 青铜 | 450 |
| 004 | user004 | user004@example.com | 2023-04-05 | 2024-01-17 | 休眠 | 普通 | 120 |
| 005 | user005 | user005@example.com | 2023-05-12 | 2024-01-16 | 活跃 | 钻石 | 2100 |
| 006 | user006 | user006@example.com | 2023-06-18 | 2024-01-15 | 活跃 | 黄金 | 1680 |
| 007 | user007 | user007@example.com | 2023-07-22 | 2024-01-14 | 休眠 | 白银 | 720 |
| 008 | user008 | user008@example.com | 2023-08-30 | 2024-01-13 | 活跃 | 青铜 | 380 |
| 009 | user009 | user009@example.com | 2023-09-14 | 2024-01-12 | 活跃 | 普通 | 95 |
| 010 | user010 | user010@example.com | 2023-10-25 | 2024-01-11 | 活跃 | 钻石 | 2850 |
""".trimIndent()