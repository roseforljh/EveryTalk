# EveryTalk 客户端同步规则

对应本地合同 `sync-api.yaml` 的 0.2.0 及账号自动恢复扩展。本文不表示线上已经部署。

1. 游客只读写本机，不连接 EveryTalk 自有服务器；登录后自动同步，同账号重新登录也包括游客期间的新数据。不增加同步开关、同步密码或删除云端按钮。
2. 所有同步和文件请求带 `X-EveryTalk-Sync-Version: 0.2.0`；写密文的 push 带当前 `X-EveryTalk-Secrets-Epoch`。服务器地址来自 local.properties 的 `EVERYTALK_SYNC_BASE_URL`，正式构建由仓库变量注入；没有地址时关闭同步。
3. 先 pull 取得 epoch 和 secrets_epoch。账号鉴权后调用 `/sync/account-key`，响应 DEK 只经 HTTPS 传输，本机用 Keystore 包裹。服务端托管恢复，具有解密能力，不是用户独占密钥。原本的密码模式仅用于兼容迁移，不在普通登录 UI 要求输入。
4. `/sync/history-key` 是独立稳定历史密钥；配置机密重置不能破坏聊天归档。密钥只在工作期间驻留，结束清零。主密钥和 R2 凭据绝不能放入客户端。
5. secrets_epoch 变化清掉旧 DEK、加密字段缓存和待传密文，从游标 0 重新协调配置；再自动取得新钥，从保留的本机凭据重新加密。遇到 SECRETS_EPOCH_MISMATCH 先 pull，不忙循环。
6. 字段信封必须完整输出 $enc、alg、nonce、ct、keyVersion，包括序列化默认字段。按 x-secret-policy 使用标准带填充 base64、12 字节随机 nonce、16 字节 GCM tag。AAD 逐字节使用合同 JSON 数组和 RFC 6901 JSON Pointer；不能绑定 canonical_id，不能直接复制设备 Keystore 文件。
7. 模型/语音/搜索 Key、MCP headers/OAuth/邮箱授权、SSH 密码/私钥/原始凭据/sudo、Cloudflare 授权和 Skill 变量值使用信封。普通名称扫描保留：maxTokens→outputLimit，maxContextTokens→contextLimit。带密码或敏感查询参数的地址不以明文同步。
8. 配置按 kind/key 一条一条存，模型、会话引用稳定 UUID。memory_switches/default 一条；voice_custom_models 按 stt/chat/tts 与平台一条；group_members 按分组名和会话 UUID 一条；pinned_items 每会话一条。kind 登记和限制以合同为准，不再使用旧 conversation_groups 整列表。
9. sessions、memories、user_settings、attachments 带 clientModifiedAt。最后修改赢，服务器裁剪未来时钟；平局后到者赢。上传确认同时检查本地版本/快照，上传中产生的新修改保持待传。
10. canonical_id 更新本地同步身份。pull 用服务器递增序号保存 next_cursor，记录与游标同事务，不产生回传；关联未到时等待后生效。模型、标签、系统提示词和服务器绑定由 conversation_settings 恢复。
11. messages 同 UUID 已有字段不可修改，只能补不存在字段，数组整体不可变。内容编辑、重答和复原生成新 UUID；流式只进本地草稿，停止/崩溃恢复后定稿才同步。
12. 64 KiB 是普通消息 JSON 上限，不是丢弃聊天的理由。超长及结构化完整历史 gzip 后 AES-GCM 加密，按 quota 的实际上限分块，以原消息 UUID 和 attachments 元数据关联；正文不截断。恢复工具记录强制终态，不重新执行旧命令。
13. 图片、音频、附件和完整工具输出与普通消息分开传输。每块和最终文件验证大小/哈希，认证成功才替换本机文件；缺文件或损坏时保留本机原件并显示错误。首次尚未上传的附件显示明确占位，点击坏文件不能崩溃。
14. Skill 同步实际版本文件和清单，包含空文件；fileRefs 列出 manifest 和全部文件块。校验路径、版本哈希后落库，不因恢复而执行 Skill 脚本。Skill 变量只传名称和加密值，删除要传墓碑。
15. 取消 MCP 授权、清空 Key、删除变量也属于同步修改，不能让旧设备把它们复活。远端恢复不产生新的本地修改时间；坏密文逐项提示，不清本机 Key，不阻塞其他项目。
16. 账号绑定变化先走三选一；用户明确选择上传后，清掉旧账号的 fileId、密文、canonical 快照，再从本机原件重新封装。不得把旧账号云端引用发给新账号。
17. 删除会话只发会话墓碑，接收后清本地子消息。session_deleted/message_deleted 停止上传对应内容；附件墓碑不可复活，新版本资源使用新引用 ID。分组/置顶墓碑允许更晚修改重新加入。
18. epoch 整体变化先暂停，非阻塞询问是否重传；不同意保留本机并暂停，同意才重新排队。401 先刷新，只有刷新明确被拒绝才 SessionExpired；断网/超时/5xx 不退出。429 等 Retry-After。
19. 唯一 WorkManager 任务需要联网，变更防抖，运行共享互斥锁；正常分批接续不当成失败指数退避。进程退出后从已落盘游标/队列继续；文件按内容去重。每个 rejected 显示安全类别和错误码，不显示原文，不无限重试同一坏版本。
20. 不复制生图会话、同步游标、草稿、待发送/执行队列、阅读位置、端口转发、连接状态、isValid、主题和语言。远程服务器在本机重新连通验证，不能复制“在线”状态。第三方撤销或到期的凭据仍可能需要重新授权。

测试使用真实 Room 的两设备数据库、真实 PostgreSQL 与模拟对象存储，以及合同加密向量；不使用生产密钥或清空真实账号。部署需先备份和迁移，再配置稳定 SYNC_MASTER_KEY_BASE64 与独立私有文件桶；无需删除用户云端数据。
