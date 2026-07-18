# Android Streamdown Parity Docs

这个目录专门存放 EveryTalk Android 对齐 assistant-ui Streamdown 的计划文档。

## 阅读顺序

1. `00-master-plan.md`：目标、范围、成功标准。
2. `01-architecture.md`：架构、数据流、入口、安全边界。
3. `02-implementation.md`：可执行任务、文件、测试命令。
4. `03-verification.md`：验证矩阵、性能、安全、发布门槛。
5. `04-review-audit.md`：计划审查结论、已修复缺口、剩余风险。

## 维护规则

- 任何新增能力必须同时更新实施计划和验证计划。
- 任何新增入口必须加入 `StreamdownEntryPoint` 覆盖。
- 任何新增 WebView 或本地 JS 资产必须写明来源、许可证和 SHA-256。
- 总验证命令统一使用 `--tests "*Streamdown*"`。
- 官方 Streamdown 新增 prop 或插件时，必须先更新配置映射表，再更新实施和验证文档。
