package com.android.everytalk.util.locale

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.android.everytalk.R

private val EXACT_MESSAGE_RESOURCES = mapOf(
    "Cloudflare Computer 或 Workspace 已变化" to R.string.ui_message_cloudflare_context_changed,
    "读取凭据期间 Cloudflare 目标或授权已变化" to R.string.ui_message_cloudflare_credential_context_changed,
    "Worker URL 必须是 HTTPS" to R.string.ui_message_cloudflare_worker_https,
    "Worker URL 主机不受信任" to R.string.ui_message_cloudflare_worker_host,
    "分页参数无效" to R.string.ui_message_cloudflare_pagination_invalid,
    "Worker 入口不能为空" to R.string.ui_message_cloudflare_worker_entry_empty,
    "Cloudflare 未返回 Worker 版本 ID" to R.string.ui_message_cloudflare_version_id_missing,
    "部署响应未完整解析，需要查询确认" to R.string.ui_message_cloudflare_deploy_response_partial,
    "Cloudflare 未返回 deployment ID" to R.string.ui_message_cloudflare_deployment_id_missing,
    "版本 ID 不能为空" to R.string.ui_message_cloudflare_version_id_empty,
    "日志限制无效" to R.string.ui_message_cloudflare_log_limits_invalid,
    "Cloudflare 未返回 Tail 会话" to R.string.ui_message_cloudflare_tail_missing,
    "部署查询返回的 ID 不一致" to R.string.ui_message_cloudflare_deploy_id_mismatch,
    "Worker 健康记录目标无效" to R.string.ui_message_cloudflare_health_target_invalid,
    "Cloudflare API 地址无效" to R.string.ui_message_cloudflare_address_invalid,
    "Cloudflare 网络请求未得到完整结果" to R.string.ui_message_cloudflare_network_incomplete,
    "multipart 方法无效" to R.string.ui_message_cloudflare_multipart_method_invalid,
    "Cloudflare multipart 请求超时" to R.string.ui_message_cloudflare_multipart_timeout,
    "Cloudflare multipart 请求未得到完整结果" to R.string.ui_message_cloudflare_multipart_incomplete,
    "Tail 地址协议无效" to R.string.ui_message_cloudflare_tail_protocol_invalid,
    "Tail 地址无效" to R.string.ui_message_cloudflare_tail_url_invalid,
    "Tail 地址主机无效" to R.string.ui_message_cloudflare_tail_host_invalid,
    "Worker Tail 日志读取超时" to R.string.ui_message_cloudflare_tail_timeout,
    "Worker Tail 日志读取失败" to R.string.ui_message_cloudflare_tail_failed,
    "Cloudflare API 路径无效" to R.string.ui_message_cloudflare_api_path_invalid,
    "Cloudflare 操作未成功" to R.string.ui_message_cloudflare_operation_unsuccessful,
    "Cloudflare 响应缺少成功状态" to R.string.ui_message_cloudflare_success_missing,
    "Cloudflare OAuth 参数为空" to R.string.ui_message_cloudflare_oauth_empty,
    "Cloudflare OAuth 回调过长" to R.string.ui_message_cloudflare_oauth_callback_large,
    "Cloudflare OAuth 回调地址不匹配" to R.string.ui_message_cloudflare_oauth_redirect_mismatch,
    "Cloudflare OAuth 回调参数重复" to R.string.ui_message_cloudflare_oauth_params_duplicate,
    "Cloudflare Token 交换失败" to R.string.ui_message_cloudflare_token_exchange_failed,
    "Cloudflare Refresh Token 为空" to R.string.ui_message_cloudflare_refresh_token_empty,
    "资源列表格式无效" to R.string.ui_message_cloudflare_resource_list_invalid,
    "Worker binding 资源不属于当前 Cloudflare Account" to R.string.ui_message_cloudflare_binding_account_mismatch,
    "资源列表已变化，请刷新" to R.string.ui_message_cloudflare_resource_list_changed,
    "原 Computer 已不存在" to R.string.ui_message_cloudflare_original_computer_missing,
    "原授权已不存在" to R.string.ui_message_cloudflare_original_auth_missing,
    "目标或授权已变化，请重新发起资源选择" to R.string.ui_message_cloudflare_resource_selection_changed,
    "资源操作缺少幂等键" to R.string.ui_message_cloudflare_idempotency_missing,
    "该工具调用已经绑定另一份资源请求" to R.string.ui_message_cloudflare_resource_already_bound,
    "Cloudflare 已明确拒绝该操作" to R.string.ui_message_cloudflare_operation_rejected,
    "结果未知，需要查询确认" to R.string.ui_message_cloudflare_result_unknown,
    "OAuth 目标不能为空" to R.string.ui_message_cloudflare_oauth_target_empty,
    "OAuth TTL 无效" to R.string.ui_message_cloudflare_oauth_ttl_invalid,
    "临时 Worker 部署包无效" to R.string.ui_message_temporary_worker_package_invalid,
    "Temporary Worker 已在返回前过期" to R.string.ui_message_temporary_worker_returned_expired,
    "Temporary Worker 网关必须使用无凭据 HTTPS 地址" to R.string.ui_message_temporary_worker_gateway_https,
    "Temporary Worker 网关地址无效" to R.string.ui_message_temporary_worker_gateway_url_invalid,
    "Temporary Worker 返回地址必须是 HTTPS" to R.string.ui_message_temporary_worker_return_https,
    "Temporary Worker ID 无效" to R.string.ui_message_temporary_worker_id_invalid,
    "Worker Workspace 不是目录" to R.string.ui_message_worker_workspace_invalid,
    "Worker Workspace 不允许符号链接根目录" to R.string.ui_message_worker_workspace_symlink_root,
    "Worker Workspace 不允许符号链接目录" to R.string.ui_message_worker_workspace_symlink_dir,
    "Worker 目录深度超过限制" to R.string.ui_message_worker_directory_depth,
    "Worker 文件数量超过限制" to R.string.ui_message_worker_file_count,
    "Worker 文件路径越界" to R.string.ui_message_worker_path_outside,
    "Worker 文件不允许符号链接" to R.string.ui_message_worker_symlink_file,
    "Worker 部署包过大" to R.string.ui_message_worker_package_large,
    "Worker 入口必须是有效 UTF-8" to R.string.ui_message_worker_entry_utf8,
    "Worker 入口必须使用模块格式" to R.string.ui_message_worker_entry_module,
    "Worker 配置不能使用空入口" to R.string.ui_message_worker_config_entry_empty,
    "Worker TypeScript 入口需要先构建为 JavaScript" to R.string.ui_message_worker_typescript_build,
    "Worker 入口缺失或不唯一，请在 .everytalk/worker.json 的 entry 中明确指定" to R.string.ui_message_worker_entry_missing,
    "Worker 入口必须是 JavaScript 模块" to R.string.ui_message_worker_entry_javascript,
    "Worker 配置目录无效" to R.string.ui_message_worker_config_dir_invalid,
    "Worker 配置目录不允许符号链接" to R.string.ui_message_worker_config_dir_symlink,
    "Worker 配置路径越界" to R.string.ui_message_worker_config_path_outside,
    "Worker 配置文件无效" to R.string.ui_message_worker_config_file_invalid,
    "Worker 配置文件过大" to R.string.ui_message_worker_config_file_large,
    "Worker 配置必须是 UTF-8" to R.string.ui_message_worker_config_utf8,
    "Worker 配置格式无效" to R.string.ui_message_worker_config_format,
    "Worker binding 数量超过限制" to R.string.ui_message_worker_binding_count,
    "Worker binding 名称无效" to R.string.ui_message_worker_binding_name_invalid,
    "Worker binding 资源引用无效" to R.string.ui_message_worker_binding_ref_invalid,
    "Worker binding 名称重复" to R.string.ui_message_worker_binding_duplicate,
    "Worker 入口路径无效" to R.string.ui_message_worker_entry_invalid,
    "Worker 入口路径越界" to R.string.ui_message_worker_entry_outside,
    "Worker 文件或部署包超过大小限制" to R.string.ui_message_worker_size_limit,
    "暂存消息落库失败，请重试" to R.string.ui_message_pending_message_save,
    "保存图片失败" to R.string.ui_message_image_save_failed,
    "图片编码失败" to R.string.ui_message_image_encode_failed,
    "图片压缩失败" to R.string.ui_message_image_compress_failed,
    "无法创建相册文件" to R.string.ui_message_image_album_create_failed,
    "无法写入相册文件" to R.string.ui_message_image_album_write_failed,
    "Agent 任务通知" to R.string.notification_agent_events_channel,
    "Agent 任务执行事件通知" to R.string.notification_agent_events_description,
    "点击查看进度" to R.string.notification_agent_view_progress,
    "Agent 需要你的确认" to R.string.notification_agent_confirmation_needed,
    "返回会话查看并处理权限请求" to R.string.notification_agent_confirmation_body,
    "SSH 连接已恢复" to R.string.notification_ssh_reconnected,
    "已继续监听原任务" to R.string.notification_ssh_monitoring_resumed,
    "服务器需要处理" to R.string.notification_server_attention,
    "任务仍在 VPS 运行，正在自动重连" to R.string.notification_ssh_reconnecting,
    "请稍后再获取验证码" to R.string.ui_message_account_code_wait,
    "验证码已发送，请查看邮箱" to R.string.ui_message_account_code_sent,
    "请先获取验证码" to R.string.ui_message_account_code_required,
    "无法打开浏览器，请检查设备是否安装浏览器" to R.string.ui_message_account_browser_missing,
    "请在浏览器中完成 Google 登录" to R.string.ui_message_account_google_continue,
    "没有等待中的 Google 登录，请重新开始" to R.string.ui_message_account_google_no_pending,
    "已退出此设备；服务端会话撤销失败，请稍后检查网络" to R.string.ui_message_account_logout_partial,
    "刷新会话的账号不匹配，请重新登录" to R.string.ui_message_account_session_user_mismatch,
    "会话的账号不匹配，请重新登录" to R.string.ui_message_account_user_mismatch,
    "登录成功" to R.string.ui_message_account_login_success,
    "登录信息无效，请重新登录或稍后重试" to R.string.ui_message_account_session_invalid,
    "无法读取或保存登录信息，请稍后重试" to R.string.ui_message_account_storage_failed,
    "账户登录服务尚未配置" to R.string.ui_message_account_service_missing,
    "账户登录配置无效，请联系维护者" to R.string.ui_message_account_configuration_invalid,
    "Supabase 项目地址必须是 HTTPS 根地址" to R.string.ui_message_account_supabase_url_invalid,
    "Supabase 公开 Key 无效" to R.string.ui_message_account_supabase_key_invalid,
    "只能配置 publishable key 或 anon key，不能使用管理员密钥" to R.string.ui_message_account_public_key_required,
    "账号回调地址无效" to R.string.ui_message_account_redirect_invalid,
    "登录回调过长" to R.string.ui_message_account_callback_large,
    "登录回调地址不匹配" to R.string.ui_message_account_callback_mismatch,
    "登录回调参数重复" to R.string.ui_message_account_callback_duplicate,
    "登录回调不属于本次授权" to R.string.ui_message_account_callback_other,
    "Google 登录已过期，请重新开始" to R.string.ui_message_account_google_expired,
    "登录回调不能包含会话凭据" to R.string.ui_message_account_callback_credentials,
    "Google 登录未完成，请重试" to R.string.ui_message_account_google_incomplete,
    "登录回调缺少有效授权码" to R.string.ui_message_account_callback_code_missing,
    "请输入有效的邮箱地址" to R.string.ui_message_account_email_invalid,
    "请输入 6 位数字验证码" to R.string.ui_message_account_code_invalid_format,
    "操作过于频繁，请稍后再试" to R.string.ui_message_account_rate_limited,
    "验证码无效或已过期，请重新获取" to R.string.ui_message_account_code_expired,
    "邮件服务尚未开放此邮箱，请联系维护者" to R.string.ui_message_account_email_unavailable,
    "登录已失效，请重新登录" to R.string.ui_message_account_login_expired,
    "登录服务暂时不可用，请稍后重试" to R.string.ui_message_account_service_unavailable,
    "登录服务响应超时，请稍后重试" to R.string.ui_message_account_request_timeout,
    "无法连接登录服务，请检查网络后重试" to R.string.ui_message_account_network_error,
    "登录服务返回的会话无效" to R.string.ui_message_account_response_session_invalid,
    "登录服务返回的用户资料无效" to R.string.ui_message_account_response_user_invalid,
    "Cloudflare OAuth 被取消或拒绝，请重试" to R.string.ui_message_cloudflare_oauth_cancelled,
    "Cloudflare OAuth 缺少 state" to R.string.ui_message_cloudflare_oauth_no_state,
    "Cloudflare OAuth 缺少 code" to R.string.ui_message_cloudflare_oauth_no_code,
    "任务完成" to R.string.ui_message_task_completed,
    "AI 已完成本轮任务" to R.string.ui_message_task_completed_body,
    "任务失败" to R.string.ui_message_task_failed,
    "AI 未能完成本轮任务" to R.string.ui_message_task_failed_body,
    "任务已取消" to R.string.ui_message_task_cancelled,
    "本轮任务已取消" to R.string.ui_message_task_cancelled_body,
    "SSH 连接断开" to R.string.ui_message_connection_lost,
    "已由新消息取代" to R.string.ui_message_task_superseded,
    "已退出登录" to R.string.cloudflare_logged_out,
    "确认当前操作" to R.string.agent_intervention_confirm_action,
    "Skill 添加失败" to R.string.skill_add_failed,
    "Skill 创建失败" to R.string.skill_create_failed,
    "Skill 名称不能为空" to R.string.ui_message_skill_name_required,
    "Skill 用途说明不能为空" to R.string.ui_message_skill_description_required,
    "Skill 具体规则不能为空" to R.string.ui_message_skill_rules_required,
    "远端 Skill 缺少来源" to R.string.ui_message_skill_remote_source_missing,
    "Skill 包为空" to R.string.ui_message_skill_package_empty,
    "Skill 包超过 100 MB" to R.string.ui_message_skill_package_large,
    "Skill 压缩包下载不完整" to R.string.ui_message_skill_archive_incomplete,
    "Skill 压缩包包含非法路径" to R.string.ui_message_skill_illegal_path,
    "Skill 文件路径越界" to R.string.ui_message_skill_path_outside,
    "Skill 解压后超过 100 MB" to R.string.ui_message_skill_extracted_large,
    "Skill 包中没有找到 SKILL.md" to R.string.ui_message_skill_package_no_markdown,
    "Skill 复制后哈希不一致" to R.string.ui_message_skill_copy_hash,
    "Skill 安装目录写入失败" to R.string.ui_message_skill_install_write,
    "ZIP 包含非法路径" to R.string.ui_message_zip_illegal_path,
    "ZIP 文件路径越界" to R.string.ui_message_zip_path_outside,
    "Skill 目录超过 100 MB" to R.string.ui_message_skill_directory_large,
    "无法读取所选目录" to R.string.ui_message_skill_directory_unreadable,
    "Skill 版本不存在" to R.string.ui_message_skill_version_missing,
    "Skill 文件不存在" to R.string.ui_message_skill_file_missing,
    "Skill 文件不在安装清单中" to R.string.ui_message_skill_file_not_listed,
    "该文件不是可读取的文本文件" to R.string.ui_message_skill_not_text,
    "Skill 文件路径无效" to R.string.ui_message_skill_file_invalid_path,
    "Skill 不存在" to R.string.ui_message_skill_missing,
    "SKILL.md 不能为空" to R.string.ui_message_skill_markdown_empty,
    "文件超过 Skill 大小限制" to R.string.ui_message_skill_file_large,
    "SKILL.md 不能删除" to R.string.ui_message_skill_markdown_delete,
    "Skill 文件删除失败" to R.string.ui_message_skill_delete_file_failed,
    "下载的原版需要先复制为用户 Skill" to R.string.ui_message_skill_remote_read_only,
    "导入内容中没有找到 SKILL.md" to R.string.ui_message_skill_import_no_markdown,
    "文件只能放入 scripts、references、templates 或 assets" to R.string.ui_message_skill_file_location,
    "Skill 目录不存在" to R.string.ui_message_skill_directory_missing,
    "Skill 禁止包含符号链接" to R.string.ui_message_skill_symlink,
    "Skill 根目录缺少 SKILL.md" to R.string.ui_message_skill_root_no_markdown,
    "Skill 缺少名称" to R.string.ui_message_skill_missing_name,
    "Skill 来源仓库无效" to R.string.ui_message_skill_repository_invalid,
    "Skill 版本无效" to R.string.ui_message_skill_version_invalid,
    "Skill 仓库文件树过大，GitHub 未返回完整结果" to R.string.ui_message_skill_repository_large,
    "仓库中没有找到有效的 SKILL.md" to R.string.ui_message_skill_repository_no_markdown,
    "Skill 仓库压缩包超过 200 MB" to R.string.ui_message_skill_archive_large,
    "Skill 压缩包为空" to R.string.ui_message_skill_archive_empty,
    "网络不可用，且没有本地目录缓存" to R.string.ui_message_skill_offline_no_cache,
    "密钥不能为空" to R.string.ui_message_skill_secret_empty,
    "密钥变量名无效" to R.string.ui_message_skill_secret_invalid,
    "Skill 密钥元数据保存失败" to R.string.ui_message_skill_secret_save_failed,
    "目录包含非法文件名" to R.string.ui_message_directory_invalid_filename,
    "用户添加的 Skill" to R.string.ui_message_skill_fallback_description,
    "安装后读取完整说明" to R.string.ui_message_skill_remote_description,
    "SSH 密码" to R.string.computer_field_password,
    "sudo 密码" to R.string.ui_message_sudo_password_label,
    "终端输入" to R.string.ui_message_terminal_input_label,
    "服务器环境变量值" to R.string.ui_message_server_env_value_label,
    "完成 Cloudflare 重新授权" to R.string.ui_message_cloudflare_reauthorization_done,
    "无法打开导出目标文件" to R.string.ui_message_export_target_unavailable,
    "处理 Agent 决定失败，请重试" to R.string.ui_message_agent_decision_failed,
    "Agent 恢复失败" to R.string.ui_message_agent_resume_failed,
    "正在停止任务" to R.string.ui_message_agent_stopping,
    "任务已停止" to R.string.ui_message_agent_stopped,
    "当前模型不支持 Agent Tool Call" to R.string.ui_message_agent_model_unsupported,
    "原工作区已删除，请先确认创建新工作区" to R.string.ui_message_agent_workspace_confirmation,
    "Agent 已开启，服务器预热失败，发送时会重试" to R.string.ui_message_agent_server_warmup_failed,
    "服务器选择失败" to R.string.ui_message_agent_server_selection_failed,
    "无法读取音频附件" to R.string.ui_message_audio_attachment_unreadable,
    "调整方向已发送，但本地消息记录保存失败" to R.string.ui_message_agent_steering_save_failed,
    "没有可添加的新模型" to R.string.ui_message_models_no_new,
    "正在等待当前步骤结束，可以点击停止终止任务" to R.string.ui_message_agent_pause_waiting,
    "正在安全暂停" to R.string.ui_message_agent_safe_pausing,
    "当前任务状态已变化，正在核对" to R.string.ui_message_agent_state_changed,
    "无法重命名：会话标识缺失" to R.string.ui_message_conversation_rename_missing,
    "复原保存失败，原对话快照已保留，请重试" to R.string.ui_message_conversation_restore_failed,
    "请先停止当前回复并处理待发送消息，再编辑历史消息" to R.string.ui_message_conversation_edit_busy,
    "请先处理输入框里的草稿，再编辑历史消息" to R.string.ui_message_conversation_edit_draft,
    "回退保存失败，原消息已保留，请重试" to R.string.ui_message_conversation_rollback_failed,
    "Cloudflare Computer 配置不存在" to R.string.ui_message_cloudflare_config_missing,
    "Cloudflare Computer 不存在" to R.string.ui_message_cloudflare_computer_missing,
    "Cloudflare 授权不存在" to R.string.ui_message_cloudflare_authorization_missing,
    "缺少 Cloudflare 授权" to R.string.ui_message_cloudflare_authorization_required,
    "Cloudflare Token 不存在" to R.string.ui_message_cloudflare_token_missing,
    "Cloudflare Token 格式无效" to R.string.ui_message_cloudflare_token_invalid,
    "Cloudflare Token 为空" to R.string.ui_message_cloudflare_token_empty,
    "Cloudflare 授权已失效，请重新授权" to R.string.ui_message_cloudflare_authorization_expired,
    "Cloudflare 授权已过期且续期失败，请重新授权" to R.string.ui_message_cloudflare_refresh_failed,
    "该 Account 不属于当前 Cloudflare 身份" to R.string.ui_message_cloudflare_account_mismatch,
    "Cloudflare 授权或 Account 已变化，请刷新后重试" to R.string.ui_message_cloudflare_selection_stale,
    "新授权无法访问当前 Account" to R.string.ui_message_cloudflare_new_auth_mismatch,
    "Cloudflare 授权已变化，请重新读取后再试" to R.string.ui_message_cloudflare_authorization_stale,
    "Cloudflare 名称无效" to R.string.ui_message_cloudflare_name_invalid,
    "Cloudflare Account 无效" to R.string.ui_message_cloudflare_account_invalid,
    "所选 Cloudflare Account 不属于当前授权身份" to R.string.ui_message_cloudflare_selected_account_mismatch,
    "Cloudflare OAuth 状态无效、过期或已消费" to R.string.ui_message_cloudflare_oauth_state_invalid,
    "Cloudflare OAuth 网络客户端未配置" to R.string.ui_message_cloudflare_oauth_client_unavailable,
    "Cloudflare OAuth Client ID 未配置" to R.string.ui_message_cloudflare_oauth_client_id_missing,
    "Cloudflare OAuth 回调地址无效" to R.string.ui_message_cloudflare_oauth_callback_invalid,
    "Cloudflare OAuth 端点必须是无凭据 HTTPS 地址" to R.string.ui_message_cloudflare_oauth_endpoint_invalid,
    "Cloudflare Token 响应无效" to R.string.ui_message_cloudflare_token_response_invalid,
    "Cloudflare Token 缺失或无效" to R.string.ui_message_cloudflare_token_response_missing,
    "Cloudflare Token 有效期无效" to R.string.ui_message_cloudflare_token_expiry_invalid,
    "Cloudflare Token 续期失败" to R.string.ui_message_cloudflare_token_renew_failed,
    "Cloudflare 请求超时" to R.string.ui_message_cloudflare_request_timeout,
    "Cloudflare 响应格式无效" to R.string.ui_message_cloudflare_response_invalid,
    "Cloudflare 响应超过限制" to R.string.ui_message_cloudflare_response_large,
    "部署结果需要查询确认" to R.string.ui_message_cloudflare_deployment_unknown,
    "版本已上传，等待 deployment 确认" to R.string.ui_message_cloudflare_version_uploaded,
    "Cloudflare 已接受部署，等待状态核验" to R.string.ui_message_cloudflare_deployment_accepted,
    "临时 Worker 不存在" to R.string.ui_message_temporary_worker_missing,
    "Temporary Worker 当前未开启" to R.string.ui_message_temporary_worker_disabled,
    "Temporary Worker Gateway 未配置" to R.string.ui_message_temporary_worker_gateway_missing,
    "Temporary Worker 网关请求失败" to R.string.ui_message_temporary_worker_gateway_failed,
    "Temporary Worker 创建响应无效" to R.string.ui_message_temporary_worker_response_invalid,
    "Temporary Worker Claim 响应无效" to R.string.ui_message_temporary_worker_claim_response_invalid,
    "Temporary Worker 已过期，请重新创建" to R.string.ui_message_temporary_worker_expired,
    "Cloudflare Claim 未完成" to R.string.ui_message_temporary_worker_claim_incomplete,
    "临时 Worker 当前不可 Claim" to R.string.ui_message_temporary_worker_claim_unavailable,
    "临时 Worker 不在 Claim 状态" to R.string.ui_message_temporary_worker_claim_state_invalid,
    "临时 Worker 已过期" to R.string.ui_message_temporary_worker_expired_short,
    "Workspace 无效" to R.string.ui_message_workspace_invalid,
    "所有图像生成配置已清除" to R.string.ui_message_image_configs_cleared,
    "没有图像生成配置可清除" to R.string.ui_message_no_image_configs,
    "所有配置已清除" to R.string.ui_message_configs_cleared,
    "请添加一个 API 配置" to R.string.ui_message_add_api_config,
    "没有配置可清除" to R.string.ui_message_no_configs,
    "未获取到模型，请手动输入模型名称" to R.string.ui_message_no_models_fetched_manual,
    "请输入模型名称" to R.string.ui_message_enter_model_name,
    "没有可用的模型" to R.string.ui_message_no_models_available,
    "请至少选择一个模型" to R.string.ui_message_select_model,
    "已移除服务器" to R.string.ui_message_server_removed,
    "无法分享会话" to R.string.ui_message_conversation_share_unavailable,
    "请先在设置-联网搜索中配置并勾选一个搜索服务商" to
        R.string.ui_message_web_search_provider_required,
    "已接收分享内容" to R.string.ui_message_shared_content_received,
    "已接收分享文件内容" to R.string.ui_message_shared_file_received,
    "分享文本过大（最大 256KB）" to R.string.ui_message_shared_text_too_large,
    "无法打开导出文件" to R.string.ui_message_export_file_unavailable,
    "未获取到任何模型" to R.string.ui_message_no_models_returned,
    "配置组已不存在" to R.string.ui_message_config_group_missing,
    "无法获取原始图片数据" to R.string.ui_message_original_image_unavailable,
    "原图已保存：应用空间与相册" to R.string.ui_message_original_image_saved_both,
    "原图已保存到相册" to R.string.ui_message_original_image_saved_gallery,
    "原图已保存到应用空间" to R.string.ui_message_original_image_saved_app,
    "保存失败：无法写入存储" to R.string.ui_message_storage_write_failed,
    "没有可下载的图片" to R.string.ui_message_no_downloadable_image,
    "图片已保存" to R.string.image_saved,
    "无法创建MediaStore条目" to R.string.ui_message_media_store_create_failed,
    "已复制到剪贴板" to R.string.ui_message_copied_clipboard,
    "复制失败" to R.string.ui_message_copy_failed,
    "请输入消息内容或选择项目" to R.string.ui_message_message_or_item_required,
    "已暂停显示" to R.string.ui_message_stream_paused,
    "已继续" to R.string.ui_message_stream_resumed,
    "新名称不能为空" to R.string.ui_message_name_empty,
    "无法重命名：对话索引错误" to R.string.ui_message_rename_index_error,
    "对话已重命名" to R.string.ui_message_conversation_renamed,
    "无法删除：无效的索引" to R.string.ui_message_delete_index_error,
    "记录已清空" to R.string.ui_message_history_cleared,
    "图像记录已清空" to R.string.ui_message_image_history_cleared,
    "无法找到对应的用户消息来重新生成回答" to R.string.ui_message_regenerate_user_missing,
    "请先选择 API 配置" to R.string.chat_input_select_api_configuration,
    "无法重新生成：原始用户消息在当前列表中未找到。" to
        R.string.ui_message_regenerate_original_missing,
    "举报已提交，感谢反馈" to R.string.ui_message_report_submitted,
    "网络暂不可用，举报已保存并会自动重试" to R.string.ui_message_report_queued,
    "已在应用内标记；举报接收服务尚未配置" to R.string.ui_message_report_saved_locally,
    "这条 AI 内容已经举报过了" to R.string.ui_message_report_duplicate,
    "举报保存失败，请稍后重试" to R.string.ui_message_report_storage_failed,
    "请先选择 图像生成 的API配置" to R.string.ui_message_select_image_api_config,
    "模型参数无效" to R.string.model_parameters_invalid,
    "IO 错误" to R.string.ai_error_io,
    "I/O 错误" to R.string.ai_error_io,
    "未知应用错误" to R.string.ai_error_unknown_app,
    "本轮回复被上游提前截断（输出长度限制）。这不代表模型参数设置错误；请重试，若反复出现请查看请求日志中的实际输出上限、结束原因和 Token 用量。" to
        R.string.ai_error_output_limit,
    "正在压缩上下文" to R.string.thinking_context_compressing,
    "最大输出必须小于上下文窗口" to R.string.model_token_output_less_than_context,
    "语音识别失败：未能识别出文字" to R.string.voice_error_no_transcription,
    "无法启动录音，请检查麦克风权限是否已开启" to R.string.voice_error_recording_start,
    "未能识别出语音内容，请检查麦克风权限或重试" to R.string.voice_error_no_speech,
    "录音数据为空，请确保麦克风正常工作" to R.string.voice_error_empty_recording,
    "该请求可能涉及未成年人性剥削内容，已被安全过滤器拦截。" to
        R.string.safety_block_child_exploitation,
    "该请求可能涉及非自愿私密内容，已被安全过滤器拦截。" to
        R.string.safety_block_non_consensual,
    "该请求可能生成露骨色情内容，已被安全过滤器拦截。" to
        R.string.safety_block_explicit_sexual,
    "该请求可能包含危险的自伤操作指导，已被安全过滤器拦截。如有人正处于紧急危险，请立即联系当地急救服务。" to
        R.string.safety_block_self_harm,
    "该请求可能生成血腥暴力内容，已被安全过滤器拦截。" to
        R.string.safety_block_graphic_violence,
    "该请求可能生成仇恨、霸凌或骚扰内容，已被安全过滤器拦截。" to
        R.string.safety_block_hate_harassment,
    "该请求可能提供危险行为指导，已被安全过滤器拦截。" to
        R.string.safety_block_dangerous_activity,
    "该请求可能用于欺诈、冒充或伪造，已被安全过滤器拦截。" to
        R.string.safety_block_fraud_impersonation,
    "该请求可能用于制作或投放恶意代码，已被安全过滤器拦截。" to
        R.string.safety_block_malicious_code,
    "模型服务已根据安全策略拦截这次生成。请调整请求内容后重试。" to
        R.string.safety_block_provider,
    "参数名不能为空" to R.string.model_parameter_name_required,
    "未知错误" to R.string.unknown_error,
    "压缩响应流在完成前中断" to R.string.compression_error_stream_interrupted,
    "摘要模型未返回有效内容" to R.string.compression_error_empty_summary,
    "待压缩内容为空" to R.string.compression_error_empty_content,
    "模型窗口没有足够空间保留压缩摘要" to R.string.compression_error_summary_space,
    "压缩结果未能继续缩小" to R.string.compression_error_not_reduced,
    "多轮压缩后仍无法放入模型上下文窗口" to R.string.compression_error_still_oversized,
    "预留输出已占满模型上下文窗口" to R.string.compression_error_output_reserve_full,
    "请求中没有可压缩的用户内容" to R.string.compression_error_no_user_content,
    "媒体附件和协议开销已超过模型可用输入空间" to R.string.compression_error_media_overhead,
    "系统提示、工具定义和媒体附件已占满模型可用输入空间" to
        R.string.compression_error_system_overhead,
    "压缩后请求仍超出模型上下文窗口" to R.string.compression_error_after_compression,
    "压缩分块本身超出模型上下文窗口" to R.string.compression_error_chunk_oversized,
    "当前请求超出模型上下文窗口，自动压缩未开启" to R.string.compression_error_disabled,
    "部分初始数据加载失败，原数据已保留" to R.string.ui_message_initial_data_partial_failure,
    "部分历史加载失败，原数据已保留" to R.string.ui_message_history_partial_failure,
    "部分历史图片迁移失败，原数据已保留" to
        R.string.ui_message_history_image_migration_failure,
    "已切换到文本模式" to R.string.ui_message_switched_text_mode,
    "已切换到图像模式" to R.string.ui_message_switched_image_mode,
)

