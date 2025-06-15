package io.github.roseforljh.kuntalk.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import java.util.regex.Pattern

object CodeHighlighter {

    // region Catppuccin Latte Theme Color Palette (Fine-tuned)
    private val colorComment = Color(0xFF9CA0B0)       // Comment, Meta
    private val colorPunctuation = Color(0xFF888B9D)   // Punctuation: <> {} () , ;
    private val colorKeyword = Color(0xFF8839EF)     // Keywords: const, function, if, for
    private val colorOperator = Color(0xFF179299)    // Operators: =, +, %
    private val colorType = Color(0xFFDD7878)       // Types: String, int, bool
    private val colorFunction = Color(0xFF1E66F5)       // Function Name
    private val colorString = Color(0xFF40A02B)      // String
    private val colorNumber = Color(0xFFFE640B)      // Number
    private val colorVariable = Color(0xFF4C4F69)    // Variable & Default Text (Dark Grey)
    private val colorAnnotation = Color(0xFFD20F39)  // Annotation, Decorator, Preprocessor
    private val colorAttribute = Color(0xFFDD7878)  // HTML/XML Attribute Name
    private val colorTag = Color(0xFF1E66F5)       // HTML/XML Tag
    private val colorValue = Color(0xFF179299) // HTML/XML Attribute Value, CSS Value
    private val colorCssSelector = Color(0xFF7287FD)   // CSS Selector
    private val colorCssProperty = Color(0xFF1E66F5)   // CSS Property
    private val colorModule = Color(0xFF7287FD)      // Purple for Libraries/Namespaces (std, include, System)
    // endregion

    private data class Rule(val pattern: Pattern, val color: Color, val groupIndex: Int = 1)

    private val commonRules = listOf(
        Rule(Pattern.compile("(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*`|`(?:\\\\.|[^`\\\\])*`)"), colorString, 1),
        Rule(Pattern.compile("\\b([0-9]+(?:\\.[0-9]+)?)\\b"), colorNumber, 1)
    )

    private val cStyleCommentRule = Rule(Pattern.compile("//.*|\\/\\*[\\s\\S]*?\\*\\/"), colorComment, 0)
    private val pythonStyleCommentRule = Rule(Pattern.compile("#.*"), colorComment, 0)

