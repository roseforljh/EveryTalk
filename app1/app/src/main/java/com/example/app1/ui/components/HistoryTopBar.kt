package com.example.app1.ui.components // 确保包名正确

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HistoryTopBar(onBackClick:() -> Unit){
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars) // 适配状态栏
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        // !!! 在 IconButton 的 onClick 中调用传入的 onBackClick !!!
        IconButton(onClick = onBackClick) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
        }
        Text(
            text = "历史记录",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .weight(1f) // 占据中心大部分空间
                .padding(horizontal = 8.dp),
            textAlign = TextAlign.Center
        )
        // 添加一个 Spacer 帮助居中
        Spacer(modifier = Modifier.width(48.dp)) // 假设 IconButton 大约 48dp 宽
    }
}