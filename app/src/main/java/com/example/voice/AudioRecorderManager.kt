package com.example.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class AudioRecorderManager(private val context: Context? = null) {

    private val lock = Any()
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var pcmBuffer = ByteArrayOutputStream()

    @Volatile
    private var isRecordingActive: Boolean = false

    fun hasRecordPermission(): Boolean {
        val ctx = context ?: return true
        return ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun startRecording(
        scope: CoroutineScope,
        onAmplitude: (Float) -> Unit,
        onError: (String) -> Unit,
        onSilenceDetected: (() -> Unit)? = null
    ) {
        if (!hasRecordPermission()) {
            onError("Microphone permission is needed to listen. Please allow microphone access or use Text Chat.")
            return
        }

        synchronized(lock) {
            if (isRecordingActive) {
                Log.d(TAG, "Ignoring concurrent startRecording call")
                return
            }
            isRecordingActive = true
        }

        val sampleRate = 16_000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            synchronized(lock) { isRecordingActive = false }
            onError("Microphone hardware is unavailable right now. You can continue using Text Chat.")
            return
        }

        try {
            val recorder = createInitializedRecorder(sampleRate, minBuf)
            if (recorder == null) {
                synchronized(lock) { isRecordingActive = false }
                onError("Microphone couldn't start cleanly. Check if another app is using it, or try Text Chat.")
                return
            }

            synchronized(lock) {
                audioRecord = recorder
                pcmBuffer = ByteArrayOutputStream()
            }
            recorder.startRecording()

            recordingJob = scope.launch(Dispatchers.IO) {
                val readBuf = ByteArray(minBuf)
                var hasHeardSpeech = false
                var silenceStartedAt = 0L
                var silenceTriggered = false
                while (isActive && isRecordingActive && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val read = recorder.read(readBuf, 0, readBuf.size)
                    if (read > 0) {
                        synchronized(pcmBuffer) {
                            pcmBuffer.write(readBuf, 0, read)
                        }
                        val rms = computeNormalizedRms(readBuf, read)
                        onAmplitude(rms)

                        if (onSilenceDetected != null && !silenceTriggered) {
                            val now = System.currentTimeMillis()
                            if (rms >= 0.16f) {
                                hasHeardSpeech = true
                                silenceStartedAt = 0L
                            } else if (hasHeardSpeech) {
                                if (silenceStartedAt == 0L) {
                                    silenceStartedAt = now
                                } else if (now - silenceStartedAt >= 1350L) {
                                    silenceTriggered = true
                                    onSilenceDetected()
                                }
                            }
                        }
                    } else if (read < 0) {
                        Log.w(TAG, "AudioRecord read returned error: $read")
                        if (isRecordingActive) {
                            stopAndDiscard()
                            onError("Microphone audio was interrupted. Tap the mic to try again or use Text Chat.")
                        }
                        break
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing microphone permission during AudioRecord start", e)
            stopAndDiscard()
            onError("Microphone permission is needed to listen. Please allow microphone access or use Text Chat.")
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord start failed", e)
            stopAndDiscard()
            onError("Unable to start the microphone right now. Tap the mic to try again or use Text Chat.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun createInitializedRecorder(sampleRate: Int, minBuf: Int): AudioRecord? {
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )
        for (source in sources) {
            val candidate = runCatching {
                AudioRecord(
                    source,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
            }.getOrNull() ?: continue

            if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                return candidate
            } else {
                runCatching { candidate.release() }
            }
        }
        return null
    }

    fun stopAndBuildWav(): ByteArray? {
        val rawPcm: ByteArray
        synchronized(lock) {
            isRecordingActive = false
            try {
                recordingJob?.cancel()
                recordingJob = null
                audioRecord?.let { rec ->
                    if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        rec.stop()
                    }
                    rec.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping AudioRecord: ${e.message}")
            } finally {
                audioRecord = null
            }
        }
        synchronized(pcmBuffer) {
            rawPcm = pcmBuffer.toByteArray()
            pcmBuffer.reset()
        }
        if (rawPcm.size < 1600) return null
        return wrapPcmAsWav(rawPcm, sampleRate = 16_000, channels = 1, bitsPerSample = 16)
    }

    fun stopAndDiscard() {
        synchronized(lock) {
            isRecordingActive = false
            try {
                recordingJob?.cancel()
                recordingJob = null
                audioRecord?.let { rec ->
                    if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        rec.stop()
                    }
                    rec.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Discard error: ${e.message}")
            } finally {
                audioRecord = null
                synchronized(pcmBuffer) { pcmBuffer.reset() }
            }
        }
    }

    private fun computeNormalizedRms(bytes: ByteArray, length: Int): Float {
        var sumSq = 0.0
        var count = 0
        var i = 0
        while (i + 1 < length) {
            val sample = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)).toShort()
            val norm = sample / 32768.0
            sumSq += norm * norm
            count++
            i += 2
        }
        if (count == 0) return 0.05f
        val rms = sqrt(sumSq / count).toFloat()
        return (rms * 4.5f).coerceIn(0.05f, 1.0f)
    }

    private fun wrapPcmAsWav(
        pcmData: ByteArray,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val totalDataLen = pcmData.size + 36
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(totalDataLen)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1.toShort())
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort((channels * bitsPerSample / 8).toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray())
            putInt(pcmData.size)
        }.array()
        return header + pcmData
    }

    private companion object {
        const val TAG = "AudioRecorderManager"
    }
}
