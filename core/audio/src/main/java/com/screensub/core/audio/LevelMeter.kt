// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.audio

import kotlin.math.abs
import kotlin.math.log10

/**
 * 电平表。峰值用于静音判定，RMS 用于界面画电平条。
 * 全部返回 dBFS（0 = 满幅，负无穷 = 静音）。
 */
object LevelMeter {

    private const val MIN_DB = -100f

    fun peakDbfs(samples: ShortArray, len: Int): Float {
        if (len <= 0) return MIN_DB
        var peak = 0
        for (i in 0 until len) {
            val v = abs(samples[i].toInt())
            if (v > peak) peak = v
        }
        return amplitudeToDbfs(peak)
    }

    fun rmsDbfs(samples: ShortArray, len: Int): Float {
        if (len <= 0) return MIN_DB
        var sum = 0.0
        for (i in 0 until len) {
            val v = samples[i].toDouble()
            sum += v * v
        }
        val rms = kotlin.math.sqrt(sum / len)
        return amplitudeToDbfs(rms)
    }

    private fun amplitudeToDbfs(amplitude: Number): Float {
        val a = amplitude.toDouble()
        if (a <= 0.0) return MIN_DB
        val db = 20.0 * log10(a / 32768.0)
        return db.toFloat().coerceIn(MIN_DB, 0f)
    }

    /** 把 dBFS 映射到 0..1，供进度条显示（-60..0 区间） */
    fun dbfsToFraction(dbfs: Float): Float {
        val clamped = dbfs.coerceIn(-60f, 0f)
        return (clamped + 60f) / 60f
    }
}
