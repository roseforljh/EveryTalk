package com.example.everytalk.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Defines the color scheme for syntax highlighting.
 * This allows for easy theme switching.
 */
data class SyntaxTheme(
    val comment: Color,
    val punctuation: Color,
    val keyword: Color,
    val operator: Color,
    val type: Color,
    val function: Color,
    val string: Color,
    val number: Color,
    val variable: Color,
    val annotation: Color,
    val attribute: Color,
    val tag: Color,
    val value: Color,
    val cssSelector: Color,
    val cssProperty: Color,
    val module: Color
)

/**
 * A beautiful, light theme inspired by Catppuccin Latte.
 */
val CatppuccinLatteTheme = SyntaxTheme(
    comment = Color(0xFF006600),     // 暗绿色 - 注释
    punctuation = Color(0xFF999999), // 浅灰色 - 标点
    keyword = Color(0xFFFF00FF),     // 洋红色 - 关键字
    operator = Color(0xFF666666),    // 灰色 - 操作符
    type = Color(0xFFD20F39),        // 红色 - 类型
    function = Color(0xFF9900CC),    // 紫色 - 函数
    string = Color(0xFFFF6600),      // 橙色 - 字符串
    number = Color(0xFF0066FF),      // 蓝色 - 数字
    variable = Color(0xFF00CCCC),    // 青色 - 变量
    annotation = Color(0xFFFF9900),  // 橙黄色 - 注解
    attribute = Color(0xFF6600CC),   // 深紫色 - 属性
    tag = Color(0xFFCC0066),         // 深红色 - 标签
    value = Color(0xFF004400),       // 暗绿色 - 值
    cssSelector = Color(0xFF7287FD), // CSS选择器颜色
    cssProperty = Color(0xFF1E66F5), // CSS属性颜色
    module = Color(0xFF7287FD)       // 模块颜色
)

object CodeHighlighter {

    private data class Rule(val pattern: Pattern, val color: (SyntaxTheme) -> Color, val groupIndex: Int = 1)

    // Cache for compiled language rules to avoid re-compilation on every call.
    private val languageRuleCache = ConcurrentHashMap<String, List<Rule>>()

