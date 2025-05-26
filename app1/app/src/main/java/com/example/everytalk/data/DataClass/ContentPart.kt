package com.example.everytalk.data.DataClass // 或者你的工具类包名

sealed class ContentPart(open val contentId: String) {
    // Html part now stores raw markdown mixed with KaTeX, to be converted later
    data class Html(override val contentId: String, val markdownWithKatex: String) : ContentPart(contentId)
    data class Code(override val contentId: String, val language: String?, val code: String) : ContentPart(contentId)
}
