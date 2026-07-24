# 重答场景用户气泡无法自动置顶的根因与修复

## 1. 问题概述

在同一个聊天中连续发送两次用户问题后，长按第一条用户气泡执行重答，用户气泡可以自动置顶。随后对当前列表中的第一条用户气泡执行重答时，自动置顶可能失效。

本次问题的触发条件是前序 AI 内容在置顶流程运行期间发生异步扩高。内容变长会触发布局变化，但直接故障点是目标用户气泡被推出 `LazyList` 的可见项集合后，置顶引擎没有重新捕获锚点。

## 2. 日志证据

关键日志来自 2026-07-24 21:38:17：

| 时间 | 布局状态 | 说明 |
| --- | --- | --- |
| 21:38:17.595 | `total=8`，末项为 index 7 | 重答消息刚加入列表，列表仍处于初始布局 |
| 21:38:17.619 | `total=9`，末项为 index 8 | 新的 AI 目标项加入，目标用户项仍在可见区 |
| 21:38:17.658 | `total=9`，末项退回 index 5，index 5 高度为 `9206px` | 前序 AI 内容异步扩高，目标用户项被推出可见区 |

目标用户气泡位于 index 7。扩高前它可以在 `visibleItemsInfo` 中被找到，扩高后只剩 index 5 及其之后的可见范围，目标 key 暂时不再出现在 `visibleItemsInfo` 中。

## 3. 原有代码的失效链路

置顶引擎的纠偏函数原先按稳定 key 查找当前锚点：

```kotlin
val anchor = snapshot.visibleItems.firstOrNull { it.key == anchorKey }
    ?: return false
```

当 index 5 的 AI 气泡扩高后，目标用户气泡被移出可见区，查找结果为空，函数直接返回。此时发生了以下连锁结果：

1. 引擎没有调用 `scrollToItem`，因此无法把目标 index 拉回可见区。
2. 纠偏循环只能继续读取当前可见布局，始终无法找到目标 key。
3. 布局稳定后，循环会等待新的 `layoutVersion`，但目标锚点已经丢失，后续没有恢复入口。
4. 底部 reserve 仍然存在，但 reserve 只能提供滚动空间，无法替代丢失的锚点定位。

因此，页面内容很长是触发条件，锚点丢失后的恢复路径缺失是代码根因。底部滚动边界没有阻止置顶动作，置顶动作在锚点查找阶段就提前结束了。

## 4. 修复内容

### 4.1 锚点不可见时按索引重新捕获

文件：`app1/app/src/main/java/com/android/everytalk/ui/topanchor/TopAnchorReserveEngine.kt`

`anchorIndex` 被传入初始纠偏和持续纠偏循环。当稳定 key 不在当前可见项中时，执行以下流程：

1. 校验 `anchorIndex` 仍在列表范围内。
2. 调用 `listState.scrollToItem(anchorIndex, scrollOffset = 0)`，将目标项重新带入可见区。
3. 重新读取 `layoutInfo`。
4. 使用稳定 `anchorKey` 校验重新捕获到的项，防止索引已经失效时误纠偏其他消息。
5. 继续执行原有的锚点几何计算和 reserve 计算。

重新捕获成功但当前漂移小于等于 1 像素时，函数仍返回“本帧已处理”，避免后续 reserve 收缩逻辑使用重新捕获前的旧布局做错误判断。

用户进入 `UserControlled` 阶段后，持续纠偏循环仍然跳过自动捕获，保留用户手动滚动的控制权。

### 4.2 让 key 重排触发布局版本变化

文件：`app1/app/src/main/java/com/android/everytalk/ui/topanchor/TopAnchorLazyListBridge.kt`

原有 `layoutVersion` 只累计可见项的尺寸和偏移总和。连续重答在几何尺寸不变时交换消息 key，可能得到相同的总和，纠偏循环无法感知消息身份和顺序已经变化。

现在按可见项顺序纳入以下字段：

```text
key.hashCode()
index
offset
size
```

这样，消息 key 重排、索引变化或单项尺寸变化都会生成新的布局版本，等待中的纠偏流程可以被正确唤醒。

## 5. 回归测试

新增测试位于：

`app1/app/src/test/java/com/android/everytalk/ui/topanchor/TopAnchorReserveEngineComposeAdditionalTest.kt`

### 5.1 前序内容异步扩高后重新捕获锚点

测试名：`anchor is reacquired when preceding content expands during initial snap`

测试构造一个前序 AI 项从 `24dp` 扩高到 `900dp` 的场景，使目标用户项离开可见区。测试最终断言：

1. 目标 key 能重新出现在可见项中。
2. 目标用户项回到设定的顶部阅读位置。

### 5.2 仅 key 重排时唤醒纠偏

测试名：`key only regeneration layout wakes waiting anchor correction`

测试保持消息几何尺寸不变，仅替换重答后的消息 key，验证布局版本会变化，等待中的置顶纠偏会继续执行。

### 5.3 布局版本单元测试

文件：`app1/app/src/test/java/com/android/everytalk/ui/topanchor/TopAnchorLayoutSnapshotTest.kt`

新增测试验证可见消息 key 顺序发生变化时，`layoutVersion` 必须变化。

## 6. 验证结果

本次修复完成了以下本地验证：

| 验证项 | 结果 |
| --- | --- |
| 异步扩高根因回归测试 | 通过，1 项测试，0 失败 |
| TopAnchor、重答、消息列表和滚动相关测试 | 通过，98 项测试，0 失败，0 错误 |
| `:app:assembleDebug` | 构建成功 |
| `git diff --check` | 通过 |
| 临时 `[DEBUG-...]` 日志检查 | 未发现 |

本次验证只使用日志分析、源码检查、JVM 单元测试和 Gradle 本地构建，未连接、安装或操作手机。

## 7. 变更记录

- 修复提交：`de8b5ae2 fix: TopAnchor 哈希纳入 item 顺序/锚点丢失后按索引重新捕获`
- 回归测试提交：`376b4d32 test: Flavour/分隔线/货币/对齐/TopAnchor 覆盖`
