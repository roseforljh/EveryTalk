# EveryTalk Markdown 与数学公式统一渲染计划

| 项目 | 内容 |
|---|---|
| 状态 | 核心生产链路已实施，自动化回归与用户视觉验收分开记录 |
| 制定日期 | 2026-07-19 |
| Android 工程 | `app1/` |
| Markdown 引擎 | MikePenz `multiplatform-markdown-renderer` `0.43.0` |
| 代码块 | EveryTalk `CodeBlockCard` |
| 数学引擎 | 本地 MathJax 4 SVG |
| 物理设备验收 | 仅由用户操作 |

## 1. 文档目的

本文档记录 EveryTalk 聊天消息中 Markdown、代码块和数学公式的最终职责划分、目标架构、实施顺序、提交拆分、测试矩阵、性能门槛、安全要求和验收标准。

本计划解决以下问题：

- Markdown、代码块和公式曾经由多套实现交叉处理。
- 当前数学公式为每个公式创建一个 WebView。
- 当前源码加载 `file:///android_asset/katex/index.html`，当前仓库和 Debug 构建产物中没有该资产。
- 当前行内公式尺寸依赖字符数量估算。
- 当前块级公式存在像素直接当作 dp 使用的问题。
- 当前数学失败状态会显示灰底 LaTeX 原文。
- 流式状态和最终 Markdown 渲染会重复解析同一条消息。
- 项目中仍有无生产调用的旧数学解析 Module 和渲染空壳。

本文档描述的是数学公式排版能力。MathJax 4 支持广泛的 TeX 数学语法，但不执行任意完整 LaTeX 文档、宏包或文档级排版。

## 实施记录（2026-07-19）

已落地：

- `PreparedMessage` 唯一解析契约、稳定公式 ID、流式 `contentVersion`。
- MikePenz 官方 `typography`、`padding`、`colors` 接口集中管理正文、标题、链接和块间距。
- 脚注作为唯一受控 Markdown 扩展，提供上标引用、双向页内跳转和文末返回入口，不再注入分隔线。
- MathJax `4.1.3`、NewCM `4.1.3` 全部 SVG 动态字体、本地资产完整性校验。
- 全应用唯一不可见 WebView、批量 JSON 转换、进程退出重试和安全来源限制。
- MikePenz `MarkdownAnnotator + InlineTextContent` 行内公式接入。
- Compose + Coil SVG 行内和块级公式视图、基线下沉、宽公式水平滚动。
- 16 MiB 按字节计费的进程内 LRU、32 px 宽度桶、内容版本过期保护。
- 明确的语法、超时、引擎和 SVG 解码错误状态。
- 旧 KaTeX 每公式 WebView、Base64 数学载荷、重复公式解析和无调用渲染空壳清理。

仍待完成或由用户验收：

- Android System WebView 真实运行时冒烟测试。
- 浅色和深色主题、字体缩放、宽度变化、长列表滚动和长会话恢复。
- 真机性能门槛和 WebView 渲染进程退出恢复。物理设备只由用户操作。

## 2. 最终架构决策

职责永久固定如下：

| 内容类型 | 唯一负责人 | 说明 |
|---|---|---|
| 普通 Markdown | MikePenz | 标题、列表、引用、表格、图片、强调和链接均使用库的 Material 3 原生组件，视觉参数通过库的官方主题接口集中配置 |
| `markdown` / `md` 外层围栏 | `StreamBlockParser` | 仅移除模型附加的传输围栏，内部内容继续按普通 Markdown、真实代码块和公式统一分流 |
| fenced code | `CodeBlockCard` | 保留复制、语言标识、预览和现有交互 |
| 行内数学公式 | MathJax 4 SVG | 通过 MikePenz 内联图片插槽嵌入正文 |
| 块级数学公式 | MathJax 4 SVG | 使用独立 Compose 公式视图，超宽内容水平滚动 |
| 公式识别 | `StreamBlockParser` | 统一处理流式闭合、代码、转义和货币 |
| 数学失败状态 | 明确公式错误视图 | 不切换其他数学引擎，不伪装成已成功渲染 |

明确排除：

- 不使用整条消息 WebView。
- 不恢复 Markwon。
- 不恢复 jLaTeXMath。
- 不保留 KaTeX 运行时。
- 不建立数学引擎运行时切换开关。
- 不重写 MikePenz 标准 Markdown 组件。
- 不为标题、链接、列表或表格建立第二套渲染器。
- 不修改 `CodeBlockCard` 的视觉和功能。
- 不修改页面上下阴影及 `ScrollFadeEdge.kt`。
- 不修改数据库结构和历史消息原文格式。

## 3. 目标调用链

