package com.android.everytalk.data.network

import android.media.*
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 语音模式（Gemini Live）客户端（服务器到服务器转发模式）。
 *
 * 后端接口（已添加）:
 *  POST /gemini/live/relay-pcm
 *  表单字段:
 *    - audio: 16-bit PCM，16kHz，mono（application/octet-stream / audio/pcm）
 *    - api_key: 用户在App里填写的Key
 *    - model: 可缺省（默认 gemini-2.5-flash-native-audio-preview-09-2025）
 *
 * 返回: 24kHz 裸PCM（audio/pcm;rate=24000），可直接用 AudioTrack 播放。
 *
 * 使用建议（UI侧）：
 * - 单击麦克风（开始）：session = GeminiLiveSession(...).start()
 * - 再次单击麦克风（结束）：session.stopAndSendAndPlay()
 *
 * 说明：
 * - 该实现采用“单次录制一段 → 上传 → 实时回流播放”的最小可用链路（与后端路由匹配）。
 * - 如需真正双工实时：后续可切换到WebSocket方案（/gemini/live/ws）。
 */
class GeminiLiveSession(
    private val baseUrl: String,  // 例如 "https://your-proxy-host"
    private val apiKey: String,
    private val model: String = "gemini-2.5-flash-native-audio-preview-09-2025"
) {
    private var audioRecord: AudioRecord? = null
    private var isRecording: Boolean = false
    private val pcmBuffer = ByteArrayOutputStream(256 * 1024) // 预估内存，避免频繁扩容

    // 录音参数（必须 16k / 16-bit / mono）
    private val sampleRate = 16_000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    /**
     * 开始麦克风采集（在 IO Dispatcher 内部初始化）。
     */
    suspend fun start() = withContext(Dispatchers.IO) {
        if (isRecording) return@withContext

        val recorder = createBestAudioRecord()
            ?: throw IllegalStateException("AudioRecord init failed for all sources. Check RECORD_AUDIO permission and device policy.")

        audioRecord = recorder
        pcmBuffer.reset()
        try {
            recorder.startRecording()
        } catch (e: SecurityException) {
            audioRecord = null
            throw e
        } catch (e: IllegalStateException) {
            // 某些机型会在没有热词/特殊权限时拒绝 VOICE_* 源，这里已改为常规MIC，但仍兜底处理
            Log.e("GeminiLiveSession", "startRecording failed: ${e.message}", e)
            try {
                recorder.release()
            } catch (_: Throwable) {}
            audioRecord = null
            throw e
        }

        isRecording = true

        // 启动读取循环（调用方控制生命周期，stopAndSendAndPlay 会将 isRecording=false）
        val readBuf = ByteArray(2048)
        try {
            while (isRecording) {
                val n = recorder.read(readBuf, 0, readBuf.size)
                if (n > 0) {
                    pcmBuffer.write(readBuf, 0, n)
                } else if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_BAD_VALUE) {
                    // 轻微错误，跳过继续
                }
                // 协程取消保护
                if (!isActive) break
            }
        } catch (ce: CancellationException) {
            // 正常取消
        } catch (t: Throwable) {
            Log.e("GeminiLiveSession", "Audio read error", t)
        } finally {
            try {
                recorder.stop()
            } catch (_: Throwable) {}
            try {
                recorder.release()
            } catch (_: Throwable) {}
            audioRecord = null
        }
    }

    /**
     * 停止录音并将PCM上传到后端，然后把返回的24kHz裸PCM实时播放。
     */
    suspend fun stopAndSendAndPlay() = withContext(Dispatchers.IO) {
        if (!isRecording) return@withContext
        isRecording = false

        val pcmBytes = pcmBuffer.toByteArray()
        pcmBuffer.reset()

        if (pcmBytes.isEmpty()) {
            Log.w("GeminiLiveSession", "No PCM captured")
            return@withContext
        }

        // 组装 multipart 表单
        val audioBody = object : RequestBody() {
            private val mt = "application/octet-stream".toMediaType()
            override fun contentType() = mt
            override fun writeTo(sink: BufferedSink) {
                sink.write(pcmBytes)
            }
        }

        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("api_key", apiKey)
            .addFormDataPart("model", model)
            .addFormDataPart(
                "audio",
                "audio_16k.pcm",
                audioBody
            )
            .build()

        val url = normalizeUrl("$baseUrl/gemini/live/relay-pcm")
        val req = Request.Builder()
            .url(url)
            .post(form)
            .build()

        // OkHttp 客户端（适度超时）
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // 流式，不设定读超时
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string() ?: "unknown"
                Log.e("GeminiLiveSession", "HTTP ${resp.code}: $err")
                return@withContext
            }
            val ctype = resp.header("Content-Type") ?: ""
            // 期望 audio/pcm;rate=24000
            val sampleRateOut = if (ctype.contains("rate=24000")) 24_000 else 24_000

            val input: InputStream = resp.body!!.byteStream()
            playPcmStream(input, sampleRateOut)
        }
    }

    private fun normalizeUrl(u: String): String {
        return u.replace("///", "/").replace("//", "://").replace(":/", "://")
    }

    /**
     * 使用 AudioTrack 播放 16-bit PCM，mono。
     * 注意：在 IO 线程中消费输入流，避免阻塞主线程。
     */
    private fun playPcmStream(input: InputStream, sampleRateOut: Int) {
        val attr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        } else null

        val fmt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioFormat.Builder()
                .setSampleRate(sampleRateOut)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        } else null

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRateOut,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && attr != null && fmt != null) {
            AudioTrack(
                attr,
                fmt,
                minBuf,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRateOut,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf,
                AudioTrack.MODE_STREAM
            )
        }

        try {
            track.play()
            val buf = ByteArray(4096)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                var written = 0
                while (written < n) {
                    val w = track.write(buf, written, n - written)
                    if (w < 0) break
                    written += w
                }
            }
        } catch (t: Throwable) {
            Log.e("GeminiLiveSession", "AudioTrack playback error", t)
        } finally {
            try {
                track.stop()
            } catch (_: Throwable) {}
            try {
                track.release()
            } catch (_: Throwable) {}
            try {
                input.close()
            } catch (_: Throwable) {}
        }
    }
    /**
     * 尝试不同的音频源，优先使用 MIC，避免 VOICE_RECOGNITION 在部分机型上需要热词等特殊权限而失败。
     * 返回已初始化成功的 AudioRecord；失败则返回 null。
     */
    private fun createBestAudioRecord(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufSize = (if (minBuf > 0) minBuf else 2048) * 2

        // 按优先级尝试的音源列表（避开 VOICE_RECOGNITION）
        val sources = buildList {
            add(MediaRecorder.AudioSource.MIC)
            add(MediaRecorder.AudioSource.DEFAULT)
            add(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            add(MediaRecorder.AudioSource.CAMCORDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                add(MediaRecorder.AudioSource.UNPROCESSED)
            }
        }

        for (src in sources) {
            try {
                val rec = AudioRecord(
                    src,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufSize.coerceAtLeast(4096)
                )
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    Log.i("GeminiLiveSession", "AudioRecord initialized with source=$src")
                    return rec
                } else {
                    Log.w("GeminiLiveSession", "AudioRecord state not initialized for source=$src")
                    try { rec.release() } catch (_: Throwable) {}
                }
            } catch (t: Throwable) {
                Log.w("GeminiLiveSession", "AudioRecord create failed for source=$src: ${t.message}")
            }
        }
        return null
    }
}

