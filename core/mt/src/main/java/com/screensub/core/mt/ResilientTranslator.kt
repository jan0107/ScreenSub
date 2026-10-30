// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.mt

import com.screensub.core.common.AppError
import com.screensub.core.common.AppResult
import com.screensub.core.common.Log
import com.screensub.core.common.LogTags
import com.screensub.core.common.TranslationFailureKind
import com.screensub.core.common.TranslatorIds
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 降级链翻译器。
 *
 * 一次 translate 的完整流程：
 *   1. 源=目标语言时直接返回原文（中文视频配中文字幕不该"翻译"）
 *   2. 术语占位符保护（⟦T0⟧ 替换）
 *   3. 按顺序试每个引擎，跳过被熔断的
 *   4. 单引擎有 6 秒总超时，超时即放弃、立刻试下一个
 *   5. 成功的译文做占位符还原
 *   6. 全部失败返回 Err —— **但调用方不应因此不显示字幕，应回退显示原文**
 *
 * ## 瞬时错误不参与熔断
 *
 * `MODEL_DOWNLOADING`（语言包正在下载）是预期中的等待，不是故障。
 * 如果它也算失败，语言包 30 MB 下几分钟，熔断器早就开闸了，
 * 而且会给用户发一条莫名其妙的"翻译失败"通知。所以这类错误：
 *   - 不计入熔断失败次数
 *   - 不改变引擎的健康状态
 *   - 但会作为最终错误类型透传出去，让 UI 能显示"语言包下载中"
 */
