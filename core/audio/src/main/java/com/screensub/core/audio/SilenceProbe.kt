// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.audio

import android.content.Context
import android.media.AudioManager

/**
 * 内录"是否被目标 App 禁止"的探针。
 *
 * 系统没有公开 API 能查目标 App 的 capturePolicy，只能间接推断。
 * 判 FAIL 必须**三个条件同时成立**，缺一不可：
 *
 *  1. audioManager.isMusicActive == true   → 系统确实在播放媒体
 *  2. STREAM_MUSIC 音量 > 0                → 用户没静音
 *  3. 连续 3 秒采集到的峰值 < -60 dBFS      → 但我们啥也没收到
 *
 * 第 3 条单独用会误判：片头纯配乐渐弱、卡点留白、角色沉默都可能触发。
 * 所以调用方还必须遵守"只在会话开始后 15 秒内判定一次"，
 * 超过窗口就不再自动降级，改为 UI 提示用户手动切换。
 */
class SilenceProbe(context: Context) {

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** 连续静音累计时长（毫秒） */
    private var silentRunMs: Long = 0L

    /** 最近一次峰值电平 */
    @Volatile
    var lastPeakDbfs: Float = -100f
        private set

    enum class Verdict {
        /** 收到了音频，内录正常工作 */
        OK,

        /** 播放中但连续静音，判定被禁止捕获 */
        BLOCKED,

        /** 系统没在放媒体（用户还没点播放），无法判定 */
        NO_PLAYBACK,
    }

    fun reset() {
        silentRunMs = 0L
        lastPeakDbfs = -100f
    }

    /** 每帧调用一次 */
    fun feed(peakDbfs: Float, frameMs: Int) {
        lastPeakDbfs = peakDbfs
        if (peakDbfs > AudioConstants.SILENCE_DBFS) {
            silentRunMs = 0L
        } else {
            silentRunMs += frameMs
        }
    }

    fun verdict(): Verdict {
        val musicActive = audioManager.isMusicActive
        val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        return when {
            silentRunMs < AudioConstants.BLOCKED_SILENCE_MS -> {
                if (musicActive) Verdict.OK else Verdict.NO_PLAYBACK
            }

            // 连续静音够久，再看系统是否真的在放
            musicActive && volume > 0 -> Verdict.BLOCKED
            else -> Verdict.NO_PLAYBACK
        }
    }

    /** 自检页用的调试快照 */
    fun snapshot(): ProbeSnapshot = ProbeSnapshot(
        musicActive = audioManager.isMusicActive,
        musicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
        musicVolumeMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
        silentRunMs = silentRunMs,
        lastPeakDbfs = lastPeakDbfs,
    )

    data class ProbeSnapshot(
        val musicActive: Boolean,
        val musicVolume: Int,
        val musicVolumeMax: Int,
        val silentRunMs: Long,
        val lastPeakDbfs: Float,
    )
}