/**
 * 只覆盖可信的本地提示模板；整句匹配避免误翻译用户内容或未知服务端响应。
 * 捕获的路径、资源名和错误代码作为格式参数保留，不改变协议或持久化内容。
 */
private val TEMPLATE_MESSAGE_RESOURCES = listOf(
    Regex("""Skill (?:包)?文件数超过 (\d+)""") to R.string.ui_message_skill_file_limit,
    Regex("""Skill 压缩包包含重复文件：(.+)""") to R.string.ui_message_skill_duplicate_file,
    Regex("""Skill 文件清单重复：(.+)""") to R.string.ui_message_skill_duplicate_manifest,
    Regex("""Skill 文件已变化：(.+)""") to R.string.ui_message_skill_file_changed,
    Regex("""Skill 压缩包缺少文件，预期 (\d+) 个，实际 (\d+) 个""") to R.string.ui_message_skill_missing_archive_files,
    Regex("""Skill 缺少 SKILL.md：(.+)""") to R.string.ui_message_skill_named_markdown_missing,
    Regex("""Skill 压缩包下载失败：HTTP (\d+)""") to R.string.ui_message_skill_archive_http_failed,
    Regex("""Skill 云目录请求失败：HTTP (\d+)""") to R.string.ui_message_skill_catalog_http_failed,
    Regex("""无法读取文件：(.+)""") to R.string.ui_message_file_named_unreadable,
    Regex("""Agent 已开启，服务器预热失败，发送时会重试：(.+)""") to R.string.ui_message_agent_warmup_reason,
    Regex("""已添加 (\d+) 个新模型""") to R.string.ui_message_models_added,
    Regex("""已删除 (\d+) 个已下架模型""") to R.string.ui_message_models_removed,
    Regex("""Cloudflare API 请求失败（(\d+)）(.*)""") to R.string.ui_message_cloudflare_http_failed,
    Regex("""Cloudflare API 操作失败(.*)""") to R.string.ui_message_cloudflare_api_failed,
    Regex("""部署请求已明确失败：(.+)""") to R.string.ui_message_cloudflare_deploy_failed_detail,
    Regex("""Worker deployment 状态：(.+)""") to R.string.ui_message_cloudflare_deployment_summary,
    Regex("""部署对账状态：(.+)""") to R.string.ui_message_cloudflare_reconcile_summary,
)

