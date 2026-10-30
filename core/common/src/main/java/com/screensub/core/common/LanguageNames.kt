// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.common

/**
 * 语言码 → 中文名。
 *
 * 放 core:common 是因为 core:mt（给 LLM 拼 prompt）和 app（UI 显示）
 * 都要用，而它们唯一的共同依赖就是 common。
 */
object LanguageNames {

    /** 源语言码 → 名称。未知码原样返回，方便排查。 */
    fun of(code: String): String = when (code.lowercase()) {
        "ja", "japanese" -> "日语"
        "en", "english" -> "英语"
        "ru", "russian" -> "俄语"
        "ko", "korean" -> "韩语"
        "zh", "zh-hans", "chinese" -> "中文"
        "zh-hant" -> "繁体中文"
        "yue" -> "粤语"
        "auto" -> "自动"
        else -> code
    }

    /** 目标语言码 → 名称 */
    fun targetOf(code: String): String = when (code) {
        "zh-Hans" -> "简体中文"
        "zh-Hant" -> "繁体中文"
        else -> of(code)
    }
}