```text
模型输出 Markdown
        |
        v
StreamBlockParser
        |
        v
PreparedMessage
        |
        +--------------------+---------------------+
        |                    |                     |
        v                    v                     v
普通 Markdown          fenced code            数学公式请求
        |                    |                     |
        v                    v                     v
    MikePenz           CodeBlockCard        MathFormulaStore
                                                   |
                                                   v
                                          MathJaxSvgRenderer
                                                   |
                                                   v
                                  SVG + 宽度 + 高度 + 基线
                                                   |
                                      +------------+------------+
                                      |                         |
                                      v                         v
                                  行内公式                  块级公式
```

架构约束：

- 全项目只有一个 `UnifiedMarkdownRenderer` 生产入口。
- 全项目只有一个公式识别实现。
- 全项目只有一个数学排版引擎。
- 聊天列表中的可见 WebView 数量必须为零。
- 应用进程内最多存在一个不可见 MathJax 转换 WebView。
- 不定义通用 `MathEngine` 插件 Interface，直接使用具体的 `MathJaxSvgRenderer`。

## 4. 迁移前实现基线

迁移前的生产入口已经命名为 [`UnifiedMarkdownRenderer`](../../app1/app/src/main/java/com/android/everytalk/ui/components/streaming/StreamBlocksRenderer.kt)，但内部数学链路仍存在以下旧实现问题。当前完成态以第 15 节实施记录为准。

迁移前分流行为：

- 普通 Markdown 进入 MikePenz。
- 普通代码围栏进入 `CodeBlockCard`。
- 行内公式被编码成 `everytalk-math-inline:` 图片链接。
- 块级公式被编码成 `everytalk-internal-math-v1` 代码围栏。
- `MathInline` 和 `MathBlock` 为每个公式创建独立 WebView。
- WebView 加载不存在的 `assets/katex/index.html`。
- 渲染失败后 `FallbackRaw` 显示原始 LaTeX。

迁移前重复解析：

1. `StreamingMessageStateManager` 调用 `StreamBlockParser`。
2. `StreamingRenderState` 保存多个 block、hash 和区间字段。
3. 页面只根据 block 是否为空决定是否调用渲染入口。
4. `MikePenzMarkdownRenderer` 再次解析完整 Markdown。

现有相关单元测试能够验证分隔符和内部占位符，但无法证明以下内容：

- 数学资产被打包进 APK。
- MathJax 或 KaTeX JavaScript 能够执行。
- WebView 能够返回正确 SVG。
- 行内宽高和基线正确。
- 块级公式不会产生异常空白。

## 5. 核心数据结构

### 5.1 PreparedMessage

`PreparedMessage` 是 Markdown 渲染入口唯一消费的预处理结果。

字段：

- `markdown`：供 MikePenz 解析的 Markdown。
- `formulas`：稳定公式 ID 到 `FormulaRequest` 的映射。
- `hasPendingFormula`：当前是否存在未闭合公式。
- `contentVersion`：消息内容版本，用于丢弃过期异步结果。

### 5.2 FormulaRequest

字段：

- `id`：根据规范化 LaTeX 和 `displayMode` 计算的稳定 ID。
- `latex`：已经去除外部分隔符的公式正文。
- `displayMode`：行内或块级。
- `contentVersion`：所属消息版本。

字号、字体缩放、密度、宽度桶、前景色、主题和 MathJax 版本属于渲染配置，只进入 SVG 缓存键，不进入解析层的 `FormulaRequest`。

### 5.3 FormulaAsset

字段：

- `svg`：经过安全检查的自包含 SVG。
- `widthPx`：公式实际宽度。
- `heightPx`：公式实际高度。
- `depthPx`：行内公式基线下沉值。
- `viewBox`：SVG 视口。
- `contentDescription`：无障碍描述。
- `engineVersion`：生成该资产的 MathJax 版本。
- `cacheKey`：完整缓存键。

### 5.4 FormulaRenderState

状态：

- `Pending`：等待转换或流式公式尚未闭合。
- `Ready`：SVG 和尺寸元数据可用。
- `SyntaxError`：输入公式无法解析。
- `EngineError`：MathJax 页面或 WebView 故障。
- `Timeout`：转换超过规定时间。

## 6. 实施阶段

### 阶段 0：固定行为基线

目标：在替换数学引擎前锁定现有 Markdown 和代码块行为。

任务：

- [x] 将完整 Markdown 测试文本保存为测试常量。
- [x] 固化标题、列表、引用、表格、图片和任务列表行为。
- [x] 固化普通代码围栏进入 `CodeBlockCard` 的行为。
- [x] 覆盖 fenced code 和 inline code 中的美元符号。
- [x] 覆盖 `$12`、`USD 12`、比分、时间、版本号和 Markdown 链接。
- [x] 覆盖表格单元格中的行内公式。
- [x] 覆盖未闭合行内和块级公式。
- [x] 记录当前 Debug APK 大小和 SHA-256。
- [x] 记录最终全量单元测试耗时。

