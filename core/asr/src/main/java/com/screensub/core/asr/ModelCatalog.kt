// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.asr

/** 源语言。决定用哪个模型、以及翻译引擎的语言对。 */
enum class SourceLanguage(val code: String, val label: String) {

    /** 交给模型自己判断（只有 SenseVoice 支持，兜底用） */
    AUTO("auto", "自动识别"),

    JAPANESE("ja", "日语"),
    ENGLISH("en", "英语"),
    RUSSIAN("ru", "俄语"),
    KOREAN("ko", "韩语"),
    CHINESE("zh", "中文"),
    ;

    companion object {
        fun fromCode(code: String): SourceLanguage =
            entries.firstOrNull { it.code == code } ?: AUTO
    }
}

/** 引擎类型，决定用哪套 sherpa-onnx 配置类。 */
enum class ModelEngineKind {
    /** SenseVoice：单文件、多语、自带标点，兜底用 */
    SENSE_VOICE,

    /** Zipformer 家族：encoder + decoder + joiner 三件套（离线 transducer） */
    TRANSDUCER,

    /** NeMo CTC：单文件，无幻觉，俄语主力 */
    NEMO_CTC,
}

/**
 * 单个模型文件。
 * @param fileName 落到本地目录后的文件名
 * @param urls **多个下载源，按优先级排列**。第一个失败会自动换下一个。
 *             主源是 HuggingFace 原始地址（可被镜像开关改写成 hf-mirror），
 *             备源是自建的 ModelScope 镜像仓库。
 * @param sizeBytes 期望大小。**必须是精确字节数**——下载器用它判断"要不要续传"：
 *                  大小不符会从文件末尾发 Range 请求，服务器返回 416，下载永远失败。
 */
data class ModelFile(
    val fileName: String,
    val urls: List<String>,
    val sizeBytes: Long,
) {
    val primaryUrl: String get() = urls.first()
}

/**
 * 一个 ASR 模型。
 *
 * ## 为什么要拆成"一语言一模型"
 *
 * 2026-10-28 之前是「SenseVoice 一个模型管五语 + Whisper 管俄语」。
 * 逐语言比对公开评测后发现，专用模型对各自语言的错误率明显更低：
 *
 * | 语言 | 专用模型 | 对照 |
 * |---|---|---|
 * | 中/英 | X-ASR 会议杂音 7.72% / 英文 2.76% | SenseVoice 9.68% / 3.41% |
 * | 俄 | GigaAM v2 CER 1.06% | Whisper-large-v3 13.9~28% |
 * | 韩 | Zipformer 专用 7.53% | 1.7B 多语模型 9.99% |
 * | 日 | ReazonSpeech（日媒语料） | — |
 *
 * 代价是**没有回落**：专用模型各管各语，缺哪个就提示下哪个。
 * SenseVoice 保留为显式兜底（默认关闭），唯一用途是"自动识别语种"。
 */
data class AsrModelSpec(
    val id: String,
    val displayName: String,
    val description: String,
    val engineKind: ModelEngineKind,
    /** 这个模型负责的语言（不含 AUTO） */
    val languages: List<SourceLanguage>,
    val files: List<ModelFile>,
    /** 主模型文件：SenseVoice/CTC 的单文件，或 transducer 的 encoder */
    val encoderFileName: String,
    val tokensFileName: String,
    /** transducer 专用 */
    val decoderFileName: String? = null,
    val joinerFileName: String? = null,
    /**
     * 默认是否开启。
     *
     * 四个专用模型默认开（用户填了对应语言就下载），
     * SenseVoice 默认关——它体积 228MB 且只作兜底，不该让所有人都先下它。
     */
    val defaultEnabled: Boolean = false,
    /** 是否"多语兜底"模型（只有它能处理 AUTO） */
    val isFallback: Boolean = false,
) {
    fun supports(language: SourceLanguage): Boolean = languages.contains(language)

    /** 四舍五入到 MB，用于 UI 上的"约 XX MB" */
    val approxSizeMb: Int
        get() = ((files.sumOf { it.sizeBytes } + 524_288L) / 1_048_576L).toInt()

    /** 全部文件名，用于"是否已下载"判断 */
    val fileNames: List<String> get() = files.map { it.fileName }
}

