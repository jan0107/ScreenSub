// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.pipeline

/** 一条字幕。UI 与 SRT 导出都用这个结构。 */
data class SubtitleCue(
    /** 递增序号，用于并发场景下的顺序保证 */
    val seq: Long,
    val startMs: Long,
    val endMs: Long,
    /** 识别原文 */
    val sourceText: String,
    /** 中文译文。翻译失败时为原文（永远有输出） */
    val translatedText: String,
    /** true = 整句已结束；false = 3 秒强制切出来的暂定结果，稍后会被覆盖 */
    val isFinal: Boolean,
    /** 端到端延迟：从这段语音开头到字幕上屏 */
    val latencyMs: Long = 0L,
    /**
     * 译文还没到，当前显示的是原文。
     *
     * 流水线现在"先出原文、后补译文"：识别完立刻发一条 pendingTranslation=true 的
     * 字幕，翻译在旁路线程完成后，用**同一个 seq** 再发一条 pendingTranslation=false
     * 的更新。同一个 seq 出现两次是正常的，不是重复。
     *
     * 落库时以 pendingTranslation=false 的那条为准，避免一句话写两行。
     */
    val pendingTranslation: Boolean = false,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0)

    /** 翻译是否成功（UI 可据此标灰提示"仅原文"） */
    val hasTranslation: Boolean
        get() = translatedText != sourceText && translatedText.isNotBlank()
}

/** 流水线状态，给 UI 显示。 */
sealed interface PipelineState {

    data object Idle : PipelineState

    /** 模型加载中 */
    data object Preparing : PipelineState

    data class Running(
        val sourceLabel: String,
        /** 最近一次端到端延迟 */
        val lastLatencyMs: Long = 0L,
        /** 已产出字幕条数 */
        val cueCount: Long = 0L,
    ) : PipelineState

    /** 能跑，但有降级（如内录被禁改用外录、翻译全挂只剩原文） */
    data class Degraded(
        val reason: String,
        val stillRunning: Boolean = true,
    ) : PipelineState

    data class Error(val message: String) : PipelineState
}
