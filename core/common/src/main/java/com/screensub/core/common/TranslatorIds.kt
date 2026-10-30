// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.common

/**
 * 翻译引擎类型标识，与设置项、用量统计、账单口径一一对应。
 *
 * 放在 `core:common` 而不是 `core:mt`：这些 id 同时被翻译层（`core:mt`）和
 * 用量/额度层（`core:data`）使用，而 `core:data` 不能反向依赖 `core:mt`
 * （会把 ML Kit 和 OkHttp 拖进数据层）。字符串常量本身零依赖，下沉到公共层最合适。
 */
object TranslatorIds {
    const val MLKIT = "mlkit"
    const val LLM = "llm"
    const val AZURE = "azure"

    /** 谷歌非官方免费接口（需 DoH 拿真实 IP） */
    const val GOOGLE_FREE = "google_free"

    /** MyMemory 公共 API，作为谷歌不可达时的兜底 */
    const val MYMEMORY = "mymemory"

    /** 阿里云机器翻译，每月 100 万字符免费 */
    const val ALIYUN = "aliyun"

    /** 火山引擎机器翻译，每月 200 万字符免费 */
    const val VOLCENGINE = "volcengine"

    /** 有免费字符额度的付费引擎，顺序即设置页序号 */
    val PAID_IN_ORDER = listOf(LLM, VOLCENGINE, ALIYUN, AZURE)

    /** 免费引擎（不吃额度，只在链里兜底） */
    val FREE = listOf(GOOGLE_FREE, MYMEMORY, MLKIT)
}