    private fun getRules(language: String?): List<Rule> {
        val lang = language?.lowercase()?.trim()
        // The order of rules is critical. More specific rules must come first.
        return when (lang) {
            "html" -> listOf(
                Rule(Pattern.compile("<!--[\\s\\S]*?-->"), colorComment, 0),
                Rule(Pattern.compile("(<\\/?)([a-zA-Z0-9\\-]+)"), colorTag, 2),
                Rule(Pattern.compile("\\s([a-zA-Z\\-]+)(?=\\s*=)"), colorAttribute, 1),
                Rule(Pattern.compile("(\"[^\"]*\"|'[^']*')"), colorValue, 1),
                Rule(Pattern.compile("(&[a-zA-Z0-9#]+;)"), colorPunctuation, 1),
                Rule(Pattern.compile("([<>/=])"), colorPunctuation, 1)
            )
            "css" -> listOf(
                Rule(Pattern.compile("\\/\\*[\\s\\S]*?\\*\\/"), colorComment, 0),
                Rule(Pattern.compile("(\"[^\"]*\"|'[^']*')"), colorValue, 1),
                Rule(Pattern.compile("(#[a-fA-F0-9]{3,8})\\b"), colorValue, 1),
                Rule(Pattern.compile("\\b(body|html|div|span|a|p|h[1-6])\\b(?=[\\s,{])"), colorCssSelector, 1),
                Rule(Pattern.compile("([#.]-?[_a-zA-Z]+[_a-zA-Z0-9-]*)(?=[\\s,{.:])"), colorCssSelector, 1),
                Rule(Pattern.compile("(:[:]?[-a-zA-Z_0-9]+)"), colorCssSelector, 1),
                Rule(Pattern.compile("\\b([a-zA-Z\\-]+)(?=\\s*:)"), colorCssProperty, 1),
                Rule(Pattern.compile("\\b(-?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:px|em|rem|%|vh|vw|s|ms|deg|turn|fr)?)\\b"), colorValue, 1),
                Rule(Pattern.compile("\\b([a-zA-Z-]+)\\b"), colorValue, 1),
                Rule(Pattern.compile("([+\\-*/%])"), colorOperator, 1),
                Rule(Pattern.compile("([:;{}()\\[\\]])"), colorPunctuation, 1)
            )
            "javascript", "js", "typescript", "ts" -> listOf(
                cStyleCommentRule,
                *commonRules.toTypedArray(),
                Rule(Pattern.compile("\\b(const|let|var|function|return|if|else|for|while|switch|case|break|continue|new|this|import|export|from|default|async|await|try|catch|finally|class|extends|super|delete|in|instanceof|typeof|void|get|set|public|private|protected|readonly|enum|type|interface|implements|declare|namespace)\\b"), colorKeyword, 1),
                Rule(Pattern.compile("\\b(document|window|console|Math|JSON|Promise|Array|Object|String|Number|Boolean|Date|RegExp|any|string|number|boolean|void|null|undefined|never)\\b"), colorType, 1),
                Rule(Pattern.compile("\\b(true|false|null|undefined)\\b"), colorKeyword, 1),
                Rule(Pattern.compile("(@[a-zA-Z_][a-zA-Z0-9_]*)"), colorAnnotation, 1),
                Rule(Pattern.compile("(?<=\\bfunction\\s)([a-zA-Z0-9_]+)"), colorFunction, 1),
                Rule(Pattern.compile("(?<=[\\s.(])([a-zA-Z0-9_]+)(?=\\s*\\()"), colorFunction, 1),
                Rule(Pattern.compile("([=+\\-*/%<>!&|?^])"), colorOperator, 1),
                Rule(Pattern.compile("([;,.()\\[\\]{}])"), colorPunctuation, 1)
            )
            "python", "py" -> listOf(
                pythonStyleCommentRule,
                *commonRules.toTypedArray(),
                Rule(Pattern.compile("\\b(def|class|if|else|elif|for|while|return|import|from|as|try|except|finally|with|lambda|pass|break|continue|in|is|not|and|or|True|False|None|self|async|await)\\b"), colorKeyword, 1),
                Rule(Pattern.compile("\\b(int|str|float|list|dict|tuple|set|bool|object)\\b"), colorType, 1),
                Rule(Pattern.compile("(@[a-zA-Z_][a-zA-Z0-9_]*)"), colorAnnotation, 1),
                Rule(Pattern.compile("(?<=\\bdef\\s)([a-zA-Z0-9_]+)"), colorFunction, 1),
                Rule(Pattern.compile("(?<=\\bclass\\s)([a-zA-Z0-9_]+)"), colorType, 1),
                Rule(Pattern.compile("([=+\\-*/%<>!&|?^])"), colorOperator, 1),
                Rule(Pattern.compile("([:,.()\\[\\]{}])"), colorPunctuation, 1)
            )
            "java", "kotlin", "kt" -> listOf(
                cStyleCommentRule,
                *commonRules.toTypedArray(),
                Rule(Pattern.compile("\\b(public|private|protected|static|final|void|class|interface|enum|extends|implements|new|this|super|return|if|else|for|while|do|switch|case|break|continue|try|catch|finally|throw|throws|synchronized|volatile|transient|package|import|true|false|null|const|goto|var|val|fun|object|when|is|in|internal|override|suspend|reified|inline|data|sealed|companion|lateinit|int|long|float|double|char|boolean|String|Int|Long|Float|Double|Char|Boolean|Array|List|Map|Set|void|string|bool|object|dynamic)\\b"), colorKeyword, 1),
                Rule(Pattern.compile("(@[A-Z][a-zA-Z0-9_]*)"), colorAnnotation, 1),
                Rule(Pattern.compile("(?<=\\b(?:class|interface|enum)\\s)([A-Z][a-zA-Z0-9_]*)"), colorType, 1),
                Rule(Pattern.compile("\\b([A-Z][a-zA-Z0-9_]*)\\b"), colorType, 1),
                Rule(Pattern.compile("(?<=[\\s.(])([a-z_][a-zA-Z0-9_]*)(?=\\s*\\()"), colorFunction, 1),
                Rule(Pattern.compile("([=+\\-*/%<>!&|?^])"), colorOperator, 1),
                Rule(Pattern.compile("([;,.()\\[\\]{}])"), colorPunctuation, 1)
            )
            "csharp", "c#" -> listOf(
                cStyleCommentRule,
                *commonRules.toTypedArray(),
                Rule(Pattern.compile("\\b(public|private|protected|internal|static|readonly|virtual|override|sealed|abstract|async|await|void|class|interface|enum|struct|delegate|event|namespace|using|new|this|base|return|if|else|for|foreach|while|do|switch|case|break|continue|try|catch|finally|throw|lock|goto|var|const|in|out|ref|params|is|as|true|false|null)\\b"), colorKeyword, 1),
                Rule(Pattern.compile("\\b(int|long|float|double|decimal|char|bool|string|object|byte|sbyte|short|ushort|uint|ulong|dynamic|var)\\b"), colorType, 1),
                Rule(Pattern.compile("(?<=\\busing\\s)(System(?:\\.[A-Z][a-zA-Z_0-9]*)*)"), colorModule, 1),
                Rule(Pattern.compile("(\\[[^\\]]*\\])"), colorAnnotation, 0),
                Rule(Pattern.compile("(?<=\\b(?:class|interface|enum|struct)\\s)([A-Z][a-zA-Z0-9_]*)"), colorType, 1),
                Rule(Pattern.compile("\\b([A-Z][a-zA-Z0-9_]*)\\b"), colorType, 1),
                Rule(Pattern.compile("(?<=[\\s.(])([A-Z_][a-zA-Z0-9_]*)(?=\\s*\\()"), colorFunction, 1),
                Rule(Pattern.compile("([=+\\-*/%<>!&|?^])"), colorOperator, 1),
                Rule(Pattern.compile("([;,.()\\[\\]{}])"), colorPunctuation, 1)
            )
            "cpp", "c++", "c" -> listOf(
                cStyleCommentRule,
                Rule(Pattern.compile("(#\\s*(?:include|define|ifdef|ifndef|if|else|elif|endif|pragma))"), colorAnnotation, 1),
                Rule(Pattern.compile("(<[^>]*>)"), colorString, 1),
                *commonRules.toTypedArray(),
                Rule(Pattern.compile("\\b(public|private|protected|static|virtual|override|final|class|struct|union|enum|new|delete|this|return|if|else|for|while|do|switch|case|break|continue|try|catch|throw|const|constexpr|volatile|typedef|using|namespace|template|typename|auto|void|int|long|float|double|char|bool|short|signed|unsigned|true|false|nullptr|wchar_t)\\b"), colorKeyword, 1),
                Rule(Pattern.compile("\\b(std)(?=::)"), colorModule, 1),
                Rule(Pattern.compile("(?<=\\b(?:class|struct|enum)\\s)([A-Z_][a-zA-Z0-9_]*)"), colorType, 1),
                Rule(Pattern.compile("\\b([A-Z][a-zA-Z0-9_]*_t|[A-Z][a-zA-Z0-9_]*)\\b"), colorType, 1),
                Rule(Pattern.compile("(?<=[\\s.(])([a-zA-Z_][a-zA-Z0-9_]*)(?=\\s*\\()"), colorFunction, 1),
                Rule(Pattern.compile("([=+\\-*/%<>!&|?^~])"), colorOperator, 1),
                Rule(Pattern.compile("([:;,.()\\[\\]{}])"), colorPunctuation, 1)
            )
            "rust", "rs" -> listOf(
                cStyleCommentRule,
                *commonRules.toTypedArray(),
                Rule(Pattern.compile("\\b(fn|let|mut|const|static|pub|use|mod|crate|super|self|if|else|loop|while|for|in|match|return|break|continue|async|await|struct|enum|union|trait|impl|type|where|true|false)\\b"), colorKeyword, 1),
                Rule(Pattern.compile("\\b(i8|i16|i32|i64|i128|isize|u8|u16|u32|u64|u128|usize|f32|f64|bool|char|str|String|Vec|Option|Result|Box|Rc|Arc|&'static)\\b"), colorType, 1),
                Rule(Pattern.compile("(#\\!\\?|#\\!?\\[[^\\]]*\\])"), colorAnnotation, 0),
                Rule(Pattern.compile("\\b([A-Z][a-zA-Z0-9_]*)\\b"), colorType, 1),
                Rule(Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)(?=!)"), colorFunction, 1), // Macros
                Rule(Pattern.compile("(?<=[\\s.(])([a-zA-Z_][a-zA-Z0-9_]*)(?=\\s*\\()"), colorFunction, 1),
                Rule(Pattern.compile("([=+\\-*/%<>!&|?^:])"), colorOperator, 1),
                Rule(Pattern.compile("([;,.()\\[\\]{}])"), colorPunctuation, 1)
            )
            "go" -> listOf(
                cStyleCommentRule,
                *commonRules.toTypedArray(),
                Rule(Pattern.compile("\\b(package|import|func|var|const|if|else|for|range|switch|case|default|return|break|continue|goto|go|select|chan|map|struct|interface|type|defer|panic|recover|true|false|nil)\\b"), colorKeyword, 1),
                Rule(Pattern.compile("\\b(int|int8|int16|int32|int64|uint|uint8|uint16|uint32|uint64|uintptr|float32|float64|complex64|complex128|bool|string|byte|rune|error)\\b"), colorType, 1),
                Rule(Pattern.compile("(?<=\\bfunc\\s)(?:\\([^)]*\\)\\s*)?([a-zA-Z_][a-zA-Z0-9_]*)"), colorFunction, 1),
                Rule(Pattern.compile("([=+\\-*/%<>!&|?^:]{1,2})"), colorOperator, 1),
                Rule(Pattern.compile("([;,.()\\[\\]{}])"), colorPunctuation, 1)
            )
            "shell", "bash" -> listOf(
                pythonStyleCommentRule,
                Rule(Pattern.compile("(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*`)"), colorString, 1),
                Rule(Pattern.compile("\\b(if|then|else|elif|fi|for|in|while|do|done|case|esac|function|select|echo|exit|return|unset|export|readonly|declare|local|source|shift|getopts)\\b"), colorKeyword, 1),
                Rule(Pattern.compile("(\\$\\w+|\\$\\{[^}]*\\})"), colorVariable, 1),
                Rule(Pattern.compile("(-[a-zA-Z][a-zA-Z0-9_-]*)"), colorAttribute, 1),
                Rule(Pattern.compile("([=><|&;])"), colorOperator, 1),
                Rule(Pattern.compile("([\\[\\]{}()])"), colorPunctuation, 1)
            )
            else -> emptyList()
        }
    }

    fun highlightToAnnotatedString(code: String, language: String?): AnnotatedString {
        val rules = getRules(language)
        
        return buildAnnotatedString {
            append(code)
            
            val appliedSpans = Array(code.length) { false }

            rules.forEach { rule ->
                val matcher = rule.pattern.matcher(code)
                while (matcher.find()) {
                    val targetGroup = rule.groupIndex
                    if (targetGroup > matcher.groupCount()) continue

                    val start = matcher.start(targetGroup)
                    val end = matcher.end(targetGroup)

                    if (start == -1 || start >= end) continue

                    var alreadyStyled = false
                    for (i in start until end) {
                        if (appliedSpans[i]) {
                            alreadyStyled = true
                            break
                        }
                    }

                    if (!alreadyStyled) {
                        addStyle(SpanStyle(color = rule.color), start, end)
                        for (i in start until end) {
                            appliedSpans[i] = true
                        }
                    }
                }
            }
        }
    }
}
