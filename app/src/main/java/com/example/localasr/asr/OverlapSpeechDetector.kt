package com.example.localasr.asr

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File

data class OverlapAnalysis(val participantSamples: List<FloatArray>)

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

    fun analyze(samples: FloatArray): OverlapAnalysis? {
        if (samples.size < MIN_SAMPLES) return null
        val segments = diarization.process(samples)
        val overlappingSpeakers = linkedSetOf<Int>()
        for (leftIndex in segments.indices) {
            val left = segments[leftIndex]
            for (rightIndex in leftIndex + 1 until segments.size) {
                val right = segments[rightIndex]
                if (left.speaker == right.speaker) continue
                val overlap = minOf(left.end, right.end) - maxOf(left.start, right.start)
                if (overlap >= MIN_OVERLAP_SECONDS) {
                    overlappingSpeakers += left.speaker
                    overlappingSpeakers += right.speaker
                }
            }
        }
        if (overlappingSpeakers.isEmpty()) return null
        val participantSamples = overlappingSpeakers.map { speaker ->
            val speakerSegments = segments.filter { it.speaker == speaker }
            val otherSegments = segments.filter { it.speaker != speaker }
            val cleanIntervals = speakerSegments.flatMap { segment ->
                var intervals = listOf(segment.start to segment.end)
                for (other in otherSegments) {
                    intervals = intervals.flatMap { (start, end) ->
                        when {
                            other.end <= start || other.start >= end -> listOf(start to end)
                            else -> buildList {
                                if (other.start > start) add(start to minOf(other.start, end))
                                if (other.end < end) add(maxOf(other.end, start) to end)
                            }
                        }
                    }
                }
                intervals
            }
            joinIntervals(samples, cleanIntervals)
        }
        return OverlapAnalysis(participantSamples)
    }

    fun release() {
        diarization.release()
    }

    private fun joinIntervals(samples: FloatArray, intervals: List<Pair<Float, Float>>): FloatArray {
        val ranges = intervals.mapNotNull { (start, end) ->
            val from = (start * SAMPLE_RATE).toInt().coerceIn(0, samples.size)
            val until = (end * SAMPLE_RATE).toInt().coerceIn(from, samples.size)
            if (until > from) from until until else null
        }
        val result = FloatArray(ranges.sumOf { it.last - it.first + 1 })
        var offset = 0
        for (range in ranges) {
            samples.copyInto(result, offset, range.first, range.last + 1)
            offset += range.last - range.first + 1
        }
        return result
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val MIN_SAMPLES = SAMPLE_RATE * 2
        private const val MIN_OVERLAP_SECONDS = 0.2f
    }
}
