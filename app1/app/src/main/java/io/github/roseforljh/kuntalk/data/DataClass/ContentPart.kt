package io.github.roseforljh.kuntalk.data.DataClass

sealed class ContentPart(open val contentId: String) {
    data class Html(override val contentId: String, val markdownWithKatex: String) : ContentPart(contentId)
    data class Code(override val contentId: String, val language: String?, val code: String) : ContentPart(contentId)
}

