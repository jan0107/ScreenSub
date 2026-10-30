// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.common

import java.util.Locale

/** 音频 / 字幕时间工具。所有时间戳统一用毫秒 Long。 */
object TimeUtils {

    /** 采样点数换算成毫秒 */
    fun samplesToMs(samples: Int, sampleRate: Int): Long =
        samples * 1000L / sampleRate

    /** 毫秒换算成采样点数 */
    fun msToSamples(ms: Long, sampleRate: Int): Int =
        (ms * sampleRate / 1000L).toInt()

    /**
     * SRT 时间格式：HH:MM:SS,mmm（注意是逗号，不是点）
     * 例：00:01:23,456
     */
    fun formatSrtTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0)
        val h = safe / 3_600_000
        val m = (safe % 3_600_000) / 60_000
        val s = (safe % 60_000) / 1000
        val msPart = safe % 1000
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, msPart)
    }

    /** 界面上显示用的 mm:ss */
    fun formatClock(ms: Long): String {
        val safe = ms.coerceAtLeast(0)
        val m = safe / 60_000
        val s = (safe % 60_000) / 1000
        return String.format(Locale.US, "%02d:%02d", m, s)
    }

    /** 毫秒 → "1.8s" 这种延迟展示 */
    fun formatLatency(ms: Long): String =
        String.format(Locale.US, "%.1fs", ms / 1000.0)
}