object ModelCatalog {

    private const val HF = "https://huggingface.co"

    private const val REPO_X_ASR =
        "$HF/csukuangfj2/sherpa-onnx-x-asr-zipformer-transducer-zh-en-punct-int8-2026-06-03/resolve/main"

    private const val REPO_GIGAAM =
        "$HF/csukuangfj/sherpa-onnx-nemo-ctc-giga-am-v2-russian-2025-04-19/resolve/main"

    private const val REPO_JA =
        "$HF/csukuangfj/reazonspeech-k2-v2/resolve/main"

    private const val REPO_KO =
        "$HF/k2-fsa/sherpa-onnx-zipformer-korean-2024-06-24/resolve/main"

    private const val REPO_SENSE_VOICE =
        "$HF/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main"

    private const val GITHUB_RELEASE =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"

    /**
     * 自建镜像（ModelScope）。
     *
     * 为什么要有第二源：HuggingFace 直连国内不通，必须靠 hf-mirror；
     * 而镜像站是第三方服务，哪天限速/挂掉就没辙了。这里放一份自己的副本做兜底。
     *
     * 实测：匿名可下、302 跳转后 `Range` 仍生效（`Content-Range` 正常返回），
     * 所以断点续传在两个源之间是通用的 —— 换个源接着下即可，不用从头来。
     */
    private const val MS_MIRROR =
        "https://modelscope.cn/models/January3901/screensub-asr/resolve/master"

    /**
     * 生成一个"双源"文件条目。
     *
     * @param hfPath HuggingFace 仓库内相对路径（会被镜像开关改写成 hf-mirror）
     * @param msPath ModelScope 仓库内相对路径（含子目录）
     */
    private fun dualSource(
        fileName: String,
        repo: String,
        hfPath: String,
        msPath: String,
        sizeBytes: Long,
    ) = ModelFile(
        fileName = fileName,
        urls = listOf("$repo/$hfPath", "$MS_MIRROR/$msPath"),
        sizeBytes = sizeBytes,
    )

    /**
     * Silero VAD，仅 629 KB。
     * 这个是"必需件"，建议随 APK 一起打进 assets，避免首启还要下。
     */
    val SILERO_VAD = ModelFile(
        fileName = "silero_vad.onnx",
        // 只有 GitHub 一个源：629KB 的小文件，不值得为它再传一份镜像
        urls = listOf("$GITHUB_RELEASE/silero_vad.onnx"),
        sizeBytes = 629_145L,
    )

    // ---------------- 1. 中/英 ----------------

    /** X-ASR：中英主力，自带标点与数字归一（ITN） */
    val X_ASR = AsrModelSpec(
        id = "x_asr_zh_en",
        displayName = "X-ASR 中英专用",
        description = "会议与混合杂音下错误率最低的一档，自带标点、数字归一。只认中英，别拿它听日语。",
        engineKind = ModelEngineKind.TRANSDUCER,
        languages = listOf(SourceLanguage.CHINESE, SourceLanguage.ENGLISH),
        files = listOf(
            dualSource(
                "encoder.int8.onnx", REPO_X_ASR,
                "encoder-epoch-99-avg-1.int8.onnx", "x_asr_zh_en/encoder.int8.onnx",
                161_744_450L,
            ),
            dualSource(
                "decoder.onnx", REPO_X_ASR,
                "decoder-epoch-99-avg-1.onnx", "x_asr_zh_en/decoder.onnx",
                11_309_084L,
            ),
            dualSource(
                "joiner.int8.onnx", REPO_X_ASR,
                "joiner-epoch-99-avg-1.int8.onnx", "x_asr_zh_en/joiner.int8.onnx",
                2_581_422L,
            ),
            dualSource(
                "tokens.txt", REPO_X_ASR,
                "tokens.txt", "x_asr_zh_en/tokens.txt",
                58_806L,
            ),
        ),
        encoderFileName = "encoder.int8.onnx",
        decoderFileName = "decoder.onnx",
        joinerFileName = "joiner.int8.onnx",
        tokensFileName = "tokens.txt",
        defaultEnabled = true,
    )

