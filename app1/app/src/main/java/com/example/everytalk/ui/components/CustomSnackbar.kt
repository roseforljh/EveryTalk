import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun CustomSnackbar(snackbarData: SnackbarData) {
    // 获取 Snackbar 的视觉信息 (消息文本, 操作标签等)
    val visuals = snackbarData.visuals

    Card( // 或者使用 Surface，Card 默认带一些圆角和阴影
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp), // 给左右留些边距
        shape = RoundedCornerShape(24.dp), // 大圆角
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.9f) // 半透明白底 (0.9f 是 90% 不透明度)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp) // 加一点阴影
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp), // 内容的内边距
            verticalAlignment = Alignment.CenterVertically,
            // 如果有操作按钮，则两端对齐；否则，起始端对齐
            horizontalArrangement = if (visuals.actionLabel != null) Arrangement.SpaceBetween else Arrangement.Start
        ) {
            // 消息文本
            Text(
                text = visuals.message,
                color = Color.Black, // 黑字
                // 让文本占据主要空间，但如果文本短，按钮不会被推到最右边
                modifier = Modifier.weight(1f, fill = false)
            )

            // 操作按钮 (如果存在)
            visuals.actionLabel?.let { actionLabel ->
                TextButton(
                    onClick = { snackbarData.performAction() }, // 点击时执行 Snackbar 的操作
                    modifier = Modifier.padding(start = 8.dp) // 文本和按钮之间的间距
                ) {
                    Text(
                        text = actionLabel.uppercase(), // 操作文本通常大写
                        color = Color.Black // 黑字 (或者用主题色如 MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
    }
}