// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.asr

import com.screensub.core.common.AppResult

/**
 * 语音识别引擎抽象。
 *
 * 刻意不把 VAD 放进来：分段是 pipeline 的职责（要控制"3 秒强制切"这种策略），
 * 引擎只负责"给我一段音频，还我一段文字"。这样离线/在线引擎可以互换，
 * 在线引擎（如 Azure）根本不需要本地 VAD。
 */
interface AsrEngine : AutoCloseable {

    val id: String

    /** 模型是否已加载完成，可以直接 transcribe */
    val isReady: Boolean

    /**
     * 加载模型。调用方应在进入会话页时就调用（预热），
     * 而不是等用户点了开始——SenseVoice 冷启需要 3~10 秒。
     */
    suspend fun prepare(): AppResult<Unit>

    /**
     * 识别一段音频。
     * @param samples 16 kHz / 单声道 / 归一化 Float [-1, 1]
     * @param startMs 该段在原音频流中的起始毫秒（由 VAD 样本偏移换算而来）
     * @param isFinal false 表示这是 3 秒强制切的暂定结果
     */
    suspend fun transcribe(
        samples: FloatArray,
        startMs: Long,
        isFinal: Boolean,
    ): AppResult<AsrResult>

    /** 释放模型内存。切语言时要先 release 再 load，否则两个模型会同时驻留。 */
    fun release()
}