    // ---------------- 2. 俄语 ----------------

    /**
     * GigaAM v2 CTC（Sber）。
     *
     * 俄语 CER 约 1%，把 Whisper 按在地上摩擦。CTC 结构不存在自回归幻觉，
     * 所以俄语这条路不再需要 HallucinationFilter —— 当初那个过滤器是为 Whisper 写的。
     */
    val GIGAAM_RU = AsrModelSpec(
        id = "gigaam_ru",
        displayName = "GigaAM v2 俄语专用",
        description = "Sber 的俄语模型，CER 约 1%（Whisper 是 14~28%）。CTC 无幻觉，不需要事后过滤。",
        engineKind = ModelEngineKind.NEMO_CTC,
        languages = listOf(SourceLanguage.RUSSIAN),
        files = listOf(
            dualSource(
                "model.int8.onnx", REPO_GIGAAM,
                "model.int8.onnx", "gigaam_ru/model.int8.onnx",
                236_457_977L,
            ),
            dualSource(
                "tokens.txt", REPO_GIGAAM,
                "tokens.txt", "gigaam_ru/tokens.txt",
                196L,
            ),
        ),
        encoderFileName = "model.int8.onnx",
        tokensFileName = "tokens.txt",
        defaultEnabled = true,
    )

    // ---------------- 3. 日语 ----------------

    /** ReazonSpeech：日本电视媒体语料训练，对口日剧/日漫/综艺 */
    val JA_REAZON = AsrModelSpec(
        id = "ja_reazonspeech",
        displayName = "ReazonSpeech 日语专用",
        description = "用日本电视媒体语料训练的日语模型，对口日剧、日漫、综艺，比多语模型稳得多。",
        engineKind = ModelEngineKind.TRANSDUCER,
        languages = listOf(SourceLanguage.JAPANESE),
        files = listOf(
            dualSource(
                "encoder.int8.onnx", REPO_JA,
                "encoder-epoch-99-avg-1.int8.onnx", "ja_reazonspeech/encoder.int8.onnx",
                154_670_139L,
            ),
            dualSource(
                "decoder.int8.onnx", REPO_JA,
                "decoder-epoch-99-avg-1.int8.onnx", "ja_reazonspeech/decoder.int8.onnx",
                2_959_337L,
            ),
            dualSource(
                "joiner.int8.onnx", REPO_JA,
                "joiner-epoch-99-avg-1.int8.onnx", "ja_reazonspeech/joiner.int8.onnx",
                2_696_970L,
            ),
            dualSource(
                "tokens.txt", REPO_JA,
                "tokens.txt", "ja_reazonspeech/tokens.txt",
                45_754L,
            ),
        ),
        encoderFileName = "encoder.int8.onnx",
        decoderFileName = "decoder.int8.onnx",
        joinerFileName = "joiner.int8.onnx",
        tokensFileName = "tokens.txt",
        defaultEnabled = true,
    )

    // ---------------- 4. 韩语 ----------------

    /** 韩语专用 Zipformer：155M 参数就打赢了 1.7B 的多语模型 */
    val KO_ZIPFORMER = AsrModelSpec(
        id = "ko_zipformer",
        displayName = "Zipformer 韩语专用",
        description = "韩语 CER 7.53%，胜过 1.7B 多语大模型（9.99%）。体积小，是五语里最省的一个。",
        engineKind = ModelEngineKind.TRANSDUCER,
        languages = listOf(SourceLanguage.KOREAN),
        files = listOf(
            dualSource(
                "encoder.int8.onnx", REPO_KO,
                "encoder-epoch-99-avg-1.int8.onnx", "ko_zipformer/encoder.int8.onnx",
                70_784_728L,
            ),
            dualSource(
                "decoder.int8.onnx", REPO_KO,
                "decoder-epoch-99-avg-1.int8.onnx", "ko_zipformer/decoder.int8.onnx",
                2_844_692L,
            ),
            dualSource(
                "joiner.int8.onnx", REPO_KO,
                "joiner-epoch-99-avg-1.int8.onnx", "ko_zipformer/joiner.int8.onnx",
                2_581_421L,
            ),
            dualSource(
                "tokens.txt", REPO_KO,
                "tokens.txt", "ko_zipformer/tokens.txt",
                60_246L,
            ),
        ),
        encoderFileName = "encoder.int8.onnx",
        decoderFileName = "decoder.int8.onnx",
        joinerFileName = "joiner.int8.onnx",
        tokensFileName = "tokens.txt",
        defaultEnabled = true,
    )

