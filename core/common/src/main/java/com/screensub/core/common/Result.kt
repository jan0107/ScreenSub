// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.common

/** 翻译失败原因分类。决定给用户的提示文案与"下一步该做什么"。 */
enum class TranslationFailureKind {

    /** 链里一个引擎都没有（没配在线 Key + 离线翻译被关） */
    NO_ENGINE,

    /** 源语言离线不支持（典型：粤语 yue，ML Kit 没有对应语言包） */
    UNSUPPORTED_LANGUAGE,

    /**
     * 语言包正在下载 —— 这是预期中的等待，**既不算故障也不发通知**。
     * 没有这个分类，语言包下载期间每句都会"失败"，会立刻误报一次故障。
     */
    MODEL_DOWNLOADING,

    /** 语言包缺失 / 下载失败（无 GMS、无网络、空间不足） */
    MODEL_MISSING,

    /** 在线引擎网络失败 / 401 / 429 */
    NETWORK,

    /**
     * 已启用的引擎额度全部耗尽。
     *
     * 这是**本地判定**出来的，不是服务端报错——服务端只会告诉你"欠费"或直接扣钱。
     * 有了这个分类，UI 才能把"额度用完了"和"网络挂了"分开提示，
     * 否则用户看到"翻译失败"会去查网络，永远查不到原因。
     */
    QUOTA_EXHAUSTED,

    UNKNOWN;

    /** 瞬时状态：不计熔断、不计失败次数 */
    val isTransient: Boolean get() = this == MODEL_DOWNLOADING
}

/**
 * 全局错误类型。字幕场景下"错误"通常意味着降级而非中止，
 * 所以这里只做到能区分来源即可。
 */
sealed class AppError(open val message: String, open val cause: Throwable? = null) {

    /** 权限缺失（悬浮窗 / 录音 / 通知 / 电池白名单） */
    data class Permission(
        override val message: String,
        val which: String = "",
    ) : AppError(message)

    /** 音频采集失败 */
    data class Audio(
        override val message: String,
        override val cause: Throwable? = null,
    ) : AppError(message, cause)

    /** 内录被目标 App 禁止（表现为全静音） */
    data class CaptureBlocked(
        override val message: String = "目标 App 禁止内部音频捕获",
    ) : AppError(message)

    /** 网络 / 在线引擎 */
    data class Network(
        override val message: String,
        override val cause: Throwable? = null,
    ) : AppError(message, cause)

    /** 识别或翻译引擎自身问题 */
    data class Engine(
        override val message: String,
        override val cause: Throwable? = null,
    ) : AppError(message, cause)

    /** 模型未就绪（未下载 / 未加载） */
    data class ModelNotReady(
        override val message: String,
    ) : AppError(message)

    /**
     * 翻译整链失败。比 [Engine] 多带一个 [kind]，
     * 用来区分"没配 Key" / "语言包没了" / "网络挂了"，好生成用户看得懂的提示。
     */
    data class Translation(
        override val message: String,
        val kind: TranslationFailureKind = TranslationFailureKind.UNKNOWN,
        override val cause: Throwable? = null,
        /**
         * 失败的具体引擎名（"谷歌翻译" / "MyMemory" / null=整条链都挂了）。
         * 通知标题要用它，用户才知道该去换哪一家，而不是只看到"翻译失败"。
         */
        val engineName: String? = null,
    ) : AppError(message, cause)

    data class Unknown(
        override val message: String,
        override val cause: Throwable? = null,
    ) : AppError(message, cause)
}

/** 轻量 Result 封装，避免到处 import kotlin.Result 的 inline 语义差异。 */
sealed interface AppResult<out T> {
    data class Ok<T>(val value: T) : AppResult<T>
    data class Err(val error: AppError) : AppResult<Nothing>
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Ok -> AppResult.Ok(transform(value))
    is AppResult.Err -> this
}

inline fun <T> AppResult<T>.onOk(action: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Ok) action(value)
    return this
}

inline fun <T> AppResult<T>.onErr(action: (AppError) -> Unit): AppResult<T> {
    if (this is AppResult.Err) action(error)
    return this
}

fun <T> AppResult<T>.getOrNull(): T? = (this as? AppResult.Ok)?.value

fun Throwable.toAppError(prefix: String = "未知错误"): AppError =
    AppError.Unknown("$prefix: ${message ?: this::class.java.simpleName}", this)