验证：

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
```

### 阶段 1：加入 MathJax 本地资产

目标：建立完全离线、固定版本、可验证的 MathJax 运行环境。

任务：

- [x] 在版本目录中加入 `androidx.webkit:webkit`。
- [x] 加入 `io.coil-kt.coil3:coil-svg:3.5.0`。
- [x] 从官方发行物锁定 MathJax `4.1.3`。
- [x] 将 TeX 输入、SVG 输出和 40 个 NewCM 动态字体分片放入 `assets/mathjax/`。
- [x] 保存 MathJax 版本、来源、许可证、文件校验和和渲染配置哈希。
- [x] 新增 `assets/mathjax/index.html`。
- [x] 添加构建资产完整性检查。
- [x] 资产缺失或哈希不一致时让构建直接失败。

本地页面地址：

```text
https://appassets.androidplatform.net/assets/mathjax/index.html
```

MathJax配置：

- TeX输入。
- SVG输出。
- `fontCache: "local"`。
- 每个公式使用唯一 `localID`。
- 禁用 MathJax 菜单。
- TeX扩展采用明确白名单。
- 禁止动态加载外部资源。

### 阶段 2：实现 MathJaxSvgRenderer

目标：使用一个不可见 WebView完成全部公式转换。

任务：

- [x] 新增具体 Module `MathJaxSvgRenderer`。
- [x] 使用 Application Context，禁止持有 Activity。
- [x] 所有 WebView 操作固定在主线程。
- [x] 使用 `WebViewCompat.startUpWebView()` 延迟预热。
- [x] 使用 `WebViewAssetLoader` 加载本地页面。
- [x] 使用可信来源的 ready 桥接消息判断页面和转换函数就绪。
- [x] ready 消息同时校验 MathJax 版本和渲染配置哈希。
- [x] 删除固定等待 150ms 的就绪策略。
- [x] 页面暴露唯一批量函数 `renderBatch(requests)`。
- [x] 使用 kotlinx.serialization 生成 JSON 参数。
- [x] 删除手工 JavaScript 字符串转义。
- [x] 通过稳定公式 ID 和缓存对同一公式请求去重。
- [x] 使用有界队列，上限 64。
- [x] 单批处理上限 16 个公式。
- [x] 每个请求携带消息版本令牌。
- [x] 支持请求取消和过期结果丢弃。
- [x] 处理 `onRenderProcessGone`。

WebView故障策略：

1. 语法错误直接返回 `SyntaxError`，不重试。
2. WebView渲染进程退出后重建一次。
3. 引擎重建后重试一次当前批次。
4. 第二次失败进入 `EngineError`。
5. 不切换其他数学引擎。

### 阶段 3：建立 SVG 安全和缓存

目标：公式只转换一次，滚动历史消息时直接使用缓存。

缓存键包含：

- 规范化 LaTeX。
- 行内或块级模式。
- 字号和字体缩放。
- 密度。
- 宽度桶。
- 前景色或主题。
- MathJax版本。
- TeX包配置哈希。

公式规范化只执行：

- CRLF统一为 LF。
- 去除外部 `$`、`$$`、`\(`、`\)`、`\[` 和 `\]`。
- 去除首尾无意义空白。
- 保留公式内部空白、命令和换行。

内存缓存：

- [x] 使用按字节计费的 LRU。
- [x] 内存缓存上限 16 MiB。
- [x] 同时缓存 SVG 和尺寸元数据。

磁盘缓存：

- [x] 保存 `<hash>.svg` 和 JSON 元数据。
- [x] 磁盘缓存上限 64 MiB。
- [x] 使用临时文件、同步落盘和原子移动。
- [x] 按最近使用时间清理。
- [x] 引擎版本或渲染配置变化后自然生成新缓存键。

失败缓存：

- [x] 短期缓存公式语法错误。
- [x] 防止同一个错误公式持续占用转换队列。

SVG安全检查：

- [x] 拒绝 `<script>`。
- [x] 拒绝事件属性。
- [x] 拒绝外部链接和外部资源。
- [x] 拒绝 `foreignObject`。
- [x] 拒绝非本地字体和图片引用。
- [x] 限制 SVG 最大 1 MiB、最多 8192 个元素节点。

### 阶段 4：接入 Compose 和 MikePenz

目标：公式最终以普通 Compose 内容参与布局，消息列表中没有公式 WebView。

块级公式：

- [x] 使用 Coil SVG 加载公式。
- [x] 使用真实宽高比。
- [x] 正常公式居中。
- [x] 超宽矩阵和无法断行公式使用独立水平滚动容器。
- [x] 删除固定 300dp 高度。
- [x] 删除旧 CSS px 估算布局逻辑。
- [x] 删除块级公式 WebView 和其自身滚动。

行内公式：

- [x] 保留 MikePenz 内联内容插槽。
- [x] 将内部链接改为稳定 SHA-256 公式 ID。
- [x] 禁止在 Markdown 内部保存完整 Base64 LaTeX。
- [x] 使用 MathJax 返回的实际宽度和高度。
- [x] 使用 `depthPx` 修正视觉基线。
- [x] 临时占位尺寸保持稳定。
- [x] 公式就绪后只更新一次尺寸。
- [x] 删除按字符数估算宽高的逻辑。

表格：

- [x] 表格继续由 MikePenz 原生渲染。
- [x] 单元格公式使用相同内联公式 Adapter。
- [x] 禁止把表格拆成多个独立 Compose Column。

复制和无障碍：

- [x] 消息复制始终读取原始 Markdown。
- [x] 公式设置 LaTeX 语义描述。
- [x] 公式错误态保留原始 LaTeX 语义和消息复制能力。
- [x] 不声明 SVG 支持跨公式文本选择。

### 阶段 5：原子切换生产路径

目标：一次切换全部行内和块级公式，最终生产路径只剩 MathJax 4。

任务：

- [x] 在同一工作树中切换行内和块级公式 Adapter。
- [x] 生产消息列表停止使用公式级 WebView。
- [x] 删除聊天公式 KaTeX 加载路径。
- [x] 删除 `FallbackRaw`。
- [x] 删除数学引擎运行时开关。
- [x] 公式错误只显示明确错误状态。
- [x] 禁止把原始 LaTeX 渲染成灰色伪代码块。
- [x] 禁止静默吞掉公式。

### 阶段 6：统一解析和流式状态

目标：同一条消息只执行一次结构解析。

唯一解析 Module 负责：

- fenced code。
- inline code。
- `$...$`。
- `$$...$$`。
- `\(...\)`。
- `\[...\]`。
- 转义美元符号。
- 货币判断。
- 流式未闭合公式。

任务：

- [x] 以 `StreamBlockParser` 为唯一标记解析入口，不新增同义解析类。
- [x] 生成 `PreparedMessage`。
- [x] 流式状态和最终渲染共享同一份结果。
- [x] `MikePenzMarkdownRenderer` 停止再次完整解析公式边界。
- [x] 旧转换结果根据 `contentVersion` 自动失效。
- [x] 已缓存公式不因后续普通文本 token 重新转换。

收缩 `StreamingRenderState`：

- [x] 保留消息内容版本。
- [x] 保留流式和完成状态。
- [x] 保留是否存在未闭合公式。
- [x] 保留 `PreparedMessage`。
- [x] 删除未被调用方消费的 `committedBlocks`。
- [x] 删除未被调用方消费的 `tailBlocks`。
- [x] 删除未被调用方消费的相关 hash。
- [x] 删除未被调用方消费的代码和数学区间。

### 阶段 7：删除旧实现和更新文档

数学迁移清理：

- [x] 删除旧公式级 WebView 实现。
- [x] 删除 `MathParser.kt`。
- [x] 删除 `MathStreamingPolicy.kt`。
- [x] 删除 `MathDelimiterNormalizer.kt`。
- [x] 删除聊天公式 KaTeX 路径和配置。
- [x] 删除旧实现专属伪测试，保留依赖回归门禁。
- [x] 更新项目技术栈文档。

隔离清理：

- [x] 全局确认 Streamdown 文件没有生产调用。
- [x] 删除 Streamdown 渲染空壳，当前工作树尚未创建 Git 提交。
- [x] 全局确认 `ContentCoordinator` 没有生产调用。
- [x] 删除渲染协调空壳，当前工作树尚未创建 Git 提交。
- [x] `MessageProcessor` 将完整原始 Markdown 保存为单个 `MarkdownPart.Text`，不再二次解析。
- [x] 删除 `ContentParser`、`ContentParseCache` 和自定义 `TableUtils` 解析链。
- [x] 保留历史 `MarkdownPart.CodeBlock` 和 `InlineImage` 数据类型用于数据库反序列化兼容。
- [x] 历史空文本消息通过统一恢复函数重建代码围栏，不再丢弃 `CodeBlock`。

## 7. 逐提交计划

每个提交必须保持项目可编译，相关测试必须通过。

| 序号 | 提交标题 | 完成标准 |
|---:|---|---|
| 1 | `test: 固化 Markdown 与公式输入契约` | 特征测试通过，生产代码不变 |
| 2 | `build: 添加 MathJax SVG依赖和本地资产` | 资产检查通过，APK可构建 |
| 3 | `feat: 定义公式请求与渲染结果模型` | 模型和缓存键单测通过 |
| 4 | `feat: 添加 MathJax批量转换页面` | 本地页面契约检查通过 |
| 5 | `feat: 添加 SVG安全校验和有界缓存` | 安全与缓存单测通过 |
| 6 | `feat: 实现单 WebView MathJax转换器` | WebView转 SVG冒烟测试通过 |
| 7 | `feat: 注册数学渲染单例并延迟预热` | Koin启动与预热测试通过 |
| 8 | `feat: 添加行内与块级 SVG Compose渲染` | Compose公式模块可独立构建 |
| 9 | `refactor: 使用 PreparedMessage和稳定公式ID` | 原有 Markdown测试继续通过 |
| 10 | `feat: 原子切换全部公式到 MathJax SVG` | 行内和块级公式均走新路径 |
| 11 | `fix: 完成流式取消错误态和宽公式处理` | 过期结果、错误和宽公式测试通过 |
| 12 | `refactor: 复用唯一公式解析结果` | 同一消息不再重复解析公式边界 |
| 13 | `refactor: 收缩 StreamingRenderState` | 删除未消费字段后全量单测通过 |
| 14 | `refactor: 删除旧 KaTeX WebView和旧数学解析` | 全局搜索确认旧路径清零 |
| 15 | `test: 完成公式端到端回归和性能门槛` | 功能、安全和性能检查通过 |
| 16 | `docs: 更新渲染归属资产版本和安全说明` | README和开发文档与实现一致 |
| 17 | `refactor: 删除确认无调用的 Streamdown空壳` | 独立提交，可单独回滚 |
| 18 | `refactor: 删除确认无调用的渲染协调空壳` | 独立提交，可单独回滚 |

## 8. 测试矩阵

### 8.1 数学语法

- [x] 简单上下标。
- [x] 分式。
- [x] 根号。
- [x] 积分。
- [x] 极限。
- [x] 中文 `\text{}`。
- [x] `pmatrix`。
- [x] `bmatrix`。
- [x] `aligned`。
- [x] `cases`。
- [x] 多行公式。
- [x] 超宽公式解析和水平滚动路径。

### 8.2 Markdown组合

- [x] 表格内行内公式。
- [x] 列表内公式。
- [x] 引用内公式。
- [x] 粗体旁公式。
- [x] 链接旁公式。
- [x] 公式前后中文标点。
- [x] 无外层围栏的完整 Markdown 正文按 MikePenz、CodeBlockCard 和 MathJax 三路分流。
- [x] `markdown` 和 `md` 外层围栏在统一预处理入口移除，内部 Markdown 与公式正常渲染。
- [x] 围栏内部真实语言代码块继续进入 `CodeBlockCard`。
- [x] 多个 Markdown 围栏与围栏外说明文字保持原有顺序并共同渲染。
- [x] 真实代码块内部出现 `markdown` 字样时保持原文，不触发围栏移除。

### 8.3 误判防护

- [x] fenced code 中的 `$`。
- [x] inline code 中的 `$`。
- [x] 转义美元符号。
- [x] `$12` 货币。
- [x] `USD 12` 货币。
- [x] 比分 `1:0`。
- [x] 时间 `03:30`。
- [x] 版本号。
- [x] Markdown 链接地址。

### 8.4 流式状态

- [x] 未闭合行内公式。
- [x] 未闭合块级公式。
- [x] 公式闭合瞬间。
- [x] 一条消息包含多个公式。
- [x] 消息取消。
- [x] 内容回滚或替换时禁止复用旧前缀解析结果。
- [x] 旧转换结果晚到时由 `contentVersion` 丢弃。
- [x] 公式后继续追加普通文本不会重新转换已缓存公式。

### 8.5 生命周期

- [ ] 浅色和深色主题切换。
- [ ] 系统字体缩放变化。
- [ ] 容器宽度变化。
- [ ] WebView渲染进程退出。
- [ ] 历史消息快速滚动。
- [ ] 长会话恢复。

### 8.6 安全

- [x] 超长公式。
- [x] 宏展开压力。
- [x] 恶意 URL。
- [x] SVG 脚本。
- [x] SVG 事件属性。
- [x] SVG 外部资源。
- [x] `foreignObject`。
- [x] 队列溢出。
- [x] 单次转换超时。

## 9. 性能门槛

| 指标 | 门槛 |
|---|---|
| 聊天列表可见 WebView | 0 |
| 应用数学转换 WebView | 最多 1 |
| 缓存命中 | 不执行 JavaScript |
| 缓存命中显示开销 | 目标低于一帧 |
| 预热后简单公式转换 p95 | 不超过 150ms |
| 复杂矩阵转换 p95 | 不超过 400ms |
| 冷启动首次公式就绪 | 不超过 1.5s |
| 长列表帧性能 | 相对基线退化不超过 10% |
| 200个不同公式后的内存 | 不持续超过缓存上限 |
| 磁盘公式缓存 | 初始上限 64MB |

APK体积要求：

- 记录迁移前后的 Debug和Release APK体积。
- MathJax只打包实际使用的 TeX和SVG资源。
- 禁止同时保留 KaTeX字体和 MathJax资源。
- 体积变化必须写入最终验证报告。

## 10. 安全配置

WebView必须满足：

- `allowFileAccess = false`。
- `allowContentAccess = false`。
- 禁止外网请求。
- 禁止混合内容。
- 禁止任意页面导航。
- 不使用通用 `addJavascriptInterface`。
- 只允许 `appassets.androidplatform.net` 本地可信来源。
- 不启用无必要的 DOM存储。

数学输入限制：

- 单条公式最大长度默认保持 4096字符。
- TeX包使用白名单。
- 宏展开数量受限。
- 转换时间受限。
- 队列长度受限。
- 不允许公式加载外部脚本、字体、图片或样式。

## 11. 失败状态

| 类型 | 用户看到的结果 | 系统动作 |
|---|---|---|
| 未闭合公式 | 稳定占位或流式原文 | 等待后续 token，不提交转换 |
| TeX语法错误 | 明确的公式错误状态 | 缓存失败，保留复制原文能力 |
| 转换超时 | 明确的超时状态 | 取消请求，不切换引擎 |
| WebView进程退出 | 短暂等待状态 | 重建一次并重试当前批次 |
| 第二次引擎失败 | 明确的引擎错误状态 | 当前请求结束，不无限重试 |

最终生产版本禁止：

- 将失败公式交给 KaTeX或 jLaTeXMath。
- 将原始 LaTeX显示成灰色代码块。
- 静默吞掉公式。
- 因单个公式失败破坏整条 Markdown消息。

## 12. 回滚策略

- 不修改数据库，因此不需要数据回滚。
- 历史消息原始 Markdown保持不变。
- MathJax资产、转换 Module、Compose显示和路由切换分开提交。
- 生产路由切换提交能够单独撤销。
- 旧 KaTeX实现只在新路径全部验证后删除。
- 删除旧文件前必须重新执行全局调用搜索。
- 不添加长期双引擎运行时开关。
- 每个提交完成后运行相关单测和 Kotlin编译。

## 13. 验证命令

所有 Gradle命令在 `app1/` 下执行。

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugKotlin
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
```

