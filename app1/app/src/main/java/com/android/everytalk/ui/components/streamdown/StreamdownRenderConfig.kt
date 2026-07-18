package com.android.everytalk.ui.components.streamdown

enum class StreamdownRenderMode {
    Streaming,
    Static,
}

enum class StreamdownCaretStyle {
    Block,
    Circle,
}

enum class StreamdownTextDirection {
    Auto,
    Ltr,
    Rtl,
}

sealed interface StreamdownControlsConfig {
    object Enabled : StreamdownControlsConfig
    object Disabled : StreamdownControlsConfig

    data class Custom(
        val table: TableControls? = null,
        val code: CodeControls? = null,
        val mermaid: MermaidControls? = null,
    ) : StreamdownControlsConfig

    data class TableControls(
        val enabled: Boolean = true,
        val copy: Boolean = true,
        val download: Boolean = true,
        val fullscreen: Boolean = true,
    )

    data class CodeControls(
        val enabled: Boolean = true,
        val copy: Boolean = true,
        val download: Boolean = true,
    )

    data class MermaidControls(
        val enabled: Boolean = true,
        val copy: Boolean = true,
        val download: Boolean = true,
        val fullscreen: Boolean = true,
        val panZoom: Boolean = true,
    )
}

data class StreamdownPluginConfig(
    val code: Boolean = true,
    val mermaid: Boolean = true,
    val math: Boolean = true,
    val cjk: Boolean = true,
)

data class StreamdownMermaidConfig(
    val theme: String = "default",
    val renderTimeoutMs: Long = 10_000L,
)

data class StreamdownLinkSafetyConfig(
    val enabled: Boolean = true,
)

data class StreamdownSecurityConfig(
    val allowedProtocols: Set<String> = setOf("http", "https", "mailto", "tel"),
    val allowDataImages: Boolean = true,
)

data class StreamdownRenderConfig(
    val children: String = "",
    val mode: StreamdownRenderMode = StreamdownRenderMode.Streaming,
    val parseIncompleteMarkdown: Boolean = true,
    val remend: Any? = null,
    val isAnimating: Boolean = false,
    val className: String? = null,
    val shikiTheme: List<String> = listOf("github-light", "github-dark"),
    val components: Map<String, String> = emptyMap(),
    val allowedTags: Map<String, Set<String>> = emptyMap(),
    val plugins: StreamdownPluginConfig = StreamdownPluginConfig(),
    val remarkPlugins: List<String> = emptyList(),
    val rehypePlugins: List<String> = emptyList(),
    val allowedElements: Set<String>? = null,
    val disallowedElements: Set<String> = emptySet(),
    val allowElement: String? = null,
    val unwrapDisallowed: Boolean = false,
    val skipHtml: Boolean = false,
    val urlTransform: String? = null,
    val caret: StreamdownCaretStyle = StreamdownCaretStyle.Block,
    val controls: StreamdownControlsConfig = StreamdownControlsConfig.Enabled,
    val mermaid: StreamdownMermaidConfig = StreamdownMermaidConfig(),
    val linkSafety: StreamdownLinkSafetyConfig = StreamdownLinkSafetyConfig(),
    val cdnUrl: String? = null,
    val blockComponent: String? = null,
    val parseMarkdownIntoBlocksFn: String? = null,
    val preprocess: String? = null,
    val defer: Boolean = false,
    val smooth: Boolean = true,
    val animated: Boolean = false,
    val security: StreamdownSecurityConfig = StreamdownSecurityConfig(),
    val remarkRehypeOptions: Map<String, String> = emptyMap(),
    val componentsByLanguage: Map<String, String> = emptyMap(),
    val icons: Map<String, String> = emptyMap(),
    val translations: Map<String, String> = emptyMap(),
    val dir: StreamdownTextDirection = StreamdownTextDirection.Auto,
    val literalTagContent: Set<String> = emptySet(),
    val loadRemoteAssets: Boolean = false,
) {
    companion object {
        val supportedPropNames: Set<String> = setOf(
            "children",
            "mode",
            "parseIncompleteMarkdown",
            "remend",
            "isAnimating",
            "className",
            "shikiTheme",
            "components",
            "allowedTags",
            "plugins",
            "remarkPlugins",
            "rehypePlugins",
            "allowedElements",
            "disallowedElements",
            "allowElement",
            "unwrapDisallowed",
            "skipHtml",
            "urlTransform",
            "caret",
            "controls",
            "mermaid",
            "linkSafety",
            "cdnUrl",
            "BlockComponent",
            "parseMarkdownIntoBlocksFn",
            "preprocess",
            "defer",
            "smooth",
            "animated",
            "security",
            "remarkRehypeOptions",
            "componentsByLanguage",
            "icons",
            "translations",
            "dir",
            "literalTagContent",
        )
    }
}
