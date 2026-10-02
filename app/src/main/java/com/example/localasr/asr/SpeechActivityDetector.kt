package com.example.localasr.asr

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

class SpeechActivityDetector(modelFile: File) {
    private val vad = Vad(
        assetManager = null,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = modelFile.absolutePath,
                threshold = 0.6f,
                minSilenceDuration = 0.3f,
                minSpeechDuration = 0.25f,
                windowSize = WINDOW_SIZE,
                maxSpeechDuration = 30f,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
        ),
    )

    fun hasSpeech(samples: FloatArray): Boolean {
        vad.reset()
        return try {
            var offset = 0
            while (offset < samples.size) {
                val count = minOf(WINDOW_SIZE, samples.size - offset)
                val window = FloatArray(WINDOW_SIZE)
                samples.copyInto(window, 0, offset, offset + count)
                vad.acceptWaveform(window)
                if (vad.isSpeechDetected() || !vad.empty()) return true
                offset += count
            }
            vad.flush()
            !vad.empty()
        } finally {
            vad.reset()
        }
    }

    fun release() {
        vad.release()
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val WINDOW_SIZE = 512
    }
}
