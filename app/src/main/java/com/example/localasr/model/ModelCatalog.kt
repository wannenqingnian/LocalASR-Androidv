package com.example.localasr.model

enum class DownloadSource(val displayName: String, val baseUrl: String) {
    DOMESTIC(
        displayName = "国内镜像",
        baseUrl = "https://hf-mirror.com/csukuangfj/streaming-paraformer-zh/resolve/main",
    ),
    OFFICIAL(
        displayName = "官方原站",
        baseUrl = "https://huggingface.co/csukuangfj/streaming-paraformer-zh/resolve/main",
    ),
}

data class ModelFile(
    val remoteName: String,
    val localName: String,
    val size: Long,
    val sha256: String,
)

data class SpeechModel(
    val id: String,
    val name: String,
    val description: String,
    val files: List<ModelFile>,
) {
    val totalBytes: Long = files.sumOf { it.size }
}

object ModelCatalog {
    val streamingParaformer = SpeechModel(
        id = "streaming-paraformer-bilingual-zh-en-int8",
        name = "中英实时识别 · Paraformer INT8",
        description = "普通话、英语和常见中文口音，完全离线，约 237 MB",
        files = listOf(
            ModelFile(
                remoteName = "model_quant.onnx",
                localName = "encoder.int8.onnx",
                size = 165_450_769L,
                sha256 = "3c62ba3c52a2308c04ce3b0625b7eaef1ab36a13c3b5c790cfd9bc65872b149d",
            ),
            ModelFile(
                remoteName = "decoder_quant.onnx",
                localName = "decoder.int8.onnx",
                size = 71_664_561L,
                sha256 = "620ecc014524456fd2c0518caff33f2fa99136aa194a1185e2437ea68ecdb7b9",
            ),
            ModelFile(
                remoteName = "tokens.txt",
                localName = "tokens.txt",
                size = 34_846L,
                sha256 = "fca8b98b28803e4235d6cd630e3666232bf55d3a34032c1cd7568c331ac213cd",
            ),
        ),
    )
}
