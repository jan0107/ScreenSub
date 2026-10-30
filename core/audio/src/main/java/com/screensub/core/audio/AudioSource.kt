// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.audio

/** 音频来源。 */
enum class AudioSource(val label: String) {

    /**
     * 内部音频（AudioPlaybackCapture）。
     * 音质最好、无环境噪声，但目标 App 可拒绝，且 DRM 内容抓不到。
     */
    INTERNAL("内部音频"),

    /**
     * 麦克风外录。
     * 永远可用，但需要外放、怕环境噪声、音质取决于扬声器与距离。
     */
    MIC("麦克风"),
    ;

    val isInternal: Boolean get() = this == INTERNAL
}

/** 采集运行状态，给 UI 与自检页共用。 */
sealed interface CaptureState {

    data object Idle : CaptureState

    data object Starting : CaptureState

    data class Running(
        val source: AudioSource,
        /** 当前峰值电平 dBFS，用于自检页画电平条 */
        val peakDbfs: Float = -100f,
    ) : CaptureState

    data class Failed(
        val reason: String,
        /** 是否建议降级到麦克风 */
        val suggestMicFallback: Boolean = false,
    ) : CaptureState
}
