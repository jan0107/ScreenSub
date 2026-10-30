// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.asr

/**
 * 一次识别的结果。
 *
 * [startMs] / [endMs] 来自 VAD 分段的样本偏移，是 SRT 时间戳的唯一真源。
 * 不要用 System.currentTimeMillis()——会受 NTP 校时跳变影响，
 * 也可能因为推理慢导致字幕整体偏移。
 */
data class AsrResult(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    /** SenseVoice 会返回检测到的语言（如 "ja"），Whisper 不一定 */
    val detectedLanguage: String = "",
    /** 0f..1f；离线模型多数拿不到真实置信度，用 1f 表示"未提供" */
    val confidence: Float = 1f,
    /** true = VAD 判定句子已结束；false = 3 秒强制切出来的暂定结果 */
    val isFinal: Boolean = true,
    /** 推理耗时，用于自检页展示实时率 */
    val inferenceMs: Long = 0L,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0)

    val isEmpty: Boolean get() = text.isBlank()
}

/** PCM16 → 归一化 FloatArray（sherpa-onnx 要 [-1, 1] 的 float） */
fun ShortArray.toNormalizedFloatArray(): FloatArray {
    val out = FloatArray(size)
    for (i in indices) {
        out[i] = this[i] / 32768.0f
    }
    return out
}

/** 只转换有效长度 */
fun ShortArray.toNormalizedFloatArray(length: Int): FloatArray {
    val n = length.coerceIn(0, size)
    val out = FloatArray(n)
    for (i in 0 until n) {
        out[i] = this[i] / 32768.0f
    }
    return out
}
