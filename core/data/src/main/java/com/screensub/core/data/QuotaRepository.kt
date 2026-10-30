// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.data

import com.screensub.core.common.Log
import com.screensub.core.common.LogTags
import com.screensub.core.common.TranslatorIds
import com.screensub.core.common.AppResult
import com.screensub.core.data.billing.AliyunBillingProbe
import com.screensub.core.data.billing.CloudBillStatus
import com.screensub.core.data.billing.CloudBillingProbe
import com.screensub.core.data.billing.DayChars
import com.screensub.core.data.billing.VolcengineBillingProbe
import com.screensub.core.data.db.UsageDao
import com.screensub.core.data.db.UsageDailyEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** 引擎 id → 中文名。放在数据层是为了让用量快照自带可读名字，UI 不必再维护一份映射 */
object EngineNames {
    fun of(engineId: String): String = when (engineId) {
        TranslatorIds.LLM -> "LLM 翻译"
        TranslatorIds.AZURE -> "微软 Azure 翻译"
        TranslatorIds.ALIYUN -> "阿里云机器翻译"
        TranslatorIds.VOLCENGINE -> "火山云机器翻译"
        TranslatorIds.GOOGLE_FREE -> "谷歌翻译"
        TranslatorIds.MYMEMORY -> "MyMemory"
        TranslatorIds.MLKIT -> "ML Kit 离线"
        else -> engineId
    }
}

/** 单日单向累计 */
private data class DayCounters(
    val chars: Long = 0,
    val requests: Long = 0,
    val tokensIn: Long = 0,
    val tokensOut: Long = 0,
    val costMicro: Long = 0,
)

/**
 * 用量统计与额度判定。
 *
 * ## 两条硬约束
 *
 * 1. [isBlocked] **必须同步且极快**：它在翻译链的候选循环里被逐引擎调用，
 *    每句话都判一次。所以只读 `@Volatile` 快照，绝不 suspend、绝不查 Room。
 * 2. 落库**不能在翻译路径上**：写 Room 是 IO，会拖慢上屏。
 *
 * ## 数据来源是双轨的
 *
 * - [record]：本地逐句累计，实时、精确到本 App
 * - [EngineQuota.baselineChars]：用户从厂商控制台抄来的账号级已用量，
 *   用来补上"别的程序也用了这份额度"的部分。云端账单是 T+1 的，
 *   不能用来实时停用，只能做这个校准基准。
 *
 * ## 监测关掉时仍然记账
 *
 * 数一遍字符是零成本的，不记纯属丢信息。用户嫌告警吵关掉几天，
 * 再打开时应该立刻看到真实累计，而不是从 0 重新开始。
 * 所以 [QuotaConfig.quotaMonitorEnabled] 只关"告警与切换"，不关统计。
 */
