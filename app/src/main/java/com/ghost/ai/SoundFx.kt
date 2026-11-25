package com.ghost.ai

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Synthesised sci-fi sound effects. Generated in code so the app needs no audio assets.
 */
object SoundFx {

    enum class Kind { ACTIVATE, DONE, ERROR }

    private const val SAMPLE_RATE = 22050
    private val handler = Handler(Looper.getMainLooper())

    /** Approximate length of the sound, so callers can wait before opening the mic. */
    fun durationMs(kind: Kind): Long = when (kind) {
        Kind.ACTIVATE -> 380L
        Kind.DONE -> 300L
        Kind.ERROR -> 300L
    }

    fun play(kind: Kind) {
        try {
            val samples = render(kind)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(samples, 0, samples.size)
            track.play()
            val lengthMs = samples.size * 1000L / SAMPLE_RATE
            handler.postDelayed({
                try {
                    track.stop()
                } catch (_: Exception) {
                }
                track.release()
            }, lengthMs + 150L)
        } catch (_: Exception) {
            // Sound is decoration only: never let it break the flow.
        }
    }

    private fun render(kind: Kind): ShortArray = when (kind) {
        Kind.ACTIVATE -> Buffer(0.38)
            .also { it.sweep(0.0, 0.34, 300.0, 1600.0, 0.6, shimmer = true) }
            .toPcm()

        Kind.DONE -> Buffer(0.3)
            .also {
                it.sweep(0.0, 0.10, 880.0, 880.0, 0.5, shimmer = false)
                it.sweep(0.10, 0.18, 1320.0, 1320.0, 0.5, shimmer = false)
            }
            .toPcm()

        Kind.ERROR -> Buffer(0.3)
            .also { it.sweep(0.0, 0.28, 260.0, 130.0, 0.6, shimmer = true) }
            .toPcm()
    }

    private class Buffer(seconds: Double) {
        private val data = DoubleArray((SAMPLE_RATE * seconds).toInt())

        fun sweep(
            startSec: Double,
            durSec: Double,
            fromHz: Double,
            toHz: Double,
            volume: Double,
            shimmer: Boolean
        ) {
            val start = (startSec * SAMPLE_RATE).toInt()
            val length = (durSec * SAMPLE_RATE).toInt()
            var phase1 = 0.0
            var phase2 = 0.0
            for (i in 0 until length) {
                val index = start + i
                if (index >= data.size) break
                val t = i.toDouble() / SAMPLE_RATE
                val p = i.toDouble() / length
                val freq = fromHz + (toHz - fromHz) * p * p
                val envelope = min(1.0, t / 0.008) * exp(-3.0 * p)
                val wobble = if (shimmer) 14.0 * sin(2 * PI * 30.0 * t) else 0.0
                phase1 += 2 * PI * freq / SAMPLE_RATE
                phase2 += 2 * PI * (freq * 1.5 + wobble) / SAMPLE_RATE
                data[index] += (sin(phase1) * 0.62 + sin(phase2) * 0.28) * envelope * volume
            }
        }

        fun toPcm(): ShortArray = ShortArray(data.size) {
            (data[it].coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
        }
    }
}
