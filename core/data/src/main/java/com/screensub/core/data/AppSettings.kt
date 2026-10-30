// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.data

/**
 * 全部可持久化设置。
 *
 * 刻意用一个扁平 data class + DataStore Preferences，
 * 不上 Room：设置项就这十几个，Room 是杀鸡用牛刀，而且改动要写迁移。
 */
data class AppSettings(

    // ---------- 音频 ----------
    /** "internal" = 内录；"mic" = 麦克风外录 */
    val audioSource: String = "internal",

    /** 内录检测到被禁止时，是否自动切外录 */
    val autoFallbackToMic: Boolean = true,

    /** 外录增益（倍数）。外放音量偏小时有用 */
    val micGain: Float = 1.0f,

    // ---------- 识别 ----------
    /** 源语言代码：auto / ja / en / ru / ko / zh */
    val sourceLanguage: String = "auto",

    /** 使用的模型 id */
    val modelId: String = "sense_voice_int8",

    // ---------- 翻译 ----------
    /** true = 在线优先（质量好）；false = 离线优先（免费/断网可用） */
    val preferOnlineTranslation: Boolean = true,

    /** 是否启用 ML Kit 离线翻译作为兜底 */
    val offlineTranslationEnabled: Boolean = true,

    /**
     * 谷歌翻译（免 Key，非官方接口）。
     *
     * **默认关闭**。这个接口在国内基本不可达（translate.googleapis.com 直连不通，
     * 系统的 fake-IP 解析还会给出假地址）。开着它的代价很实在：每次装配翻译链
     * 都要等一次探测超时，实测 2.4 秒上下，而这段时间字幕是翻不出来的。
     *
     * 有代理/能出国的用户可以在设置里手动打开，能连通就会自动生效。
     */
    val googleTranslationEnabled: Boolean = false,

    /**
     * MyMemory（免 Key 公共 API）。
     *
     * 作为谷歌不可达时的兜底。质量中等，匿名额度很小（每天约 1000 词），
     * 跑长片会中途耗尽——耗尽后本次会话自动停用。
     */
    val myMemoryTranslationEnabled: Boolean = true,

    /**
     * 目标语言（字幕显示的语言）。
     * 1.0 只支持简体中文，但这里保留字段而不是写死：
     * 以后加语言只需扩 [com.screensub.core.mt.TargetLanguage.SUPPORTED]。
     */
    val targetLanguage: String = "zh-Hans",

    /**
     * 选中的 LLM 服务商（deepseek / qwen / openai / custom）。
     * Base URL 和模型名由预设表决定，用户只需要填一个 Key；
     * 选 custom 时才用下面的 llmBaseUrl / llmModel 手填值。
     */
    val llmProvider: String = "deepseek",

    /** OpenAI 兼容的 LLM 配置 */
    val llmBaseUrl: String = "",
    val llmApiKey: String = "",
    val llmModel: String = "",

    /**
     * 各在线引擎的启用开关。
     * 光看"填没填"没法控制——三家都填了到底用哪个，只能靠这个开关明确指定，
     * 而且链的顺序还会再决定一次优先级。
     */
    val llmTranslationEnabled: Boolean = true,
    val azureTranslationEnabled: Boolean = true,
    val aliyunTranslationEnabled: Boolean = true,
    val volcengineTranslationEnabled: Boolean = true,

    /**
     * 阿里云机器翻译。每月 100 万字符免费，无停服公告。
     * AccessKey 在 RAM 控制台创建，注意要给它 AliyunMTFullAccess 权限。
     */
    val aliyunAccessKeyId: String = "",
    val aliyunAccessKeySecret: String = "",

    /**
     * 火山引擎机器翻译。
     *
     * 每月 **200 万字符免费，按自然月刷新**（每月 1 号重置），
     * 一集约 5000 字符 ≈ 每月 400 集。超出后 49 元/百万字符，不会自动停服。
     *
     * 凭据是 IAM 的 **AccessKeyId + SecretAccessKey**。
     * 别填 AppID/Token —— 那是火山另一个产品「豆包翻译大模型」的凭据（按 token 计费、
     * 赠送额度一次性），两套互不通用。
     */
    val volcengineAccessKeyId: String = "",
    val volcengineSecretAccessKey: String = "",

    /** Azure Translator 配置 */
    val azureKey: String = "",
    val azureRegion: String = "global",

    /**
     * 额度与用量监测配置（序列化后的 [QuotaConfig]）。
     *
     * 压成一个字段的理由见 [QuotaConfig]：`SettingsStore.update{}` 是全量读写，
     * 每多一个独立字段就要多改 5 处，漏一处就会静默丢配置。
     * 空串 = 用默认值（默认开启 90% 监测与自动切换）。
     */
    val quotaConfigJson: String = "",

    // ---------- 字幕外观 ----------
    val subtitleFontSizeSp: Float = 18f,

    /**
     * 字幕文字颜色（ARGB Int）。默认纯白。
     *
     * 用 Int 而不是 @ColorInt 资源：颜色要跟着设置实时变，
     * 存整数最直接，overlay 侧 setTextColor 直接用。
     * 深色视频配白色、浅色视频配黄/青更好读，所以做成可选。
     */
    val subtitleTextColor: Int = 0xFFFFFFFF.toInt(),

    val subtitleShowSource: Boolean = false,
    val subtitleBackgroundAlpha: Float = 0.45f,
    val subtitleMaxLines: Int = 2,
    val subtitleBottomMarginDp: Int = 96,
    val subtitleDraggable: Boolean = true,

    // ---------- 其他 ----------
    /**
     * HuggingFace 下载走国内镜像 hf-mirror.com。
     *
     * 默认开：huggingface.co 在国内直连基本不通，关着的话模型永远下不下来。
     * 实测镜像上五个模型的 16 个文件都能正常返回（含 236MB 的俄语模型），
     * 所以这里没有理由再让用户先去踩一遍墙。
     */
    val useHfMirror: Boolean = true,

    /** 是否记录字幕历史（用于导出 SRT） */
    val recordHistory: Boolean = true,

    val verboseLog: Boolean = false,
) {
    val isMicMode: Boolean get() = audioSource == "mic"

    companion object {
        val DEFAULT = AppSettings()
    }
}

/**
 * 切换源语言并做模型联动。
 *
 * 语言与模型是**一一对应**的（见 `ModelCatalog.defaultFor`），不是"按需切换"。
 * 抽成扩展函数是为了让首页和设置页共用同一份逻辑，避免两处行为漂移。
 */
fun AppSettings.withSourceLanguage(lang: com.screensub.core.asr.SourceLanguage): AppSettings {
    // 早期版本只做了"切俄语→Whisper"这一半，离开俄语不切回，
    // 结果 modelId 会永久卡在 Whisper：用户选中文/韩语时用的还是 Whisper，
    // 而 Whisper 根本没下载 → 首页一直弹"没下载模型"，用户一脸懵。
    //
    // 现在规则一句话：语言决定模型。
    //   中 / 英 → X-ASR（带标点）    日 → ReazonSpeech
    //   韩     → Zipformer 韩语      俄 → GigaAM v2 CTC
    //   自动   → SenseVoice（多语兜底，默认关闭，用前要单独下）
    val target = com.screensub.core.asr.ModelCatalog.defaultFor(lang)
    return copy(
        sourceLanguage = lang.code,
        modelId = target.id,
    )
}
