package com.android.everytalk.util.locale

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.android.everytalk.R

private val EXACT_MESSAGE_RESOURCES = mapOf(
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
 * 将旧控制器产生的中文提示在最终展示边界转换为当前应用语言。
 * 未知文本通常来自服务端，原样保留，避免错误改写用户或服务端内容。
 */
fun Context.localizeUiMessage(message: String): String {
    val text = message.trim()
    if (text.isEmpty()) return message
    EXACT_MESSAGE_RESOURCES[text]?.let { return getString(it) }

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
    EXACT_MESSAGE_RESOURCES[reason]?.let(::getString) ?: reason

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
