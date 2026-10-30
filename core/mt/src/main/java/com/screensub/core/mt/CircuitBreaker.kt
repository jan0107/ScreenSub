// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.mt

import android.os.SystemClock
import com.screensub.core.common.Log
import com.screensub.core.common.LogTags
import java.util.concurrent.ConcurrentHashMap

/**
 * 熔断器。
 *
 * 字幕场景的可用性哲学：**宁可这一句不译，也不能让整条流水线等**。
 * 所以失败不重试，连续失败三次直接把该引擎踢下线 5 分钟，
 * 期间所有请求立刻失败、跳到下一个引擎，不浪费一次网络往返。
 *
 * 三种触发器：
 *  - 429（限流/超额）：立刻熔断 30 秒
 *  - 5xx / 超时：累计 3 次熔断 5 分钟
 *  - 401/403（Key 错了）：熔断 30 分钟，别白试
 */
class CircuitBreaker(
    private val failureThreshold: Int = 3,
    private val defaultCooldownMs: Long = 5 * 60_000L,
) {

    companion object {
        private const val TAG = LogTags.MT
        private const val RATE_LIMIT_COOLDOWN_MS = 30_000L
        private const val AUTH_COOLDOWN_MS = 30 * 60_000L
    }

    private val failures = ConcurrentHashMap<String, Int>()
    private val openUntil = ConcurrentHashMap<String, Long>()
    private val lastReason = ConcurrentHashMap<String, String>()

    fun isAvailable(engineId: String): Boolean {
        val until = openUntil[engineId] ?: return true
        if (SystemClock.elapsedRealtime() >= until) {
            // 冷却结束，重新给它一次机会
            openUntil.remove(engineId)
            failures.remove(engineId)
            lastReason.remove(engineId)
            return true
        }
        return false
    }

    fun onSuccess(engineId: String) {
        failures.remove(engineId)
        openUntil.remove(engineId)
        lastReason.remove(engineId)
    }

    fun onFailure(engineId: String, reason: String, httpCode: Int? = null) {
        val count = failures.merge(engineId, 1, Int::plus) ?: 1
        lastReason[engineId] = reason

        val cooldown = when (httpCode) {
            401, 403 -> AUTH_COOLDOWN_MS
            429 -> RATE_LIMIT_COOLDOWN_MS
            else -> defaultCooldownMs
        }

        val shouldOpen = when (httpCode) {
            401, 403, 429 -> true
            else -> count >= failureThreshold
        }

        if (shouldOpen) {
            openUntil[engineId] = SystemClock.elapsedRealtime() + cooldown
            Log.w(
                TAG,
                "熔断 $engineId ${cooldown / 1000}s，原因：$reason（连续失败 $count 次）",
            )
        }
    }

    /** 给设置页展示各引擎健康状态 */
    fun snapshot(): Map<String, EngineHealth> =
        failures.keys.associateWith { id ->
            val until = openUntil[id]
            EngineHealth(
                engineId = id,
                failureCount = failures[id] ?: 0,
                open = until != null && SystemClock.elapsedRealtime() < until,
                reason = lastReason[id].orEmpty(),
            )
        }

    fun resetAll() {
        failures.clear()
        openUntil.clear()
        lastReason.clear()
    }

    data class EngineHealth(
        val engineId: String,
        val failureCount: Int,
        val open: Boolean,
        val reason: String,
    )
}
