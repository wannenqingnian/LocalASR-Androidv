package com.example.localasr.history

data class TranscriptSession(
    val id: Long,
    val startedAt: Long,
    val endedAt: Long,
    val text: String,
) {
    val durationMillis: Long
        get() = (endedAt - startedAt).coerceAtLeast(0L)
}
