// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.mt

import com.screensub.core.common.Log
import com.screensub.core.common.LogTags

/** 翻译相关设置，由 data 层从 DataStore 读出后注入 */
data class TranslationSettings(
    val sourceCode: String = "ja",
    /** 目标语言（字幕显示的语言）。1.0 恒为简体中文。 */
    val targetCode: String = TargetLanguage.CHINESE_SIMPLIFIED,
    /** true = 在线优先（质量好）；false = 离线优先（省钱/断网可用） */
    val preferOnline: Boolean = true,
    val offlineEnabled: Boolean = true,
    /** 谷歌非官方免费接口 */
    val googleFreeEnabled: Boolean = true,
    /** MyMemory 公共 API（兜底） */
    val myMemoryEnabled: Boolean = true,
    /** 阿里云机器翻译（每月 100 万字符免费） */
    val aliyunConfig: AliyunTranslator.AliyunConfig? = null,
    /** 火山引擎机器翻译（每月 200 万字符免费） */
    val volcengineConfig: VolcengineTranslator.VolcengineConfig? = null,
    val llmConfig: LlmTranslator.LlmConfig? = null,
    val azureConfig: AzureTranslator.AzureConfig? = null,
)

/**
 * 按设置装配翻译降级链。
 *
 * 装配规则：
 *  - 只把"配置完整"的引擎放进链里，避免链里躺着必然失败的引擎
 *  - **要配置的排在前面，免 Key 的聚在后面**：用户填过 Key 说明他愿意用，
 *    这些引擎质量也确实更好；免 Key 的三家作为"什么都没配也能用"的兜底
 *  - 离线引擎（ML Kit）永远放在链尾，因为它免费但最慢、质量最差
 *  - 在线优先：LLM → Azure → 阿里云 → 火山 → 谷歌 → MyMemory → ML Kit
 *  - 离线优先：ML Kit → 上述在线部分
 */
object TranslatorFactory {

    private const val TAG = LogTags.MT

    fun buildChain(settings: TranslationSettings): List<Translator> {
        val online = mutableListOf<Translator>()

        // 顺序 = 质量与性价比顺序，必须和设置页左侧的序号完全一致，
        // 否则"序号就是优先级"这句话就是骗人的。
        //
        // 顺序 = 优先级，按"国内可达 + 延迟"排，不按名气排：
        //   LLM        懂上下文，质量最好，排第一
        //   火山云      实测单句 300ms
        //   阿里云      实测单句 413ms
        //   Azure       **排最后**：海外节点，光 TLS 握手 280~510ms，整句 540~750ms；
        //               国内移动网络还经常直接不通，排前面会让每句都先等它一次超时
        // 免 Key 组（谷歌 → MyMemory）放在配置组之后兜底。
        settings.llmConfig?.let { config ->
            if (config.isUsable) online.add(LlmTranslator(config))
        }

        settings.volcengineConfig?.let { config ->
            if (config.isUsable) online.add(VolcengineTranslator(config))
        }

        settings.aliyunConfig?.let { config ->
            if (config.isUsable) online.add(AliyunTranslator(config))
        }

        settings.azureConfig?.let { config ->
            if (config.isUsable) online.add(AzureTranslator(config))
        }

        if (settings.googleFreeEnabled) online.add(GoogleFreeTranslator())
        if (settings.myMemoryEnabled) online.add(MyMemoryTranslator())

        val offline = mutableListOf<Translator>()
        if (settings.offlineEnabled) {
            offline.add(
                MlKitTranslator(
                    configuredSourceCode = settings.sourceCode,
                    configuredTargetCode = settings.targetCode,
                ),
            )
        }

        val chain = if (settings.preferOnline) {
            online + offline
        } else {
            offline + online
        }

        Log.i(
            TAG,
            "翻译链装配完成：${chain.joinToString(" → ") { it.displayName }.ifBlank { "空" }}",
        )
        return chain
    }

    /** 包成带术语保护、熔断、用量上报与额度门禁的翻译器 */
    fun build(
        settings: TranslationSettings,
        glossaryTerms: List<GlossaryProtector.GlossaryTerm> = emptyList(),
        usageSink: UsageSink = NoopUsageSink,
        quotaGate: QuotaGate = AllowAllQuotaGate,
        probeAllowed: (String) -> Boolean = { false },
    ): ResilientTranslator = ResilientTranslator(
        engines = buildChain(settings),
        glossary = GlossaryProtector(glossaryTerms),
        usageSink = usageSink,
        quotaGate = quotaGate,
        probeAllowed = probeAllowed,
    )
}
