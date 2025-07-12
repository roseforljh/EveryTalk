package com.example.everytalk.data.DataClass

sealed class ContentPart(open val contentId: String) {
    data class Html(override val contentId: String, val markdownWithKatex: String) : ContentPart(contentId)
    data class Code(override val contentId: String, val language: String?, val code: String) : ContentPart(contentId)
    data class Audio(override val contentId: String, val mimeType: String, val data: String) : ContentPart(contentId)
}
