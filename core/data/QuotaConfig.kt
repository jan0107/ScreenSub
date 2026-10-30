// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.data

import com.screensub.core.common.Log
import com.screensub.core.common.LogTags
import com.screensub.core.common.TranslatorIds
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 单个引擎的额度配置 */
data class EngineQuota(
    val engineId: String,
    /** 每月可用字符数。0 = 不限（只提示不停止），见 QuotaConfig 的两种模式说明 */
    val monthlyQuotaChars: Long,
    /**
     * 云端校准基准：用户从厂商控制台抄来的"本月已用"。
     *
     * 本地计数只看得见本 App 的消耗，看不见用户在别的程序里用掉的额度；
     * 一个账号只有一份额度，所以要把账号级已用量作为起点，本地再往上累加。
     */
    val baselineChars: Long = 0,
    /** 基准属于哪个月（"2026-10"）。换月后基准自动失效，不能跨月沿用 */
    val baselineMonth: String = "",
    /** 额度接近耗尽时仍发探测请求（用户显式开启，默认关） */
    val allowProbeNearLimit: Boolean = false,
) {
    /** 额度是否已配置（0 = 用户不设限） */
    val hasLimit: Boolean get() = monthlyQuotaChars > 0L

    /** 本月有效的基准（月份对不上就当 0） */
    fun baselineFor(monthKey: String): Long =
        if (baselineMonth == monthKey) baselineChars else 0L
}

/**
 * 额度与用量监测的全部配置。
 *
 * **为什么压成一个 JSON 存**：`SettingsStore.update{}` 是「读全量 → 转换 → 写全量」，
 * 每新增一个字段就要同步改 5 处（Keys / settings 读 / update 读段 / update 写段 / data class），
 * 漏一处就会在用户保存任意设置时被静默重置。这里十几项配置压成 1 个字段，
 * 只需要改 5 处，而不是 5 × 15 = 75 处。
 */
