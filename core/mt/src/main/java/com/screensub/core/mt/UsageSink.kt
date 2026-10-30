// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.mt

import com.screensub.core.common.TranslatorIds

/**
 * 计费口径。
 *
 * 三种差别很大，混在一起算会把账算错：
 *  - [CHAR]  按字符计费（阿里云 / 火山 / Azure），一个字一个字地累加
 *  - [TOKEN] 按 token 计费（LLM），要拿响应里的 usage 字段，不是文本长度
 *  - [FREE]  免费（谷歌免 Key / MyMemory / ML Kit），只统计次数不记费用
 */
enum class BillingMode { CHAR, TOKEN, FREE }

/**
 * 一次翻译尝试的用量记录。
 *
 * 为什么成功失败都记：[ok] = false 的那次虽然不计费，但"试了几次、失败几次"
 * 本身是排查依据——用户看到"本月已用 0 字符但请求 300 次"就知道是 Key 配错了。
 */
data class UsageEvent(
    val engineId: String,
    val mode: BillingMode,
    /** 计费字符数（只对 [BillingMode.CHAR] 有意义） */
    val chars: Int = 0,
    /** LLM 输入 token（只对 [BillingMode.TOKEN] 有意义） */
    val tokensIn: Int = 0,
    /** LLM 输出 token */
    val tokensOut: Int = 0,
    val ok: Boolean = true,
)

/**
 * 用量上报出口。
 *
 * 用函数式接口而不是直接依赖 `core:data`：`core:mt` 只依赖 `core:common`，
 * 反向依赖会把 Room / DataStore 一起拖进翻译层，也会让模块图成环。
 * 实现由 `AppContainer` 注入（见 `QuotaRepository::record`）。
 */
fun interface UsageSink {
    fun record(event: UsageEvent)
}

/** 什么都不做的 sink，用于测试翻译之外的场景与单元测试 */
val NoopUsageSink = UsageSink { }

/**
 * 额度门禁。
 *
 * **必须同步返回**：它在 `translate()` 的候选循环里被逐引擎调用，
 * 每句话都要判一次，不能 suspend 去查 Room。
 * 实现方（`QuotaRepository`）负责维护内存快照。
 */
fun interface QuotaGate {
    fun isBlocked(engineId: String): Boolean
}

/** 永不拦截，用于关闭额度监测时 */
val AllowAllQuotaGate = QuotaGate { false }

/** 引擎 id → 计费口径 */
fun billingModeOf(engineId: String): BillingMode = when (engineId) {
    TranslatorIds.LLM -> BillingMode.TOKEN
    TranslatorIds.GOOGLE_FREE, TranslatorIds.MYMEMORY, TranslatorIds.MLKIT -> BillingMode.FREE
    else -> BillingMode.CHAR
}
