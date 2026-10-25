package com.android.everytalk.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.ColorFilter
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.svg.SvgDecoder

/** 只读取随应用打包的图标；模型名称优先于代理平台，未知模型使用名称首字母。 */
fun modelBrand(model: String): String? = listOf(
    "anthropic" to "claude|anthropic", "google" to "gemini|gemma|google", "qwen" to "qwen|通义|z-image",
    "deepseek" to "deepseek", "moonshot" to "kimi|moonshot", "zhipu" to "glm|zhipu", "minimax" to "minimax",
    "mistral" to "mistral|codestral", "meta" to "llama", "doubao" to "doubao|seedream", "kolors" to "kolors|kwai",
    "openai" to "(^|[/\\s_-])(gpt|o[134](-|$)|chatgpt|dall-e)|openai",
).firstOrNull { (_, pattern) -> Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(model) }?.first

@Composable
fun ModelIcon(model: String, modifier: Modifier = Modifier) {
    val brand = remember(model) { modelBrand(model) }
    val context = LocalContext.current
    // 只保留布局占位和图标本身，不给品牌图标额外添加底板。
    Box(modifier, contentAlignment = Alignment.Center) {
        if (brand == null) Text(model.trim().firstOrNull()?.uppercase() ?: "M", style = MaterialTheme.typography.labelLarge)
        else AsyncImage(
            model = remember(brand, context) {
                ImageRequest.Builder(context).data("file:///android_asset/model-icons/$brand.svg")
                    .decoderFactory(SvgDecoder.Factory()).crossfade(false).build()
            },
            contentDescription = null,
            colorFilter = if (brand == "openai") ColorFilter.tint(MaterialTheme.colorScheme.onSurface) else null,
            modifier = Modifier.size(24.dp),
        )
    }
}
