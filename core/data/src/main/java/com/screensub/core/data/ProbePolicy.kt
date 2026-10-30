// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.data

/**
 * 探测安全门。
 *
 * ## 为什么需要它
 *
 * 会话开始时对引擎发一次 "hi" 探测，能立刻发现"Key 填错了"，
 * 而不是等用户看三句失败字幕才发现。但探测**本身要花额度**——
 * 如果免费额度只剩 100 字符，探两次就可能把用户推过线，产生真实扣费。
 *
 * **用来省钱的机制本身不能让人花钱。** 所以探测必须被额度反制。
 *
 * ## 三道闸门
 *
 * 1. `quota == 0`（没设额度）→ 不探。没有停用线，也就无从判断风险
 * 2. 剩余 < [PROBE_COST_CHARS] → 不探。物理下限，探了必然超额，**用户开关也放开不了**
 * 3. 超过 `probeMaxPercent`（默认 80%）或剩余 < 1 万字符 → 不探；
 *    用户显式打开「接近耗尽仍探测」后放开这两条**软限制**
 */
object ProbePolicy {

    /** 一次探测的字符开销（"hi" = 2） */
    const val PROBE_COST_CHARS = 2L

    /** 剩余不足这么多字符就不探（软限制，可被用户开关放开） */
    const val MIN_HEADROOM_CHARS = 10_000L

    /**
     * 判定能否探测。
     *
     * @param quotaChars 本月额度，0 = 未设限
     * @param usedChars 本月已用（本地累计 + 校准基准）
     * @param allowNearLimit 用户是否打开了「接近耗尽仍探测」
     * @param probeMaxPercent 探测线，来自 QuotaConfig
     */
    fun decide(
        quotaChars: Long,
        usedChars: Long,
        allowNearLimit: Boolean,
        probeMaxPercent: Int,
    ): ProbeDecision {
        if (quotaChars <= 0L) return ProbeDecision.SKIP_NO_QUOTA_SET

        val headroom = quotaChars - usedChars

        // 硬下限：探了必然超额，开关也放开不了
        if (headroom < PROBE_COST_CHARS) return ProbeDecision.SKIP_NO_ROOM

        if (!allowNearLimit) {
            if (headroom < MIN_HEADROOM_CHARS) return ProbeDecision.SKIP_HEADROOM_LOW
            if (usedChars * 100.0 / quotaChars >= probeMaxPercent) return ProbeDecision.SKIP_NEAR_LIMIT
        }
        return ProbeDecision.ALLOW
    }
}

enum class ProbeDecision {
    ALLOW,

    /** 没设额度，无从判断 */
    SKIP_NO_QUOTA_SET,

    /** 剩余不足一次探测的开销（物理下限） */
    SKIP_NO_ROOM,

    /** 剩余不足 1 万字符（软限制） */
    SKIP_HEADROOM_LOW,

    /** 已超过探测线（软限制） */
    SKIP_NEAR_LIMIT,
    ;

    val allowed: Boolean get() = this == ALLOW

    /** 给日志看的原因 */
    val reason: String
        get() = when (this) {
            ALLOW -> "允许"
            SKIP_NO_QUOTA_SET -> "未设置额度"
            SKIP_NO_ROOM -> "剩余不足 2 字符"
            SKIP_HEADROOM_LOW -> "剩余不足 1 万字符"
            SKIP_NEAR_LIMIT -> "已超过探测线"
        }
}
