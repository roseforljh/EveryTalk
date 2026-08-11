package com.android.everytalk.data.safety

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.android.everytalk.BuildConfig
import com.android.everytalk.data.DataClass.Message
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class AiContentReportCategory(
    val displayName: String,
    val description: String,
) {
    CHILD_SAFETY("儿童安全", "涉及未成年人剥削或伤害"),
    SEXUAL_CONTENT("色情或私密内容", "色情、非自愿私密内容或换脸内容"),
    VIOLENCE_SELF_HARM("暴力或自伤", "鼓励危险行为、暴力、自残或自杀"),
    HATE_HARASSMENT("仇恨或骚扰", "仇恨攻击、霸凌、威胁或骚扰"),
    DECEPTION_IMPERSONATION("欺诈或冒充", "诈骗、虚假文件、深度伪造或冒充"),
    MALICIOUS_CODE("恶意代码", "用于攻击、窃取或破坏的代码"),
    OTHER("其他问题", "其他令人不适或疑似违规的内容"),
}

@Serializable
data class AiContentReportPayload(
    val reportId: String,
    val messageId: String,
    val category: AiContentReportCategory,
    val details: String,
    val messageText: String,
    val imageCount: Int,
    val isImageGeneration: Boolean,
    val modelName: String?,
    val providerName: String?,
    val appVersion: String,
    val platform: String = "android",
    val createdAtEpochMillis: Long,
)

@Serializable
private enum class ReportDeliveryState {
    PENDING,
    SUBMITTED,
}

@Serializable
private data class StoredAiContentReport(
    val payload: AiContentReportPayload,
    val deliveryState: ReportDeliveryState = ReportDeliveryState.PENDING,
)

sealed interface AiContentReportSubmissionResult {
    data object Submitted : AiContentReportSubmissionResult
    data object QueuedForRetry : AiContentReportSubmissionResult
    data object SavedLocally : AiContentReportSubmissionResult
    data object AlreadyReported : AiContentReportSubmissionResult
    data object StorageFailure : AiContentReportSubmissionResult
}

class AiContentReportRepository(
    context: Context,
    private val httpClient: HttpClient,
    reportEndpoint: String = BuildConfig.AI_CONTENT_REPORT_URL,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val storageMutex = Mutex()
    private val deliveryMutex = Mutex()
    private val reportFile = File(context.applicationContext.filesDir, REPORT_FILE_PATH)
    private val atomicFile = AtomicFile(reportFile)
    private val endpoint = reportEndpoint.trim().takeIf(::isValidHttpsEndpoint)

    suspend fun submit(
        message: Message,
        category: AiContentReportCategory,
        details: String,
        isImageGeneration: Boolean,
    ): AiContentReportSubmissionResult {
        val payload = createAiContentReportPayload(
            message = message,
            category = category,
            details = details,
            isImageGeneration = isImageGeneration,
        )
        val stored = try {
            storageMutex.withLock {
                val reports = readReports()
                if (reports.any { it.payload.messageId == message.id }) {
                    return AiContentReportSubmissionResult.AlreadyReported
                }
                writeReports((reports + StoredAiContentReport(payload)).takeLast(MAX_STORED_REPORTS))
                true
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
        if (!stored) return AiContentReportSubmissionResult.StorageFailure

        val targetEndpoint = endpoint ?: return AiContentReportSubmissionResult.SavedLocally
        return deliveryMutex.withLock {
            if (deliver(targetEndpoint, payload)) {
                markSubmitted(payload.reportId)
                AiContentReportSubmissionResult.Submitted
            } else {
                AiContentReportSubmissionResult.QueuedForRetry
            }
        }
    }

    suspend fun retryPendingReports() {
        val targetEndpoint = endpoint ?: return
        deliveryMutex.withLock {
            val pending = try {
                storageMutex.withLock {
                    readReports()
                        .asSequence()
                        .filter { it.deliveryState == ReportDeliveryState.PENDING }
                        .take(MAX_RETRY_BATCH_SIZE)
                        .map(StoredAiContentReport::payload)
                        .toList()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return
            }

            pending.forEach { payload ->
                if (deliver(targetEndpoint, payload)) {
                    markSubmitted(payload.reportId)
                }
            }
        }
    }

    private suspend fun deliver(
        targetEndpoint: String,
        payload: AiContentReportPayload,
    ): Boolean = try {
        httpClient.post(targetEndpoint) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.UserAgent, "EveryTalk/${BuildConfig.VERSION_NAME} Android")
            header("X-EveryTalk-Report-Version", "1")
            setBody(payload)
        }.status.isSuccess()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    private suspend fun markSubmitted(reportId: String) {
        try {
            storageMutex.withLock {
                val updated = readReports().map { stored ->
                    if (stored.payload.reportId != reportId) {
                        stored
                    } else {
                        stored.copy(
                            payload = stored.payload.copy(
                                details = "",
                                messageText = "",
                            ),
                            deliveryState = ReportDeliveryState.SUBMITTED,
                        )
                    }
                }
                writeReports(updated)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // 上传已经成功，本地回执清理失败不应把成功状态误报为失败。
            Log.w(TAG, "举报已上传，但本地回执清理失败", error)
        }
    }

    private fun readReports(): List<StoredAiContentReport> {
        if (!reportFile.isFile) return emptyList()
        val raw = atomicFile.openRead().bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        if (raw.isBlank()) return emptyList()
        return json.decodeFromString(raw)
    }

    private fun writeReports(reports: List<StoredAiContentReport>) {
        val parent = reportFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            error("无法创建 AI 内容举报存储目录")
        }
        val output = atomicFile.startWrite()
        try {
            output.write(json.encodeToString(reports).toByteArray(StandardCharsets.UTF_8))
            output.flush()
            atomicFile.finishWrite(output)
        } catch (error: Exception) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    companion object {
        private const val REPORT_FILE_PATH = "safety/ai_content_reports.json"
        private const val TAG = "AiContentReport"
        private const val MAX_STORED_REPORTS = 100
        private const val MAX_RETRY_BATCH_SIZE = 20
        internal const val MAX_DETAILS_CHARS = 500
        internal const val MAX_MESSAGE_CHARS = 4_000
    }
}

internal fun createAiContentReportPayload(
    message: Message,
    category: AiContentReportCategory,
    details: String,
    isImageGeneration: Boolean,
    reportId: String = UUID.randomUUID().toString(),
    createdAtEpochMillis: Long = System.currentTimeMillis(),
): AiContentReportPayload = AiContentReportPayload(
    reportId = reportId,
    messageId = message.id,
    category = category,
    details = details.sanitizedReportText(AiContentReportRepository.MAX_DETAILS_CHARS),
    messageText = message.text.sanitizedReportText(AiContentReportRepository.MAX_MESSAGE_CHARS),
    imageCount = message.imageUrls.orEmpty().size,
    isImageGeneration = isImageGeneration,
    modelName = message.modelName?.sanitizedReportText(200)?.takeIf(String::isNotBlank),
    providerName = message.providerName?.sanitizedReportText(200)?.takeIf(String::isNotBlank),
    appVersion = BuildConfig.VERSION_NAME,
    createdAtEpochMillis = createdAtEpochMillis,
)

private fun String.sanitizedReportText(maxChars: Int): String = asSequence()
    .filter { character -> !character.isISOControl() || character == '\n' || character == '\t' }
    .take(maxChars)
    .joinToString("")
    .trim()

private fun isValidHttpsEndpoint(value: String): Boolean = try {
    val uri = URI(value)
    uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
} catch (_: Exception) {
    false
}
