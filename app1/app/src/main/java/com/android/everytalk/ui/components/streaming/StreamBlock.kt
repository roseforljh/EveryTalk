package com.android.everytalk.ui.components.streaming

sealed interface StreamBlock {
    val stableId: String
    val type: StreamBlockType
    val text: String
    val start: Int
    val endExclusive: Int

    data class PlainText(
        override val stableId: String,
        override val text: String,
        override val start: Int,
        override val endExclusive: Int
    ) : StreamBlock {
        override val type: StreamBlockType = StreamBlockType.PLAIN_TEXT
    }

    data class CodeBlock(
        override val stableId: String,
        override val text: String,
        override val start: Int,
        override val endExclusive: Int
    ) : StreamBlock {
        override val type: StreamBlockType = StreamBlockType.CODE_BLOCK
    }

    data class MathInline(
        override val stableId: String,
        override val text: String,
        override val start: Int,
        override val endExclusive: Int,
        val state: MathBlockState
    ) : StreamBlock {
        override val type: StreamBlockType = StreamBlockType.MATH_INLINE
    }

    data class MathBlock(
        override val stableId: String,
        override val text: String,
        override val start: Int,
        override val endExclusive: Int,
        val state: MathBlockState
    ) : StreamBlock {
        override val type: StreamBlockType = StreamBlockType.MATH_BLOCK
    }
}

