// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.data

import com.screensub.core.common.TimeUtils
import com.screensub.core.data.db.CueEntity

/**
 * SRT 导出。
 *
 * 时间轴处理的两个坑：
 * 1. **重叠**：VAD 切出来的段偶尔会重叠几毫秒，SRT 播放器遇到重叠会闪。
 *    处理方式是让上一条提前 30 ms 收尾。
 * 2. **过短的条**：小于 400 ms 的字幕在播放器里根本来不及读，
 *    直接并到下一条（把下一条的起点提前）。
 */
object SrtExporter {

    private const val MIN_CUE_DURATION_MS = 400L
    private const val OVERLAP_GUARD_MS = 30L

    /**
     * @param cues 同一会话的字幕，按 startMs 升序
     * @param finalOnly 是否只导出已定稿的字幕（推荐 true，暂定字幕是重复的）
     */
    fun toSrt(cues: List<CueEntity>, finalOnly: Boolean = true): String {
        val source = cues
            .filter { if (finalOnly) it.isFinal else true }
            .filter { it.translatedText.isNotBlank() }
            .sortedBy { it.startMs }

        if (source.isEmpty()) return ""

        val merged = mergeShortCues(source)

        return buildString {
            merged.forEachIndexed { index, cue ->
                append(index + 1)
                append('\n')
                append(TimeUtils.formatSrtTime(cue.startMs))
                append(" --> ")
                append(TimeUtils.formatSrtTime(cue.endMs))
                append('\n')
                append(cue.translatedText.trim())
                append("\n\n")
            }
        }
    }

    /** 同时导出双语 SRT（上一行中文，下一行原文） */
    fun toBilingualSrt(cues: List<CueEntity>): String {
        val source = cues
            .filter { it.isFinal && it.translatedText.isNotBlank() }
            .sortedBy { it.startMs }

        if (source.isEmpty()) return ""

        val merged = mergeShortCues(source)

        return buildString {
            merged.forEachIndexed { index, cue ->
                append(index + 1)
                append('\n')
                append(TimeUtils.formatSrtTime(cue.startMs))
                append(" --> ")
                append(TimeUtils.formatSrtTime(cue.endMs))
                append('\n')
                append(cue.translatedText.trim())
                append('\n')
                append(cue.sourceText.trim())
                append("\n\n")
            }
        }
    }

    /**
     * 合并过短的条，并保证相邻不重叠。
     */
    private fun mergeShortCues(cues: List<CueEntity>): List<CueEntity> {
        if (cues.isEmpty()) return emptyList()

        val result = ArrayList<CueEntity>(cues.size)
        var pending: CueEntity? = null

        for (cue in cues) {
            val current = pending
            if (current == null) {
                pending = cue
                continue
            }

            val duration = cue.endMs - cue.startMs
            if (duration < MIN_CUE_DURATION_MS) {
                // 太短，拼到上一条后面
                pending = current.copy(
                    endMs = maxOf(current.endMs, cue.endMs),
                    translatedText = current.translatedText + cue.translatedText,
                    sourceText = "${current.sourceText} ${cue.sourceText}",
                )
            } else {
                result.add(current)
                pending = cue
            }
        }
        pending?.let { result.add(it) }

        // 消除重叠
        return result.mapIndexed { index, cue ->
            if (index == 0) return@mapIndexed cue
            val previous = result[index - 1]
            if (cue.startMs < previous.endMs) {
                cue.copy(startMs = previous.endMs + OVERLAP_GUARD_MS)
            } else {
                cue
            }
        }
    }

    /** 生成建议文件名：ScreenSub_20261026_0930.srt */
    fun suggestFileName(timestamp: Long = System.currentTimeMillis()): String {
        val format = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US)
        return "ScreenSub_${format.format(java.util.Date(timestamp))}.srt"
    }
}
