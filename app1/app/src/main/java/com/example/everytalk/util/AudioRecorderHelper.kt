package com.example.everytalk.util

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.IOException

class AudioRecorderHelper(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var audioFilePath: String? = null

    fun startRecording() {
        val audioFile = File(context.cacheDir, "audio_record.3gp")
        audioFilePath = audioFile.absolutePath

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            setOutputFile(audioFilePath)
            try {
                prepare()
                start()
                Log.d("AudioRecorderHelper", "Recording started")
            } catch (e: IOException) {
                Log.e("AudioRecorderHelper", "prepare() failed", e)
            }
        }
    }

    fun stopRecording(): String? {
        return try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            Log.d("AudioRecorderHelper", "Recording stopped. File saved at: $audioFilePath")
            audioFilePath
        } catch (e: Exception) {
            Log.e("AudioRecorderHelper", "stopRecording() failed", e)
            null
        }
    }

    fun encodeAudioToBase64(filePath: String): String? {
        return try {
            val file = File(filePath)
            val bytes = file.readBytes()
            Base64.encodeToString(bytes, Base64.DEFAULT)
        } catch (e: IOException) {
            Log.e("AudioRecorderHelper", "Failed to encode audio file", e)
            null
        }
    }
}