# 本次优化审查

## 结论

发现 2 个潜在问题，均集中在日志脱敏链路。历史保存、重答插入、配置迁移相关核心路径未发现新的明显逻辑问题。

## 问题 1：配置字段仍有原文日志残留

- 严重级别：P1
- 位置：
  - `app/src/main/java/com/android/everytalk/statecontroller/MessageSender.kt:760`
  - `app/src/main/java/com/android/everytalk/statecontroller/MessageSender.kt:768`
  - `app/src/main/java/com/android/everytalk/statecontroller/MessageSender.kt:769`
  - `app/src/main/java/com/android/everytalk/statecontroller/MessageSender.kt:770`
  - `app/src/main/java/com/android/everytalk/statecontroller/MessageSender.kt:1191`
  - `app/src/main/java/com/android/everytalk/ui/screens/viewmodel/ConfigManager.kt:227`
  - `app/src/main/java/com/android/everytalk/data/network/ApiClient.kt:831`
- 现象：虽然新增了 `safeApiConfigSummary()`，但仍有 `model/provider/channel` 原文日志。
- 风险案例：用户把模型名或 provider 填成包含 key、私有域名、内部模型名的字符串，这些内容仍会进入日志。
- 修复方向：这些日志统一改成 `safeApiConfigSummary()` 或只输出长度、是否为空、枚举类型，不输出用户可编辑字段原文。

## 问题 2：脱敏摘要仍输出确定性 hash

- 严重级别：P2
- 位置：
  - `app/src/main/java/com/android/everytalk/statecontroller/MessageSender.kt:396`
  - `app/src/test/java/com/android/everytalk/statecontroller/MessageSenderRegenerationInsertTest.kt:52`
- 现象：`providerHash/modelHash/channelHash` 使用 JVM `String.hashCode()`，仍是用户可编辑字段的确定性指纹。
- 风险案例：如果字段里混入密钥或私有模型名，日志中的 hash 可被离线猜测验证，尤其常见模型名、域名、短 token 片段很容易枚举。
- 修复方向：严格脱敏时不要输出用户可编辑字段 hash，改为 `providerChars/modelChars/channelChars` 或固定占位。

## 已核验

- `git diff --check` 通过，仅有 CRLF 提示。
- 搜索确认无 `HMDBG` 临时诊断残留。
- 复核 `HistoryManager.kt` 新增的重复会话替换、`migrationSourceId` 迁移、异步保存测试等待条件，未发现当前同级回归。
