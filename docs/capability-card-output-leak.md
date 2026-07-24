# 能力选择卡泄露到正文的修复记录

## 现象

模型回答开头偶尔出现能力选择协议内容，例如：

```text
局方能力选择：

• general-answer

---

结论：……
```

其中“能力选择”和能力 ID 属于应用内部协议，用户只应看到后面的正常回答。

## 根因

能力选择工具 `everytalk_select_capabilities` 的返回值包含 `selected` 和 `instructions`。工具结果会被放回模型上下文，模型随后继续生成最终回答。系统提示词要求模型不要提及能力卡，但正文流入口没有应用侧的硬性隔离。

`MessageProcessor` 原先会把 `Text` 和 `Content` 事件的文本直接累积，`ApiHandlerStreamProcessor` 再把同一段文本追加到消息正文。模型偶尔复述内部工具结果时，能力卡就会进入可见正文并被保存。

问题与页面滚动、正文长度和置顶逻辑无关，发生点在模型输出进入消息正文的边界。

## 修复方案

### 1. 在公共消息处理层过滤

新增 `CapabilityCardOutputSanitizer`，识别以下结构：

1. 能力选择标题，例如“能力选择”“局方能力选择”“Capability selection”。
2. 标题后仅包含能力目录中的 ID，例如 `general-answer`。
3. 可选的 Markdown 分隔线。

过滤范围限定为完整的标题加能力 ID 结构。普通正文中单独提到 `general-answer` 时不会被删除。
过滤器只在当前消息收到 `everytalk_select_capabilities` 工具调用后启用，未使用能力选择工具的普通回答保持原始处理路径。

### 2. 支持跨流式分块

模型输出可能把标题、能力 ID 和正文拆到多个网络块。`StreamingDetector` 会暂存可能属于能力卡的前缀，确认结构后直接丢弃卡片并释放后续正文。普通回答不符合卡片结构时会立即释放，避免增加正常回答延迟。

### 3. 终态再次清理

消息最终化时仅对已启用能力卡过滤的消息再次执行完整文本清理，覆盖流式分块边界和旧处理路径，避免卡片重新进入最终消息和历史记录。

## 修改位置

- `app1/app/src/main/java/com/android/everytalk/util/text/CapabilityCardOutputSanitizer.kt`
- `app1/app/src/main/java/com/android/everytalk/util/messageprocessor/MessageProcessor.kt`
- `app1/app/src/main/java/com/android/everytalk/statecontroller/api/ApiHandlerStreamProcessor.kt`
- `app1/app/src/test/java/com/android/everytalk/util/text/CapabilityCardOutputSanitizerTest.kt`
- `app1/app/src/test/java/com/android/everytalk/util/messageprocessor/MessageProcessorFinalizePartsTest.kt`

## 验证结果

- 能力卡完整输出会被移除，卡片后的正常回答保留。
- 标题和能力 ID 跨多个流式块时仍会被移除。
- 普通正文提到 `general-answer` 时保持原文。
- 相关单测通过。
- 未连接、安装或操作手机。