/**
 * 将旧控制器产生的中文提示在最终展示边界转换为当前应用语言。
 * 未知文本通常来自服务端，原样保留，避免错误改写用户或服务端内容。
 */
fun Context.localizeUiMessage(message: String): String =
    appLanguageContext().resolveUiMessage(message)

private fun Context.resolveUiMessage(message: String): String {
    val text = message.trim()
    if (text.isEmpty()) return message
    EXACT_MESSAGE_RESOURCES[text]?.let { return getString(it) }
    TEMPLATE_MESSAGE_RESOURCES.forEach { (pattern, resource) ->
        pattern.matchEntire(text)?.let { match ->
            return getString(resource, *match.groupValues.drop(1).toTypedArray())
        }
    }

    return when {
        text.startsWith("⚠️ ") -> "⚠️ " + localizeUiMessage(text.removePrefix("⚠️ "))
        text.startsWith("网络通讯故障: ") -> getString(
            R.string.ai_error_network,
            localizeUiMessage(text.removePrefix("网络通讯故障: ")),
        )
        text.startsWith("处理时发生错误: ") -> getString(
            R.string.ai_error_processing,
            localizeUiMessage(text.removePrefix("处理时发生错误: ")),
        )
        text.startsWith("更新失败：未找到配置 ID ") -> formatSuffix(
            R.string.ui_message_config_update_not_found,
            text,
            "更新失败：未找到配置 ID ",
        )
        text.hasWrappedNumber("获取到 ", " 个模型") -> quantityFromWrappedNumber(
            R.plurals.ui_message_models_fetched,
            text,
            "获取到 ",
            " 个模型",
        )
        text.startsWith("获取模型失败: ") -> formatReason(
            R.string.ui_message_model_fetch_failed,
            text.removePrefix("获取模型失败: "),
        )
        text.startsWith("已添加服务器: ") -> formatSuffix(
            R.string.ui_message_server_added,
            text,
            "已添加服务器: ",
        )
        text.startsWith("添加服务器失败: ") -> formatReason(
            R.string.ui_message_server_add_failed,
            text.removePrefix("添加服务器失败: "),
        )
        text.startsWith("移除服务器失败: ") -> formatReason(
            R.string.ui_message_server_remove_failed,
            text.removePrefix("移除服务器失败: "),
        )
        text.startsWith("已更新服务器: ") -> formatSuffix(
            R.string.ui_message_server_updated,
            text,
            "已更新服务器: ",
        )
        text.startsWith("更新服务器失败: ") -> formatReason(
            R.string.ui_message_server_update_failed,
            text.removePrefix("更新服务器失败: "),
        )
        text.startsWith("操作失败: ") -> formatReason(
            R.string.ui_message_operation_failed,
            text.removePrefix("操作失败: "),
        )
        text.startsWith("分享失败: ") -> formatReason(
            R.string.ui_message_share_failed,
            text.removePrefix("分享失败: "),
        )
        text.startsWith("启动新图像生成失败: ") -> formatReason(
            R.string.ui_message_new_image_chat_failed,
            text.removePrefix("启动新图像生成失败: "),
        )
        text.startsWith("加载文本历史对话失败: ") -> formatReason(
            R.string.ui_message_text_history_load_failed,
            text.removePrefix("加载文本历史对话失败: "),
        )
        text.startsWith("图片下载失败: ") -> formatReason(
            R.string.ui_message_image_download_failed,
            text.removePrefix("图片下载失败: "),
        )
        text.startsWith("启动新聊天失败: ") -> formatReason(
            R.string.ui_message_new_chat_failed,
            text.removePrefix("启动新聊天失败: "),
        )
        text.startsWith("读取文件失败: ") -> formatReason(
            R.string.ui_message_file_read_failed,
            text.removePrefix("读取文件失败: "),
        )
        text.startsWith("导出失败: ") -> formatReason(
            R.string.ui_message_export_failed,
            text.removePrefix("导出失败: "),
        )
        text.hasWrappedNumber("成功创建 ", " 个配置") -> quantityFromWrappedNumber(
            R.plurals.ui_message_configs_created,
            text,
            "成功创建 ",
            " 个配置",
        )
        text.hasWrappedNumber("", " 个配置创建失败") -> quantityFromWrappedNumber(
            R.plurals.ui_message_configs_create_failed,
            text,
            "",
            " 个配置创建失败",
        )
        text.startsWith("刷新模型失败: ") -> formatReason(
            R.string.ui_message_models_refresh_failed,
            text.removePrefix("刷新模型失败: "),
        )
        text.hasWrappedNumber("刷新成功，已更新 ", " 个模型") -> quantityFromWrappedNumber(
            R.plurals.ui_message_models_refreshed,
            text,
            "刷新成功，已更新 ",
            " 个模型",
        )
        text.startsWith("保存失败: ") -> formatReason(
            R.string.ui_message_save_failed,
            text.removePrefix("保存失败: "),
        )
        text.startsWith("发送失败: ") -> formatReason(
            R.string.ui_message_send_failed,
            text.removePrefix("发送失败: "),
        )
        text.startsWith("无法处理附件: ") -> formatSuffix(
            R.string.ui_message_attachment_process_failed,
            text,
            "无法处理附件: ",
        )
        text.startsWith("无法读取图片“") && text.endsWith("”，请重新选择。") -> getString(
            R.string.ui_message_image_read_failed,
            text.removePrefix("无法读取图片“").removeSuffix("”，请重新选择。"),
        )
        text.startsWith("加载图像历史失败: ") -> formatReason(
            R.string.ui_message_image_history_load_failed,
            text.removePrefix("加载图像历史失败: "),
        )
        text.startsWith("参数 ") && text.endsWith(" 需要填写有效数字") -> getString(
            R.string.model_parameter_number_required,
            text.removePrefix("参数 ").removeSuffix(" 需要填写有效数字"),
        )
        text.startsWith("参数 ") && text.endsWith(" 需要填写 true 或 false") -> getString(
            R.string.model_parameter_boolean_required,
            text.removePrefix("参数 ").removeSuffix(" 需要填写 true 或 false"),
        )
        text.startsWith("参数名不能重复：") -> getString(
            R.string.model_parameter_duplicate_name,
            text.removePrefix("参数名不能重复："),
        )
        text.startsWith("参数 ") && text.endsWith(" 由应用管理，不能覆盖") -> getString(
            R.string.model_parameter_reserved,
            text.removePrefix("参数 ").removeSuffix(" 由应用管理，不能覆盖"),
        )
        text.startsWith("上下文压缩失败：") -> getString(
            R.string.thinking_context_compression_failed,
            localizeKnownReason(text.removePrefix("上下文压缩失败：")),
        )
        text.endsWith(": API 密钥无效或已过期") -> formatProviderSuffix(
            R.string.network_error_api_key,
            text,
            ": API 密钥无效或已过期",
        )
        text.endsWith(": 访问被拒绝，请检查 API 权限") -> formatProviderSuffix(
            R.string.network_error_access_denied,
            text,
            ": 访问被拒绝，请检查 API 权限",
        )
        text.endsWith(": 请求过于频繁，请稍后重试") -> formatProviderSuffix(
            R.string.network_error_rate_limited,
            text,
            ": 请求过于频繁，请稍后重试",
        )
        text.endsWith(": 服务器暂时不可用，请稍后重试") -> formatProviderSuffix(
            R.string.network_error_server_unavailable,
            text,
            ": 服务器暂时不可用，请稍后重试",
        )
        text.endsWith(": 该模型不支持图像识别，请切换支持视觉的模型") -> formatProviderSuffix(
            R.string.network_error_image_unsupported,
            text,
            ": 该模型不支持图像识别，请切换支持视觉的模型",
        )
        text.contains(" API 错误: ") -> formatProviderParts(
            R.string.network_error_api_status,
            text,
            " API 错误: ",
        )
        text.endsWith(": 无法连接服务器，请检查网络") -> formatProviderSuffix(
            R.string.network_error_cannot_connect,
            text,
            ": 无法连接服务器，请检查网络",
        )
        text.endsWith(": 连接超时，请检查网络") -> formatProviderSuffix(
            R.string.network_error_timeout,
            text,
            ": 连接超时，请检查网络",
        )
        text.endsWith(": SSL 连接失败，请检查网络安全设置") -> formatProviderSuffix(
            R.string.network_error_ssl,
            text,
            ": SSL 连接失败，请检查网络安全设置",
        )
        text.contains(" 连接失败: ") -> formatProviderParts(
            R.string.network_error_connection_failed,
            text,
            " 连接失败: ",
        )
        text.startsWith("最大输出需在 1 到 ") && text.endsWith(" tokens 之间") ->
            text.removePrefix("最大输出需在 1 到 ")
                .removeSuffix(" tokens 之间")
                .toIntOrNull()
                ?.let { getString(R.string.model_token_output_range, it) }
                ?: message
        text.startsWith("上下文窗口需在 2 到 ") && text.endsWith(" tokens 之间") ->
            text.removePrefix("上下文窗口需在 2 到 ")
                .removeSuffix(" tokens 之间")
                .toIntOrNull()
                ?.let { getString(R.string.model_token_context_range, it) }
                ?: message
        else -> message
    }
}

