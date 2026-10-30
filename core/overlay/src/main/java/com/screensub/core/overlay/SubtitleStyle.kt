// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.overlay

import android.graphics.Color

/**
 * 字幕外观。
 *
 * [backgroundAlpha] 有个硬限制：**不能超过 0.55**。
 * Android 12 起，悬浮窗背景不透明度超过阈值时系统会认为它"遮挡内容"，
 * 从而拦截穿透触摸，用户的点击就传不到下面的播放器了。
 * 0.45 是既能看清又不挡操作的实测值。
 */
data class SubtitleStyle(
    val fontSizeSp: Float = 18f,
    val sourceFontSizeSp: Float = 12f,
    /** 是否同时显示原文（学习用） */
    val showSourceText: Boolean = false,
    val textColor: Int = Color.WHITE,
    val backgroundColor: Int = Color.BLACK,
    val backgroundAlpha: Float = 0.45f,
    val maxLines: Int = 2,
    /** 距屏幕底部的距离（dp） */
    val bottomMarginDp: Int = 96,
    val draggable: Boolean = true,
    /** 暂定字幕（未定稿）的透明度 */
    val provisionalAlpha: Float = 0.72f,
) {
    companion object {
        const val MAX_SAFE_BACKGROUND_ALPHA = 0.55f

        val DEFAULT = SubtitleStyle()

        /** 大字号（老人/远距离） */
        val LARGE = SubtitleStyle(fontSizeSp = 24f, sourceFontSizeSp = 15f, maxLines = 2)

        /** 极简（只看中文，透明到底） */
        val MINIMAL = SubtitleStyle(
            fontSizeSp = 20f,
            backgroundAlpha = 0.28f,
            maxLines = 1,
        )
    }

    /** 保证背景不会挡住下面的点击 */
    fun safeAlpha(): Float = backgroundAlpha.coerceIn(0f, MAX_SAFE_BACKGROUND_ALPHA)
}
