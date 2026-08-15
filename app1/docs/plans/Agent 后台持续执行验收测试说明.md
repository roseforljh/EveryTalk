# Agent 后台持续执行验收测试说明

## 目标

本测试组对应《Agent 后台持续执行与通知恢复实施计划》。只有本测试组全部通过，才能把计划状态改成“已完成”。

测试只验证计划已经确认的产品行为，不要求固定内部类名。字段允许使用现有代码里的等价名称，例如 `runId` 或 `agentRunId`、`stdoutCursor` 或 `stdoutOffset`。

## 禁止事项

1. 禁止删除、跳过、忽略或放宽验收测试。
2. 禁止通过注释、字符串、空函数或无调用方代码骗过源码契约；测试会先剔除注释和字符串。
3. 禁止为了通过测试恢复固定 1 秒或 2 秒 ViewModel 轮询。
4. 禁止把真机和真实 VPS 验收伪装成 JVM 自动测试。
5. 原有测试必须继续通过。

## 自动测试入口

在 `app1` 目录执行：

```powershell
./gradlew :app:testDebugUnitTest --tests "com.android.everytalk.acceptance.AgentBackgroundPlan*" --no-daemon
```

测试组覆盖：

1. 前台服务拥有长期任务监听，系统重建后从 Room 恢复。
2. 监听不依赖页面 ViewModel，不设置十分钟或六十分钟硬上限。
3. VPS Runtime 提供结构化增量事件、日志游标、终态和安全取消。
4. Execution 保存 Run 关联、游标、取消状态、事件时间和结果接回标记。
5. 远端结果接回原 AgentRun，模型失败后进入待续写并能再次恢复。
6. 同一结果只接回一次，同一会话多任务按完成时间续写。
7. 输入框停止只取消当前 Run 的全部前台和后台任务。
8. 系统回收、息屏和离开页面不会取消 VPS 任务。
9. 删除会话先确认、取消、等待终态，再删除 Workspace 和聊天。
10. 删除服务器配置默认不取消任务、不删除 VPS 文件。
11. Agent 开启、发送和恢复共用统一通知权限门槛。
12. 通知没有操作按钮，不泄露命令和输出，能进入对应会话。
13. 整个 AgentLoop 由前台服务或应用级后台运行时持有，不能挂在 `viewModelScope`。
14. `Software caused connection abort` 等临时网络错误必须结构化标记为可重试，原 Run 保存为 `MODEL_CONTINUATION_PENDING`，禁止永久标记失败。

## 完成后的验证顺序

1. 运行本计划测试组。
2. 运行现有 Computer、Agent、数据库和 Service 定向测试。
3. 运行 `:app:testDebugUnitTest` 全量 JVM 测试。
4. 用户自行完成真机和真实 VPS 验收。

自动测试全部通过只代表代码层完成。真机和真实 VPS 项仍必须由用户实际操作后再勾选。
