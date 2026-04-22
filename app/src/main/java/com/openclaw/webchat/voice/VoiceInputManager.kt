package com.openclaw.webchat.voice

import android.app.Activity
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Audio recorder - records voice as WAV file.
 * Tap to start recording, tap again to stop and send.
 */
class VoiceInputManager(private val activity: Activity) {

    companion object {
        private const val TAG = "VoiceInputManager"
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val MAX_DURATION_MS = 60000
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null
    private var outputFile: File? = null
    private var onStatusCallback: ((String) -> Unit)? = null
    private var onResultCallback: ((String) -> Unit)? = null

    fun startRecording(statusCallback: (String) -> Unit, resultCallback: (String) -> Unit) {
        if (isRecording) {
            stopRecording()
            return
        }

        Log.d(TAG, "startRecording called")

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        Log.d(TAG, "Buffer size: $bufferSize")
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            statusCallback("录音初始化失败")
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )

            Log.d(TAG, "AudioRecord state: ${audioRecord?.state}")

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                statusCallback("录音初始化失败")
                audioRecord?.release()
                audioRecord = null
                return
            }

            outputFile = File(activity.cacheDir, "voice_${System.currentTimeMillis()}.pcm")
            onStatusCallback = statusCallback
            onResultCallback = resultCallback

            isRecording = true
            audioRecord?.startRecording()
            Log.d(TAG, "Recording started")

            recordingThread = Thread {
                writeAudioDataToFile(bufferSize)
            }
            recordingThread?.start()

            statusCallback("正在录音...")

        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException", e)
            statusCallback("没有麦克风权限")
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e)
            statusCallback("录音失败: ${e.message}")
        }
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false

        Log.d(TAG, "stopRecording called")

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            recordingThread?.interrupt()
            recordingThread = null

            outputFile?.let { file ->
                Log.d(TAG, "PCM file size: ${file.length()} bytes at ${file.absolutePath}")
                if (file.exists() && file.length() > 44) {
                    val wavFile = File(activity.cacheDir, "voice_${System.currentTimeMillis()}.wav")
                    writeWavHeader(file, wavFile, SAMPLE_RATE, 1, 16)
                    Log.d(TAG, "WAV file created: ${wavFile.absolutePath}, size: ${wavFile.length()}")
                    onResultCallback?.invoke(wavFile.absolutePath)
                } else {
                    Log.e(TAG, "PCM file invalid: exists=${file.exists()}, size=${file.length()}")
                    onStatusCallback?.invoke("录音文件无效")
                }
            } ?: run {
                Log.e(TAG, "outputFile is null")
                onStatusCallback?.invoke("录音文件未创建")
            }

        } catch (e: Exception) {
            Log.e(TAG, "stopRecording failed", e)
            onStatusCallback?.invoke("停止录音失败")
        }
    }

    private fun writeWavHeader(pcmFile: File, wavFile: File, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        val pcmData = pcmFile.readBytes()
        val wavOut = FileOutputStream(wavFile)
        val dataSize = pcmData.size
        val fileSize = 36 + dataSize

        wavOut.write("RIFF".toByteArray())
        wavOut.write(intToByteArray(fileSize))
        wavOut.write("WAVE".toByteArray())
        wavOut.write("fmt ".toByteArray())
        wavOut.write(intToByteArray(16))
        wavOut.write(shortToByteArray(1))
        wavOut.write(shortToByteArray(channels.toShort()))
        wavOut.write(intToByteArray(sampleRate))
        wavOut.write(intToByteArray(sampleRate * channels * bitsPerSample / 8))
        wavOut.write(shortToByteArray((channels * bitsPerSample / 8).toShort()))
        wavOut.write(shortToByteArray(bitsPerSample.toShort()))
        wavOut.write("data".toByteArray())
        wavOut.write(intToByteArray(dataSize))
        wavOut.write(pcmData)
        wavOut.close()
        pcmFile.delete()
    }

    private fun intToByteArray(value: Int): ByteArray = byteArrayOf(
        (value and 0xff).toByte(),
        ((value shr 8) and 0xff).toByte(),
        ((value shr 16) and 0xff).toByte(),
        ((value shr 24) and 0xff).toByte()
    )

    private fun shortToByteArray(value: Short): ByteArray = byteArrayOf(
        (value.toInt() and 0xff).toByte(),
        ((value.toInt() shr 8) and 0xff).toByte()
    )

    private fun writeAudioDataToFile(bufferSize: Int) {
        val buffer = ByteArray(bufferSize)
        var outputStream: FileOutputStream? = null

        try {
            outputStream = FileOutputStream(outputFile)
            val startTime = System.currentTimeMillis()

            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                if (read > 0) {
                    outputStream.write(buffer, 0, read)
                }
                if (System.currentTimeMillis() - startTime > MAX_DURATION_MS) {
                    onStatusCallback?.invoke("录音超时")
                    break
                }
            }
            outputStream.flush()
            Log.d(TAG, "writeAudioDataToFile finished, isRecording=$isRecording")
        } catch (e: Exception) {
            Log.e(TAG, "writeAudioDataToFile failed", e)
            onStatusCallback?.invoke("录音写入失败")
        } finally {
            try { outputStream?.close() } catch (e: Exception) { }
        }
    }

    fun isCurrentlyRecording(): Boolean = isRecording
}
