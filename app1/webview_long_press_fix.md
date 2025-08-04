# WebView 长按事件修复文档

## 问题描述
在聊天气泡中，当消息包含数学公式（MathView）或代码预览（CodePreview）时，这些WebView组件会拦截长按事件，阻止事件传递到父级的气泡长按处理器，导致无法触发AI气泡的长按菜单。

## 解决方案

### 1. MathView 组件修改 (`MathView.kt`)

#### 触摸事件处理优化
- 修改 `onTouchEvent` 方法，对于非基本触摸事件（如长按），返回 `false` 不消费事件
- 保留基本的触摸事件处理（DOWN, MOVE, UP）用于水平滚动

#### WebView 设置优化
- 添加 `setOnLongClickListener { false }` - 不消费长按事件，让父级处理
- 设置 `isLongClickable = false` - 禁用WebView自身的长按功能
- 添加 `settings.textZoom = 100` - 禁用文本缩放

#### CSS 样式禁用文本选择
在HTML模板中添加了全局CSS规则：
```css
* {
    -webkit-user-select: none;
    -moz-user-select: none;
    -ms-user-select: none;
    user-select: none;
    -webkit-touch-callout: none;
    -webkit-tap-highlight-color: transparent;
}
```

### 2. CodePreview 组件修改 (`CodePreview.kt`)

#### WebView 设置优化
- 添加 `setOnLongClickListener { false }` - 不消费长按事件
- 设置 `isLongClickable = false` - 禁用长按功能
- 添加缩放和文本选择相关的禁用设置
- 添加 `setOnTouchListener` 处理基本触摸事件

#### 通用CSS样式
创建了 `disableSelectionCSS` 变量，包含禁用文本选择的CSS规则，并应用到所有HTML模板中：
- HTML 模板
- XML/代码显示模板
- SVG 模板
- Markdown 模板
- Mermaid 图表模板
- PlantUML 模板
- D3.js 模板
- P5.js 模板
- Three.js 模板
- Chart.js 模板
- Canvas 模板
- LaTeX 模板
- JSON 模板
- JavaScript 模板
- 默认代码模板

## 技术细节

### 事件传递机制
1. WebView 不消费长按事件（返回 `false`）
2. 事件向上传递到父级 Surface 组件
3. Surface 的 `pointerInput` 检测到长按手势
4. 触发 `onLongPress` 回调，显示AI气泡菜单

### CSS 禁用选择的作用
- 防止用户在WebView内容上进行文本选择
- 禁用长按选择菜单
- 禁用触摸高亮效果
- 确保所有触摸交互都能正确传递

## 测试建议
1. 测试包含数学公式的消息长按功能
2. 测试包含代码块的消息长按功能
3. 验证WebView内容仍可正常显示和滚动
4. 确认长按AI气泡能正确显示菜单

## 兼容性
- 支持所有现代WebView版本
- CSS规则兼容主流浏览器内核
- 不影响WebView的基本功能（显示、滚动等）
