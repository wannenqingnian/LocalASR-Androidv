package com.example.localasr.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import java.io.File
import java.util.Locale
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
    private val modelDirectory: File,
    private val listener: SpeechRecorderListener,
) {
    @Volatile
    private var running = false
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null
    private val committed = StringBuilder()
    private var startedAt = 0L

    fun start() {
        check(!running) { "录音已在运行" }
        running = true
        worker = thread(name = "asr-recorder") {
            try {
                val recognizer = createRecognizer()
                if (!running) {
                    recognizer.release()
                    val cancelledAt = System.currentTimeMillis()
                    listener.onFinished("", cancelledAt, cancelledAt)
                    return@thread
                }
                val recorder = createAudioRecord()
                audioRecord = recorder
                committed.clear()
                startedAt = System.currentTimeMillis()
                recorder.startRecording()
                listener.onReady(startedAt)
                recognize(recognizer, recorder)
            } catch (error: Exception) {
                running = false
                releaseRecorder()
                listener.onError(error.message ?: "语音识别启动失败")
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

    private fun recognize(recognizer: OnlineRecognizer, recorder: AudioRecord) {
        val stream = recognizer.createStream()
        val buffer = ShortArray(SAMPLE_RATE / 10)
        var currentText = ""
        try {
            while (running) {
                val count = recorder.read(buffer, 0, buffer.size)
                if (count <= 0) continue
                val samples = FloatArray(count) { buffer[it] / 32768.0f }
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

                listener.onTranscriptChanged(combinedText(currentText))
                if (endpoint) {
                    if (commit(currentText)) listener.onSegmentFinalized(committed.toString())
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
            if (commit(finalText)) listener.onSegmentFinalized(committed.toString())
            listener.onFinished(committed.toString(), startedAt, System.currentTimeMillis())
        } catch (error: Exception) {
            if (running) {
                listener.onError(error.message ?: "录音处理中断")
            } else {
                if (commit(currentText)) listener.onSegmentFinalized(committed.toString())
                listener.onFinished(committed.toString(), startedAt, System.currentTimeMillis())
            }
        } finally {
            running = false
            releaseRecorder()
            stream.release()
            recognizer.release()
        }
    }

    private fun commit(text: String): Boolean {
        if (text.isBlank()) return false
        if (committed.isNotEmpty()) committed.append('\n')
        committed.append('[').append(elapsed()).append("] ").append(text)
        return true
    }

    private fun combinedText(partial: String): String {
        if (partial.isBlank()) return committed.toString()
        val line = "[${elapsed()}] $partial"
        return if (committed.isEmpty()) line else "$committed\n$line"
    }

    private fun elapsed(): String {
        val seconds = ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(0L)
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
    }

    private fun createRecognizer(): OnlineRecognizer {
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
    }
}