MathJax运行时还需要：

- Android WebView转 SVG仪器测试。
- MathJax资产完整性测试。
- SVG安全过滤测试。
- 流式版本令牌和取消测试。
- 公式缓存一致性测试。
- CI模拟器执行 Android测试。

Agent不连接、不控制、不安装、不截图用户手机。手机端最终视觉验收由用户本人完成。

## 14. 最终验收标准

功能：

- [ ] 用户提供的完整 Markdown和数学测试文本全部正确显示。
- [ ] 标题、列表、引用和表格没有视觉回归。
- [x] 普通代码块继续使用 `CodeBlockCard`。
- [ ] 行内公式没有灰底和明显基线漂移。
- [ ] 块级公式没有异常空白和错误高度。
- [x] 宽公式进入独立水平滚动容器。
- [x] 表格单元格内公式走统一内联公式路径。
- [x] 公式错误不会破坏后续 Markdown。
- [x] 货币、代码和普通美元符号不被误判。

架构：

- [x] 全项目只有一个 Markdown 生产入口。
- [x] 全项目只有一个数学公式识别实现。
- [x] 全项目只有一个聊天数学引擎。
- [x] 公式列表项中没有 `AndroidView`。
- [x] 应用进程内最多只有一个数学转换 WebView。
- [x] 最终依赖中没有 Markwon 和 jLaTeXMath。
- [x] 聊天公式资产和生产路径中没有 KaTeX。Mermaid 独立上游资产仍保留其内嵌模块。
- [x] 页面上下阴影代码保持原样。

