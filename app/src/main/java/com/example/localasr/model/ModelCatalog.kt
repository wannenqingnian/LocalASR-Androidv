package com.example.localasr.model

enum class DownloadSource(val displayName: String, val huggingFaceHost: String) {
    DOMESTIC(
        displayName = "国内镜像",
        huggingFaceHost = "https://hf-mirror.com",
    ),
    OFFICIAL(
        displayName = "官方原站",
        huggingFaceHost = "https://huggingface.co",
    ),
}

enum class RecognitionMode {
    STREAMING,
    OFFLINE,
}

data class ModelFile(
    val remoteName: String,
    val localName: String,
    val size: Long,
    val sha256: String,
    val directUrl: String? = null,
    val domesticUrl: String? = null,
    val officialUrl: String? = null,
)

interface DownloadableModel {
    val id: String
    val name: String
    val description: String
    val repository: String
    val files: List<ModelFile>

    val totalBytes: Long
        get() = files.sumOf { it.size }

    fun downloadUrl(source: DownloadSource, file: ModelFile): String {
        return when (source) {
            DownloadSource.DOMESTIC -> file.domesticUrl
            DownloadSource.OFFICIAL -> file.officialUrl
        } ?: file.directUrl
        ?: "${source.huggingFaceHost}/$repository/resolve/main/${file.remoteName}"
    }

    fun repositoryUrl(source: DownloadSource): String = when (source) {
        DownloadSource.DOMESTIC -> files.firstOrNull()?.domesticUrl
        DownloadSource.OFFICIAL -> files.firstOrNull()?.officialUrl
    } ?: if (repository.startsWith("http")) repository else "${source.huggingFaceHost}/$repository"
}

data class SpeechModel(
    override val id: String,
    override val name: String,
    override val description: String,
    override val repository: String,
    val mode: RecognitionMode,
    override val files: List<ModelFile>,
) : DownloadableModel

data class AuxiliaryModel(
    override val id: String,
    override val name: String,
    override val description: String,
    override val repository: String,
    override val files: List<ModelFile>,
) : DownloadableModel

object ModelCatalog {
    val streamingParaformer = SpeechModel(
        id = "streaming-paraformer-bilingual-zh-en-int8",
        name = "通用普通话 / 英语",
        description = "实时显示文字，兼顾常见中文口音，约 237 MB",
        repository = "csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en",
        mode = RecognitionMode.STREAMING,
        files = listOf(
            ModelFile(
                remoteName = "encoder.int8.onnx",
                localName = "encoder.int8.onnx",
                size = 165_462_184L,
                sha256 = "81a70226a8934e6ed92aa1d4fc486b428b5398e2f2619ed4897b7294cab90e9a",
            ),
            ModelFile(
                remoteName = "decoder.int8.onnx",
                localName = "decoder.int8.onnx",
                size = 71_664_561L,
                sha256 = "f3cca9f77bb9d93c8fcbfb63ae617b6b1ee96818df3aa3b151c40658fe38594f",
            ),
            ModelFile(
                remoteName = "tokens.txt",
                localName = "tokens.txt",
                size = 75_756L,
                sha256 = "59aba8873a2ed1e122c25fee421e25f283b63290efbde85c1f01a853d83cb6e6",
            ),
        ),
    )

    val streamingTrilingual = SpeechModel(
        id = "streaming-paraformer-trilingual-zh-cantonese-en-int8",
        name = "普通话 / 粤语 / 英语",
        description = "实时显示文字，增强粤语识别，约 239 MB",
        repository = "csukuangfj/sherpa-onnx-streaming-paraformer-trilingual-zh-cantonese-en",
        mode = RecognitionMode.STREAMING,
        files = listOf(
            ModelFile(
                remoteName = "encoder.int8.onnx",
                localName = "encoder.int8.onnx",
                size = 166_362_800L,
                sha256 = "6047a644b41b236d9d8e89e3b94ef39d1b7037daab028131b722ca52e10b0357",
            ),
            ModelFile(
                remoteName = "decoder.int8.onnx",
                localName = "decoder.int8.onnx",
                size = 72_062_549L,
                sha256 = "545427acf508452b7d89969be082c8128c681e3432ff43aef09f6159f4b61a7e",
            ),
            ModelFile(
                remoteName = "tokens.txt",
                localName = "tokens.txt",
                size = 81_289L,
                sha256 = "45b31504211675dd52aa88f998a6f6161703a2834e86760c1cda645a22538085",
            ),
        ),
    )

    val offlineChuan = SpeechModel(
        id = "offline-paraformer-chuan-int8",
        name = "四川话 / 重庆话",
        description = "停顿后显示整段文字，针对川渝方言优化，约 239 MB",
        repository = "csukuangfj/sherpa-onnx-paraformer-zh-int8-2025-10-07",
        mode = RecognitionMode.OFFLINE,
        files = listOf(
            ModelFile(
                remoteName = "model.int8.onnx",
                localName = "model.int8.onnx",
                size = 238_429_929L,
                sha256 = "53813ee1d41722cc6370a571c887e6d0b391d25b8312cf714a31af85ea603812",
            ),
            ModelFile(
                remoteName = "tokens.txt",
                localName = "tokens.txt",
                size = 75_756L,
                sha256 = "59aba8873a2ed1e122c25fee421e25f283b63290efbde85c1f01a853d83cb6e6",
            ),
            ModelFile(
                remoteName = "silero_vad.int8.onnx",
                localName = "silero_vad.int8.onnx",
                size = 212_860L,
                sha256 = "c36d490aff5ab924ca6c7aeec4d8f6bd3d22db6fa17611b9c5b17eae58ac3a20",
                directUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.int8.onnx",
            ),
        ),
    )

    val speakerEmbedding = AuxiliaryModel(
        id = "speaker-embedding-campplus-zh",
        name = "中文音色区分",
        description = "过滤静音、标记重叠讲话，并按音色区分说话人，约 34.5 MB",
        repository = "https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models",
        files = listOf(
            ModelFile(
                remoteName = "3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx",
                localName = "speaker.onnx",
                size = 28_281_138L,
                sha256 = "f682b514c05d947ee3fa91cd6ec6c5c7543479a128373fa29b1faedccd21fd11",
                domesticUrl = "https://gh-proxy.com/https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx",
                officialUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx",
            ),
            ModelFile(
                remoteName = "silero_vad.int8.onnx",
                localName = "vad.onnx",
                size = 212_860L,
                sha256 = "c36d490aff5ab924ca6c7aeec4d8f6bd3d22db6fa17611b9c5b17eae58ac3a20",
                domesticUrl = "https://gh-proxy.com/https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.int8.onnx",
                officialUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.int8.onnx",
            ),
            ModelFile(
                remoteName = "model.onnx",
                localName = "overlap.onnx",
                size = 5_992_913L,
                sha256 = "220ad67ca923bef2fa91f2390c786097bf305bceb5e261d4af67b38e938e1079",
                domesticUrl = "https://hf-mirror.com/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/main/model.onnx",
                officialUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/main/model.onnx",
            ),
        ),
    )

    val models = listOf(streamingParaformer, streamingTrilingual, offlineChuan)
    val downloadableModels: List<DownloadableModel> = models + speakerEmbedding

    fun find(id: String?): SpeechModel = models.firstOrNull { it.id == id } ?: streamingParaformer

    fun findDownloadable(id: String?): DownloadableModel? = downloadableModels.firstOrNull { it.id == id }
}
