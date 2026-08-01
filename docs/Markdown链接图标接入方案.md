# Markdown 链接 Logo 接入计划

当前状态：已完成代码接入，定向测试和 Debug APK 构建通过。全量单测仍有一个既有的 `ChatMessagesListRenderRouteTest` 字符串断言失败，与本功能无关。

## 1. 目标

为普通 Markdown 链接、自动链接和引用链接增加网站 Logo，达到以下效果：

```text
网站 Logo  链接标题
```

链接文字继续使用当前淡蓝色样式，点击目标、文本选择和换行行为保持不变。

本计划只处理链接前置 Logo，不改动以下内容：

1. MikePenz Markdown 的解析归属。
2. EveryTalk 自有代码块 `CodeBlockCard`。
3. 表格透明背景、水平滚动和边缘渐变阴影。
4. 数学公式 MathJax 渲染链。
5. 页面顶部穿透、滚动阴影和会话滚动逻辑。

## 2. 当前根因

当前 `MikePenzMarkdownRenderer` 只配置了 `TextLinkStyles`，库会生成链接文本和点击标注，但不会根据 URL 查询网站 favicon，也没有在 `AnnotatedString` 中插入图标占位符。

项目已有两处 favicon 逻辑：

1. `WebSourcesDialog.kt`
2. `ChatMessagesList.kt`

这两处仅用于网页来源卡片，Markdown 普通链接没有复用，因此正文链接没有 Logo。

## 3. 采用方案

### 3.1 Logo 来源

复用当前项目已经使用的 favicon 服务：

```text
https://www.google.com/s2/favicons?domain={host}&sz=64
```

只向服务发送规范化后的域名，不发送完整 URL 的路径、查询参数或页面内容。

### 3.2 URL 安全范围

只为以下链接加载 Logo：

1. `http` 链接。
2. `https` 链接。
3. 能够解析出有效 host 的链接。

以下链接不加载 Logo：

1. 脚注内部链接 `everytalk-footnote-*`。
2. `mailto:`、`tel:`、`file:`、`javascript:` 和自定义 scheme。
3. Markdown 图片地址。
4. 代码块中的 URL。

### 3.3 渲染接入点

在 `MarkdownAnnotator` 处理链接节点时完成以下动作：

1. 识别 `INLINE_LINK`、自动链接和引用链接。
2. 解析链接的最终 destination。
3. 为有效外链追加一个稳定的 `InlineTextContent` 占位符。
4. 返回 `false`，继续交给 MikePenz 原生逻辑渲染链接文本和点击标注。

这样可以保持原生 Markdown 链接结构，Logo 只作为前置视觉内容插入。

### 3.4 InlineContent 设计

新增一个稳定的 Logo 内容 Map，键值使用规范化 host，避免同一网站在一条消息中重复创建加载任务。

每个 Logo 占位符固定尺寸，建议如下：

1. 图标尺寸：`16.dp`。
2. 图标与链接文字间距：`4.dp`。
3. 圆角：`4.dp`。
4. 背景：跟随 Material 3 的 `surfaceVariant`。
5. 加载中：使用现有 `EveryTalkLoadingIndicator` 的小尺寸版本。
6. 加载失败：显示网站 host 首字母或通用地球图标，保留占位尺寸，避免布局跳动。

Logo 的 `contentDescription` 使用“链接来源：{host}”，链接正文仍由原生链接标注负责点击。

## 4. 代码改动范围

### 第一步：抽取 favicon 工具

新增通用工具文件，建议位置：

```text
app/src/main/java/com/android/everytalk/util/web/LinkFavicon.kt
```

负责：

1. URL 解析。
2. host 规范化。
3. favicon URL 生成。
4. scheme 和空 host 校验。
5. fallback 首字母生成。

将 `WebSourcesDialog.kt` 和 `ChatMessagesList.kt` 的重复 host、favicon、首字母逻辑迁移到该工具，保持现有来源卡片外观不变。

### 第二步：接入 Markdown 渲染器

修改：

```text
app/src/main/java/com/android/everytalk/ui/components/markdown/MikePenzMarkdownRenderer.kt
```

新增：

1. Markdown 链接收集函数。
2. host 去重后的 Logo 请求集合。
3. `InlineTextContent` Map。
4. Logo 占位符的 Compose 内容。
5. Annotator 的前置 Logo 插入逻辑。

`remember` 的 key 只使用链接集合和当前消息版本，避免 AI 流式输出的每个字符都重新创建全部 Logo 内容。

### 第三步：保持流式渲染稳定

流式输出期间遵循以下规则：

1. 未形成完整 URL 的片段不创建 Logo 请求。
2. 已创建的 host 内容 Map 保持稳定引用。
3. Coil 命中缓存时直接显示图标。
4. 网络加载只更新对应 Logo 的 InlineContent，不重建整条 AI 气泡。
5. 流式结束切换静态全文时复用同一 host key，避免闪烁。

## 5. 测试计划

### 5.1 单元测试

新增或补充 Markdown 组件测试，覆盖：

1. `https://example.com/a` 能生成 favicon URL。
2. `http://example.com` 能生成 favicon URL。
3. `mailto:`、`javascript:`、脚注 scheme 不生成 favicon URL。
4. 无 host、非法 URL 不崩溃。
5. 同一 host 的多个链接只创建一个 Logo 内容项。
6. 普通链接、自动链接、引用链接都能被识别。
7. 图片链接和代码块 URL 不会插入 Logo。
8. 表格内链接仍能正常显示 Logo，表格滚动参数不变。

### 5.2 现有回归测试

继续执行：

```text
./gradlew :app:testDebugUnitTest --tests "com.android.everytalk.ui.components.markdown.*"
./gradlew :app:compileDebugKotlin
./gradlew :app:assembleDebug
```

不操作手机、模拟器或真机验收。

## 6. 验收标准

1. 官方政策链接前显示网站 Logo。
2. Logo 加载中有小型转圈动画。
3. Logo 加载失败显示稳定的首字母或通用图标，不出现大块空白。
4. 白天和夜间模式下 Logo 背景、加载动画和文字均清晰。
5. 链接文字保持淡蓝色，无深蓝色和粗下划线。
6. 链接仍可点击、复制和选择。
7. 表格、代码块、数学公式和图片渲染无回归。
8. AI 流式输出期间不出现整条气泡因 Logo 加载而频繁重组。
9. 相同网站在同一消息中只发生一次实际图片加载。

## 7. 实施顺序

1. 先抽取并测试 favicon 工具。
2. 再接入 Markdown Annotator 和 InlineContent。
3. 再迁移两个网页来源卡片的重复工具逻辑。
4. 运行 Markdown 定向测试和 Kotlin 编译。
5. 最后运行 Debug APK 构建。
