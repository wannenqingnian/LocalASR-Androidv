package com.example.localasr.asr

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File

class OverlapSpeechDetector(segmentationModel: File, embeddingModel: File) {
    private val diarization = OfflineSpeakerDiarization(
        assetManager = null,
        config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                    model = segmentationModel.absolutePath,
                    windowShiftRatio = 0.1f,
                ),
                numThreads = 2,
            ),
            embedding = SpeakerEmbeddingExtractorConfig(
                model = embeddingModel.absolutePath,
                numThreads = 2,
            ),
            clustering = FastClusteringConfig(
                numClusters = -1,
                threshold = 0.5f,
            ),
            minDurationOn = 0.2f,
            minDurationOff = 0.5f,
        ),
    )

    fun hasOverlap(samples: FloatArray): Boolean {
        if (samples.size < MIN_SAMPLES) return false
        val segments = diarization.process(samples)
        for (leftIndex in segments.indices) {
            val left = segments[leftIndex]
            for (rightIndex in leftIndex + 1 until segments.size) {
                val right = segments[rightIndex]
                if (left.speaker == right.speaker) continue
                val overlap = minOf(left.end, right.end) - maxOf(left.start, right.start)
                if (overlap >= MIN_OVERLAP_SECONDS) return true
            }
        }
        return false
    }

    fun release() {
        diarization.release()
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val MIN_SAMPLES = SAMPLE_RATE * 2
        private const val MIN_OVERLAP_SECONDS = 0.2f
    }
}