    private fun getRules(language: String?, theme: SyntaxTheme): List<Rule> {
        val lang = language?.lowercase()?.trim() ?: "text"
        return languageRuleCache.getOrPut(lang) {
            // The order of rules is critical. More specific rules must come first.
            when (lang) {
                "html" -> listOf(
                    Rule(Pattern.compile("<!--[\\s\\S]*?-->"), { it.comment }, 0),
                    Rule(Pattern.compile("(<!DOCTYPE[\\s\\S]*?>)"), { it.annotation }, 0),
                    Rule(Pattern.compile("(<\\/?)([a-zA-Z0-9\\-]+)"), { it.tag }, 2),
                    // HTML5 语义化标签
                    Rule(Pattern.compile("\\b(html|head|body|header|nav|main|section|article|aside|footer|figure|figcaption|details|summary|mark|time|progress|meter|dialog|template|slot)\\b"), { it.tag }, 1),
                    // 表单和输入元素
                    Rule(Pattern.compile("\\b(form|input|textarea|select|option|optgroup|button|label|fieldset|legend|datalist|output)\\b"), { it.tag }, 1),
                    // 媒体元素
                    Rule(Pattern.compile("\\b(img|video|audio|source|track|canvas|svg|picture)\\b"), { it.tag }, 1),
                    // 表格元素
                    Rule(Pattern.compile("\\b(table|thead|tbody|tfoot|tr|td|th|caption|colgroup|col)\\b"), { it.tag }, 1),
                    // 列表元素
                    Rule(Pattern.compile("\\b(ul|ol|li|dl|dt|dd|menu|menuitem)\\b"), { it.tag }, 1),
                    // 文本内容元素
                    Rule(Pattern.compile("\\b(h[1-6]|p|div|span|a|strong|em|b|i|u|s|small|sub|sup|ins|del|abbr|cite|code|kbd|samp|var|pre|blockquote|q|br|hr|wbr)\\b"), { it.tag }, 1),
                    // 嵌入内容
                    Rule(Pattern.compile("\\b(iframe|embed|object|param|map|area|base|link|meta|script|noscript|style|title)\\b"), { it.tag }, 1),
                    // 全局属性
                    Rule(Pattern.compile("\\s(class|id|style|title|lang|dir|tabindex|accesskey|contenteditable|draggable|dropzone|hidden|spellcheck|translate|data-[a-zA-Z0-9\\-]+)(?=\\s*=)"), { it.attribute }, 1),
                    // 事件属性
                    Rule(Pattern.compile("\\s(onclick|ondblclick|onmousedown|onmouseup|onmouseover|onmousemove|onmouseout|onkeydown|onkeyup|onkeypress|onload|onunload|onresize|onscroll|onfocus|onblur|onchange|onsubmit|onreset|onselect|oninput|oninvalid|ondrag|ondrop|ondragover|ondragenter|ondragleave|ondragstart|ondragend|ontouchstart|ontouchmove|ontouchend|ontouchcancel)(?=\\s*=)"), { it.attribute }, 1),
                    // 表单属性
                    Rule(Pattern.compile("\\s(action|method|enctype|target|autocomplete|novalidate|accept-charset|name|value|type|placeholder|required|disabled|readonly|checked|selected|multiple|size|maxlength|minlength|min|max|step|pattern|autofocus|autocomplete|form|formaction|formenctype|formmethod|formnovalidate|formtarget)(?=\\s*=)"), { it.attribute }, 1),
                    // 媒体属性
                    Rule(Pattern.compile("\\s(src|alt|width|height|controls|autoplay|loop|muted|preload|poster|crossorigin|sizes|srcset|media|download|href|hreflang|rel|rev|charset|coords|shape|usemap|ismap)(?=\\s*=)"), { it.attribute }, 1),
                    // 表格属性
                    Rule(Pattern.compile("\\s(colspan|rowspan|headers|scope|abbr|axis|align|valign|char|charoff|span|summary|rules|frame|border|cellpadding|cellspacing)(?=\\s*=)"), { it.attribute }, 1),
                    // 其他常用属性
                    Rule(Pattern.compile("\\s([a-zA-Z\\-]+)(?=\\s*=)"), { it.attribute }, 1),
                    // 属性值
                    Rule(Pattern.compile("(\"[^\"]*\"|'[^']*')"), { it.string }, 1),
                    // HTML实体
                    Rule(Pattern.compile("(&[a-zA-Z0-9#]+;)"), { it.value }, 1),
                    // 标点符号
                    Rule(Pattern.compile("([<>/=])"), { it.punctuation }, 1)
                )
                "css" -> listOf(
                    // 注释 - 最高优先级
                    Rule(Pattern.compile("\\/\\*[\\s\\S]*?\\*\\/"), { it.comment }, 0),
                    // 字符串 - 高优先级
                    Rule(Pattern.compile("(\"[^\"]*\"|'[^']*')"), { it.string }, 1),

                    // 媒体查询和@规则 - 需要在其他规则之前匹配
                    Rule(Pattern.compile("(@(?:media|supports|document|page|font-face|keyframes|import|namespace|charset|counter-style|font-feature-values|property|layer|container|scope))\\b"), { it.annotation }, 1),

                    // 颜色值 - 需要在ID选择器之前匹配，避免冲突
                    Rule(Pattern.compile("(#[a-fA-F0-9]{3,8})\\b"), { it.value }, 1),

                    // CSS函数
                    Rule(Pattern.compile("\\b(rgb|rgba|hsl|hsla|hwb|lab|lch|oklab|oklch|color)\\s*\\("), { it.function }, 1),
                    Rule(Pattern.compile("\\b(url|calc|var|env|attr|counter|counters|symbols|repeat|minmax|fit-content|clamp|min|max|round|mod|rem|sin|cos|tan|asin|acos|atan|atan2|pow|sqrt|hypot|log|exp|abs|sign)\\s*\\("), { it.function }, 1),
                    Rule(Pattern.compile("\\b(linear-gradient|radial-gradient|conic-gradient|repeating-linear-gradient|repeating-radial-gradient|repeating-conic-gradient)\\s*\\("), { it.function }, 1),
                    Rule(Pattern.compile("\\b(matrix|matrix3d|translate|translate3d|translateX|translateY|translateZ|scale|scale3d|scaleX|scaleY|scaleZ|rotate|rotate3d|rotateX|rotateY|rotateZ|skew|skewX|skewY|perspective)\\s*\\("), { it.function }, 1),
                    Rule(Pattern.compile("\\b(blur|brightness|contrast|drop-shadow|grayscale|hue-rotate|invert|opacity|saturate|sepia)\\s*\\("), { it.function }, 1),
                    Rule(Pattern.compile("\\b(cubic-bezier|steps|frames)\\s*\\("), { it.function }, 1),

                    // CSS属性 - 具体属性优先于通用匹配
                    Rule(Pattern.compile("\\b(display|position|top|right|bottom|left|float|clear|z-index|overflow|overflow-x|overflow-y|clip|visibility|opacity|filter|backdrop-filter|mix-blend-mode|isolation|object-fit|object-position)(?=\\s*:)"), { it.cssProperty }, 1),
                    Rule(Pattern.compile("\\b(width|height|min-width|min-height|max-width|max-height|margin|margin-top|margin-right|margin-bottom|margin-left|padding|padding-top|padding-right|padding-bottom|padding-left|border|border-width|border-style|border-color|border-top|border-right|border-bottom|border-left|border-radius|border-top-left-radius|border-top-right-radius|border-bottom-right-radius|border-bottom-left-radius|outline|outline-width|outline-style|outline-color|outline-offset|box-sizing|box-shadow)(?=\\s*:)"), { it.cssProperty }, 1),
                    Rule(Pattern.compile("\\b(background|background-color|background-image|background-repeat|background-attachment|background-position|background-size|background-origin|background-clip|background-blend-mode)(?=\\s*:)"), { it.cssProperty }, 1),
                    Rule(Pattern.compile("\\b(color|font|font-family|font-size|font-weight|font-style|font-variant|font-stretch|font-size-adjust|font-synthesis|font-kerning|font-variant-ligatures|font-variant-position|font-variant-caps|font-variant-numeric|font-variant-alternates|font-variant-east-asian|font-feature-settings|font-variation-settings|line-height|letter-spacing|word-spacing|text-align|text-align-last|text-indent|text-transform|text-decoration|text-decoration-line|text-decoration-color|text-decoration-style|text-decoration-thickness|text-underline-position|text-shadow|text-overflow|text-wrap|white-space|word-break|word-wrap|hyphens|tab-size|direction|unicode-bidi|writing-mode|text-orientation|text-combine-upright)(?=\\s*:)"), { it.cssProperty }, 1),
                    Rule(Pattern.compile("\\b(flex|flex-direction|flex-wrap|flex-flow|justify-content|align-items|align-content|align-self|order|flex-grow|flex-shrink|flex-basis|gap|row-gap|column-gap)(?=\\s*:)"), { it.cssProperty }, 1),
                    Rule(Pattern.compile("\\b(grid|grid-template|grid-template-rows|grid-template-columns|grid-template-areas|grid-auto-rows|grid-auto-columns|grid-auto-flow|grid-row|grid-column|grid-area|grid-row-start|grid-row-end|grid-column-start|grid-column-end|place-items|place-content|place-self|justify-items|justify-self|align-items|align-self|align-content)(?=\\s*:)"), { it.cssProperty }, 1),
                    Rule(Pattern.compile("\\b(transition|transition-property|transition-duration|transition-timing-function|transition-delay|animation|animation-name|animation-duration|animation-timing-function|animation-delay|animation-iteration-count|animation-direction|animation-fill-mode|animation-play-state|transform|transform-origin|transform-style|perspective|perspective-origin|backface-visibility)(?=\\s*:)"), { it.cssProperty }, 1),
                    Rule(Pattern.compile("\\b(table-layout|border-collapse|border-spacing|caption-side|empty-cells)(?=\\s*:)"), { it.cssProperty }, 1),
                    Rule(Pattern.compile("\\b(list-style|list-style-type|list-style-position|list-style-image|counter-reset|counter-increment)(?=\\s*:)"), { it.cssProperty }, 1),
                    Rule(Pattern.compile("\\b(cursor|pointer-events|user-select|resize|scroll-behavior|scroll-snap-type|scroll-snap-align|scroll-snap-stop|overscroll-behavior|overscroll-behavior-x|overscroll-behavior-y|touch-action|will-change|contain|content-visibility|appearance|accent-color|caret-color|ime-mode)(?=\\s*:)"), { it.cssProperty }, 1),

                    // 通用CSS属性匹配 - 放在具体属性之后
                    Rule(Pattern.compile("\\b([a-zA-Z][a-zA-Z0-9-]*)(?=\\s*:)"), { it.cssProperty }, 1),

                    // 选择器 - 在属性之后匹配
                    // 类选择器 (.class-name)
                    Rule(Pattern.compile("(\\.[_a-zA-Z][_a-zA-Z0-9-]*)"), { it.cssSelector }, 1),
                    // ID选择器 (#id-name) - 确保不与颜色值冲突
                    Rule(Pattern.compile("(#[_a-zA-Z][_a-zA-Z0-9-]*)"), { it.cssSelector }, 1),
                    // HTML标签选择器
                    Rule(Pattern.compile("\\b(html|body|div|span|a|p|h[1-6]|ul|ol|li|table|tr|td|th|form|input|button|img|nav|header|footer|section|article|aside|main|figure|figcaption|details|summary|dialog|canvas|svg|video|audio|iframe|embed|object|select|textarea|label|fieldset|legend|optgroup|option|datalist|output|progress|meter|time|mark|ruby|rt|rp|bdi|bdo|wbr|area|base|br|col|colgroup|hr|link|meta|param|source|track|noscript|script|style|title|address|blockquote|cite|code|kbd|pre|samp|var|abbr|dfn|em|i|small|strong|sub|sup|b|u|s|del|ins|q)\\b(?=\\s*[,{\\s]|$)"), { it.tag }, 1),
                    // 伪类
                    Rule(Pattern.compile("(::?(?:hover|active|focus|visited|link|first-child|last-child|nth-child|nth-last-child|first-of-type|last-of-type|nth-of-type|nth-last-of-type|only-child|only-of-type|root|empty|target|enabled|disabled|checked|indeterminate|valid|invalid|in-range|out-of-range|required|optional|read-only|read-write|placeholder-shown|default|focus-within|focus-visible|current|past|future|playing|paused|seeking|buffering|stalled|muted|volume-locked|fullscreen|picture-in-picture|user-invalid|blank|not|is|where|has))"), { it.cssSelector }, 1),
                    // 伪元素
                    Rule(Pattern.compile("(::(?:before|after|first-line|first-letter|selection|backdrop|placeholder|marker|spelling-error|grammar-error|highlight|target-text|file-selector-button|webkit-[a-zA-Z-]+))"), { it.cssSelector }, 1),

                    // CSS关键字和值
                    Rule(Pattern.compile("\\b(inherit|initial|unset|revert|revert-layer|auto|none|normal|hidden|visible|block|inline|inline-block|flex|grid|table|table-row|table-cell|absolute|relative|fixed|sticky|static|left|right|center|justify|start|end|baseline|stretch|space-between|space-around|space-evenly|wrap|nowrap|column|row|reverse|ease|ease-in|ease-out|ease-in-out|linear|infinite|alternate|forwards|backwards|both|running|paused|fill|stroke|content-box|border-box|padding-box|cover|contain|repeat|no-repeat|round|space|local|scroll|fixed|transparent|currentColor|serif|sans-serif|monospace|cursive|fantasy|system-ui|bold|normal|italic|oblique|small-caps|uppercase|lowercase|capitalize|underline|overline|line-through|solid|dashed|dotted|double|groove|ridge|inset|outset|thick|medium|thin|larger|smaller|xx-small|x-small|small|medium|large|x-large|xx-large)\\b"), { it.keyword }, 1),

                    // 数字和单位 - 分开匹配以提高准确性
                    Rule(Pattern.compile("\\b(-?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+))(px|em|rem|%|vh|vw|vmin|vmax|pt|pc|in|cm|mm|ex|ch|lh|rlh|vi|vb|s|ms|deg|rad|grad|turn|fr|dpi|dpcm|dppx|Hz|kHz)\\b"), { it.number }, 0),
                    Rule(Pattern.compile("\\b(-?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+))\\b"), { it.number }, 1),

                    // 操作符
                    Rule(Pattern.compile("([+\\-*/%])"), { it.operator }, 1),
                    // 标点符号 - 最低优先级
                    Rule(Pattern.compile("([:;{}()\\[\\],])"), { it.punctuation }, 1)
                )
                "javascript", "js", "typescript", "ts" -> listOf(
                        // 注释
                        Rule(Pattern.compile("//.*|\\/\\*[\\s\\S]*?\\*\\/"), { it.comment }, 0),
                        // 字符串和模板字符串
                        Rule(Pattern.compile("(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`(?:\\\\.|[^`\\\\])*`)"), { it.string }),
                        // 正则表达式
                        Rule(Pattern.compile("(\\/(?:\\\\.|[^\\/\\\\\\n])+\\/[gimsuvy]*)"), { it.string }),
                        // 数字
                        Rule(Pattern.compile("\\b([0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?|0x[0-9a-fA-F]+|0b[01]+|0o[0-7]+|BigInt\\([0-9]+\\)|[0-9]+n)\\b"), { it.number }),
                        // ES6+ 关键字
                        Rule(Pattern.compile("\\b(const|let|var|function|class|interface|type|enum|namespace|module|declare|abstract|readonly|public|private|protected|static|async|await|return|if|else|for|while|do|switch|case|default|break|continue|try|catch|finally|throw|new|this|super|typeof|instanceof|in|of|delete|void|yield|import|export|from|as|extends|implements|with|debugger|get|set|constructor|keyof|infer|is|asserts|satisfies|override|accessor)\\b"), { it.keyword }),
                        // TypeScript 特定关键字
                        Rule(Pattern.compile("\\b(interface|type|enum|namespace|module|declare|abstract|readonly|public|private|protected|static|keyof|infer|is|asserts|satisfies|override|accessor|unique|symbol|never|unknown|any)\\b"), { it.keyword }),
                        // 布尔值和特殊值
                        Rule(Pattern.compile("\\b(true|false|null|undefined|NaN|Infinity)\\b"), { it.keyword }),
                        // 基本类型 (TypeScript)
                        Rule(Pattern.compile("\\b(string|number|boolean|bigint|symbol|object|any|unknown|never|void|null|undefined)\\b"), { it.type }),
                        // 内置对象和构造函数
                        Rule(Pattern.compile("\\b(Array|Object|String|Number|Boolean|Date|RegExp|Error|TypeError|ReferenceError|SyntaxError|RangeError|EvalError|URIError|Function|Promise|Symbol|BigInt|Map|Set|WeakMap|WeakSet|ArrayBuffer|DataView|Int8Array|Uint8Array|Uint8ClampedArray|Int16Array|Uint16Array|Int32Array|Uint32Array|Float32Array|Float64Array|BigInt64Array|BigUint64Array|Proxy|Reflect|JSON|Math|console|window|document|global|globalThis|process|Buffer|URL|URLSearchParams)\\b"), { it.type }),
                        // DOM 和 Web API
                        Rule(Pattern.compile("\\b(document|window|navigator|location|history|screen|localStorage|sessionStorage|indexedDB|fetch|XMLHttpRequest|WebSocket|Worker|ServiceWorker|Notification|Geolocation|FileReader|Blob|File|FormData|Headers|Request|Response|AbortController|IntersectionObserver|MutationObserver|ResizeObserver|PerformanceObserver|EventTarget|Element|Node|HTMLElement|HTMLDocument|Event|MouseEvent|KeyboardEvent|TouchEvent|CustomEvent|DOMParser|XMLSerializer)\\b"), { it.variable }),
                        // Node.js 全局对象
                        Rule(Pattern.compile("\\b(require|module|exports|__dirname|__filename|process|Buffer|global|setImmediate|clearImmediate|setInterval|clearInterval|setTimeout|clearTimeout)\\b"), { it.variable }),
                        // React 相关
                        Rule(Pattern.compile("\\b(React|Component|PureComponent|useState|useEffect|useContext|useReducer|useCallback|useMemo|useRef|useImperativeHandle|useLayoutEffect|useDebugValue|createElement|Fragment|StrictMode|Suspense|lazy|memo|forwardRef|createContext|createRef|cloneElement|isValidElement|Children|PropTypes|JSX)\\b"), { it.type }),
                        // 装饰器
                        Rule(Pattern.compile("(@[a-zA-Z_$][a-zA-Z0-9_$]*)"), { it.annotation }),
                        // 泛型参数
                        Rule(Pattern.compile("(<[A-Z][a-zA-Z0-9_$]*(?:\\s*,\\s*[A-Z][a-zA-Z0-9_$]*)*>)"), { it.type }),
                        // 函数定义
                        Rule(Pattern.compile("(?<=\\bfunction\\s+)([a-zA-Z_$][a-zA-Z0-9_$]*)"), { it.function }),
                        Rule(Pattern.compile("(?<=\\basync\\s+function\\s+)([a-zA-Z_$][a-zA-Z0-9_$]*)"), { it.function }),
                        // 箭头函数
                        Rule(Pattern.compile("([a-zA-Z_$][a-zA-Z0-9_$]*)(?=\\s*=>)"), { it.function }),
                        Rule(Pattern.compile("(\\([^)]*\\))(?=\\s*=>)"), { it.function }),
                        // 类定义
                        Rule(Pattern.compile("(?<=\\bclass\\s+)([a-zA-Z_$][a-zA-Z0-9_$]*)"), { it.type }),
                        Rule(Pattern.compile("(?<=\\babstract\\s+class\\s+)([a-zA-Z_$][a-zA-Z0-9_$]*)"), { it.type }),
                        // 接口和类型定义
                        Rule(Pattern.compile("(?<=\\binterface\\s+)([a-zA-Z_$][a-zA-Z0-9_$]*)"), { it.type }),
                        Rule(Pattern.compile("(?<=\\btype\\s+)([a-zA-Z_$][a-zA-Z0-9_$]*)"), { it.type }),
                        Rule(Pattern.compile("(?<=\\benum\\s+)([a-zA-Z_$][a-zA-Z0-9_$]*)"), { it.type }),
                        Rule(Pattern.compile("(?<=\\bnamespace\\s+)([a-zA-Z_$][a-zA-Z0-9_$]*)"), { it.type }),
                        // 变量声明
                        Rule(Pattern.compile("(?<=\\bconst\\s+)([a-zA-Z_$][a-zA-Z0-9_$]*)"), { it.variable }),
                        Rule(Pattern.compile("(?<=\\blet\\s+)([a-zA-Z_$][a-zA-Z0-9_$]*)"), { it.variable }),
                        Rule(Pattern.compile("(?<=\\bvar\\s+)([a-zA-Z_$][a-zA-Z0-9_$]*)"), { it.variable }),
                        // 解构赋值
                        Rule(Pattern.compile("(?<=\\{\\s*)([a-zA-Z_$][a-zA-Z0-9_$]*)(?=\\s*[,}])"), { it.variable }),
                        Rule(Pattern.compile("(?<=\\[\\s*)([a-zA-Z_$][a-zA-Z0-9_$]*)(?=\\s*[,\\]])"), { it.variable }),
                        // 导入导出
                        Rule(Pattern.compile("(?<=\\bfrom\\s+['\"`])([^'\"`]+)"), { it.string }),
                        Rule(Pattern.compile("(?<=\\bimport\\s+)([a-zA-Z_$][a-zA-Z0-9_$]*)"), { it.variable }),
                        Rule(Pattern.compile("(?<=\\bexport\\s+)([a-zA-Z_$][a-zA-Z0-9_$]*)"), { it.variable }),
                        Rule(Pattern.compile("(?<=\\bimport\\s*\\{\\s*)([a-zA-Z_$][a-zA-Z0-9_$]*)"), { it.variable }),
                        Rule(Pattern.compile("(?<=\\bexport\\s*\\{\\s*)([a-zA-Z_$][a-zA-Z0-9_$]*)"), { it.variable }),
                        // 方法调用
                        Rule(Pattern.compile("([a-zA-Z_$][a-zA-Z0-9_$]*)(?=\\s*\\()"), { it.function }),
                        // 对象属性
                        Rule(Pattern.compile("([a-zA-Z_$][a-zA-Z0-9_$]*)(?=\\s*:)"), { it.attribute }),
                        Rule(Pattern.compile("(?<=\\.)([a-zA-Z_$][a-zA-Z0-9_$]*)"), { it.attribute }),
                        // 对象和变量
                        Rule(Pattern.compile("([a-zA-Z_$][a-zA-Z0-9_$]*)(?=\\s*\\.)"), { it.variable }),
                        // 类型注解
                        Rule(Pattern.compile("(?<=:\\s*)([A-Z][a-zA-Z0-9_$]*)"), { it.type }),
                        // 泛型约束
                        Rule(Pattern.compile("(?<=\\bextends\\s+)([A-Z][a-zA-Z0-9_$]*)"), { it.type }),
                        // JSX 标签 (如果是 React)
                        Rule(Pattern.compile("(</?[A-Z][a-zA-Z0-9_$]*>)"), { it.tag }),
                        // 操作符
                        Rule(Pattern.compile("([=+\\-*/%<>!&|?^~])"), { it.operator }),
                        Rule(Pattern.compile("(\\+\\+|--|\\*\\*|===|!==|==|!=|<=|>=|&&|\\|\\||\\?\\?|\\?\\.|\\.\\.\\.)"), { it.operator }),
                        // 标点符号
                        Rule(Pattern.compile("([{}()\\[\\];,.:])"), { it.punctuation })
                    )
                "python", "py" -> listOf(
                    Rule(Pattern.compile("#.*"), { it.comment }, 0),
                    Rule(Pattern.compile("(\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')"), { it.string }),
                    Rule(Pattern.compile("\\b([0-9]+(?:\\.[0-9]+)?)\\b"), { it.number }),
                    Rule(Pattern.compile("\\b(def|class|if|else|elif|for|while|return|import|from|as|try|except|finally|with|lambda|pass|break|continue|in|is|not|and|or|True|False|None|self|async|await|yield|global|nonlocal|assert|del|raise)\\b"), { it.keyword }),
                    Rule(Pattern.compile("\\b(int|str|float|list|dict|tuple|set|bool|object|bytes|bytearray|complex|frozenset|range|type|Exception|ValueError|TypeError|KeyError|IndexError|AttributeError)\\b"), { it.type }),
                    Rule(Pattern.compile("(@[a-zA-Z_][a-zA-Z0-9_]*)"), { it.annotation }),
                    Rule(Pattern.compile("(?<=\\bdef\\s+)([a-zA-Z0-9_]+)"), { it.function }),
                    Rule(Pattern.compile("(?<=\\bclass\\s+)([a-zA-Z0-9_]+)"), { it.type }),
                    Rule(Pattern.compile("(?<=\\bfrom\\s+)([a-zA-Z0-9_.]+)"), { it.attribute }),
                    Rule(Pattern.compile("(?<=\\bimport\\s+)([a-zA-Z0-9_.]+)"), { it.attribute }),
                    Rule(Pattern.compile("\\b([A-Z][a-zA-Z0-9_]*)\\b"), { it.type }),
                    Rule(Pattern.compile("\\b([a-z_][a-zA-Z0-9_]*(?=\\s*\\())"), { it.function }),
                    Rule(Pattern.compile("\\b([a-z_][a-zA-Z0-9_]*(?=\\s*\\.))"), { it.variable }),
                    Rule(Pattern.compile("(?<=\\.)([a-z_][a-zA-Z0-9_]*)"), { it.attribute }),
                    Rule(Pattern.compile("\\b(self|cls)\\b"), { it.variable }),
                    Rule(Pattern.compile("([=+\\-*/%<>!&|?^])"), { it.operator }),
                    Rule(Pattern.compile("([:,.()\\[\\]{}])"), { it.punctuation })
                )
                "java" -> listOf(
                    Rule(Pattern.compile("//.*|\\/\\*[\\s\\S]*?\\*\\/"), { it.comment }, 0),
                    Rule(Pattern.compile("(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')"), { it.string }),
                    Rule(Pattern.compile("\\b([0-9]+(?:\\.[0-9]+)?[fFdDlL]?)\\b"), { it.number }),
                    Rule(Pattern.compile("\\b(public|private|protected|static|final|abstract|synchronized|volatile|transient|native|strictfp|class|interface|enum|extends|implements|import|package|if|else|for|while|do|switch|case|default|break|continue|return|try|catch|finally|throw|throws|new|this|super|instanceof|null|true|false|void)\\b"), { it.keyword }),
                    Rule(Pattern.compile("\\b(int|long|short|byte|char|float|double|boolean|String|Object|Integer|Long|Short|Byte|Character|Float|Double|Boolean|List|Map|Set|ArrayList|HashMap|HashSet)\\b"), { it.type }),
                    Rule(Pattern.compile("(@[a-zA-Z_][a-zA-Z0-9_]*)"), { it.annotation }),
                    Rule(Pattern.compile("(?<=\\bclass\\s+)([a-zA-Z_][a-zA-Z0-9_]*)"), { it.type }),
                    Rule(Pattern.compile("(?<=\\binterface\\s+)([a-zA-Z_][a-zA-Z0-9_]*)"), { it.type }),
                    Rule(Pattern.compile("(?<=\\benum\\s+)([a-zA-Z_][a-zA-Z0-9_]*)"), { it.type }),
                    Rule(Pattern.compile("(?<=\\bpackage\\s+)([a-zA-Z_][a-zA-Z0-9_.]*[a-zA-Z0-9_])"), { it.attribute }),
                    Rule(Pattern.compile("(?<=\\bimport\\s+)([a-zA-Z_][a-zA-Z0-9_.]*[a-zA-Z0-9_])"), { it.attribute }),
                    Rule(Pattern.compile("\\b([A-Z][a-zA-Z0-9_]*(?:\\.[A-Z][a-zA-Z0-9_]*)*)\\b"), { it.type }),
                    Rule(Pattern.compile("\\b([a-z][a-zA-Z0-9_]*(?=\\s*\\())"), { it.function }),
                    Rule(Pattern.compile("\\b([a-z][a-zA-Z0-9_]*(?=\\s*\\.))"), { it.variable }),
                    Rule(Pattern.compile("(?<=\\.)([a-z][a-zA-Z0-9_]*)"), { it.attribute }),
                    Rule(Pattern.compile("([=+\\-*/%<>!&|?^])"), { it.operator }),
                    Rule(Pattern.compile("([;,.()\\[\\]{}])"), { it.punctuation })
                )
                "kotlin", "kt" -> listOf(
                    Rule(Pattern.compile("//.*|\\/\\*[\\s\\S]*?\\*\\/"), { it.comment }, 0),
                    Rule(Pattern.compile("(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')"), { it.string }),
                    Rule(Pattern.compile("\\b([0-9]+(?:\\.[0-9]+)?[fFdDlL]?)\\b"), { it.number }),
                    Rule(Pattern.compile("\\b(fun|val|var|class|interface|object|enum|data|sealed|abstract|open|final|override|private|public|protected|internal|if|else|when|for|while|do|break|continue|return|try|catch|finally|throw|import|package|as|is|in|out|by|where|init|constructor|companion|lateinit|lazy|suspend|inline|noinline|crossinline|reified|vararg|tailrec|operator|infix|external|annotation|expect|actual|null|true|false|this|super)\\b"), { it.keyword }),
                    Rule(Pattern.compile("\\b(Int|Long|Short|Byte|Char|Float|Double|Boolean|String|Any|Unit|Nothing|List|MutableList|Map|MutableMap|Set|MutableSet|Array|IntArray|LongArray|FloatArray|DoubleArray|BooleanArray|CharArray|ByteArray|ShortArray)\\b"), { it.type }),
                    Rule(Pattern.compile("(@[a-zA-Z_][a-zA-Z0-9_]*)"), { it.annotation }),
                    Rule(Pattern.compile("(?<=\\bfun\\s+)([a-zA-Z_][a-zA-Z0-9_]*)"), { it.function }),
                    Rule(Pattern.compile("(?<=\\bclass\\s+)([a-zA-Z_][a-zA-Z0-9_]*)"), { it.type }),
                    Rule(Pattern.compile("(?<=\\bobject\\s+)([a-zA-Z_][a-zA-Z0-9_]*)"), { it.type }),
                    Rule(Pattern.compile("(?<=\\binterface\\s+)([a-zA-Z_][a-zA-Z0-9_]*)"), { it.type }),
                    Rule(Pattern.compile("(?<=\\benum\\s+)([a-zA-Z_][a-zA-Z0-9_]*)"), { it.type }),
                    Rule(Pattern.compile("(?<=\\bval\\s+)([a-zA-Z_][a-zA-Z0-9_]*)"), { it.variable }),
                    Rule(Pattern.compile("(?<=\\bvar\\s+)([a-zA-Z_][a-zA-Z0-9_]*)"), { it.variable }),
                    Rule(Pattern.compile("(?<=\\bpackage\\s+)([a-zA-Z_][a-zA-Z0-9_.]*[a-zA-Z0-9_])"), { it.attribute }),
                    Rule(Pattern.compile("(?<=\\bimport\\s+)([a-zA-Z_][a-zA-Z0-9_.]*[a-zA-Z0-9_])"), { it.attribute }),
                    Rule(Pattern.compile("\\b([A-Z][a-zA-Z0-9_]*(?:\\.[A-Z][a-zA-Z0-9_]*)*)\\b"), { it.type }),
                    Rule(Pattern.compile("\\b([a-z][a-zA-Z0-9_]*(?=\\s*[({]))"), { it.function }),
                    Rule(Pattern.compile("\\b([a-z][a-zA-Z0-9_]*(?=\\s*\\.))"), { it.variable }),
                    Rule(Pattern.compile("(?<=\\.)([a-z][a-zA-Z0-9_]*)"), { it.attribute }),
                    Rule(Pattern.compile("([=+\\-*/%<>!&|?^])"), { it.operator }),
                    Rule(Pattern.compile("([;,.()\\[\\]{}])"), { it.punctuation })
                )
                "c", "cpp", "c++" -> listOf(
                    Rule(Pattern.compile("//.*|\\/\\*[\\s\\S]*?\\*\\/"), { it.comment }, 0),
                    Rule(Pattern.compile("(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')"), { it.string }),
                    Rule(Pattern.compile("\\b([0-9]+(?:\\.[0-9]+)?[fFdDlLuU]*)\\b"), { it.number }),
                    Rule(Pattern.compile("\\b(auto|break|case|char|const|continue|default|do|double|else|enum|extern|float|for|goto|if|inline|int|long|register|restrict|return|short|signed|sizeof|static|struct|switch|typedef|union|unsigned|void|volatile|while|bool|true|false|nullptr|class|private|public|protected|virtual|override|final|namespace|using|template|typename|try|catch|throw|new|delete|this|operator)\\b"), { it.keyword }),
                    Rule(Pattern.compile("\\b(int|char|float|double|void|bool|long|short|unsigned|signed|const|volatile|auto|string|vector|map|set|list|queue|stack|pair|tuple)\\b"), { it.type }),
                    Rule(Pattern.compile("(#[a-zA-Z_][a-zA-Z0-9_]*)"), { it.annotation }),
                    Rule(Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)(?=\\s*\\()"), { it.function }),
                    Rule(Pattern.compile("([=+\\-*/%<>!&|?^~])"), { it.operator }),
                    Rule(Pattern.compile("([;,.()\\[\\]{}])"), { it.punctuation })
                )
                "rust", "rs" -> listOf(
                    Rule(Pattern.compile("//.*|\\/\\*[\\s\\S]*?\\*\\/"), { it.comment }, 0),
                    Rule(Pattern.compile("(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')"), { it.string }),
                    Rule(Pattern.compile("\\b([0-9]+(?:\\.[0-9]+)?[fFdDlLuU]*)\\b"), { it.number }),
                    Rule(Pattern.compile("\\b(fn|let|mut|const|static|if|else|match|loop|while|for|in|break|continue|return|impl|trait|struct|enum|mod|pub|use|crate|super|self|Self|where|async|await|move|ref|dyn|unsafe|extern|type|as|true|false)\\b"), { it.keyword }),
                    Rule(Pattern.compile("\\b(i8|i16|i32|i64|i128|isize|u8|u16|u32|u64|u128|usize|f32|f64|bool|char|str|String|Vec|HashMap|HashSet|Option|Result|Box|Rc|Arc|RefCell|Mutex|RwLock)\\b"), { it.type }),
                    Rule(Pattern.compile("(#\\[[^\\]]*\\])"), { it.annotation }),
                    Rule(Pattern.compile("(?<=\\bfn\\s+)([a-zA-Z_][a-zA-Z0-9_]*)"), { it.function }),
                    Rule(Pattern.compile("([=+\\-*/%<>!&|?^])"), { it.operator }),
                    Rule(Pattern.compile("([;,.()\\[\\]{}])"), { it.punctuation })
                )
                "go" -> listOf(
                    Rule(Pattern.compile("//.*|\\/\\*[\\s\\S]*?\\*\\/"), { it.comment }, 0),
                    Rule(Pattern.compile("(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`[^`]*`)"), { it.string }),
                    Rule(Pattern.compile("\\b([0-9]+(?:\\.[0-9]+)?)\\b"), { it.number }),
                    Rule(Pattern.compile("\\b(break|case|chan|const|continue|default|defer|else|fallthrough|for|func|go|goto|if|import|interface|map|package|range|return|select|struct|switch|type|var|true|false|nil|iota)\\b"), { it.keyword }),
                    Rule(Pattern.compile("\\b(bool|byte|complex64|complex128|error|float32|float64|int|int8|int16|int32|int64|rune|string|uint|uint8|uint16|uint32|uint64|uintptr)\\b"), { it.type }),
                    Rule(Pattern.compile("(?<=\\bfunc\\s+)([a-zA-Z_][a-zA-Z0-9_]*)"), { it.function }),
                    Rule(Pattern.compile("([=+\\-*/%<>!&|?^:])"), { it.operator }),
                    Rule(Pattern.compile("([;,.()\\[\\]{}])"), { it.punctuation })
                )
                "swift" -> listOf(
                    Rule(Pattern.compile("//.*|\\/\\*[\\s\\S]*?\\*\\/"), { it.comment }, 0),
                    Rule(Pattern.compile("(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')"), { it.string }),
                    Rule(Pattern.compile("\\b([0-9]+(?:\\.[0-9]+)?)\\b"), { it.number }),
                    Rule(Pattern.compile("\\b(class|struct|enum|protocol|extension|func|var|let|if|else|switch|case|default|for|while|repeat|break|continue|return|import|public|private|internal|fileprivate|open|final|static|override|required|convenience|lazy|weak|unowned|mutating|nonmutating|dynamic|inout|associatedtype|typealias|true|false|nil|self|Self|super|throws|rethrows|try|catch|defer|guard|where|is|as|in)\\b"), { it.keyword }),
                    Rule(Pattern.compile("\\b(Int|Double|Float|String|Bool|Character|Array|Dictionary|Set|Optional|Any|AnyObject|Void|Never)\\b"), { it.type }),
                    Rule(Pattern.compile("(@[a-zA-Z_][a-zA-Z0-9_]*)"), { it.annotation }),
                    Rule(Pattern.compile("(?<=\\bfunc\\s+)([a-zA-Z_][a-zA-Z0-9_]*)"), { it.function }),
                    Rule(Pattern.compile("([=+\\-*/%<>!&|?^])"), { it.operator }),
                    Rule(Pattern.compile("([;,.()\\[\\]{}])"), { it.punctuation })
                )
                "php" -> listOf(
                    Rule(Pattern.compile("//.*|#.*|\\/\\*[\\s\\S]*?\\*\\/"), { it.comment }, 0),
                    Rule(Pattern.compile("(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')"), { it.string }),
                    Rule(Pattern.compile("\\b([0-9]+(?:\\.[0-9]+)?)\\b"), { it.number }),
                    Rule(Pattern.compile("\\b(abstract|and|array|as|break|callable|case|catch|class|clone|const|continue|declare|default|die|do|echo|else|elseif|empty|enddeclare|endfor|endforeach|endif|endswitch|endwhile|eval|exit|extends|final|finally|for|foreach|function|global|goto|if|implements|include|include_once|instanceof|insteadof|interface|isset|list|namespace|new|or|print|private|protected|public|require|require_once|return|static|switch|throw|trait|try|unset|use|var|while|xor|true|false|null)\\b"), { it.keyword }),
                    Rule(Pattern.compile("\\b(string|int|float|bool|array|object|resource|null|mixed|callable|iterable|void)\\b"), { it.type }),
                    Rule(Pattern.compile("(\\$[a-zA-Z_][a-zA-Z0-9_]*)"), { it.variable }),
                    Rule(Pattern.compile("(?<=\\bfunction\\s+)([a-zA-Z_][a-zA-Z0-9_]*)"), { it.function }),
                    Rule(Pattern.compile("([=+\\-*/%<>!&|?^.])"), { it.operator }),
                    Rule(Pattern.compile("([;,.()\\[\\]{}])"), { it.punctuation })
                )
                "ruby", "rb" -> listOf(
                    Rule(Pattern.compile("#.*"), { it.comment }, 0),
                    Rule(Pattern.compile("(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')"), { it.string }),
                    Rule(Pattern.compile("\\b([0-9]+(?:\\.[0-9]+)?)\\b"), { it.number }),
                    Rule(Pattern.compile("\\b(alias|and|begin|break|case|class|def|defined|do|else|elsif|end|ensure|false|for|if|in|module|next|nil|not|or|redo|rescue|retry|return|self|super|then|true|undef|unless|until|when|while|yield|require|include|extend|attr_reader|attr_writer|attr_accessor|private|protected|public)\\b"), { it.keyword }),
                    Rule(Pattern.compile("\\b(String|Integer|Float|Array|Hash|Symbol|Regexp|Class|Module|Object|Numeric|TrueClass|FalseClass|NilClass)\\b"), { it.type }),
                    Rule(Pattern.compile("(:[a-zA-Z_][a-zA-Z0-9_]*[?!]?)"), { it.value }),
                    Rule(Pattern.compile("(?<=\\bdef\\s+)([a-zA-Z_][a-zA-Z0-9_]*[?!]?)"), { it.function }),
                    Rule(Pattern.compile("([=+\\-*/%<>!&|?^])"), { it.operator }),
                    Rule(Pattern.compile("([;,.()\\[\\]{}])"), { it.punctuation })
                )
                "shell", "bash", "sh" -> listOf(
                    Rule(Pattern.compile("#.*"), { it.comment }, 0),
                    Rule(Pattern.compile("(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')"), { it.string }),
                    Rule(Pattern.compile("\\b([0-9]+)\\b"), { it.number }),
                    Rule(Pattern.compile("\\b(if|then|else|elif|fi|case|esac|for|while|until|do|done|function|return|break|continue|exit|export|local|readonly|declare|unset|shift|eval|exec|source|alias|unalias|type|which|command|builtin|enable|help|let|mapfile|printf|read|readarray|test|time|trap|ulimit|umask|wait|jobs|bg|fg|disown|kill|nohup|true|false)\\b"), { it.keyword }),
                    Rule(Pattern.compile("(\\$[a-zA-Z_][a-zA-Z0-9_]*|\\$\\{[^}]*\\}|\\$[0-9@#?*!$-])"), { it.variable }),
                    Rule(Pattern.compile("([=+\\-*/%<>!&|?^])"), { it.operator }),
                    Rule(Pattern.compile("([;,.()\\[\\]{}|&])"), { it.punctuation })
                )
                "sql" -> listOf(
                    Rule(Pattern.compile("--.*|\\/\\*[\\s\\S]*?\\*\\/"), { it.comment }, 0),
                    Rule(Pattern.compile("(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')"), { it.string }),
                    Rule(Pattern.compile("\\b([0-9]+(?:\\.[0-9]+)?)\\b"), { it.number }),
                    Rule(Pattern.compile("\\b(?i)(SELECT|FROM|WHERE|INSERT|UPDATE|DELETE|CREATE|DROP|ALTER|TABLE|INDEX|VIEW|DATABASE|SCHEMA|GRANT|REVOKE|COMMIT|ROLLBACK|TRANSACTION|BEGIN|END|IF|ELSE|CASE|WHEN|THEN|AS|AND|OR|NOT|IN|EXISTS|BETWEEN|LIKE|IS|NULL|TRUE|FALSE|DISTINCT|GROUP|ORDER|BY|HAVING|LIMIT|OFFSET|JOIN|INNER|LEFT|RIGHT|FULL|OUTER|ON|UNION|ALL|ANY|SOME|PRIMARY|KEY|FOREIGN|REFERENCES|UNIQUE|CHECK|DEFAULT|AUTO_INCREMENT|IDENTITY)\\b"), { it.keyword }),
                    Rule(Pattern.compile("\\b(?i)(INT|INTEGER|BIGINT|SMALLINT|TINYINT|DECIMAL|NUMERIC|FLOAT|REAL|DOUBLE|CHAR|VARCHAR|TEXT|DATE|TIME|DATETIME|TIMESTAMP|BOOLEAN|BOOL|BINARY|VARBINARY|BLOB|CLOB)\\b"), { it.type }),
                    Rule(Pattern.compile("([=+\\-*/%<>!])"), { it.operator }),
                    Rule(Pattern.compile("([;,.()\\[\\]])"), { it.punctuation })
                )
                "json" -> listOf(
                    Rule(Pattern.compile("(\"(?:\\\\.|[^\"\\\\])*\")(?=\\s*:)"), { it.attribute }),
                    Rule(Pattern.compile("(\"(?:\\\\.|[^\"\\\\])*\")(?!\\s*:)"), { it.string }),
                    Rule(Pattern.compile("\\b([0-9]+(?:\\.[0-9]+)?)\\b"), { it.number }),
                    Rule(Pattern.compile("\\b(true|false|null)\\b"), { it.keyword }),
                    Rule(Pattern.compile("([{}\\[\\],:])"), { it.punctuation })
                )
                "xml" -> listOf(
                    Rule(Pattern.compile("<!--[\\s\\S]*?-->"), { it.comment }, 0),
                    Rule(Pattern.compile("(<\\?[\\s\\S]*?\\?>)"), { it.annotation }, 0),
                    Rule(Pattern.compile("(<!DOCTYPE[\\s\\S]*?>)"), { it.annotation }, 0),
                    Rule(Pattern.compile("(<\\/?)([a-zA-Z0-9\\-:]+)"), { it.tag }, 2),
                    Rule(Pattern.compile("\\s([a-zA-Z\\-:]+)(?=\\s*=)"), { it.attribute }, 1),
                    Rule(Pattern.compile("(\"[^\"]*\"|'[^']*')"), { it.value }, 1),
                    Rule(Pattern.compile("([<>/=])"), { it.punctuation }, 1)
                )
                "yaml", "yml" -> listOf(
                    Rule(Pattern.compile("#.*"), { it.comment }, 0),
                    Rule(Pattern.compile("(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')"), { it.string }),
                    Rule(Pattern.compile("\\b([0-9]+(?:\\.[0-9]+)?)\\b"), { it.number }),
                    Rule(Pattern.compile("\\b(true|false|null|yes|no|on|off)\\b"), { it.keyword }),
                    Rule(Pattern.compile("^\\s*([a-zA-Z_][a-zA-Z0-9_]*)(?=\\s*:)"), { it.attribute }),
                    Rule(Pattern.compile("([:\\-|>])"), { it.punctuation })
                )
                // Add other languages here...
                else -> listOf( // Default fallback for plain text
                    Rule(Pattern.compile("."), { it.variable }, 0)
                )
            }
        }
    }

    fun highlightToAnnotatedString(
        code: String,
        language: String?,
        theme: SyntaxTheme = CatppuccinLatteTheme
    ): AnnotatedString {
        if (code.isEmpty()) return AnnotatedString(code)

        return try {
            val rules = getRules(language, theme)
            buildAnnotatedString {
                append(code) // Append the raw code first

                // Use a list of ranges for more efficient overlap checking
                val appliedRanges = mutableListOf<IntRange>()

                rules.forEach { rule ->
                    val matcher = rule.pattern.matcher(code)
                    while (matcher.find()) {
                        val targetGroup = rule.groupIndex
                        if (targetGroup > matcher.groupCount()) continue

                        val start = matcher.start(targetGroup)
                        val end = matcher.end(targetGroup)

                        if (start == -1 || start >= end) continue

                        val currentRange = start until end
                        // 更宽松的重叠检测：只有完全重叠的范围才跳过
                        val isCompletelyOverlapping = appliedRanges.any {
                            it.first <= currentRange.first && it.last >= currentRange.last
                        }

                        if (!isCompletelyOverlapping) {
                            addStyle(SpanStyle(color = rule.color(theme)), start, end)
                            appliedRanges.add(currentRange)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Graceful fallback in case of a regex error or other issue
            AnnotatedString(code)
        }
    }
}
