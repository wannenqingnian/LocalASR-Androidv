package com.example.localasr.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.example.localasr.model.RecognitionMode
import com.example.localasr.model.SpeechModel
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

interface SpeechRecorderListener {
    fun onReady(startedAt: Long)
    fun onTranscriptChanged(text: String)
    fun onSegmentFinalized(text: String) = Unit
    fun onFinished(text: String, startedAt: Long, endedAt: Long)
    fun onError(message: String)
}

class SpeechRecorder(
    private val context: Context,
    private val model: SpeechModel,
    private val modelDirectory: File,
    private val speakerModelDirectory: File?,
    private val listener: SpeechRecorderListener,
) {
    @Volatile
    private var running = false
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null
    private val committed = StringBuilder()
    private var startedAt = 0L
    private var speakerClusterer: SpeakerClusterer? = null
    private var speechDetector: SpeechActivityDetector? = null
    private var overlapDetector: OverlapSpeechDetector? = null

    fun start() {
        check(!running) { "录音已在运行" }
        running = true
        worker = thread(name = "asr-recorder") {
            try {
                speakerClusterer = speakerModelDirectory?.let {
                    runCatching { SpeakerClusterer(File(it, "speaker.onnx")) }.getOrNull()
                }
                speechDetector = speakerModelDirectory?.let {
                    runCatching { SpeechActivityDetector(File(it, "vad.onnx")) }.getOrNull()
                }
                overlapDetector = speakerModelDirectory?.let {
                    runCatching {
                        OverlapSpeechDetector(
                            segmentationModel = File(it, "overlap.onnx"),
                            embeddingModel = File(it, "speaker.onnx"),
                        )
                    }.getOrNull()
                }
                when (model.mode) {
                    RecognitionMode.STREAMING -> startStreaming()
                    RecognitionMode.OFFLINE -> startOffline()
                }
            } catch (error: Exception) {
                running = false
                releaseRecorder()
                listener.onError(error.message ?: "语音识别启动失败")
            } finally {
                speakerClusterer?.release()
                speakerClusterer = null
                speechDetector?.release()
                speechDetector = null
                overlapDetector?.release()
                overlapDetector = null
            }
        }
    }

    fun stop() {
        running = false
        try {
            audioRecord?.stop()
        } catch (_: IllegalStateException) {
            // The worker may have already stopped the recorder.
        }
    }

    fun isRunning(): Boolean = running

    private fun startStreaming() {
        val recognizer = createOnlineRecognizer()
        if (!running) {
            recognizer.release()
            finishCancelled()
            return
        }
        val recorder = try {
            beginRecording()
        } catch (error: Exception) {
            recognizer.release()
            throw error
        }
        recognizeStreaming(recognizer, recorder)
    }

    private fun startOffline() {
        val recognizer = createOfflineRecognizer()
        val vad = try {
            createVad()
        } catch (error: Exception) {
            recognizer.release()
            throw error
        }
        if (!running) {
            vad.release()
            recognizer.release()
            finishCancelled()
            return
        }
        val recorder = try {
            beginRecording()
        } catch (error: Exception) {
            vad.release()
            recognizer.release()
            throw error
        }
        recognizeOffline(recognizer, vad, recorder)
    }

    private fun beginRecording(): AudioRecord {
        val recorder = createAudioRecord()
        audioRecord = recorder
        committed.clear()
        startedAt = System.currentTimeMillis()
        recorder.startRecording()
        listener.onReady(startedAt)
        return recorder
    }

    private fun finishCancelled() {
        val cancelledAt = System.currentTimeMillis()
        listener.onFinished("", cancelledAt, cancelledAt)
    }

    private fun recognizeStreaming(recognizer: OnlineRecognizer, recorder: AudioRecord) {
        val stream = recognizer.createStream()
        val buffer = ShortArray(SAMPLE_RATE / 10)
        val segmentAudio = mutableListOf<FloatArray>()
        var segmentSampleCount = 0
        var segmentSquaredSum = 0.0
        var currentText = ""
        try {
            while (running) {
                val count = recorder.read(buffer, 0, buffer.size)
                if (count <= 0) continue
                val samples = FloatArray(count) { buffer[it] / 32768.0f }
                segmentAudio += samples
                segmentSampleCount += samples.size
                for (sample in samples) segmentSquaredSum += sample * sample
                stream.acceptWaveform(samples, SAMPLE_RATE)
                while (recognizer.isReady(stream)) recognizer.decode(stream)

                val endpoint = recognizer.isEndpoint(stream)
                currentText = recognizer.getResult(stream).text.trim()
                if (endpoint) {
                    val padding = FloatArray((SAMPLE_RATE * 0.8f).toInt())
                    stream.acceptWaveform(padding, SAMPLE_RATE)
                    while (recognizer.isReady(stream)) recognizer.decode(stream)
                    currentText = recognizer.getResult(stream).text.trim()
                }

                listener.onTranscriptChanged(
                    combinedText(currentText, hasAudibleSignal(segmentSquaredSum, segmentSampleCount)),
                )
                if (endpoint) {
                    val speakerSamples = joinSamples(segmentAudio, segmentSampleCount)
                    if (commit(currentText, speakerSamples)) listener.onSegmentFinalized(committed.toString())
                    segmentAudio.clear()
                    segmentSampleCount = 0
                    segmentSquaredSum = 0.0
                    currentText = ""
                    recognizer.reset(stream)
                    listener.onTranscriptChanged(committed.toString())
                }
            }

            val padding = FloatArray((SAMPLE_RATE * 0.8f).toInt())
            stream.acceptWaveform(padding, SAMPLE_RATE)
            stream.inputFinished()
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            val finalText = recognizer.getResult(stream).text.trim().ifBlank { currentText }
            if (commit(finalText, joinSamples(segmentAudio, segmentSampleCount))) {
                listener.onSegmentFinalized(committed.toString())
            }
            listener.onFinished(committed.toString(), startedAt, System.currentTimeMillis())
        } catch (error: Exception) {
            if (running) {
                listener.onError(error.message ?: "录音处理中断")
            } else {
                if (commit(currentText, joinSamples(segmentAudio, segmentSampleCount))) {
                    listener.onSegmentFinalized(committed.toString())
                }
                listener.onFinished(committed.toString(), startedAt, System.currentTimeMillis())
            }
        } finally {
            running = false
            releaseRecorder()
            stream.release()
            recognizer.release()
        }
    }

    private fun recognizeOffline(recognizer: OfflineRecognizer, vad: Vad, recorder: AudioRecord) {
        val inferenceError = AtomicReference<Throwable?>(null)
        val inferenceQueue = ArrayBlockingQueue<Runnable>(OFFLINE_QUEUE_CAPACITY)
        val inference = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            inferenceQueue,
        ).apply {
            rejectedExecutionHandler = java.util.concurrent.RejectedExecutionHandler { task, executor ->
                if (!executor.isShutdown) inferenceQueue.put(task)
            }
        }
        val buffer = ShortArray(VAD_WINDOW_SIZE)
        var recordingError: Throwable? = null

        fun submitVadSegments() {
            while (!vad.empty() && inferenceError.get() == null) {
                val segment = vad.front()
                val samples = segment.samples
                vad.pop()
                val task = Runnable {
                    if (inferenceError.get() != null) return@Runnable
                    try {
                        val text = decodeOffline(recognizer, samples)
                        if (commit(text, samples)) {
                            listener.onSegmentFinalized(committed.toString())
                            listener.onTranscriptChanged(committed.toString())
                        }
                    } catch (error: Throwable) {
                        inferenceError.compareAndSet(null, error)
                        running = false
                        try {
                            recorder.stop()
                        } catch (_: IllegalStateException) {
                            // The recording loop may already be stopping.
                        }
                    }
                }
                if (inferenceError.get() == null) inference.execute(task)
            }
        }

        try {
            while (running && inferenceError.get() == null) {
                val count = recorder.read(buffer, 0, buffer.size)
                if (count <= 0) continue
                vad.acceptWaveform(FloatArray(count) { buffer[it] / 32768.0f })
                submitVadSegments()
            }
        } catch (error: Throwable) {
            if (running && inferenceError.get() == null) recordingError = error
        } finally {
            if (inferenceError.get() == null && recordingError == null) {
                vad.flush()
                submitVadSegments()
            }
            inference.shutdown()
            if (!inference.awaitTermination(OFFLINE_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                inference.shutdownNow()
                recordingError = IllegalStateException("最后一段识别超时")
            }
            val error = inferenceError.get() ?: recordingError
            if (error == null) {
                listener.onFinished(committed.toString(), startedAt, System.currentTimeMillis())
            } else {
                listener.onError(error.message ?: "分段识别中断")
            }
            running = false
            releaseRecorder()
            vad.release()
            recognizer.release()
        }
    }

    private fun decodeOffline(recognizer: OfflineRecognizer, samples: FloatArray): String {
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    private fun commit(text: String, samples: FloatArray): Boolean {
        if (text.isBlank()) return false
        if (!containsSpeech(samples)) return false
        if (committed.isNotEmpty()) committed.append('\n')
        if (hasOverlappingSpeech(samples)) {
            committed.append("多人同时说话：")
        } else {
            val speaker = assignSpeaker(samples)
            if (speaker != null) committed.append("说话人").append(speaker).append('：')
        }
        committed.append(text)
        return true
    }

    private fun hasOverlappingSpeech(samples: FloatArray): Boolean {
        val detector = overlapDetector ?: return false
        return runCatching { detector.hasOverlap(samples) }.getOrElse {
            runCatching { detector.release() }
            overlapDetector = null
            false
        }
    }

    private fun assignSpeaker(samples: FloatArray): Int? {
        val clusterer = speakerClusterer ?: return null
        return runCatching { clusterer.assign(samples) }.getOrElse {
            runCatching { clusterer.release() }
            speakerClusterer = null
            null
        }
    }

    private fun combinedText(partial: String, hasAudibleSignal: Boolean): String {
        if (partial.isBlank() || !hasAudibleSignal) return committed.toString()
        val line = "识别中：$partial"
        return if (committed.isEmpty()) line else "$committed\n$line"
    }

    private fun containsSpeech(samples: FloatArray): Boolean {
        val detector = speechDetector ?: return hasAudibleSignal(samples)
        return runCatching { detector.hasSpeech(samples) }.getOrElse {
            runCatching { detector.release() }
            speechDetector = null
            hasAudibleSignal(samples)
        }
    }

    private fun hasAudibleSignal(samples: FloatArray): Boolean {
        var squaredSum = 0.0
        for (sample in samples) squaredSum += sample * sample
        return hasAudibleSignal(squaredSum, samples.size)
    }

    private fun hasAudibleSignal(squaredSum: Double, sampleCount: Int): Boolean =
        sampleCount > 0 && squaredSum / sampleCount >= MIN_AUDIBLE_POWER

    private fun joinSamples(chunks: List<FloatArray>, totalSize: Int): FloatArray {
        if (totalSize == 0) return FloatArray(0)
        val result = FloatArray(totalSize)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(result, offset)
            offset += chunk.size
        }
        return result
    }

    private fun createOnlineRecognizer(): OnlineRecognizer {
        val encoder = File(modelDirectory, "encoder.int8.onnx")
        val decoder = File(modelDirectory, "decoder.int8.onnx")
        val tokens = File(modelDirectory, "tokens.txt")
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                paraformer = OnlineParaformerModelConfig(
                    encoder = encoder.absolutePath,
                    decoder = decoder.absolutePath,
                ),
                tokens = tokens.absolutePath,
                numThreads = 2,
                modelType = "paraformer",
            ),
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0.0f),
                rule2 = EndpointRule(true, 1.2f, 0.0f),
                rule3 = EndpointRule(false, 0.0f, 20.0f),
            ),
            enableEndpoint = true,
        )
        return OnlineRecognizer(assetManager = null, config = config)
    }

    private fun createOfflineRecognizer(): OfflineRecognizer {
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                paraformer = OfflineParaformerModelConfig(
                    model = File(modelDirectory, "model.int8.onnx").absolutePath,
                ),
                tokens = File(modelDirectory, "tokens.txt").absolutePath,
                numThreads = 2,
                modelType = "paraformer",
            ),
        )
        return OfflineRecognizer(assetManager = null, config = config)
    }

    private fun createVad(): Vad {
        val config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = File(modelDirectory, "silero_vad.int8.onnx").absolutePath,
                threshold = 0.5f,
                minSilenceDuration = 0.8f,
                minSpeechDuration = 0.25f,
                windowSize = VAD_WINDOW_SIZE,
                maxSpeechDuration = 15f,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
        )
        return Vad(assetManager = null, config = config)
    }

    private fun createAudioRecord(): AudioRecord {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw IllegalStateException("没有麦克风权限")
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) throw IllegalStateException("设备不支持 16 kHz 单声道录音")
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer * 2,
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException("麦克风初始化失败")
        }
        return recorder
    }

    private fun releaseRecorder() {
        try {
            audioRecord?.release()
        } catch (_: Exception) {
            // Nothing else can be done while releasing audio hardware.
        }
        audioRecord = null
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val VAD_WINDOW_SIZE = 512
        private const val OFFLINE_QUEUE_CAPACITY = 4
        private const val OFFLINE_SHUTDOWN_TIMEOUT_SECONDS = 120L
        private const val MIN_AUDIBLE_POWER = 0.000016
    }
}
