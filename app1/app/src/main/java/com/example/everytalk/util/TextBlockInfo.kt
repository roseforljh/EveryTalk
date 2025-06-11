package com.example.everytalk.util

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult

data class TextBlockInfo(
    val annotatedString: AnnotatedString,
    val layoutResult: TextLayoutResult,
    val rectInWindow: Rect
)