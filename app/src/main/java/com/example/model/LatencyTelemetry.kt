package com.example.model

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToLong

/**
 * Privacy-safe monotonic latency diagnostics for SAATHI text and voice turns.
 * Never logs or stores API keys, user/assistant message text, or audio payloads.
 */
data class TurnLatencyMetrics(
    val turnId: Long,
    val isVoiceMode: Boolean,
    val languageCode: String,
    val userActionAtNs: Long,
    val viewModelStartAtNs: Long,
    val historyPreparedAtNs: Long,
    val geminiStartAtNs: Long,
    val geminiFirstTokenAtNs: Long,
    val geminiCompleteAtNs: Long,
    val sttLatencyMs: Double = 0.0,
    val backendRequestStartAtNs: Long = 0L,
    val backendRequestFinishAtNs: Long = 0L,
    val ttsFirstAudioAtNs: Long = 0L,
    val audioPlaybackStartAtNs: Long = 0L,
    val segmentCount: Int = 1,
    val succeeded: Boolean = true
) {
    /** ViewModel + history preparation overhead before Gemini request starts (ms). */
    val preRequestOverheadMs: Double
        get() = nanosToMs(geminiStartAtNs - userActionAtNs)

    /** Text or Voice Time-To-First-Token from user action (ms). */
    val textTtftMs: Double
        get() = if (geminiFirstTokenAtNs > 0L) nanosToMs(geminiFirstTokenAtNs - userActionAtNs) else 0.0

    /** Pure Gemini network/generation Time-To-First-Token (ms). */
    val geminiTtftMs: Double
        get() = if (geminiFirstTokenAtNs > 0L && geminiStartAtNs > 0L) {
            nanosToMs(geminiFirstTokenAtNs - geminiStartAtNs)
        } else 0.0

    /** Total text response completion time from user action (ms). */
    val totalTextResponseMs: Double
        get() = if (geminiCompleteAtNs > 0L) nanosToMs(geminiCompleteAtNs - userActionAtNs) else 0.0

    /** Gemini voice-response generation latency (ms). */
    val geminiVoiceResponseMs: Double
        get() = if (geminiCompleteAtNs > 0L && geminiStartAtNs > 0L) {
            nanosToMs(geminiCompleteAtNs - geminiStartAtNs)
        } else 0.0

    /** Backend TTS HTTP request-to-first-audio-chunk latency (ms). */
    val ttsFirstAudioLatencyMs: Double
        get() = if (ttsFirstAudioAtNs > 0L && backendRequestStartAtNs > 0L) {
            nanosToMs(ttsFirstAudioAtNs - backendRequestStartAtNs)
        } else 0.0

    /** End-to-end voice turn latency from user speech completion to audio playback start (ms). */
    val totalVoiceTurnLatencyMs: Double
        get() = when {
            audioPlaybackStartAtNs > 0L -> nanosToMs(audioPlaybackStartAtNs - userActionAtNs) + sttLatencyMs
            ttsFirstAudioAtNs > 0L -> nanosToMs(ttsFirstAudioAtNs - userActionAtNs) + sttLatencyMs
            else -> totalTextResponseMs + sttLatencyMs
        }

    companion object {
        fun nanosToMs(deltaNs: Long): Double {
            if (deltaNs <= 0L) return 0.0
            return (deltaNs / 10_000L).toDouble() / 100.0
        }
    }
}

data class LatencyBenchmarkSummary(
    val textTurnCount: Int,
    val voiceTurnCount: Int,
    val medianTextTtftMs: Double,
    val medianTotalTextResponseMs: Double,
    val medianGeminiVoiceLatencyMs: Double,
    val medianTtsFirstAudioMs: Double,
    val medianTotalVoiceTurnMs: Double,
    val failedRequests: Int,
    val duplicateMessages: Int
)

class SaathiLatencyRecorder(
    private val nanoClock: () -> Long = { System.nanoTime() }
) {
    private val recordedTurns = CopyOnWriteArrayList<TurnLatencyMetrics>()

    fun nowNs(): Long = nanoClock()

    fun recordTurn(metrics: TurnLatencyMetrics) {
        recordedTurns.add(metrics)
        while (recordedTurns.size > MAX_HISTORY) {
            recordedTurns.removeAt(0)
        }
        Log.d(
            TAG,
            "Turn#${metrics.turnId} mode=${if (metrics.isVoiceMode) "VOICE" else "TEXT"} " +
                "lang=${metrics.languageCode} preOverheadMs=${metrics.preRequestOverheadMs} " +
                "ttftMs=${metrics.textTtftMs} geminiTotalMs=${metrics.geminiVoiceResponseMs} " +
                "ttsFirstAudioMs=${metrics.ttsFirstAudioLatencyMs} " +
                "totalTurnMs=${if (metrics.isVoiceMode) metrics.totalVoiceTurnLatencyMs else metrics.totalTextResponseMs}"
        )
    }

    fun getRecordedTurns(): List<TurnLatencyMetrics> = recordedTurns.toList()

    fun clear() {
        recordedTurns.clear()
    }

    fun buildSummary(
        failedRequests: Int = recordedTurns.count { !it.succeeded },
        duplicateMessages: Int = 0
    ): LatencyBenchmarkSummary {
        val textTurns = recordedTurns.filter { !it.isVoiceMode && it.succeeded }
        val voiceTurns = recordedTurns.filter { it.isVoiceMode && it.succeeded }
        return LatencyBenchmarkSummary(
            textTurnCount = textTurns.size,
            voiceTurnCount = voiceTurns.size,
            medianTextTtftMs = medianOf(textTurns.map { it.textTtftMs }),
            medianTotalTextResponseMs = medianOf(textTurns.map { it.totalTextResponseMs }),
            medianGeminiVoiceLatencyMs = medianOf(voiceTurns.map { it.geminiVoiceResponseMs }),
            medianTtsFirstAudioMs = medianOf(voiceTurns.map { it.ttsFirstAudioLatencyMs }),
            medianTotalVoiceTurnMs = medianOf(voiceTurns.map { it.totalVoiceTurnLatencyMs }),
            failedRequests = failedRequests,
            duplicateMessages = duplicateMessages
        )
    }

    companion object {
        private const val TAG = "SaathiLatency"
        private const val MAX_HISTORY = 60

        fun medianOf(values: List<Double>): Double {
            if (values.isEmpty()) return 0.0
            val sorted = values.sorted()
            val mid = sorted.size / 2
            val raw = if (sorted.size % 2 == 1) {
                sorted[mid]
            } else {
                (sorted[mid - 1] + sorted[mid]) / 2.0
            }
            return (raw * 100.0).roundToLong() / 100.0
        }
    }
}
