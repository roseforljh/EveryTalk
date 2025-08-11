package com.example.everytalk.ui.components.math

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * 简单的MathML解析器
 * 将MathML转换为LaTeX，然后使用高性能渲染器显示
 */
class MathMLParser {
    
    companion object {
        /**
         * 解析MathML字符串并转换为LaTeX
         */
        fun parseToLatex(mathml: String): String {
            return try {
                val parser = createParser(mathml)
                val result = StringBuilder()
                
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    when (parser.eventType) {
                        XmlPullParser.START_TAG -> {
                            handleStartTag(parser, result)
                        }
                        XmlPullParser.TEXT -> {
                            val text = parser.text?.trim()
                            if (!text.isNullOrEmpty()) {
                                result.append(text)
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            handleEndTag(parser, result)
                        }
                    }
                    parser.next()
                }
                
                result.toString().trim()
            } catch (e: Exception) {
                // 解析失败时返回原始内容
                mathml
            }
        }
        
        private fun createParser(mathml: String): XmlPullParser {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(mathml))
            return parser
        }
        
        private fun handleStartTag(parser: XmlPullParser, result: StringBuilder) {
            when (parser.name?.lowercase()) {
                "mfrac" -> {
                    result.append("\\frac{")
                }
                "msup" -> {
                    // 上标处理在内容中完成
                }
                "msub" -> {
                    // 下标处理在内容中完成
                }
                "msqrt" -> {
                    result.append("\\sqrt{")
                }
                "mroot" -> {
                    result.append("\\sqrt[")
                }
                "msum" -> {
                    result.append("\\sum")
                }
                "mintegral" -> {
                    result.append("\\int")
                }
                "mi" -> {
                    // 标识符，通常是变量
                }
                "mn" -> {
                    // 数字
                }
                "mo" -> {
                    // 操作符
                }
                "mtext" -> {
                    result.append("\\text{")
                }
                "mspace" -> {
                    result.append(" ")
                }
                "mrow" -> {
                    // 行容器，通常不需要特殊处理
                }
            }
        }
        
        private fun handleEndTag(parser: XmlPullParser, result: StringBuilder) {
            when (parser.name?.lowercase()) {
                "mfrac" -> {
                    result.append("}")
                }
                "msup" -> {
                    // 需要在处理内容时添加^{}
                }
                "msub" -> {
                    // 需要在处理内容时添加_{}
                }
                "msqrt" -> {
                    result.append("}")
                }
                "mroot" -> {
                    result.append("]{}")
                }
                "mtext" -> {
                    result.append("}")
                }
            }
        }
        
        /**
         * 处理常见的MathML实体和符号
         */
        private fun processEntities(text: String): String {
            return text
                .replace("&alpha;", "\\alpha")
                .replace("&beta;", "\\beta")
                .replace("&gamma;", "\\gamma")
                .replace("&delta;", "\\delta")
                .replace("&epsilon;", "\\epsilon")
                .replace("&theta;", "\\theta")
                .replace("&lambda;", "\\lambda")
                .replace("&mu;", "\\mu")
                .replace("&pi;", "\\pi")
                .replace("&sigma;", "\\sigma")
                .replace("&phi;", "\\phi")
                .replace("&omega;", "\\omega")
                .replace("&infin;", "\\infty")
                .replace("&plusmn;", "\\pm")
                .replace("&times;", "\\times")
                .replace("&divide;", "\\div")
                .replace("&le;", "\\leq")
                .replace("&ge;", "\\geq")
                .replace("&ne;", "\\neq")
                .replace("&approx;", "\\approx")
                .replace("&equiv;", "\\equiv")
                .replace("&isin;", "\\in")
                .replace("&sub;", "\\subset")
                .replace("&sup;", "\\supset")
                .replace("&cup;", "\\cup")
                .replace("&cap;", "\\cap")
                .replace("&rarr;", "\\rightarrow")
                .replace("&larr;", "\\leftarrow")
                .replace("&harr;", "\\leftrightarrow")
                .replace("&rArr;", "\\Rightarrow")
                .replace("&lArr;", "\\Leftarrow")
                .replace("&hArr;", "\\Leftrightarrow")
        }
    }
}

/**
 * MathML支持的组合组件
 */
@Composable
fun MathMLView(
    mathml: String,
    modifier: Modifier = Modifier,
    textColor: Color = Color.Black,
    textSize: TextUnit = 16.sp,
    isDisplay: Boolean = false
) {
    val latex = remember(mathml) {
        MathMLParser.parseToLatex(mathml)
    }
    
    HighPerformanceMathView(
        latex = latex,
        modifier = modifier,
        textColor = textColor,
        textSize = textSize,
        isDisplay = isDisplay
    )
}

/**
 * 通用数学表达式组件 - 自动检测格式
 */
@Composable
fun UniversalMathView(
    expression: String,
    modifier: Modifier = Modifier,
    textColor: Color = Color.Black,
    textSize: TextUnit = 16.sp,
    isDisplay: Boolean = false
) {
    val processedExpression = remember(expression) {
        when {
            expression.trimStart().startsWith("<math") || expression.contains("<mrow") -> {
                // MathML格式
                MathMLParser.parseToLatex(expression)
            }
            expression.contains("\\") || expression.contains("^") || expression.contains("_") -> {
                // LaTeX格式
                expression
            }
            else -> {
                // 纯文本，应用基本符号替换
                expression
                    .replace("alpha", "\\alpha")
                    .replace("beta", "\\beta")
                    .replace("pi", "\\pi")
                    .replace("infinity", "\\infty")
                    .replace("<=", "\\leq")
                    .replace(">=", "\\geq")
                    .replace("!=", "\\neq")
                    .replace("+-", "\\pm")
                    .replace("*", "\\times")
                    .replace("->", "\\rightarrow")
            }
        }
    }
    
    HighPerformanceMathView(
        latex = processedExpression,
        modifier = modifier,
        textColor = textColor,
        textSize = textSize,
        isDisplay = isDisplay
    )
}