class ResilientTranslator(
    private val engines: List<Translator>,
    private val glossary: GlossaryProtector = GlossaryProtector.EMPTY,
    private val perEngineTimeoutMs: Long = 6_000L,
    /** 用量出口。由 AppContainer 注入 QuotaRepository::record */
    private val usageSink: UsageSink = NoopUsageSink,
    /** 额度门禁。同步判定，超限的引擎在本句直接跳过 */
    private val quotaGate: QuotaGate = AllowAllQuotaGate,
    /**
     * 是否允许对某引擎发探测请求。
     *
     * 探测能提前发现"Key 填错了"，但探测会消耗额度，所以由上层（AppContainer）
     * 用 ProbePolicy 判断"剩余额度够不够探"，这里只负责执行。
     * 默认全不允许 —— 宁可不知道 Key 错，也不能让监测机制自己把额度用超。
     */
    private val probeAllowed: (String) -> Boolean = { false },
) : Translator {

    companion object {
        private const val TAG = LogTags.MT

        /** 探测用的最短文本，2 个字符 */
        private const val PROBE_TEXT = "hi"

        /** 探测超时。比正常翻译短，避免拖慢会话启动 */
        private const val PROBE_TIMEOUT_MS = 3_000L

        /** 需要探测的引擎：LLM 自己会预热，免费引擎不需要 Key，离线引擎不联网 */
        private val PROBE_ENGINES = setOf(
            TranslatorIds.ALIYUN,
            TranslatorIds.VOLCENGINE,
            TranslatorIds.AZURE,
        )
    }

    private val breaker = CircuitBreaker()

    /**
     * 准备成功的引擎。
     *
     * 关键：prepare() 失败的引擎**必须从链里摘掉**，不能只是记个日志。
     * 否则每句话都会去撞一次它的连接超时（谷歌在国内可达时 3 秒、不可达时
     * 直接超时），而字幕等不了那么久——表现就是"永远只有原文，
     * 明明配了可用的离线引擎却从没被调用到"。
     */
    private val activeEngines = CopyOnWriteArrayList<Translator>()

    @Volatile
    private var prepared = false

    override val id: String = "resilient"

    override val displayName: String = "翻译链"

    override val isOffline: Boolean = engines.isNotEmpty() && engines.all { it.isOffline }

    /** 暴露给设置页看各引擎健康度 */
    val engineHealth: Map<String, CircuitBreaker.EngineHealth>
        get() = breaker.snapshot()

    val engineNames: List<String> get() = engines.map { it.displayName }

    /** 准备成功后真正在用的引擎（测试翻译页展示这个才有意义） */
    val activeEngineNames: List<String> get() = activeEngines.map { it.displayName }

    /**
     * 最近一次真正翻译成功的是哪个引擎。
     *
     * 用户填了三四家之后，光看设置页根本不知道实际在用哪个——链是按顺序试的，
     * 前面通了就不会试后面的。测试翻译页显示这个，才能回答"到底是谁在干活"。
     */
    @Volatile
    var lastUsedEngineName: String? = null
        private set

    /** 最近一次成功的是哪个引擎（id 版）。提示文案要按 id 查"后面还有没有可用引擎" */
    @Volatile
    var lastUsedEngineId: String? = null
        private set

    /** 本次 translate 里因额度超限被跳过的引擎 id，用来生成准确提示 */
    @Volatile
    var lastSkippedForQuota: List<String> = emptyList()
        private set

    /** 链上装配进来的引擎（静态可用） */
    val chainEngineIds: List<String> get() = engines.map { it.id }

    /**
     * 此刻真正可用的引擎：prepare 通过 且 熔断未打开。
     *
     * 注意不含额度判定——额度是每句都可能变的，而这里只是给 UI 算
     * "还有没有退路"。额度状态由 QuotaGate 在 translate 里逐句判。
     */
    val readyEngineIds: List<String>
        get() = activeEngines.filter { breaker.isAvailable(it.id) }.map { it.id }

    val engineCount: Int get() = engines.size

    override suspend fun prepare(): AppResult<Unit> {
        activeEngines.clear()
        var anyReady = false

        for (engine in engines) {
            val result = engine.prepare()
            if (result is AppResult.Ok) {
                // 探测：这三种引擎的 prepare() 只校验"Key 非空"，填错了要到第一次
                // 真正翻译才暴露。探测一次 2 字符就能提前判断，但要先过额度安全门。
                if (engine.id in PROBE_ENGINES && probeAllowed(engine.id)) {
                    val probeOk = probe(engine)
                    if (!probeOk) {
                        Log.w(TAG, "${engine.id} 探测失败，本会话不再使用")
                        continue
                    }
                }
                activeEngines.add(engine)
                anyReady = true
            } else if (result is AppResult.Err) {
                Log.w(TAG, "${engine.id} 准备失败，本会话不再使用：${result.error.message}")
            }
        }
        prepared = true

        Log.i(
            TAG,
            "可用翻译引擎：" +
                activeEngines.joinToString(" → ") { it.displayName }.ifBlank { "无" },
        )

        return if (anyReady) {
            AppResult.Ok(Unit)
        } else {
            AppResult.Err(
                AppError.Translation(
                    if (engines.isEmpty()) "没有可用的翻译引擎" else "所有翻译引擎都准备失败",
                    if (engines.isEmpty()) {
                        TranslationFailureKind.NO_ENGINE
                    } else {
                        TranslationFailureKind.MODEL_MISSING
                    },
                ),
            )
        }
    }

    /** 源语言变化时通知所有引擎（离线引擎据此后台预热语言包） */
    override fun onSourceLanguageChanged(sourceCode: String) {
        engines.forEach { engine ->
            try {
                engine.onSourceLanguageChanged(sourceCode)
            } catch (t: Throwable) {
                Log.w(TAG, "${engine.id} 处理语言变更失败", t)
            }
        }
    }

    /**
     * 批量翻译：整批交给同一个引擎的一次请求。
     *
     * 为什么要覆写：接口默认实现是"循环调 translate"，那会把 N 句拆成 N 次往返，
     * 批量能力就白做了。这里保留降级链既有行为（额度门禁、熔断、术语保护、用量记账），
     * 只把粒度从"一句"变成"一批"。
     *
     * 失败策略：批里只要有一条失败就整批换下一个引擎；全部引擎失败后逐条回退，
     * 宁可慢，也不让字幕整段消失。
     */
    override suspend fun translateBatch(
        texts: List<String>,
        context: TranslationContext,
    ): List<AppResult<TranslationResult>> {
        if (texts.isEmpty()) return emptyList()
        if (texts.size == 1) return listOf(translate(texts[0], context))
        if (context.isSameLanguage) return texts.map { AppResult.Ok(TranslationResult(it)) }

        val candidates = (if (prepared) activeEngines else engines)
            .filter { breaker.isAvailable(it.id) && !quotaGate.isBlocked(it.id) }

        for (engine in candidates) {
            // 每句单独做术语保护：占位符只在本句内有效，跨句会串味
            val protectedTexts = texts.map { glossary.protect(it) }
            val startedAt = System.currentTimeMillis()

            val outcome = try {
                withTimeoutOrNull(perEngineTimeoutMs) {
                    engine.translateBatch(protectedTexts.map { it.text }, context)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "${engine.id} 批量抛出异常", t)
                null
            }

            if (outcome != null && outcome.all { it is AppResult.Ok }) {
                breaker.onSuccess(engine.id)
                lastUsedEngineName = engine.displayName
                lastUsedEngineId = engine.id
                Log.d(
                    TAG,
                    "${engine.shortName()} 批量成功 ${texts.size} 句 " +
                        "${System.currentTimeMillis() - startedAt}ms",
                )
                return outcome.mapIndexed { i, r ->
                    val value = (r as AppResult.Ok).value
                    val fallback = protectedTexts[i].text.billableChars()
                    recordUsage(
                        engineId = engine.id,
                        chars = if (value.billedChars > 0) value.billedChars else fallback,
                        tokensIn = value.tokensIn,
                        tokensOut = value.tokensOut,
                        ok = true,
                    )
                    AppResult.Ok(
                        value.copy(
                            text = glossary.restore(value.text, protectedTexts[i].tokens),
                        ),
                    )
                }
            }

            val reason = outcome?.firstOrNull { it is AppResult.Err }
                ?.let { (it as AppResult.Err).error.message }
                ?: "批量超时 ${perEngineTimeoutMs}ms"
            breaker.onFailure(engine.id, reason)
            Log.w(TAG, "${engine.shortName()} 批量失败：$reason，换下一个引擎")
        }

        Log.w(TAG, "批量全部失败，退回逐条翻译")
        return texts.map { translate(it, context) }
    }

    override suspend fun translate(
        text: String,
        context: TranslationContext,
    ): AppResult<TranslationResult> {
        if (text.isBlank()) return AppResult.Ok(TranslationResult(""))

        if (engines.isEmpty()) {
            return AppResult.Err(
                AppError.Translation(
                    "没有可用的翻译引擎",
                    TranslationFailureKind.NO_ENGINE,
                ),
            )
        }

        // 源语言与目标语言相同：不该翻（否则中文会被"翻译"成奇怪的东西）
        if (context.isSameLanguage) {
            return AppResult.Ok(TranslationResult(text))
        }

        // 1. 术语保护
        val protectedText = glossary.protect(text)

        // 2. 依次降级
        var lastRealKind = TranslationFailureKind.UNKNOWN
        var sawRealFailure = false
        var sawTransient = false

        /** 这次失败的引擎名，用来拼出"××接口失败，请更换接口" */
        val failedNames = ArrayList<String>(4)

        /**
         * 每个失败引擎的具体原因。
         *
         * 只报"××接口失败"对排查毫无帮助——用户根本不知道是签名错、没开通、
         * 还是限流。这里把底层 message 留下来，最终拼进错误信息，
         * 让用户在测试翻译页直接看到"火山引擎 SignatureDoesNotMatch"这种可行动的提示。
         */
        val failureReasons = LinkedHashMap<String, String>(4)

        // 没跑过 prepare（理论上不会）就退回全部引擎
        val candidates = if (prepared) activeEngines else engines

        if (candidates.isEmpty()) {
            return AppResult.Err(
                AppError.Translation(
                    "所有翻译引擎准备失败，请检查网络或更换接口",
                    TranslationFailureKind.NO_ENGINE,
                ),
            )
        }

        // 每次调用重置"被额度跳过"的记录，供上层生成提示文案
        lastSkippedForQuota = emptyList()
        val quotaSkipped = ArrayList<String>(2)

        // 实际发出去的计费字符（含术语占位符，因为厂商计的就是这份文本）
        val billable = protectedText.text.billableChars()

        for (engine in candidates) {
            if (!breaker.isAvailable(engine.id)) {
                continue
            }

            // 额度门禁：超限的引擎直接跳过，本句落到下一个。
            // 这里是"平滑转移"的实现点——不等这一句失败，而是提前换人。
            if (quotaGate.isBlocked(engine.id)) {
                quotaSkipped.add(engine.id)
                Log.i(TAG, "${engine.shortName()} 已达额度上限，跳过（本句改用下一个引擎）")
                continue
            }

            val startedAt = System.currentTimeMillis()

            val outcome = try {
                withTimeoutOrNull(perEngineTimeoutMs) {
                    engine.translate(protectedText.text, context)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "${engine.id} 抛出异常", t)
                AppResult.Err(AppError.Engine("${engine.id} 异常：${t.message}", t))
            }

            when (outcome) {
                null -> {
                    // 超时属于真实故障
                    breaker.onFailure(engine.id, "超时 ${perEngineTimeoutMs}ms")
                    sawRealFailure = true
                    lastRealKind = TranslationFailureKind.NETWORK
                    failedNames.add(engine.shortName())
                    failureReasons[engine.shortName()] = "超时 ${perEngineTimeoutMs}ms"
                    recordUsage(engine.id, chars = billable, tokensIn = 0, tokensOut = 0, ok = false)
                    Log.w(TAG, "${engine.shortName()} 超时 ${perEngineTimeoutMs}ms，降级下一个")
                }

                is AppResult.Ok -> {
                    breaker.onSuccess(engine.id)
                    lastUsedEngineName = engine.displayName
                    lastUsedEngineId = engine.id

                    val result = outcome.value
                    // 引擎没自报字数就按实际发送文本长度补算（多数引擎就是这样计费的）
                    recordUsage(
                        engineId = engine.id,
                        chars = if (result.billedChars > 0) result.billedChars else billable,
                        tokensIn = result.tokensIn,
                        tokensOut = result.tokensOut,
                        ok = true,
                    )

                    Log.d(
                        TAG,
                        "${engine.shortName()} 成功 ${System.currentTimeMillis() - startedAt}ms" +
                            "，计费 ${result.billedChars.orFallback(billable)} 字符",
                    )

                    // 3. 占位符还原
                    return AppResult.Ok(
                        result.copy(text = glossary.restore(result.text, protectedText.tokens)),
                    )
                }

                is AppResult.Err -> {
                    val kind = (outcome.error as? AppError.Translation)?.kind
                        ?: TranslationFailureKind.UNKNOWN

                    if (kind.isTransient) {
                        // 语言包下载中：不记熔断，只标记，继续试下一个引擎
                        sawTransient = true
                        Log.d(TAG, "${engine.shortName()} 暂不可用（${kind.name}）：${outcome.error.message}")
                    } else {
                        breaker.onFailure(engine.id, outcome.error.message)
                        sawRealFailure = true
                        lastRealKind = kind
                        failedNames.add(engine.shortName())
                        failureReasons[engine.shortName()] = outcome.error.message
                        recordUsage(engine.id, chars = billable, tokensIn = 0, tokensOut = 0, ok = false)
                        Log.w(TAG, "${engine.shortName()} 失败：${outcome.error.message}，降级下一个")
                    }
                }
            }
        }

        lastSkippedForQuota = quotaSkipped

        // 4. 汇总失败原因：真实故障优先，其次"还在下载"，最后才是"全被额度挡了"
        val finalKind = when {
            sawRealFailure -> lastRealKind
            sawTransient -> TranslationFailureKind.MODEL_DOWNLOADING
            quotaSkipped.isNotEmpty() -> TranslationFailureKind.QUOTA_EXHAUSTED
            else -> TranslationFailureKind.UNKNOWN
        }

        // 失败引擎要去重（熔断打开的引擎会被重复计入）
        val names = failedNames.distinct()

        // 文案直接点名是哪家失败——用户才知道该去关哪一家 / 换哪一家。
        // 只说"翻译失败"对他没有任何指导意义。
        val detail = when {
            finalKind == TranslationFailureKind.MODEL_DOWNLOADING -> "翻译语言包正在下载，请稍候"
            finalKind == TranslationFailureKind.QUOTA_EXHAUSTED && names.isEmpty() ->
                "已启用的翻译引擎额度都已用尽"
            names.isEmpty() -> "所有翻译引擎都不可用，请更换翻译接口"
            names.size == 1 -> {
                val reason = failureReasons[names[0]].orEmpty()
                if (reason.isBlank()) "${names[0]}接口失败，请更换翻译接口"
                else "${names[0]}失败：$reason"
            }

            else -> names.joinToString("；") { n ->
                val reason = failureReasons[n].orEmpty()
                if (reason.isBlank()) n else "$n（$reason）"
            } + " 都失败了"
        }

        return AppResult.Err(
            AppError.Translation(
                message = detail,
                kind = finalKind,
                engineName = names.singleOrNull(),
            ),
        )
    }

    /**
     * 发一次 "hi" 验证引擎真的能调通。
     *
     * 结果处理：
     *  - 成功 → 记账（这 2 个字符是真花掉的），返回 true
     *  - 暂态失败（语言包下载中）→ 返回 true，不能因为还在下载就把引擎摘掉
     *  - 其他失败（签名错 / 401 / 欠费）→ 记账并返回 false，本会话摘除
     */
    private suspend fun probe(engine: Translator): Boolean {
        val ctx = TranslationContext(
            sourceCode = "en",
            targetCode = TargetLanguage.CHINESE_SIMPLIFIED,
        )
        val outcome = try {
            withTimeoutOrNull(PROBE_TIMEOUT_MS) { engine.translate(PROBE_TEXT, ctx) }
        } catch (t: Throwable) {
            Log.w(TAG, "${engine.id} 探测异常", t)
            null
        }

        return when (outcome) {
            null -> {
                recordUsage(engine.id, PROBE_TEXT.billableChars(), 0, 0, ok = false)
                Log.w(TAG, "${engine.id} 探测超时 ${PROBE_TIMEOUT_MS}ms")
                false
            }

            is AppResult.Ok -> {
                recordUsage(engine.id, PROBE_TEXT.billableChars(), 0, 0, ok = true)
                Log.i(TAG, "${engine.id} 探测通过（消耗 ${PROBE_TEXT.billableChars()} 字符额度）")
                true
            }

            is AppResult.Err -> {
                val kind = (outcome.error as? AppError.Translation)?.kind
                    ?: TranslationFailureKind.UNKNOWN
                recordUsage(engine.id, PROBE_TEXT.billableChars(), 0, 0, ok = false)
                if (kind.isTransient) {
                    Log.d(TAG, "${engine.id} 探测遇到暂态状态（${kind.name}），保留")
                    true
                } else {
                    Log.w(TAG, "${engine.id} 探测失败：${outcome.error.message}")
                    false
                }
            }
        }
    }

    /**
     * 记账。**任何异常都不能影响翻译** —— 用量统计是附属功能，
     * 不能因为统计挂了导致字幕不出。
     */
    private fun recordUsage(
        engineId: String,
        chars: Int,
        tokensIn: Int,
        tokensOut: Int,
        ok: Boolean,
    ) {
        try {
            usageSink.record(
                UsageEvent(
                    engineId = engineId,
                    mode = billingModeOf(engineId),
                    chars = chars,
                    tokensIn = tokensIn,
                    tokensOut = tokensOut,
                    ok = ok,
                ),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "记录用量失败（$engineId）", t)
        }
    }

    override fun release() {
        engines.forEach { engine ->
            try {
                engine.release()
            } catch (_: Throwable) {
            }
        }
        breaker.resetAll()
        activeEngines.clear()
        prepared = false
    }

    override fun close() = release()
}

/**
 * 通知里用的短名。
 * displayName 带括号说明（"谷歌翻译（免 Key）"），放进"××接口失败"里太长，
 * 只取括号前的部分。
 */
internal fun Translator.shortName(): String = displayName.substringBefore("（").trim()

/**
 * 按 Unicode 码点计字符数。
 *
 * 厂商口径是"一个汉字、一个字母、一个数字、一个符号或空格都算一个字符"，
 * 与 `String.length`（UTF-16 单元数）在 BMP 范围内一致，
 * 但 emoji 之类代理对会被算成 2。字幕里偶有 emoji，用码点更贴近账单。
 */
internal fun String.billableChars(): Int = if (isEmpty()) 0 else codePointCount(0, length)

/** 引擎没自报字数时用兜底值 */
private fun Int.orFallback(fallback: Int): Int = if (this > 0) this else fallback
