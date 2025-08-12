fun main() {
    val testMarkdown = """
        这份训练清单是**李小龙 (Bruce Lee)**在
        
        测试加粗文本：**粗体文字**
        测试斜体文本：*斜体文字*
        测试内联代码：`代码块`
        
        # 标题测试
        ## 二级标题
        
        - 列表项1
        - 列表项2
    """.trimIndent()
    
    println("测试Markdown内容：")
    println(testMarkdown)
    println("\n预期效果：李小龙应该显示为加粗文本")
}