package com.android.everytalk.util.text

object TextSanitizer {
    private const val UnicodeReplacementChar = '\uFFFD'

    fun removeUnicodeReplacementCharacters(text: String): String {
        if (text.indexOf(UnicodeReplacementChar) == -1) return text
        return text.filterNot { it == UnicodeReplacementChar }
    }
}
