package com.android.everytalk.data.network

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.android.everytalk.config.PerformanceConfig
import com.android.everytalk.util.audio.AudioTestUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class StreamAudioPlayer(private val inputSampleRate: Int, private val onVolumeChanged: ((Float) -> Unit)? = null) {
    private var audioTrack: AudioTrack? = null
    private val bufferSize: Int
    
    // 目标采样率：强制使用 48000Hz (Android 原生最安全采样率)
    // 解决部分设备 (OnePlus/OPPO) 在非标准采样率 (如 32kHz) 下 AudioTrack 卡死的问题
    private val targetSampleRate = 48000
    
    // 预缓冲配置
    private var totalWrittenBytes = 0
    private var isBuffering = true // 初始状态为缓冲
    
    // 字节对齐缓冲 (处理奇数包)
    private var leftoverByte: Byte? = null

    // 音量计算
    private var lastVolumeUpdateTime = 0L
    
    // 设备信息
    private val isProblematicDevice = AudioTestUtil.isKnownProblematicDevice()

    init {
        // 【关键修复 1】强制使用双声道输出 (Stereo)
        // 解决 Mono 模式下部分设备驱动不工作的问题
        val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        
        // 【关键修复 2】强制使用 48kHz 采样率
        // 解决 32kHz/24kHz 在部分设备上导致 playbackHeadPosition 不动的问题
        
        // 缓冲区计算：Stereo (2ch) * 16bit (2bytes) = 4 bytes/frame
        val minBufSize = AudioTrack.getMinBufferSize(targetSampleRate, channelConfig, audioFormat)
        // 缓冲区大小调整：使用较小的倍数 (2x minBuf) 以减少延迟并避免某些设备的 buffer 协商问题
        // 之前使用 1秒 (targetSampleRate * 4) 可能过大导致驱动行为异常
        bufferSize = maxOf(minBufSize * 2, targetSampleRate * 4 / 2)
        
        Log.i("StreamAudioPlayer", "=== AudioTrack 初始化 (Resample: $inputSampleRate -> $targetSampleRate, Stereo) ===")
        Log.i("StreamAudioPlayer", "参数: targetSampleRate=$targetSampleRate, minBuf=$minBufSize, actualBuf=$bufferSize")
        Log.i("StreamAudioPlayer", "设备: ${Build.MANUFACTURER} ${Build.MODEL}, isProblematic=$isProblematicDevice")

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    // 【关键修复】使用 CONTENT_TYPE_MUSIC 而非 SPEECH
                    // 某些设备 (如 OnePlus/OPPO) 对 SPEECH 类型的流有特殊的路由或处理逻辑
                    // 可能导致 AudioTrack 写入成功但无法播放 (head stuck at 0)
                    // MUSIC 类型通常走标准媒体通道，兼容性最好
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(targetSampleRate)
                    .setEncoding(audioFormat)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        
        val state = audioTrack?.state
        val actualSampleRate = audioTrack?.sampleRate
        val playState = audioTrack?.playState
        
        if (state != AudioTrack.STATE_INITIALIZED) {
            Log.e("StreamAudioPlayer", "AudioTrack 初始化失败! state=$state, playState=$playState")
        } else {
            Log.i("StreamAudioPlayer", "AudioTrack 初始化成功: state=$state, sampleRate=$actualSampleRate, playState=$playState")
        }
    }
    
    @Volatile
    private var forceStop = false

    fun requestStop() {
        forceStop = true
    }
    
    fun start() {
        // 【关键修复】立即启动播放
        // 不要等待数据写入后再 play()，这在某些设备上会导致死锁或状态异常
        // AudioTrack 在没有数据时会自动处于 Underrun 状态，写入数据后会立即发声
        try {
            audioTrack?.play()
            Log.i("StreamAudioPlayer", "start() 调用，AudioTrack 已设置为 PLAYING 状态")
        } catch (e: Exception) {
            Log.e("StreamAudioPlayer", "start() 播放失败", e)
        }
    }
    
    fun forceStop() {
        requestStop()
        val track = takeAudioTrack()
        try {
            track?.pause()
            track?.flush()
            track?.stop()
        } catch (_: Throwable) {
        } finally {
            try {
                track?.release()
            } catch (_: Throwable) {
            }
        }
    }

    private fun takeAudioTrack(): AudioTrack? = synchronized(this) {
        audioTrack.also { audioTrack = null }
    }
    
    suspend fun write(data: ByteArray) {
        if (forceStop) return
        val track = audioTrack ?: return
        
        // 计算音量并回调 (使用原始 Mono 数据计算)
        val currentTime = SystemClock.elapsedRealtime()
        if (onVolumeChanged != null && currentTime - lastVolumeUpdateTime >= 50) {
            lastVolumeUpdateTime = currentTime
            
            var sum = 0.0
            val step = 4
            var i = 0
            while (i < data.size - 1) {
                val sample = (data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)
                val shortValue = sample.toShort()
                sum += shortValue * shortValue
                i += 2 * step
            }
            
            val sampleCount = data.size / (2 * step)
            if (sampleCount > 0) {
                val rms = kotlin.math.sqrt(sum / sampleCount)
                val normalizedVolume = (rms / 2000.0).coerceIn(0.0, 1.0).toFloat()
                withContext(Dispatchers.Main) {
                    onVolumeChanged.invoke(normalizedVolume)
                }
            }
        }

        // 处理字节对齐
        var dataToProcess = data
        
        // 1. 如果有上次剩余的字节，拼接到开头
        if (leftoverByte != null) {
            val newData = ByteArray(data.size + 1)
            newData[0] = leftoverByte!!
            System.arraycopy(data, 0, newData, 1, data.size)
            dataToProcess = newData
            leftoverByte = null
        }
        
        // 2. 如果当前数据长度是奇数，剥离最后一个字节
        if (dataToProcess.size % 2 != 0) {
            leftoverByte = dataToProcess[dataToProcess.size - 1]
            dataToProcess = dataToProcess.copyOfRange(0, dataToProcess.size - 1)
        }
        
        if (dataToProcess.isEmpty()) return

        // 【关键处理】重采样 + 声道扩展
        // 1. Resample: inputSampleRate -> 48000Hz
        // 2. Mono -> Stereo
        
        val resampledData = if (inputSampleRate != targetSampleRate) {
            resample(dataToProcess, inputSampleRate, targetSampleRate)
        } else {
            dataToProcess
        }

        // Mono (16-bit) -> Stereo (16-bit)
        // 输入: [L-low, L-high]
        // 输出: [L-low, L-high, L-low, L-high] (即 Left=Right)
        val stereoData = ByteArray(resampledData.size * 2)
        for (i in 0 until resampledData.size / 2) {
            val low = resampledData[i * 2]
            val high = resampledData[i * 2 + 1]
            
            // Left channel
            stereoData[i * 4] = low
            stereoData[i * 4 + 1] = high
            
            // Right channel (duplicated)
            stereoData[i * 4 + 2] = low
            stereoData[i * 4 + 3] = high
        }

        // 循环写入 Stereo 数据
        var offset = 0
        val totalToWrite = stereoData.size
        
        while (offset < totalToWrite && !forceStop) {
            val remaining = totalToWrite - offset
            val written = track.write(stereoData, offset, remaining)
            if (written > 0) {
                offset += written
                totalWrittenBytes += written
                // 检查播放状态，如果处于停止状态则重新启动
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    try {
                        track.play()
                        Log.i("StreamAudioPlayer", "AudioTrack restarted from non-playing state")
                    } catch (e: Exception) {
                        Log.e("StreamAudioPlayer", "Failed to restart playback", e)
                    }
                }
            } else if (written == 0) {
                kotlinx.coroutines.delay(10)
            } else {
                Log.e("StreamAudioPlayer", "AudioTrack.write error: $written")
                break
            }
        }
    }
    
    suspend fun close() {
        val track = takeAudioTrack() ?: return
        try {
            if (forceStop) {
                Log.i("StreamAudioPlayer", "Playback force stopped by user")
                return
            }
            // 【关键修复】写入静音 Padding
            // 在停止前写入约 200ms 的静音数据，将硬件缓冲区中的有效音频"挤"出来
            // 这能解决部分设备尾音被吞或 playbackHeadPosition 不更新到最后的问题
            try {
                val paddingMs = 200
                val paddingBytes = (targetSampleRate * 4 * paddingMs) / 1000 // Stereo: 4 bytes/frame
                val paddingData = ByteArray(paddingBytes) // 全 0 即静音
                track.write(paddingData, 0, paddingBytes)
                Log.i("StreamAudioPlayer", "Written ${paddingBytes} bytes of silence padding")
                // 计入总写入量，以便后续等待逻辑包含这段 Padding
                totalWrittenBytes += paddingBytes
            } catch (e: Exception) {
                Log.w("StreamAudioPlayer", "Failed to write padding", e)
            }
            // 挂起等待播放完成，避免占用协程调度线程。
            // Stereo 模式下: 1 frame = 2 channels * 16bit = 4 bytes
            val totalFrames = totalWrittenBytes / 4
            var waitedMs = 0
            val timeoutMs = PerformanceConfig.VOICE_STREAM_CLOSE_TIMEOUT_MS.toInt()
            var lastPosition = -1L
            var positionStuckCount = 0
            // 启动等待
            val startupGraceMs = if (isProblematicDevice) 2000 else 1000  // 问题设备给更长启动时间
            var startupWaitMs = 0
            Log.i("StreamAudioPlayer", "Waiting for playback completion. Total frames: $totalFrames")
            if (totalFrames > 0) {
                // 1. 等待播放启动 (head > 0)
                while (startupWaitMs < startupGraceMs && !forceStop) {
                    val currentPosition = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                    if (currentPosition > 0) break
                    // 如果还没开始且不在播放状态，尝试再次 play
                    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        try { track.play() } catch (_: Exception) {}
                    }
                    kotlinx.coroutines.delay(50)
                    startupWaitMs += 50
                }
                
                // 2. 等待播放结束
                // 问题设备的卡死阈值更高，避免误判
                val stuckThreshold = if (isProblematicDevice) 60 else 40  // 3s 或 2s
                while (waitedMs < timeoutMs && !forceStop) {
                    val currentPosition = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                    // 允许 5% 的误差，加上 Padding 后通常能完整播放有效音频
                    if (currentPosition >= totalFrames * 0.95) {
                        Log.i("StreamAudioPlayer", "Playback completed naturally ($currentPosition / $totalFrames)")
                        break
                    }
                    // 卡死检测
                    if (currentPosition == lastPosition) {
                        positionStuckCount++
                        if (positionStuckCount > stuckThreshold) {
                            Log.w("StreamAudioPlayer", "Playback stuck at $currentPosition / $totalFrames after ${positionStuckCount * 50}ms, aborting wait")
                            break
                        }
                    } else {
                        lastPosition = currentPosition
                        positionStuckCount = 0
                    }
                    kotlinx.coroutines.delay(50)
                    waitedMs += 50
                }
            }
            if (waitedMs >= timeoutMs) {
                Log.w("StreamAudioPlayer", "Playback wait timed out. Final pos: ${track.playbackHeadPosition} / $totalFrames")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("StreamAudioPlayer", "Error closing AudioTrack", e)
        } finally {
            try {
                track.stop()
            } catch (_: Throwable) {
            }
            try {
                track.release()
            } catch (_: Throwable) {
            }
        }
    }
}

/**
 * 简单的线性插值重采样 (16-bit PCM Mono)
 */
internal fun resample(input: ByteArray, inRate: Int, outRate: Int): ByteArray {
    if (inRate == outRate) return input
    
    val inputShorts = ShortArray(input.size / 2)
    java.nio.ByteBuffer.wrap(input).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(inputShorts)
    
    val ratio = inRate.toDouble() / outRate
    val outputLength = (inputShorts.size / ratio).toInt()
    val outputShorts = ShortArray(outputLength)
    
    for (i in 0 until outputLength) {
        val inputIndex = i * ratio
        val index1 = inputIndex.toInt()
        val index2 = minOf(index1 + 1, inputShorts.size - 1)
        val fraction = inputIndex - index1
        
        val val1 = inputShorts[index1]
        val val2 = inputShorts[index2]
        
        // 线性插值
        val interpolated = (val1 + fraction * (val2 - val1)).toInt().toShort()
        outputShorts[i] = interpolated
    }
    
    val outputBytes = ByteArray(outputShorts.size * 2)
    java.nio.ByteBuffer.wrap(outputBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(outputShorts)
    
    return outputBytes
}
