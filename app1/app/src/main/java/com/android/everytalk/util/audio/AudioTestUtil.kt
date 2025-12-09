package com.android.everytalk.util.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.sin

/**
 * 音频测试工具类
 * 
 * 用于诊断 AudioTrack 播放问题，提供本地正弦波测试功能，
 * 完全绕过 STT/TTS 和网络，只验证本应用内 AudioTrack 播放 PCM 的能力。
 */
object AudioTestUtil {
    private const val TAG = "AudioTestUtil"
    
    /**
     * 测试结果数据类
     */
    data class TestResult(
        val success: Boolean,
        val message: String,
        val details: Map<String, Any> = emptyMap()
    )
    
    /**
     * 播放测试正弦波
     * 
     * 生成一段 440Hz 的正弦波并播放，用于验证 AudioTrack 是否能正常工作。
     * 
     * @param sampleRate 采样率，默认 24000Hz（与 TTS 一致）
     * @param durationMs 播放时长，默认 2000ms
     * @param frequency 正弦波频率，默认 440Hz (A4 音符)
     * @param onProgress 播放进度回调 (0.0 ~ 1.0)
     * @return 测试结果
     */
    suspend fun playSineWaveTest(
        sampleRate: Int = 24000,
        durationMs: Int = 2000,
        frequency: Int = 440,
        onProgress: ((Float) -> Unit)? = null
    ): TestResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "=== 开始 AudioTrack 播放测试 ===")
        Log.i(TAG, "参数: sampleRate=$sampleRate, duration=${durationMs}ms, frequency=${frequency}Hz")
        Log.i(TAG, "设备信息: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.SDK_INT}")
        
        val details = mutableMapOf<String, Any>(
            "sampleRate" to sampleRate,
            "durationMs" to durationMs,
            "frequency" to frequency,
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "sdkVersion" to Build.VERSION.SDK_INT
        )
        
        try {
            // 1. 生成正弦波 PCM 数据
            val numSamples = (sampleRate * durationMs / 1000)
            val pcmData = generateSineWave(frequency, sampleRate, numSamples)
            Log.i(TAG, "生成 PCM 数据: ${pcmData.size} 字节, ${numSamples} 采样点")
            details["pcmBytes"] = pcmData.size
            details["numSamples"] = numSamples
            
            // 2. 创建 AudioTrack
            val channelConfig = AudioFormat.CHANNEL_OUT_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val bufferSize = maxOf(minBufSize * 2, sampleRate * 2) // 至少 1 秒缓冲
            
            Log.i(TAG, "AudioTrack 配置: minBufSize=$minBufSize, actualBufSize=$bufferSize")
            details["minBufferSize"] = minBufSize
            details["actualBufferSize"] = bufferSize
            
            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(audioFormat)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            
            // 3. 检查初始化状态
            val initState = audioTrack.state
            Log.i(TAG, "AudioTrack 初始化状态: state=$initState (期望=${AudioTrack.STATE_INITIALIZED})")
            details["initState"] = initState
            
            if (initState != AudioTrack.STATE_INITIALIZED) {
                audioTrack.release()
                val errorMsg = "AudioTrack 初始化失败! state=$initState"
                Log.e(TAG, errorMsg)
                return@withContext TestResult(false, errorMsg, details)
            }
            
            try {
                // 4. 写入静音预热数据（模拟 StreamAudioPlayer 的预热逻辑）
                val prewarmBytes = (sampleRate * 2 * 100 / 1000) // 100ms 静音
                val silenceData = ByteArray(prewarmBytes)
                Log.i(TAG, "写入静音预热数据: ${silenceData.size} 字节")
                
                val prewarmWritten = audioTrack.write(silenceData, 0, silenceData.size)
                Log.i(TAG, "预热写入结果: $prewarmWritten 字节")
                details["prewarmWritten"] = prewarmWritten
                
                // 5. 启动播放
                Log.i(TAG, "调用 play() 前: playState=${audioTrack.playState}, headPosition=${audioTrack.playbackHeadPosition}")
                audioTrack.play()
                Log.i(TAG, "调用 play() 后: playState=${audioTrack.playState}, headPosition=${audioTrack.playbackHeadPosition}")
                details["playStateAfterPlay"] = audioTrack.playState
                
                // 6. 等待播放启动
                var startupWaitMs = 0
                val maxStartupWait = 500
                while (startupWaitMs < maxStartupWait) {
                    val headPos = audioTrack.playbackHeadPosition
                    if (headPos > 0) {
                        Log.i(TAG, "播放已启动: headPosition=$headPos, 等待时间=${startupWaitMs}ms")
                        details["startupWaitMs"] = startupWaitMs
                        break
                    }
                    delay(50)
                    startupWaitMs += 50
                }
                
                if (audioTrack.playbackHeadPosition == 0) {
                    Log.w(TAG, "警告: 播放启动后 ${maxStartupWait}ms headPosition 仍为 0")
                    details["startupWarning"] = "headPosition stuck at 0"
                }
                
                // 7. 写入正弦波数据
                var offset = 0
                val chunkSize = 4096
                var totalWritten = 0
                val startTime = System.currentTimeMillis()
                
                while (offset < pcmData.size) {
                    val toWrite = minOf(chunkSize, pcmData.size - offset)
                    val written = audioTrack.write(pcmData, offset, toWrite)
                    
                    if (written > 0) {
                        offset += written
                        totalWritten += written
                        
                        // 报告进度
                        val progress = offset.toFloat() / pcmData.size
                        onProgress?.invoke(progress)
                        
                        // 每 500ms 记录一次状态
                        if (totalWritten % (sampleRate * 2 / 2) < chunkSize) {
                            Log.d(TAG, "写入进度: $offset/${pcmData.size}, playState=${audioTrack.playState}, headPos=${audioTrack.playbackHeadPosition}")
                        }
                    } else if (written == 0) {
                        // 缓冲区满，等待
                        delay(10)
                    } else {
                        Log.e(TAG, "写入错误: $written")
                        break
                    }
                }
                
                Log.i(TAG, "数据写入完成: 总共 $totalWritten 字节")
                details["totalWritten"] = totalWritten
                
                // 8. 等待播放完成
                val totalFrames = (pcmData.size + prewarmBytes) / 2
                var waitedMs = 0
                val timeoutMs = durationMs + 2000
                var lastHeadPos = 0L
                var stuckCount = 0
                
                Log.i(TAG, "等待播放完成: 总帧数=$totalFrames, 超时=${timeoutMs}ms")
                
                while (waitedMs < timeoutMs) {
                    val currentPos = audioTrack.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                    
                    if (currentPos >= totalFrames) {
                        Log.i(TAG, "播放完成: headPosition=$currentPos >= totalFrames=$totalFrames")
                        details["playbackCompleted"] = true
                        details["finalHeadPosition"] = currentPos
                        break
                    }
                    
                    if (audioTrack.playState == AudioTrack.PLAYSTATE_STOPPED) {
                        Log.w(TAG, "播放意外停止")
                        details["unexpectedStop"] = true
                        break
                    }
                    
                    // 检测卡死
                    if (currentPos == lastHeadPos && currentPos > 0) {
                        stuckCount++
                        if (stuckCount > 20) {
                            Log.w(TAG, "播放卡死: headPosition=$currentPos 持续 1 秒未变化")
                            details["playbackStuck"] = true
                            break
                        }
                    } else {
                        lastHeadPos = currentPos
                        stuckCount = 0
                    }
                    
                    delay(50)
                    waitedMs += 50
                }
                
                val elapsed = System.currentTimeMillis() - startTime
                Log.i(TAG, "播放结束: 耗时=${elapsed}ms, 最终 headPosition=${audioTrack.playbackHeadPosition}")
                details["elapsedMs"] = elapsed
                details["finalHeadPosition"] = audioTrack.playbackHeadPosition
                
                // 9. 判断测试结果
                val finalHeadPos = audioTrack.playbackHeadPosition
                val success = finalHeadPos > 0 && finalHeadPos >= totalFrames * 0.9 // 允许 10% 误差
                
                audioTrack.stop()
                audioTrack.release()
                
                val message = if (success) {
                    "测试通过: AudioTrack 播放正常"
                } else if (finalHeadPos == 0) {
                    "测试失败: playbackHeadPosition 始终为 0，音频未能播放"
                } else {
                    "测试部分通过: 播放了 ${finalHeadPos * 100 / totalFrames}% 的数据"
                }
                
                Log.i(TAG, "=== 测试结果: $message ===")
                return@withContext TestResult(success, message, details)
                
            } catch (e: Exception) {
                Log.e(TAG, "播放过程出错", e)
                try {
                    audioTrack.stop()
                    audioTrack.release()
                } catch (_: Exception) {}
                return@withContext TestResult(false, "播放出错: ${e.message}", details)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "测试失败", e)
            return@withContext TestResult(false, "测试失败: ${e.message}", details)
        }
    }
    
    /**
     * 生成正弦波 PCM 数据
     * 
     * @param frequency 频率 (Hz)
     * @param sampleRate 采样率 (Hz)
     * @param numSamples 采样点数
     * @return 16-bit PCM 数据 (Little Endian)
     */
    private fun generateSineWave(frequency: Int, sampleRate: Int, numSamples: Int): ByteArray {
        val pcmData = ByteArray(numSamples * 2) // 16-bit = 2 bytes per sample
        val amplitude = 16000 // 约 50% 音量，避免削波
        
        for (i in 0 until numSamples) {
            val angle = 2.0 * Math.PI * frequency * i / sampleRate
            val sample = (amplitude * sin(angle)).toInt().toShort()
            
            // Little Endian
            pcmData[i * 2] = (sample.toInt() and 0xFF).toByte()
            pcmData[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        
        return pcmData
    }
    
    /**
     * 获取设备音频信息
     */
    fun getDeviceAudioInfo(audioManager: AudioManager?): Map<String, Any> {
        val info = mutableMapOf<String, Any>(
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "sdkVersion" to Build.VERSION.SDK_INT,
            "brand" to Build.BRAND,
            "device" to Build.DEVICE
        )
        
        audioManager?.let { am ->
            info["musicStreamVolume"] = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            info["musicStreamMaxVolume"] = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            info["isMusicActive"] = am.isMusicActive
            info["mode"] = am.mode
            info["ringerMode"] = am.ringerMode
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                info["isStreamMute"] = am.isStreamMute(AudioManager.STREAM_MUSIC)
            }
        }
        
        return info
    }
    
    /**
     * 检查是否是已知有 AudioTrack 问题的设备
     */
    fun isKnownProblematicDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val model = Build.MODEL.lowercase()
        
        // OPPO/Realme/OnePlus 设备可能需要特殊处理
        return manufacturer in listOf("oppo", "realme", "oneplus", "oplus") ||
               model.contains("oppo") || model.contains("realme") || model.contains("oneplus")
    }
    
    /**
     * 获取推荐的音频延迟配置
     */
    fun getRecommendedAudioDelayMs(): Long {
        return if (isKnownProblematicDevice()) {
            600L // OPPO 等设备使用更长的延迟
        } else {
            300L // 默认延迟
        }
    }
}