private fun Context.formatSuffix(
    @StringRes resourceId: Int,
    text: String,
    prefix: String,
): String = getString(resourceId, text.removePrefix(prefix))

private fun Context.formatReason(@StringRes resourceId: Int, reason: String): String {
    val normalizedReason = reason.trim().takeUnless {
        it.isEmpty() || it.equals("null", ignoreCase = true) || it == "未知错误"
    }
    val localizedReason = normalizedReason?.let(::localizeKnownReason)
        ?: getString(R.string.unknown_error)
    return getString(resourceId, localizedReason)
}

private fun Context.localizeKnownReason(reason: String): String =
    localizeUiMessage(reason)

private fun Context.formatProviderSuffix(
    @StringRes resourceId: Int,
    text: String,
    suffix: String,
): String = getString(resourceId, text.removeSuffix(suffix))

private fun Context.formatProviderParts(
    @StringRes resourceId: Int,
    text: String,
    separator: String,
): String = getString(
    resourceId,
    text.substringBefore(separator),
    text.substringAfter(separator),
)

private fun String.hasWrappedNumber(prefix: String, suffix: String): Boolean =
    startsWith(prefix) && endsWith(suffix) &&
        removePrefix(prefix).removeSuffix(suffix).trim().toIntOrNull() != null

private fun Context.quantityFromWrappedNumber(
    @PluralsRes resourceId: Int,
    text: String,
    prefix: String,
    suffix: String,
): String {
    val count = requireNotNull(
        text.removePrefix(prefix).removeSuffix(suffix).trim().toIntOrNull(),
    )
    return resources.getQuantityString(resourceId, count, count)
}
