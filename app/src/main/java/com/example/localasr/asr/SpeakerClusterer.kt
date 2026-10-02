package com.example.localasr.asr

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File
import kotlin.math.sqrt

class SpeakerClusterer(modelFile: File) {
    private data class Speaker(var center: FloatArray, var sampleCount: Int)

    private val extractor = SpeakerEmbeddingExtractor(
        assetManager = null,
        config = SpeakerEmbeddingExtractorConfig(
            model = modelFile.absolutePath,
            numThreads = 2,
        ),
    )
    private val speakers = mutableListOf<Speaker>()
    private var lastSpeaker = 1

    fun assign(samples: FloatArray): Int {
        if (samples.size < MIN_SAMPLES) return lastSpeaker
        val embedding = extract(samples) ?: return lastSpeaker
        return assignEmbedding(embedding)
    }

    fun assignReliable(samples: FloatArray): Int? {
        if (samples.size < MIN_SAMPLES) return null
        val embedding = extract(samples) ?: return null
        return assignEmbedding(embedding)
    }

    private fun assignEmbedding(embedding: FloatArray): Int {
        val best = speakers.indices.maxByOrNull { cosine(embedding, speakers[it].center) }
        val speakerIndex = if (
            best != null && cosine(embedding, speakers[best].center) >= SIMILARITY_THRESHOLD
        ) {
            updateCenter(speakers[best], embedding)
            best
        } else {
            speakers += Speaker(embedding.copyOf(), 1)
            speakers.lastIndex
        }
        lastSpeaker = speakerIndex + 1
        return lastSpeaker
    }

    fun release() {
        extractor.release()
    }

    private fun extract(samples: FloatArray): FloatArray? {
        val stream = extractor.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            stream.inputFinished()
            if (extractor.isReady(stream)) normalize(extractor.compute(stream)) else null
        } finally {
            stream.release()
        }
    }

    private fun updateCenter(speaker: Speaker, embedding: FloatArray) {
        val nextCount = speaker.sampleCount + 1
        for (index in speaker.center.indices) {
            speaker.center[index] =
                (speaker.center[index] * speaker.sampleCount + embedding[index]) / nextCount
        }
        speaker.center = normalize(speaker.center)
        speaker.sampleCount = nextCount
    }

    private fun cosine(left: FloatArray, right: FloatArray): Float {
        var value = 0f
        for (index in left.indices) value += left[index] * right[index]
        return value
    }

    private fun normalize(values: FloatArray): FloatArray {
        var squaredLength = 0f
        for (value in values) squaredLength += value * value
        val length = sqrt(squaredLength)
        if (length > 0f) {
            for (index in values.indices) values[index] /= length
        }
        return values
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val MIN_SAMPLES = SAMPLE_RATE * 3 / 2
        private const val SIMILARITY_THRESHOLD = 0.35f
    }
}
