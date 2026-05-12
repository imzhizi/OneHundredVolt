package com.ohv.android.platform

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.pow
import kotlin.math.tanh

class TanhLoudnessAudioProcessor : BaseAudioProcessor() {

    private var enabled = false
    private val gain = 10.0.pow(6.0 / 20.0).toFloat() // 6dB ≈ 1.995

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat = inputAudioFormat

    override fun isActive(): Boolean = enabled

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!enabled) {
            replaceOutputBuffer(inputBuffer.remaining()).put(inputBuffer).flip()
            return
        }

        val remaining = inputBuffer.remaining()
        val output = replaceOutputBuffer(remaining)
        val encoding = inputAudioFormat.encoding

        when (encoding) {
            android.media.AudioFormat.ENCODING_PCM_FLOAT -> {
                while (inputBuffer.hasRemaining()) {
                    output.putFloat(tanh(inputBuffer.float * gain))
                }
            }
            android.media.AudioFormat.ENCODING_PCM_16BIT -> {
                while (inputBuffer.hasRemaining()) {
                    val sample = inputBuffer.short.toFloat() / Short.MAX_VALUE
                    output.putShort((tanh(sample * gain) * Short.MAX_VALUE).toInt().toShort())
                }
            }
            else -> output.put(inputBuffer)
        }

        output.flip()
    }
}