/**
 * 便捷静态方法：直接上传一段PCM字节并播放返回音频（不涉及麦克风控制）。
 */
object GeminiLiveClient {
    suspend fun sendOnceAndPlay(
        baseUrl: String,
        apiKey: String,
        pcm16kMonoBytes: ByteArray,
        model: String = "gemini-2.5-flash-native-audio-preview-09-2025"
    ) {
        val session = GeminiLiveSession(baseUrl, apiKey, model)
        // 将已有PCM放入session内部的缓冲再发送
        // 这里复用逻辑：直接触发“停止并上传”的路径
        session.apply {
            // 模拟录音已完成的缓冲：简单方式是直接走专用的上传方法，这里复用 stopAndSend 路径
            // 为避免暴露内部缓冲，提供一个简单位点上传的内部实现：
            uploadAndPlay(baseUrl, apiKey, model, pcm16kMonoBytes)
        }
    }

    private suspend fun GeminiLiveSession.uploadAndPlay(
        baseUrl: String,
        apiKey: String,
        model: String,
        pcmBytes: ByteArray
    ) = withContext(Dispatchers.IO) {
        val audioBody = object : RequestBody() {
            private val mt = "application/octet-stream".toMediaType()
            override fun contentType() = mt
            override fun writeTo(sink: okio.BufferedSink) {
                sink.write(pcmBytes)
            }
        }

        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("api_key", apiKey)
            .addFormDataPart("model", model)
            .addFormDataPart("audio", "audio_16k.pcm", audioBody)
            .build()

        val url = (baseUrl.trimEnd('/') + "/gemini/live/relay-pcm")
            .replace("///", "/").replace("//", "://").replace(":/", "://")

        val req = Request.Builder().url(url).post(form).build()
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string() ?: "unknown"
                Log.e("GeminiLiveClient", "HTTP ${resp.code}: $err")
                return@withContext
            }
            val ctype = resp.header("Content-Type") ?: ""
            val sampleRateOut = if (ctype.contains("rate=24000")) 24_000 else 24_000
            val input = resp.body!!.byteStream()
            // 直接播放
            playStream(input, sampleRateOut)
        }
    }

    private fun playStream(input: InputStream, sampleRateOut: Int) {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRateOut,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        @Suppress("DEPRECATION")
        val track = AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRateOut,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf,
            AudioTrack.MODE_STREAM
        )

        try {
            track.play()
            val buf = ByteArray(4096)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                var written = 0
                while (written < n) {
                    val w = track.write(buf, written, n - written)
                    if (w < 0) break
                    written += w
                }
            }
        } catch (t: Throwable) {
            Log.e("GeminiLiveClient", "AudioTrack playback error", t)
        } finally {
            try { track.stop() } catch (_: Throwable) {}
            try { track.release() } catch (_: Throwable) {}
            try { input.close() } catch (_: Throwable) {}
        }
    }
}