验证：

- [x] 相关单元测试通过。
- [x] 全量 Debug 单元测试通过。
- [x] Kotlin 编译通过。
- [x] Debug APK 构建通过。
- [x] Lint 通过。
- [x] MathJax 本地页面真实浏览器冒烟测试通过。
- [ ] MathJax WebView冒烟测试通过。
- [ ] 性能指标达到门槛或有明确测量记录。
- [ ] 用户完成手机端视觉验收。

## 15. 实施进度

| 阶段 | 状态 | 说明 |
|---|---|---|
| 阶段 0：固定行为基线 | 已完成 | 自动化基线完成，手机视觉验收待用户执行 |
| 阶段 1：加入 MathJax 本地资产 | 已完成 | MathJax 4.1.3、NewCM 字体、许可证和哈希已锁定 |
| 阶段 2：实现 MathJaxSvgRenderer | 已完成 | 单例 WebView、批处理、超时、进程退出重试和版本握手已完成 |
| 阶段 3：建立 SVG 安全和缓存 | 已完成 | 16 MiB 内存 LRU、64 MiB 磁盘缓存、失败缓存和双层 SVG 校验已完成 |
| 阶段 4：接入 Compose 和 MikePenz | 已完成 | 行内基线、块级真实尺寸、宽公式滚动和 Coil SVG 已接入 |
| 阶段 5：原子切换生产路径 | 已完成 | 聊天数学生产路径只剩 MathJax SVG，无数学回退 |
| 阶段 6：统一解析和流式状态 | 已完成 | 唯一解析入口、内容替换保护、`PreparedMessage` 和未消费状态字段清理已完成 |
| 阶段 7：删除旧实现和更新文档 | 已完成 | 旧数学实现、Streamdown、协调空壳、`ContentParser`、解析缓存和自定义表格解析已清理并完成最终复核 |