data class QuotaConfig(
    /** 全局开关①：免费额度监测（关掉则不告警、不切换，但统计照常记） */
    val quotaMonitorEnabled: Boolean = true,
    /** 全局开关②：额度耗尽时自动切换引擎（监测关闭时强制 false） */
    val autoSwitchEnabled: Boolean = true,
    /** 告警线（百分比），默认 90 */
    val alertPercent: Int = 90,
    /** 探测线（百分比），默认 80。超过就不发探测请求 */
    val probeMaxPercent: Int = 80,
    /** 停用线安全垫：停用条件 = 已用 + 此值 >= 额度，防止最后一句整句超额 */
    val disableSafetyBufferChars: Long = 500,
    /** LLM 本月预算（元） */
    val llmBudgetYuan: Double = 0.5,
    /** LLM 输入单价（元/百万 token）。0 = 不按金额停用，只显示 token */
    val llmInPricePerMillion: Double = 0.0,
    /** LLM 输出单价（元/百万 token） */
    val llmOutPricePerMillion: Double = 0.0,
    val engines: Map<String, EngineQuota> = DEFAULT_ENGINES,
) {

    /** 自动切换实际是否生效（监测关了就不切） */
    val switchEffective: Boolean get() = quotaMonitorEnabled && autoSwitchEnabled

    fun engine(engineId: String): EngineQuota =
        engines[engineId] ?: EngineQuota(engineId, 0L)

    /** 改一个引擎的配置 */
    fun withEngine(updated: EngineQuota): QuotaConfig =
        copy(engines = engines + (updated.engineId to updated))

    /**
     * 联动规则：关掉监测时，自动切换必须同步落成 false。
     *
     * 反过来**不做**：重新打开监测时不自动把切换打开——
     * 用户上次可能是主动关的，自动恢复会变成"我关一趟怎么自己变开了"。
     */
    fun withMonitor(enabled: Boolean): QuotaConfig = copy(
        quotaMonitorEnabled = enabled,
        autoSwitchEnabled = if (enabled) autoSwitchEnabled else false,
    )

    fun toJson(): String = JSONObject().apply {
        put("v", VERSION)
        put("monitor", quotaMonitorEnabled)
        put("switch", autoSwitchEnabled)
        put("alert", alertPercent)
        put("probe", probeMaxPercent)
        put("buf", disableSafetyBufferChars)
        put("budget", llmBudgetYuan)
        put("inPrice", llmInPricePerMillion)
        put("outPrice", llmOutPricePerMillion)
        put("engines", JSONObject().apply {
            engines.forEach { (id, q) ->
                put(id, JSONObject().apply {
                    put("q", q.monthlyQuotaChars)
                    put("base", q.baselineChars)
                    put("month", q.baselineMonth)
                    put("probe", q.allowProbeNearLimit)
                })
            }
        })
    }.toString()

    companion object {
        private const val TAG = LogTags.DATA
        private const val VERSION = 1

        /**
         * 默认额度 = 各家官方免费额度。
         *
         * 腾讯云已从工程中移除；LLM 按金额计费不在此表；
         * 谷歌 / MyMemory / ML Kit 本身免费，不设限。
         */
        val DEFAULT_ENGINES: Map<String, EngineQuota> = mapOf(
            TranslatorIds.LLM to EngineQuota(TranslatorIds.LLM, 0L),
            TranslatorIds.AZURE to EngineQuota(TranslatorIds.AZURE, 2_000_000L),
            TranslatorIds.ALIYUN to EngineQuota(TranslatorIds.ALIYUN, 1_000_000L),
            TranslatorIds.VOLCENGINE to EngineQuota(TranslatorIds.VOLCENGINE, 2_000_000L),
        )

        val DEFAULT = QuotaConfig()

        /** 解析失败一律回默认值——配置文件坏了不能让 App 起不来 */
        fun fromJson(raw: String): QuotaConfig {
            if (raw.isBlank()) return DEFAULT
            return try {
                val o = JSONObject(raw)
                val engines = LinkedHashMap<String, EngineQuota>()
                DEFAULT_ENGINES.forEach { (id, def) -> engines[id] = def }
                o.optJSONObject("engines")?.let { arr ->
                    arr.keys().forEach { id ->
                        val e = arr.optJSONObject(id) ?: return@forEach
                        val def = DEFAULT_ENGINES[id]
                        engines[id] = EngineQuota(
                            engineId = id,
                            monthlyQuotaChars = e.optLong("q", def?.monthlyQuotaChars ?: 0L),
                            baselineChars = e.optLong("base", 0L),
                            baselineMonth = e.optString("month", ""),
                            allowProbeNearLimit = e.optBoolean("probe", false),
                        )
                    }
                }
                QuotaConfig(
                    quotaMonitorEnabled = o.optBoolean("monitor", true),
                    autoSwitchEnabled = o.optBoolean("switch", true),
                    alertPercent = o.optInt("alert", 90).coerceIn(10, 100),
                    probeMaxPercent = o.optInt("probe", 80).coerceIn(10, 100),
                    disableSafetyBufferChars = o.optLong("buf", 500L).coerceAtLeast(0L),
                    llmBudgetYuan = o.optDouble("budget", 0.5).takeIf { !it.isNaN() } ?: 0.5,
                    llmInPricePerMillion = o.optDouble("inPrice", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                    llmOutPricePerMillion = o.optDouble("outPrice", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                    engines = engines,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "额度配置解析失败，回退默认值", t)
                DEFAULT
            }
        }
    }
}

/** 日期键工具。全部用本地时区，minSdk 31 直接用 java.time */
object UsageKeys {
    private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val MONTH = DateTimeFormatter.ofPattern("yyyy-MM")

    fun dayKey(date: LocalDate = LocalDate.now()): String = date.format(DAY)

    fun monthKey(date: LocalDate = LocalDate.now()): String = date.format(MONTH)

    fun monthKeyOf(dayKey: String): String = dayKey.substring(0, 7)

    /** 最近 N 天的起始日键（含今天） */
    fun daysAgoKey(days: Int, from: LocalDate = LocalDate.now()): String =
        from.minusDays(days.toLong()).format(DAY)
}
