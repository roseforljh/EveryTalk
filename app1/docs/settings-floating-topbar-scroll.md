# 设置页顶部浮动栏与滚动内容遮挡问题记录

## 背景

设置页顶部使用浮动按钮栏，内容列表需要像聊天页一样滚动到顶部按钮区域后方。目标效果是：

- 顶部按钮浮在最上层。
- 内容列表铺满整屏。
- 初始状态下，第一张卡片不顶到顶部按钮。
- 用户向上滚动时，卡片可以继续滚到顶部区域后方，不被背景色或空白区域截断。

本问题曾表现为：卡片向上滚动到顶部时，被一块红色或黑色区域遮住。红色是调试用的 `Scaffold.containerColor = Color.Red`，黑色是正常主题背景色。

## 根因

根因不是 `Scaffold topBar` 本身，也不是按钮背景，也不是渐变。

真正根因是：**把顶部避让写在滚动容器外层 padding 上，导致滚动容器的可绘制区域被整体下移。**

错误模式示例：

```kotlin
Column(
    modifier = Modifier
        .fillMaxSize()
        .padding(top = topContentPadding)
        .verticalScroll(rememberScrollState())
) {
    Card(...)
}
```

或：

```kotlin
LazyColumn(
    modifier = Modifier
        .fillMaxSize()
        .padding(top = topContentPadding)
) {
    items(...)
}
```

这种写法会让滚动窗口从顶部按钮下方才开始。卡片向上滚动时，超过滚动窗口顶部边界的部分会被裁掉。被裁掉后露出的是 `Scaffold.containerColor`，所以调试时看到红块，正常主题下看到黑块。

## 正确模式

顶部避让应该放到滚动内容内部，而不是滚动容器外部。

正确模式：

```kotlin
Column(
    modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 20.dp)
) {
    Spacer(Modifier.height(topContentPadding))
    Card(...)
}
```

LazyColumn 场景应该使用 `contentPadding`：

```kotlin
LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(top = topContentPadding)
) {
    items(...)
}
```

核心区别：

- 外层 padding：移动滚动窗口，内容滚到顶部会被裁掉。
- 内部 Spacer / contentPadding：滚动窗口仍铺满全屏，只是初始内容往下留空。

## 当前设置页修复点

### 1. 平台配置列表

文件：

```text
app/src/main/java/com/android/everytalk/ui/screens/settings/SettingsScreenContent.kt
```

修复前的关键问题：

```kotlin
.padding(paddingValues)
.verticalScroll(rememberScrollState())
```

修复后：

```kotlin
Column(
    modifier = Modifier
        .fillMaxSize()
        .background(Color.Transparent)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 20.dp)
) {
    Spacer(Modifier.height(paddingValues.calculateTopPadding()))
    ...
}
```

### 2. 联网搜索列表

文件：

```text
app/src/main/java/com/android/everytalk/ui/screens/settings/SettingsScreenContent.kt
```

修复前：

```kotlin
.padding(top = topContentPadding + 20.dp, bottom = 20.dp)
```

修复后：

```kotlin
Column(
    modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp)
        .padding(top = 20.dp, bottom = 20.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
) {
    Spacer(Modifier.height(topContentPadding))
    ...
}
```

### 3. MCP 列表

文件：

```text
app/src/main/java/com/android/everytalk/ui/screens/settings/SettingsScreen.kt
```

修复前：

```kotlin
Column(
    modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 20.dp)
        .padding(top = topContentPadding, bottom = 20.dp)
) {
    McpServerListContent(...)
}
```

修复后：

```kotlin
Column(
    modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 20.dp)
        .padding(bottom = 20.dp)
) {
    Spacer(Modifier.height(topContentPadding))
    McpServerListContent(...)
}
```

## 调试方法

为了确认遮挡区域来自哪里，可以临时把页面底色改成明显颜色：

```kotlin
Scaffold(
    containerColor = Color.Red,
)
```

判断规则：

- 如果某块区域跟着 `containerColor` 变色，说明那里没有内容绘制，是底板露出来了。
- 如果区域颜色不变，说明它来自卡片、按钮、渐变或其他组件。

本次问题中，顶部遮挡区域会跟随 `containerColor` 变色，因此确定是滚动容器没有覆盖到顶部区域，而不是单独遮罩。

## 经验结论

需要浮动顶栏时：

1. 顶栏放在内容层上方叠加。
2. 滚动容器必须 `fillMaxSize()`，不能被顶部 padding 下移。
3. 初始避让用内部 `Spacer` 或 `LazyColumn.contentPadding`。
4. 不要用外层 `Modifier.padding(top = topContentPadding)` 给滚动容器做顶部避让。

一句话：

**让滚动窗口铺满全屏，让滚动内容自己留顶部空白。**