### 15.1 本次实施记录

实施日期：2026-07-19。

- Markdown：MikePenz `0.43.0` 唯一生产入口，标准元素继续使用库原生组件；正文、H1 至 H6、链接和块间距通过官方主题接口集中配置。
- Markdown 视觉：正文 `16sp/24sp`；H1 至 H6 为 `24/22/20/18/17/16sp`；MikePenz `block=3dp`，典型双换行块间距约 `9dp`；列表上下 `4dp`、列表项间距 `6dp`、缩进 `22dp`；链接使用主题主色、中等字重和下划线。
- Markdown 图片：继续使用 MikePenz 图片组件与 Coil3 加载器；加载中且尺寸未知时使用 `48dp × 48dp` 占位并显示主题化旋转进度环，加载失败时显示最大 `160dp × 32dp` 的“图片加载失败”提示；仅包含图片的段落使用 `1sp` 临时行高，加载成功后继续使用 MikePenz 原生宽高缩放；普通段落和正文内行内图片仍使用正文行高。
- 用户气泡：Markdown 根容器按内容宽度收缩，保留原有最大宽度上限，长文本继续在上限内换行。
- Markdown 表格：背景保持透明；表头、数据行和单元格继续复用 MikePenz 组件；仅滚动容器由 EveryTalk 接管，存在横向溢出时按滚动位置显示边缘遮罩，浅色主题使用纯白到透明渐变，深色主题使用纯黑到透明渐变。
- 脚注：每次引用生成唯一来源地址，定义提供返回入口并回到最近一次点击来源；正文与 `details` 共享编号和跳转状态；已有 Markdown 链接内不生成嵌套脚注链接；脚注不再追加 `---`；fenced code、缩进代码、行内代码、HTML 注释、`pre/code/style/textarea/title` 内的定义和扩展语法保持原文，`script` 继续按安全策略删除。
- 普通代码块：继续使用 EveryTalk `CodeBlockCard`。
- Markdown 传输围栏：`markdown` 和 `md` 围栏统一移除；内部标题、列表、表格交给 MikePenz，公式交给 MathJax，真实语言代码块仍交给 `CodeBlockCard`。
- 数学：MathJax `4.1.3` 单例 WebView 批量转 SVG，Compose + Coil 绘制。
- 消息持久化：完整原始 Markdown 保存为单个 `MarkdownPart.Text`，不再执行第二次 Markdown 结构解析。
- 历史兼容：旧 `MarkdownPart.CodeBlock` 可恢复为安全长度的 fenced code，InlineImage 不注入 Base64 文本。
- 流式替换：只有新内容以前一版本为完整前缀时才复用增量解析缓存，否则执行全量解析。
- 缓存：16 MiB 内存 LRU、64 MiB 磁盘缓存、32 px 宽度桶、60 秒语法错误缓存。
- 安全：公式长度 4096 字符、宏替换 1000 次、SVG 1 MiB、8192 节点；超长公式在 Kotlin 入口和 WebView 页面双层拦截，SVG 在 WebView 和 Kotlin 双层校验。
- Debug APK：`C:\Users\33039\.everytalk-gradle-build\app1\app\outputs\apk\debug\app-debug.apk`。
- Debug APK 大小：51,262,930 字节，48.89 MiB。
- Debug APK SHA-256：`9BB5D6E6131FF28D377F489B7F884FD85BCA238844E8AA034E96006795E13791`。
- 上一次全量 Debug 单元测试：550 项，0 失败、0 错误、0 跳过；Gradle 总耗时 1 分 12 秒，测试用例累计耗时 17.161 秒。
- 本轮定向单元测试：Markdown 图片、宽度策略、表格边缘状态和渲染职责测试 12 秒通过；用户气泡相关测试 3 秒通过。
- Kotlin Debug 编译：随本轮定向单元测试成功。
- Debug APK 构建：本轮增量构建 4 秒，成功。
- Lint：本轮按小范围 UI 修改的分级验证策略未重复执行；上一次全工程 Lint 8 分 7 秒通过。
- MathJax 自动化边界：消息取消释放队列并传播取消状态，超长公式在 WebView 前拒绝，队列溢出立即失败，单批转换超时明确分类。
- Playwright 离线页面验证：`pmatrix`、`aligned`、`cases`、`\mathbb`、`\mathfrak`、`\mathcal` 均返回有效 SVG；递归宏被 `maxMacros` 拒绝为语法错误。
- 旧生产符号扫描：`ContentParser`、`ContentPart`、`ContentParseCache`、`TableUtils`、`MathParser`、`MathStreamingPolicy`、`MathDelimiterNormalizer`、`ContentCoordinator`、Streamdown、FallbackRaw、Markwon、jLaTeXMath 和聊天 KaTeX 路径均为 0。
- 依赖解析：保留 MikePenz `0.43.0`、AndroidX WebKit `1.16.0`、Coil SVG `3.5.0`；无 Markwon、jLaTeXMath 和 KaTeX 依赖。
- 页面上下阴影和 `ScrollFadeEdge.kt`：Git 差异为 0。
- Git：未提交，未推送。

