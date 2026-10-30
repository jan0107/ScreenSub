// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.mt

import com.screensub.core.common.AppResult

/**
 * 翻译目标语言。
 *
 * 1.0 只做简体中文，但通过 [DEFAULT] / [SUPPORTED] 预留扩展位：
 * 以后加语言只需在这里补常量并加入 [SUPPORTED]，不用改引擎接口。
 */
object TargetLanguage {
    const val CHINESE_SIMPLIFIED = "zh-Hans"

    /** ML Kit 用的是不带 Hans 的短码 */
    const val MLKIT_CHINESE = "zh"

    const val ENGLISH = "en"
    const val JAPANESE = "ja"
    const val KOREAN = "ko"
    const val RUSSIAN = "ru"

    /** 默认目标语言 */
    const val DEFAULT = CHINESE_SIMPLIFIED

    /**
     * 支持的目标语言。
     *
     * 原来只做中文，现在放开到四门常用语言——
     * 翻译方向本来是"任意→中文"，字幕场景同样有人需要中文→外语（学语言、给外国人看）。
     *
     * 各引擎都能吃两位小写码（内部统一 substringBefore('-') 处理），
     * 所以这里直接用 zh-Hans / en / ja / ko / ru 就行。
     */
    val SUPPORTED = listOf(
        CHINESE_SIMPLIFIED, ENGLISH, JAPANESE, KOREAN, RUSSIAN,
    )
}

/**
 * 翻译上下文。
 *
 * [targetCode] 放在这里而不是引擎构造函数里：目标的语言是"每句话的参数"，
 * 不是引擎身份。放这里以后做界面切换目标语言时零成本；放构造函数则每次切换都得重建整条链。
 *
 * [previousSourceText] 只带 1~2 句：更长的上下文会让 LLM"续写"上一句，
 * 而不是翻译当前句——这是字幕场景的典型翻车点。传统 NMT（Azure）用不到这个字段。
 */
data class TranslationContext(
    val sourceCode: String,
    val targetCode: String = TargetLanguage.CHINESE_SIMPLIFIED,
    val previousSourceText: String? = null,
    val previousTranslatedText: String? = null,
) {
    /** 源和目标同语言时不该翻译（如中文视频配中文字幕），直接返回原文 */
    val isSameLanguage: Boolean
        get() {
            if (sourceCode.isBlank() || sourceCode.equals("auto", ignoreCase = true)) return false
            val src = sourceCode.substringBefore('-').lowercase()
            val tgt = targetCode.substringBefore('-').lowercase()
            return src == tgt
        }
}

/**
 * 一次成功翻译的结果。
 *
 * 原来 `translate()` 只返回 `String`，导致两件事做不到：
 *  1. 用量统计拿不到"这一句到底花了多少"（LLM 的 token 只能从响应里读，跟文本长度无关）
 *  2. 火山返回的 `DetectedSourceLanguage` 白白扔掉，"源语言自动跟随"只能靠另做检测
 *
 * [billedChars] 留 0 = 让调用方用文本长度补算（多数引擎就是"发多少字符就计多少"）。
 */
data class TranslationResult(
    val text: String,
    /** 引擎自报的计费字符；0 = 由 ResilientTranslator 用文本长度补算 */
    val billedChars: Int = 0,
    val tokensIn: Int = 0,
    val tokensOut: Int = 0,
    /** 引擎识别出的源语言（火山提供，其余为 null） */
    val detectedLanguage: String? = null,
) {
    companion object {
        fun of(text: String) = TranslationResult(text)
    }
}

/**
 * 翻译引擎抽象。
 *
 * 设计原则：
 * - 所有引擎统一返回 [AppResult]，失败不抛异常，方便串成降级链
 * - [isOffline] 用于 UI 标注"免费/可能联网"
 * - 引擎自己不做重试，重试与熔断交给 [ResilientTranslator]
 */
interface Translator : AutoCloseable {

    val id: String

    val displayName: String

    /** 是否完全离线（离线引擎不吃流量、不花配额） */
    val isOffline: Boolean

    /** 模型/客户端准备。离线引擎会在此下载语言包。 */
    suspend fun prepare(): AppResult<Unit>

    /**
     * 翻译一句。
     * @return 成功时返回译文与用量；失败返回 AppError，调用方应继续试下一个引擎
     */
    suspend fun translate(text: String, context: TranslationContext): AppResult<TranslationResult>

    /**
     * 批量翻译，返回顺序与 [texts] 一一对应。
     *
     * 默认实现逐个调用 [translate]；支持批量能力的引擎（火山 `TextList`、
     * 阿里云 `GetBatchTranslate`）应当覆写它。
     *
     * ## 为什么要批量
     *
     * 字幕按标点切成"一句一条"后，**一次识别会产出多条文本**。
     * 逐条发就是 N 次往返——移动网络下每条约 300ms，切 3 句就是 900ms，
     * 越切越慢，和"让字幕更及时"的目标正好相反。
     *
     * 压成一次请求后：请求数不变（不撞 QPS）、延迟是一次往返而非 N 次累加、
     * 且计费按字符累计，字符总量不因切分而增加。
     */
    suspend fun translateBatch(
        texts: List<String>,
        context: TranslationContext,
    ): List<AppResult<TranslationResult>> = texts.map { translate(it, context) }

    /**
     * 源语言发生变化（自动检测模式下首次锁定、或连续多句后改锁）。
     *
     * 离线引擎（ML Kit）据此**在后台预热新语言包** —— 注意是后台，
     * 绝不能在这里同步下载：翻译请求有 6 秒超时，同步下载会被反复砍断，
     * 表现为"每句都失败"，反而触发误告警。
     */
    fun onSourceLanguageChanged(sourceCode: String) {}

    fun release()

    override fun close() = release()
}