    // ---------------- 5. 多语兜底 ----------------

    /**
     * SenseVoice：唯一能处理"自动识别语种"的模型，所以保留。
     *
     * 默认**关闭**——它 228MB，而专用模型已经覆盖了五个语种。
     * 只有用户真的需要"不知道语言、让模型自己判断"时才该打开。
     */
    val SENSE_VOICE = AsrModelSpec(
        id = "sense_voice_int8",
        displayName = "SenseVoice 多语兜底",
        description = "中/英/日/韩/粤 + 自动识别语种。专供「不知道是什么语言」的场景，已知语种请选上面的专用模型。",
        engineKind = ModelEngineKind.SENSE_VOICE,
        languages = listOf(
            SourceLanguage.CHINESE,
            SourceLanguage.ENGLISH,
            SourceLanguage.JAPANESE,
            SourceLanguage.KOREAN,
        ),
        files = listOf(
            dualSource(
                "model.int8.onnx", REPO_SENSE_VOICE,
                "model.int8.onnx", "sense_voice_int8/model.int8.onnx",
                239_233_841L,
            ),
            dualSource(
                "tokens.txt", REPO_SENSE_VOICE,
                "tokens.txt", "sense_voice_int8/tokens.txt",
                315_894L,
            ),
        ),
        encoderFileName = "model.int8.onnx",
        tokensFileName = "tokens.txt",
        defaultEnabled = false,
        isFallback = true,
    )

    /** 列表顺序 = 设置页顺序：中英 → 俄 → 日 → 韩 → 兜底 */
    val ALL = listOf(X_ASR, GIGAAM_RU, JA_REAZON, KO_ZIPFORMER, SENSE_VOICE)

    fun findById(id: String): AsrModelSpec? = ALL.firstOrNull { it.id == id }

    /**
     * 语言 → 模型，**固定映射，不做回落**。
     *
     * 刻意不写成"找第一个支持的模型"：那样一旦顺序变了，语言就会悄悄换模型，
     * 用户会发现"昨天听日语还挺准，今天怎么不行了"，还查不出原因。
     *
     * AUTO 只能交给 SenseVoice —— 专用模型都是单语的，没人能自己判断语种。
     */
    fun defaultFor(language: SourceLanguage): AsrModelSpec = when (language) {
        // 中文保留 X-ASR：它自带标点与数字归一（ITN），中文场景下明显优于多语模型。
        SourceLanguage.CHINESE -> X_ASR
        // 英语 / 日语 / 韩语默认都用 SenseVoice：实测（2026-10-29）三语的表现
        // 都优于各自的专用模型——英语翻得通顺、日语更稳、韩语有词边界。
        // 专用模型仍保留在首页「识别模型」里，用户可以随时手动换回做兜底。
        SourceLanguage.ENGLISH,
        SourceLanguage.JAPANESE,
        SourceLanguage.KOREAN,
        -> SENSE_VOICE
        // 俄语不变：SenseVoice 不支持俄语，GigaAM 是唯一选择。
        SourceLanguage.RUSSIAN -> GIGAAM_RU
        SourceLanguage.AUTO -> SENSE_VOICE
    }

    /** 语言是否只能靠兜底模型处理（UI 上要提示"需要开启 SenseVoice"） */
    fun requiresFallback(language: SourceLanguage): Boolean =
        language == SourceLanguage.AUTO
}