仍需用户执行：

- 手机端完整 Markdown 和公式视觉验收。
- Android System WebView 真实运行时验收。
- 浅色和深色主题、字体缩放、宽度变化、长列表滚动和长会话恢复验收。
- WebView 渲染进程退出恢复和真机性能门槛测量。

## 16. 参考资料

- [MathJax 字符串转换接口](https://docs.mathjax.org/en/latest/web/convert.html)
- [MathJax SVG输出配置](https://docs.mathjax.org/en/latest/options/output/svg.html)
- [MathJax组合组件](https://docs.mathjax.org/en/latest/web/components/combined.html)
- [MathJax本地部署](https://docs.mathjax.org/en/latest/web/hosting.html)
- [MathJax 4换行能力](https://docs.mathjax.org/en/latest/output/linebreaks.html)
- [Android本地 Web内容](https://developer.android.com/develop/ui/views/layout/webapps/load-local-content)
- [MikePenz multiplatform-markdown-renderer](https://github.com/mikepenz/multiplatform-markdown-renderer)

## 17. 变更记录

| 日期 | 内容 |
|---|---|
| 2026-07-19 | 创建计划，确定 MikePenz + CodeBlockCard + MathJax 4 SVG 架构 |
| 2026-07-19 | 完成生产切换、旧链路清理、内存与磁盘缓存、SVG 安全限制、自动化验证和实施记录 |
| 2026-07-19 | 删除第二套 Markdown 分块与自定义表格解析，修复内容替换增量复用 |
| 2026-07-19 | 补齐消息取消、超长公式、队列溢出和单批超时自动化边界，完成阶段 7 最终复核 |
| 2026-07-19 | 修正错误的 Markdown 源码围栏契约，统一渲染 `markdown` / `md` 围栏内容并保护内部真实代码块 |
| 2026-07-19 | 统一 MikePenz 移动端视觉主题，完成脚注上标、双向跳转和多余分隔线清理 |
| 2026-07-19 | 补齐 HTML 注释及 raw-text 元素保护边界，阻止隐藏脚注定义泄漏或扩展语法误改写 |
| 2026-07-19 | 收敛 Markdown 失败图片的未知尺寸占位，消除链接后方 `200dp` 大块空白 |
| 2026-07-19 | 压缩仅图片段落的临时行高，清除失败图片留下的一整行正文高度 |
| 2026-07-19 | 为 Markdown 图片加载失败增加紧凑的前端可见提示，避免静默留白 |
| 2026-07-19 | 为 Markdown 图片加载过程增加适配明暗主题的旋转进度环 |
| 2026-07-19 | 修复用户 Markdown 根容器强制铺满最大宽度，恢复气泡内容自适应 |
| 2026-07-19 | 将 Markdown 表格背景改为透明，并增加跟随横向滚动位置变化的双侧渐变阴影 |
| 2026-07-19 | 将表格边缘灰色阴影修正为浅色纯白、深色纯黑的同色透明渐变遮罩 |
