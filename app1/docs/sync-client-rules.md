# EveryTalk 客户端同步规则

客户端必须遵守以下规则：

1. `sessions`、`memories`、`user_settings` 的 payload 必须有非负毫秒时间戳 `clientModifiedAt`。未来时间按服务器当前时间计算；较大值生效；相等时后到服务器的变更生效。`updated_at` 永远使用服务器时间。
2. `user_settings` 的身份是 `(user_id, kind, key)`，`kind` 只能是 `conversation_groups` 或 `pinned_items`。同一分组改名必须删除旧 key，再创建新 key。
3. 如果 push 返回 `canonical_id`，客户端必须把它作为该设置的 id；pull 永远以服务器返回的 id 为准。
4. push 是逐条处理的。`accepted` 已写入，`ignored` 或 `unchanged` 不要重复生成本地变更，`rejected` 要依据 `error.code` 处理；被拒绝条目不分配 `server_seq`，不会阻止同批其他条目。
5. 收到 `session_deleted` 时丢弃这条 message 的本地待同步内容。服务器不会保存它；被删除会话下已有 message 的 pull 结果是 `deleted=true` 且 `payload={}`。
6. pull 使用 `since` 序号游标，按 `server_seq` 升序处理，保存 `next_cursor`。`epoch` 变化表示云端被 `DELETE /sync/all` 清空，客户端必须丢弃本地云端快照并重新协调。
7. 收到 401 时刷新 Supabase token 或要求重新登录后再试。收到 429 时读取 `Retry-After`，等待后重试；不要立即忙循环。Redis 不可用时服务器会放行限流请求，但会记录告警。
8. 不要上传 API Key、语音 Key、MCP headers、图片、附件、搜索结果、大段工具输出或 pending messages。服务器会递归丢弃敏感字段；不要把它们当作同步数据依赖。

服务器不接受客户端的 `user_id`、创建/更新时间或 `server_seq`；这些字段由服务器生成。`messages` 只追加，编辑内容不会覆盖服务器记录；删除只产生墓碑。

