package com.example.localasr.model

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class DownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val message: String,
)

sealed interface DownloadResult {
    data object Completed : DownloadResult
    data object Paused : DownloadResult
    data class Failed(val message: String) : DownloadResult
}

class ModelManager(context: Context) {
    private val appContext = context.applicationContext
    private val modelsRoot = appContext.getExternalFilesDir("models")
        ?: File(appContext.filesDir, "models")
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor()
    private val pauseRequested = AtomicBoolean(false)

    val selectedModel: SpeechModel
        get() = ModelCatalog.find(preferences.getString(KEY_SELECTED_MODEL, null))

    val modelDirectory: File
        get() = modelDirectory(selectedModel)

    fun selectModel(model: SpeechModel) {
        preferences.edit().putString(KEY_SELECTED_MODEL, model.id).apply()
    }

    fun modelDirectory(model: SpeechModel): File = File(modelsRoot, model.id)

    fun isInstalled(model: SpeechModel = selectedModel): Boolean {
        val root = modelDirectory(model)
        if (!File(root, READY_MARKER).isFile) return false
        return model.files.all {
            val file = File(root, it.localName)
            file.isFile && file.length() == it.size
        }
    }

    fun downloadedBytes(model: SpeechModel = selectedModel): Long {
        val root = modelDirectory(model)
        return model.files.sumOf { item ->
            val finalFile = File(root, item.localName)
            val partFile = File(root, "${item.localName}.part")
            when {
                finalFile.isFile -> finalFile.length().coerceAtMost(item.size)
                partFile.isFile -> partFile.length().coerceAtMost(item.size)
                else -> 0L
            }
        }
    }

    fun download(
        model: SpeechModel = selectedModel,
        source: DownloadSource,
        onProgress: (DownloadProgress) -> Unit,
        onResult: (DownloadResult) -> Unit,
    ) {
        pauseRequested.set(false)
        executor.execute {
            val root = modelDirectory(model)
            try {
                root.mkdirs()
                File(root, READY_MARKER).delete()
                for (item in model.files) {
                    if (pauseRequested.get()) throw PausedException()
                    val target = File(root, item.localName)
                    if (target.isFile && target.length() == item.size) continue
                    downloadFile(model, source, item, root, target, onProgress)
                }
                onProgress(DownloadProgress(model.totalBytes, model.totalBytes, "正在校验模型…"))
                validateModel(model, root, onProgress)
                File(root, READY_MARKER).writeText("ok\n")
                onResult(DownloadResult.Completed)
            } catch (_: PausedException) {
                onResult(DownloadResult.Paused)
            } catch (error: Exception) {
                onResult(DownloadResult.Failed(error.message ?: "下载失败"))
            }
        }
    }

    fun pause() {
        pauseRequested.set(true)
    }

    fun deleteModel(model: SpeechModel = selectedModel) {
        pause()
        val root = modelDirectory(model)
        if (root.exists()) root.deleteRecursively()
    }

    private fun downloadFile(
        model: SpeechModel,
        source: DownloadSource,
        item: ModelFile,
        root: File,
        target: File,
        onProgress: (DownloadProgress) -> Unit,
    ) {
        val part = File(root, "${item.localName}.part")
        if (part.length() > item.size) part.delete()
        var offset = part.length()
        val connection = URL(model.downloadUrl(source, item)).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0L) connection.setRequestProperty("Range", "bytes=$offset-")
            connection.connect()

            val append = offset > 0L && connection.responseCode == HttpURLConnection.HTTP_PARTIAL
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("服务器返回 ${connection.responseCode}")
            }
            if (!append) {
                part.delete()
                offset = 0L
            }

            RandomAccessFile(part, "rw").use { output ->
                output.seek(offset)
                connection.inputStream.buffered().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                    while (true) {
                        if (pauseRequested.get()) throw PausedException()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        offset += count
                        onProgress(
                            DownloadProgress(
                                downloadedBytes = completedBytesBefore(model, root, item) + offset,
                                totalBytes = model.totalBytes,
                                message = "${source.displayName} · 正在下载 ${item.localName}",
                            ),
                        )
                    }
                }
            }
            if (part.length() != item.size) {
                throw IllegalStateException("${item.localName} 大小不完整，可稍后继续")
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IllegalStateException("无法保存 ${item.localName}")
        } finally {
            connection.disconnect()
        }
    }

    private fun completedBytesBefore(model: SpeechModel, root: File, current: ModelFile): Long {
        var bytes = 0L
        for (item in model.files) {
            if (item == current) break
            val file = File(root, item.localName)
            if (file.isFile && file.length() == item.size) bytes += item.size
        }
        return bytes
    }

    private fun validateModel(model: SpeechModel, root: File, onProgress: (DownloadProgress) -> Unit) {
        model.files.forEachIndexed { index, item ->
            if (pauseRequested.get()) throw PausedException()
            val file = File(root, item.localName)
            val actual = sha256(file)
            if (!actual.equals(item.sha256, ignoreCase = true)) {
                file.delete()
                throw IllegalStateException("${item.localName} 校验失败，请重新下载")
            }
            onProgress(
                DownloadProgress(
                    model.totalBytes,
                    model.totalBytes,
                    "模型校验 ${index + 1}/${model.files.size}",
                ),
            )
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                if (pauseRequested.get()) throw PausedException()
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class PausedException : RuntimeException()

    companion object {
        private const val READY_MARKER = ".ready"
        private const val PREFERENCES = "settings"
        private const val KEY_SELECTED_MODEL = "selected_model_id"
    }
}