class QuotaRepository(
    private val dao: UsageDao,
    private val settingsStore: SettingsStore,
    private val scope: CoroutineScope,
) {

    companion object {
        private const val TAG = LogTags.DATA

        /** 只保留 40 天：够算"本月 + 最近 7 日 + 30 日趋势" */
        private const val RETENTION_DAYS = 40

        /** UI 刷新防抖。字幕每秒一条，不防抖会让首页持续重组 */
        private const val PUBLISH_DEBOUNCE_MS = 300L
    }

    /** 单引擎的用量快照 */
    data class EngineUsage(
        val engineId: String,
        val displayName: String,
        /** 本月字符（本地累计 + 校准基准） */
        val monthChars: Long,        /** 本月额度，0 = 不限 */
        val quotaChars: Long,
        /** 本月金额（元），只有 LLM 有意义 */
        val monthYuan: Double,
        /** 本月 token 用量，只有 LLM 有意义 */
        val tokensIn: Long = 0,
        val tokensOut: Long = 0,
        /** 本月预算（元），0 = 不按金额停用 */
        val budgetYuan: Double,
        /** 用量比例 0f..∞，字符额度优先，没配额度的 LLM 用金额 */
        val percent: Float,
        val blocked: Boolean,
        val last7: List<DayUsage>,
        /** 云端账单数字（批次 5 接入），null = 未接入 */
        val cloudChars: Long? = null,
        val cloudAt: Long? = null,
        /**
         * **平台口径的本月已用字符**（阿里云 `GetTranslateReport` / 火山账单 `Count×Unit`）。
         * null = 还没拉过、查询失败，或本月还没出账。UI 优先展示它。
         */
        val officialChars: Long? = null,
        /**
         * 没拿到平台用量的**具体原因**（权限不足 / 密钥不匹配 / 还没出账）。
         *
         * 单独一个字段是为了让 UI 说人话：只说"暂时没拿到"用户无法判断
         * 是该去改密钥、还是干等一天。null 且 [officialChars] 为 null = 还没出账，正常。
         */
        val officialError: String? = null,
        /** 平台口径的昨日用量——"昨天用了多少"只有平台答得出来 */
        val officialYesterday: Long? = null,
        /** 平台口径的每日用量（7 日图用它，而不是本地累加） */
        val officialDaily: List<DayChars> = emptyList(),
        val officialAt: Long? = null,
        /** 平台查到欠费 → 强制停用（本地计数看不见"别的程序用掉的"） */
        val platformArrears: Boolean = false,
        val platformArrearsYuan: Double? = null,
        /** 本月是否已批准继续使用告警线以上的剩余额度 */
        val overAlertApproved: Boolean = false,
        /** 本月是否已批准超出额度继续用（按厂商费率付费，费用记在用户云账号） */
        val paidApproved: Boolean = false,
        /** LLM 平台账户余额（元），null = 未查或不支持 */
        val platformBalanceYuan: Double? = null,
        val configured: Boolean = true,
        val enabled: Boolean = true,
        val lastUsedAt: Long? = null,
    ) {
        /** 正在使用：配了 + 开着 + 没超限 */
        val isActive: Boolean get() = configured && enabled && !blocked
    }

    data class DayUsage(val dayKey: String, val chars: Long, val yuan: Double)

    /** 一次全量快照 */
    data class UsageSnapshot(
        val engines: Map<String, EngineUsage> = emptyMap(),
        val alertThresholdHits: Set<String> = emptySet(),
        val monthKey: String = "",
        val capturedAt: Long = 0,
    ) {
        fun engine(id: String): EngineUsage? = engines[id]

        companion object {
            val EMPTY = UsageSnapshot()
        }
    }

    // ---------------- 内存状态 ----------------

    /** dayKey → engineId → 累计。用 compute 保证并发安全（翻译是 2 路并发） */
    private val days = ConcurrentHashMap<String, ConcurrentHashMap<String, DayCounters>>()

    /** 最近调用时间，用来排序 */
    private val lastUsed = ConcurrentHashMap<String, AtomicLong>()

    /**
     * 平台口径用量快照（engineId → 最近一次拉到的状态）。
     *
     * 只有阿里云填得进来——它的 `GetTranslateReport` 能返回账号级、按天的真实字符数。
     * 火山没有这个接口（已穷尽：translate 服务 26 个用量 Action 全不存在、
     * 云监控 141 个命名空间无 translate、账单在免费额度内 0 行），所以那家恒为空。
     */
    private val cloud = ConcurrentHashMap<String, CloudBillStatus>()

    /** LLM 平台账户余额（元）。null = 没查过，或该服务商没有余额接口 */
    private val platformBalance = ConcurrentHashMap<String, Double>()

    @Volatile
    private var config: QuotaConfig = QuotaConfig.DEFAULT

    @Volatile
    private var snap: UsageSnapshot = UsageSnapshot.EMPTY

    private val _state = MutableStateFlow(UsageSnapshot.EMPTY)
    val state: StateFlow<UsageSnapshot> = _state.asStateFlow()

    /** 哪些引擎配了 Key（由 AppContainer 从 AppSettings 推过来，仓库自己看不到凭据判定规则） */
    @Volatile
    private var configuredIds: Set<String> = emptySet()

    @Volatile
    private var enabledIds: Set<String> = emptySet()

    private var publishJob: Job? = null

    init {
        // 1. 先把历史用量读进内存
        scope.launch {
            runCatching {
                dao.since(UsageKeys.daysAgoKey(RETENTION_DAYS)).forEach { row -> absorb(row) }
            }.onFailure { Log.w(TAG, "读取历史用量失败", it) }
            recomputeAndPublish()
        }
        // 2. 跟随设置更新配置
        scope.launch {
            settingsStore.settings.collect { s ->
                config = QuotaConfig.fromJson(s.quotaConfigJson)
                recomputeAndPublish()
            }
        }
    }

    // ---------------- 对外读 ----------------

    fun currentConfig(): QuotaConfig = config

    fun snapshot(): UsageSnapshot = snap

    /**
     * 额度门禁。**同步**，只读快照。
     *
     * 注意这里只判"超没超额度"，不管全局开关——开关的判断放在
     * AppContainer 构造 QuotaGate 时做，职责分开。
     */
    fun isBlocked(engineId: String): Boolean = snap.engines[engineId]?.blocked == true

    /**
     * 拉取平台口径用量。
     *
     * 目前只有阿里云拉得到（`GetTranslateReport` 返回账号级、按天的真实字符数）。
     * 火山没有这个接口，调用会拿到 `Err`，UI 侧据此显示"该平台不提供用量接口"。
     *
     * **不把平台数字写进 Room**：它是"权威视图"，不是事实来源。写进本地表会和
     * 逐次累加的记账互相污染，下次拉取又要覆盖，反而没法对账。
     */
    suspend fun refreshCloud(engineId: String, credentials: CloudBillingProbe.Credentials) {
        val probe = when (engineId) {
            TranslatorIds.ALIYUN -> AliyunBillingProbe
            TranslatorIds.VOLCENGINE -> VolcengineBillingProbe
            else -> return
        }
        when (val r = probe.query(credentials, UsageKeys.monthKey())) {
            is AppResult.Ok -> {
                val fresh = r.value
                val old = cloud[engineId]
                // 两路并发拉取时，晚到的旧结果不能覆盖新结果
                if (old == null || fresh.checkedAt >= old.checkedAt) {
                    cloud[engineId] = fresh
                    recomputeAndPublish()
                    Log.i(
                        TAG,
                        "$engineId 平台用量：本月 ${fresh.monthChars} 字符 / 昨日 ${fresh.yesterdayChars}" +
                            " / ${fresh.dailyChars.size} 天明细",
                    )
                }
            }

            is AppResult.Err -> Log.w(TAG, "$engineId 平台用量拉取失败：${r.error.message}")
        }
    }

    /** 最近一次平台快照，null = 还没拉过或不支持 */
    fun cloudStatus(engineId: String): CloudBillStatus? = cloud[engineId]

    /**
     * 写入从厂商**控制台页面**读到的官方用量。
     *
     * 现在两家都能通过 OpenAPI 拿到字符数（阿里云 `GetTranslateReport`、
     * 火山账单 `Count×Unit`），所以这条手动通道降级为**补位**：
     *  - 控制台比火山账单接口早几个小时出昨天的数（账单要次日下午才落库）
     *  - 用户自己粘贴校准，用来修正"本 App 之外的消耗"
     */
    fun applyOfficialUsage(
        engineId: String,
        monthChars: Long,
        yesterdayChars: Long?,
        dailyChars: List<DayChars> = emptyList(),
    ) {
        cloud[engineId] = CloudBillStatus(
            engineId = engineId,
            monthKey = UsageKeys.monthKey(),
            monthChars = monthChars,
            yesterdayChars = yesterdayChars,
            dailyChars = dailyChars,
            checkedAt = System.currentTimeMillis(),
        )
        recomputeAndPublish()
        Log.i(TAG, "$engineId 控制台同步用量：本月 $monthChars，昨日 $yesterdayChars")
    }

    /** 按链顺序排好序的列表，UI 直接用 */
    fun sortedForDisplay(chainOrder: List<String> = TranslatorIds.PAID_IN_ORDER): List<EngineUsage> {
        val alert = snap.alertThresholdHits
        return snap.engines.values
            .filter { it.configured }
            .sortedWith(
                compareByDescending<EngineUsage> { it.engineId in alert }
                    .thenByDescending { it.isActive }
                    .thenByDescending { (it.lastUsedAt ?: 0L) / 3_600_000L }
                    .thenBy {
                        chainOrder.indexOf(it.engineId).let { i -> if (i < 0) Int.MAX_VALUE else i }
                    },
            )
    }

    // ---------------- 对外写 ----------------

    /** 由 AppContainer 推送"哪些引擎配了 Key / 开着" */
    fun updateConfigured(configured: Set<String>, enabled: Set<String>) {
        if (configured == configuredIds && enabled == enabledIds) return
        configuredIds = configured
        enabledIds = enabled
        recomputeAndPublish()
    }

    /**
     * 记一笔。**同步更新内存**（保证下一句立刻能判到），落库异步。
     */
    fun record(engineId: String, chars: Int, tokensIn: Int, tokensOut: Int, ok: Boolean) {
        val day = UsageKeys.dayKey()
        val delta = DayCounters(
            chars = if (ok) chars.toLong() else 0L,
            requests = 1L,
            tokensIn = if (ok) tokensIn.toLong() else 0L,
            tokensOut = if (ok) tokensOut.toLong() else 0L,
        )

        days.computeIfAbsent(day) { ConcurrentHashMap() }
            .compute(engineId) { _, old ->
                val base = old ?: DayCounters()
                DayCounters(
                    chars = base.chars + delta.chars,
                    requests = base.requests + delta.requests,
                    tokensIn = base.tokensIn + delta.tokensIn,
                    tokensOut = base.tokensOut + delta.tokensOut,
                    costMicro = base.costMicro,
                )
            }

        if (ok) lastUsed.getOrPut(engineId) { AtomicLong() }.set(System.currentTimeMillis())

        // 落库（异步、逐笔；SQLite upsert 很快，串行化反而更容易丢失）
        scope.launch {
            runCatching {
                dao.accumulate(
                    engineId = engineId,
                    dayKey = day,
                    chars = delta.chars,
                    requests = delta.requests,
                    tokensIn = delta.tokensIn,
                    tokensOut = delta.tokensOut,
                    costMicro = 0L,
                    now = System.currentTimeMillis(),
                )
            }.onFailure { Log.w(TAG, "用量落库失败（$engineId）", it) }
        }

        // 是否刚跨线：跨了就立刻刷新（不能等防抖，否则多放过去一句）
        val hadBlocked = snap.engines[engineId]?.blocked == true
        if (!hadBlocked) {
            val nowBlocked = computeUsage(engineId)?.blocked == true
            if (nowBlocked) {
                Log.i(TAG, "$engineId 已到额度上限，下一句起跳过")
                recomputeAndPublish()
                return
            }
        }
        schedulePublish()
    }

    /** 手动校准：把控制台显示的本月已用抄进来 */
    fun calibrate(engineId: String, usedChars: Long) {
        val cfg = config
        val updated = cfg.withEngine(
            cfg.engine(engineId).copy(
                baselineChars = usedChars.coerceAtLeast(0L),
                baselineMonth = UsageKeys.monthKey(),
            ),
        )
        writeConfig(updated)
    }

    /** 恢复"本月从 0 开始"（清掉基准，不动历史明细） */
    fun resetBaseline(engineId: String) {
        calibrate(engineId, 0L)
    }

    /** 用户开了「接近耗尽仍探测」开关 */
    fun setAllowProbeNearLimit(engineId: String, allow: Boolean) {
        val cfg = config
        writeConfig(cfg.withEngine(cfg.engine(engineId).copy(allowProbeNearLimit = allow)))
    }

    fun setQuotaChars(engineId: String, chars: Long) {
        val cfg = config
        writeConfig(cfg.withEngine(cfg.engine(engineId).copy(monthlyQuotaChars = chars.coerceAtLeast(0L))))
    }

    /**
     * 用户批准「本月继续使用告警线以上的剩余额度」。
     *
     * 默认到告警线（默认 90%）就停，把最后那段当安全垫。用户点了弹窗里的
     * 「允许」才放开到 100%（再减一点缓冲），并且只在当月有效——下个月重新问。
     */
    fun approveOverAlert(engineId: String) {
        val cfg = config
        writeConfig(
            cfg.withEngine(
                cfg.engine(engineId).copy(overAlertApprovedMonth = UsageKeys.monthKey()),
            ),
        )
    }

    /** 撤销批准（用户反悔，回到到告警线就停） */
    fun revokeOverAlert(engineId: String) {
        val cfg = config
        writeConfig(cfg.withEngine(cfg.engine(engineId).copy(overAlertApprovedMonth = "")))
    }

    /**
     * 用户接受「额度用完后继续用，费用由厂商向他的云账号收取」。
     *
     * 注意这不是我们收费：钱是厂商扣的（火山 49 元/百万字符、阿里云约 50 元），
     * ScreenSub 不经手账单。授权同样只在本月有效。
     */
    fun approvePaid(engineId: String) {
        val cfg = config
        writeConfig(
            cfg.withEngine(cfg.engine(engineId).copy(paidApprovedMonth = UsageKeys.monthKey())),
        )
    }

    /** 撤销付费授权，回到额度到线就停 */
    fun revokePaid(engineId: String) {
        val cfg = config
        writeConfig(cfg.withEngine(cfg.engine(engineId).copy(paidApprovedMonth = "")))
    }

    /**
     * 写入 LLM 平台账户余额（元）。
     *
     * 只有 DeepSeek / Kimi / 硅基流动 提供余额接口，其余服务商传不进来。
     * 余额 ≤ 0 时该引擎会被强制停用——调用注定失败，没必要每句去撞网络。
     */
    fun applyPlatformBalance(engineId: String, balanceYuan: Double) {
        platformBalance[engineId] = balanceYuan
        recomputeAndPublish()
        Log.i(TAG, "$engineId 平台余额：¥$balanceYuan")
    }

    fun setMonitorEnabled(enabled: Boolean) = writeConfig(config.withMonitor(enabled))

    fun setAutoSwitchEnabled(enabled: Boolean) {
        // 监测关着的时候不允许单独把切换打开，否则会出现"没监测却自动切"的矛盾状态
        if (!config.quotaMonitorEnabled) return
        writeConfig(config.copy(autoSwitchEnabled = enabled))
    }

    fun setAlertPercent(percent: Int) = writeConfig(config.copy(alertPercent = percent.coerceIn(10, 100)))

    fun setLlmBudget(yuan: Double) = writeConfig(config.copy(llmBudgetYuan = yuan.coerceAtLeast(0.0)))

    fun setLlmPrices(inPerMillion: Double, outPerMillion: Double) = writeConfig(
        config.copy(
            llmInPricePerMillion = inPerMillion.coerceAtLeast(0.0),
            llmOutPricePerMillion = outPerMillion.coerceAtLeast(0.0),
        ),
    )

    /** 清空全部用量（调试用，也在"用量页"里给一个入口） */
    fun clearAllUsage() {
        days.clear()
        lastUsed.clear()
        scope.launch {
            runCatching { dao.clear() }.onFailure { Log.w(TAG, "清空用量失败", it) }
            recomputeAndPublish()
        }
    }

    // ---------------- 内部 ----------------

    private fun writeConfig(next: QuotaConfig) {
        config = next
        scope.launch {
            runCatching { settingsStore.update { it.copy(quotaConfigJson = next.toJson()) } }
                .onFailure { Log.w(TAG, "保存额度配置失败", it) }
        }
        recomputeAndPublish()
    }

    private fun absorb(row: UsageDailyEntity) {
        days.computeIfAbsent(row.dayKey) { ConcurrentHashMap() }
            .compute(row.engineId) { _, old ->
                val base = old ?: DayCounters()
                DayCounters(
                    chars = base.chars + row.chars,
                    requests = base.requests + row.requests,
                    tokensIn = base.tokensIn + row.tokensIn,
                    tokensOut = base.tokensOut + row.tokensOut,
                    costMicro = base.costMicro + row.costMicroYuan,
                )
            }
        if (row.updatedAt > (lastUsed[row.engineId]?.get() ?: 0L)) {
            lastUsed[row.engineId] = AtomicLong(row.updatedAt)
        }
    }

    /** 本月某引擎的字符与 token 累计 */
    private fun monthTotals(engineId: String, monthKey: String): LongArray {
        var chars = 0L
        var tin = 0L
        var tout = 0L
        days.forEach { (dayKey, byEngine) ->
            if (!dayKey.startsWith(monthKey)) return@forEach
            byEngine[engineId]?.let {
                chars += it.chars
                tin += it.tokensIn
                tout += it.tokensOut
            }
        }
        return longArrayOf(chars, tin, tout)
    }

    /** 最近 7 日（含今天），缺失的日期补 0，保证柱状图有 7 根 */
    private fun last7(engineId: String): List<DayUsage> {
        val today = LocalDate.now()
        val inPrice = config.llmInPricePerMillion
        val outPrice = config.llmOutPricePerMillion
        return (6 downTo 0).map { offset ->
            val date = today.minusDays(offset.toLong())
            val key = UsageKeys.dayKey(date)
            val c = days[key]?.get(engineId)
            val yuan = if (c == null) 0.0 else yuanOf(c.tokensIn, c.tokensOut, inPrice, outPrice)
            DayUsage(dayKey = key, chars = c?.chars ?: 0L, yuan = yuan)
        }
    }

    private fun yuanOf(tin: Long, tout: Long, inPrice: Double, outPrice: Double): Double =
        tin / 1_000_000.0 * inPrice + tout / 1_000_000.0 * outPrice

    private fun computeUsage(engineId: String): EngineUsage? {
        val cfg = config
        val q = cfg.engine(engineId)
        val monthKey = UsageKeys.monthKey()

        val (chars, tokensIn, tokensOut) = monthTotals(engineId, monthKey).let {
            Triple(it[0], it[1], it[2])
        }
        val baseline = q.baselineFor(monthKey)
        val localUsed = chars + baseline

        // 平台口径优先展示；但**判定取两者较大值**：
        // 平台报表是 T+1，今天已经用掉的部分它还看不见，
        // 只信平台会让停用判定偏晚，那正是"超额度开始扣钱"的场景。
        val cloudStatus = cloud[engineId]
        val official = cloudStatus?.monthChars
        val usedChars = maxOf(localUsed, official ?: 0L)
        val yuan = yuanOf(tokensIn, tokensOut, cfg.llmInPricePerMillion, cfg.llmOutPricePerMillion)

        // 平台欠费是**硬信号**：账单查到欠费，说明已经在免费额度外倒挂了。
        // 火山的本地计数看不见"别的程序/历史遗留"的消耗，这道闸就是给它兜底的：
        // 每天查一次欠费，一旦为正立即停用，不看本地额度算到哪。
        val platformArrears = (cloudStatus?.arrearsYuan ?: 0.0) > 0.0

        // 判定口径：字符额度优先；没配字符额度但配了金额预算的（LLM）用金额
        val percent: Float
        var blocked: Boolean
        when {
            q.hasLimit -> {
                percent = (usedChars.toDouble() / q.monthlyQuotaChars).toFloat()
                // 默认停在告警线（默认 90%），把最后 10% 当作安全垫，**不自动花掉**。
                // 用户显式批准后本月才允许用到 100%（再减一点缓冲，防最后一句整句超额）。
                val stopAt = when {
                    // 用户已接受付费：本引擎不再按额度拦（平台欠费那道闸仍然生效）
                    q.paidApprovedFor(monthKey) -> Long.MAX_VALUE
                    q.overAlertApprovedFor(monthKey) ->
                        (q.monthlyQuotaChars - cfg.disableSafetyBufferChars).coerceAtLeast(0L)
                    else -> (q.monthlyQuotaChars * cfg.alertPercent / 100L).coerceAtLeast(1L)
                }
                blocked = usedChars >= stopAt
            }

            engineId == TranslatorIds.LLM && cfg.llmBudgetYuan > 0 && cfg.llmInPricePerMillion > 0 -> {
                percent = (yuan / cfg.llmBudgetYuan).toFloat()
                blocked = yuan >= cfg.llmBudgetYuan
            }

            else -> {
                percent = 0f
                blocked = false
            }
        }
        if (platformArrears) blocked = true

        // LLM 账户余额见底：调用一定会失败（要么 402 要么欠费），
        // 与其每句都去撞一次网络，不如直接停用并明确告诉用户"该充值了"。
        val llmBalance = platformBalance[engineId]
        val balanceEmpty = engineId == TranslatorIds.LLM && llmBalance != null && llmBalance <= 0.0
        if (balanceEmpty) blocked = true

        // 7 日图优先用平台逐日数据；没有平台数据（火山/未拉取）才回落本地累加
        val officialDaily = cloudStatus?.dailyChars.orEmpty()
        val weekList = if (officialDaily.isNotEmpty()) {
            officialDaily.takeLast(7).map { DayUsage(dayKey = it.day, chars = it.chars, yuan = 0.0) }
        } else {
            last7(engineId)
        }

        return EngineUsage(
            engineId = engineId,
            displayName = EngineNames.of(engineId),
            monthChars = usedChars,
            quotaChars = q.monthlyQuotaChars,
            monthYuan = yuan,
            tokensIn = tokensIn,
            tokensOut = tokensOut,
            budgetYuan = cfg.llmBudgetYuan,
            percent = percent,
            blocked = blocked,
            last7 = weekList,
            cloudChars = official,
            cloudAt = cloudStatus?.checkedAt,
            officialChars = official,
            officialError = cloudStatus?.usageCharsError,
            officialYesterday = cloudStatus?.yesterdayChars,
            officialDaily = officialDaily,
            officialAt = cloudStatus?.checkedAt,
            platformArrears = platformArrears,
            platformArrearsYuan = cloudStatus?.arrearsYuan?.takeIf { it > 0.0 },
            overAlertApproved = q.overAlertApprovedFor(monthKey),
            paidApproved = q.paidApprovedFor(monthKey),
            platformBalanceYuan = llmBalance,
            configured = engineId in configuredIds,
            enabled = engineId in enabledIds,
            lastUsedAt = lastUsed[engineId]?.get()?.takeIf { it > 0 },
        )
    }

    private fun recomputeAndPublish() {
        publishJob?.cancel()
        publishJob = null

        val cfg = config
        val ids = cfg.engines.keys
        val map = LinkedHashMap<String, EngineUsage>(ids.size)
        val alert = HashSet<String>(2)

        ids.forEach { id ->
            val u = computeUsage(id) ?: return@forEach
            map[id] = u
            if (u.percent * 100f >= cfg.alertPercent) alert.add(id)
        }

        val next = UsageSnapshot(
            engines = map,
            alertThresholdHits = alert,
            monthKey = UsageKeys.monthKey(),
            capturedAt = System.currentTimeMillis(),
        )
        snap = next
        _state.value = next
    }

    /** 防抖刷新：字幕每秒一条，不防抖首页会一直重组 */
    private fun schedulePublish() {
        if (publishJob?.isActive == true) return
        publishJob = scope.launch {
            delay(PUBLISH_DEBOUNCE_MS)
            recomputeAndPublish()
        }
    }
}
