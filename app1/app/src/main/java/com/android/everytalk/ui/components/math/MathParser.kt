package com.android.everytalk.ui.components.math

/**
 * 数学公式解析工具
 * 
 * 功能：
 * - 检测未闭合的数学公式（$...$ 或 $$...$$）
 * - 查找安全的流式切分点（避免在公式中间切断）
 * - 支持流式渲染的缓冲策略
 */
object MathParser {
    
    /**
     * 检查文本是否处于未闭合的数学公式中
     * 
     * 规则：
     * - 内联公式 $...$ ：单个 $ 必须成对
     * - 块级公式 $$...$$ ：双 $$ 必须成对
     * - 优先匹配 $$，避免将 $$ 误判为两个单独的 $
     * 
     * @param text 待检测文本
     * @return true 表示存在未闭合的数学公式
     */
    fun isInsideUnclosedMath(text: String): Boolean {
        // 策略：从左到右扫描，优先匹配 $$，剩余的 $ 必须成对
        var i = 0
        var inDoubleDollar = false
        var singleDollarCount = 0
        
        while (i < text.length) {
            // 检查是否为 $$
            if (i + 1 < text.length && text[i] == '$' && text[i + 1] == '$') {
                inDoubleDollar = !inDoubleDollar
                i += 2
                continue
            }
            
            // 单个 $（仅在非 $$ 块内计数）
            if (text[i] == '$' && !inDoubleDollar) {
                singleDollarCount++
            }
            
            i++
        }
        
        // 未闭合条件：$$ 块未闭合 或 单 $ 数量为奇数
        return inDoubleDollar || (singleDollarCount % 2 != 0)
    }
    
    /**
     * 查找安全的数学公式切分点
     * 
     * 返回最近一个完整闭合的数学公式结束位置（之后的索引）
     * 如果没有找到安全点，返回 -1
     * 
     * @param text 待切分文本
     * @return 安全切分点索引，-1 表示无安全点
     */
    fun findSafeMathCut(text: String): Int {
        var lastSafeCut = -1
        var i = 0
        var inDoubleDollar = false
        var singleDollarOpen = false
        
        while (i < text.length) {
            // 检查 $$
            if (i + 1 < text.length && text[i] == '$' && text[i + 1] == '$') {
                if (inDoubleDollar) {
                    // $$ 块闭合
                    lastSafeCut = i + 2
                    inDoubleDollar = false
                } else {
                    // $$ 块开启
                    inDoubleDollar = true
                }
                i += 2
                continue
            }
            
            // 单个 $（仅在非 $$ 块内处理）
            if (text[i] == '$' && !inDoubleDollar) {
                if (singleDollarOpen) {
                    // 单 $ 闭合
                    lastSafeCut = i + 1
                    singleDollarOpen = false
                } else {
                    // 单 $ 开启
                    singleDollarOpen = true
                }
            }
            
            i++
        }
        
        // 如果当前所有公式都已闭合，返回文本末尾
        if (!inDoubleDollar && !singleDollarOpen && lastSafeCut > 0) {
            return lastSafeCut
        }
        
        return lastSafeCut
    }
    
    /**
     * 提取文本中的数学公式（用于调试/测试）
     * 
     * @param text 源文本
     * @return 公式列表（包含 $ 或 $$ 标记）
     */
    fun extractMathExpressions(text: String): List<String> {
        val expressions = mutableListOf<String>()
        var i = 0
        
        while (i < text.length) {
            // 检查 $$
            if (i + 1 < text.length && text[i] == '$' && text[i + 1] == '$') {
                val start = i
                i += 2
                // 查找闭合的 $$
                while (i + 1 < text.length) {
                    if (text[i] == '$' && text[i + 1] == '$') {
                        expressions.add(text.substring(start, i + 2))
                        i += 2
                        break
                    }
                    i++
                }
                continue
            }
            
            // 单个 $
            if (text[i] == '$') {
                val start = i
                i++
                // 查找闭合的 $
                while (i < text.length) {
                    if (text[i] == '$') {
                        expressions.add(text.substring(start, i + 1))
                        i++
                        break
                    }
                    i++
                }
                continue
            }
            
            i++
        }
        
        return expressions
    }
}