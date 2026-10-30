// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.audio

/**
 * 音频常量。
 *
 * 为什么内录用 48 kHz 而不是直接要 16 kHz：
 * AudioFlinger 混音器内部就是 48 kHz，如果向系统请求 16 kHz，
 * 会走 AudioRecord 的重采样路径，在部分机型上会拿到"补齐帧"或
 * 直接返回不支持的采样率。自己采集 48k 再做 3:1 抽取更可控。
 */
object AudioConstants {

    /** ASR 模型要求的采样率 */
    const val TARGET_SAMPLE_RATE = 16_000

    /** 内录使用的原生采样率 */
    const val CAPTURE_SAMPLE_RATE = 48_000

    /** 48k → 16k 的抽取比 */
    const val DOWNSAMPLE_RATIO = CAPTURE_SAMPLE_RATE / TARGET_SAMPLE_RATE // = 3

    /** 每帧时长（毫秒）。20 ms = 内录 960 样本 / 输出 320 样本 */
    const val FRAME_MS = 20

    /** 内录每帧样本数 */
    const val CAPTURE_FRAME_SAMPLES = CAPTURE_SAMPLE_RATE * FRAME_MS / 1000

    /** 输出（16k）每帧样本数 */
    const val TARGET_FRAME_SAMPLES = TARGET_SAMPLE_RATE * FRAME_MS / 1000

    /** 判静音的峰值门限（dBFS）。-60 对应约 1/1000 满幅 */
    const val SILENCE_DBFS = -60f

    /** 判定"被禁止捕获"需要连续静音多久（毫秒） */
    const val BLOCKED_SILENCE_MS = 3_000L

    /** 会话开始后多久之内做一次禁捕判定（毫秒），避免整集纯配乐误判 */
    const val PROBE_WINDOW_MS = 15_000L
}
