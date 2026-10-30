// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.mt

import com.screensub.core.common.TranslatorIds

import android.os.SystemClock
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.screensub.core.common.AppDispatchers
import com.screensub.core.common.AppError
import com.screensub.core.common.AppResult
import com.screensub.core.common.Log
import com.screensub.core.common.LogTags
import com.screensub.core.common.TranslationFailureKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ML Kit 端上翻译。
 *
 * ## 两个关键设计（改之前务必读）
 *
 * **1. auto 不是错误，是"还不知道"**
 *
 * 用户把源语言设成「自动」时，真正的语言要等 ASR 第一句识别完才知道。
 * 旧实现让 `mapLanguage("auto")` 返回 null → prepare 直接失败 → 整条翻译链挂掉。
 * 现在改成：auto 时 prepare 直接返回 Ok，等 pipeline 锁定语言后通过
 * [onSourceLanguageChanged] 触发后台预热。
 *
 * **2. 语言包下载绝不能放在 translate() 里**
 *
 * 上游 `ResilientTranslator` 给每个引擎 6 秒超时。语言包首次约 30 MB，
 * 同步下载必然被反复砍断 → 每句都"失败" → 熔断开闸 → 误发失败通知。
 * 所以：translate() 发现未就绪时**立刻返回 MODEL_DOWNLOADING**，
 * 真正的下载在 [prefetchScope] 里跑，下好之后自动恢复。
 * MODEL_DOWNLOADING 是 isTransient，不计熔断也不计失败次数。
 */
class MlKitTranslator(
    private val configuredSourceCode: String,
    private val configuredTargetCode: String = TargetLanguage.MLKIT_CHINESE,
) : Translator {

    companion object {
        private const val TAG = LogTags.MT

        /** 语言包下载失败后的退避，避免每句都重试打网络 */
        private const val RETRY_BACKOFF_MS = 60_000L
    }

    override val id: String = TranslatorIds.MLKIT

    override val displayName: String = "ML Kit 离线翻译"

    override val isOffline: Boolean = true

    private val mutex = Mutex()
    private val prefetchScope = CoroutineScope(SupervisorJob() + AppDispatchers.io)

    private var client: com.google.mlkit.nl.translate.Translator? = null

    /** 已就绪的语言对（源语言 ML Kit 码），如 "ja" */
    @Volatile
    private var activeSource: String? = null

    @Volatile
    private var ready: Boolean = false

    /** 正在后台下载的语言，用于合并重复请求 */
    @Volatile
    private var pendingSource: String? = null

    @Volatile
    private var retryNotBefore: Long = 0L

    private val target: String = mapLanguage(configuredTargetCode) ?: TranslateLanguage.CHINESE

    // ---------------- 准备 ----------------

    override suspend fun prepare(): AppResult<Unit> = withContext(AppDispatchers.io) {
        val source = mapLanguage(configuredSourceCode)

        if (source == null) {
            if (isAuto(configuredSourceCode)) {
                // ★ 核心修复：auto 只是"还不知道语言"，绝不算失败
                Log.i(TAG, "源语言=自动，ML Kit 延迟到首句检测出语言后再准备语言包")
                return@withContext AppResult.Ok(Unit)
            }
            // 明确指定了不支持的语言（典型：粤语 yue）
            return@withContext AppResult.Err(
                AppError.Translation(
                    "ML Kit 不支持源语言：$configuredSourceCode",
                    TranslationFailureKind.UNSUPPORTED_LANGUAGE,
                ),
            )
        }

        // 不阻塞：会话先跑起来，语言包在后台下
        prefetch(configuredSourceCode)
        AppResult.Ok(Unit)
    }

    /**
     * 源语言变了（自动模式下 pipeline 锁定或改锁时调用）。
     * 只做前置预热，不阻塞翻译。
     */
    override fun onSourceLanguageChanged(sourceCode: String) {
        prefetch(sourceCode)
    }

    /**
     * 后台准备某个语言对。重复调用会合并；下载失败后 [RETRY_BACKOFF_MS] 内不再重试。
     */
    fun prefetch(rawCode: String) {
        val source = mapLanguage(rawCode) ?: return
        if (ready && activeSource == source) return
        if (pendingSource == source) return
        if (SystemClock.elapsedRealtime() < retryNotBefore) return

        pendingSource = source
        prefetchScope.launch {
            Log.i(TAG, "ML Kit 语言包准备开始 $source -> $target")
            val outcome = ensureClient(source)
            if (outcome is AppResult.Err) {
                retryNotBefore = SystemClock.elapsedRealtime() + RETRY_BACKOFF_MS
                Log.w(TAG, "ML Kit 语言包准备失败：${outcome.error.message}")
            }
        }
    }

    // ---------------- 翻译 ----------------

    override suspend fun translate(
        text: String,
        context: TranslationContext,
    ): AppResult<TranslationResult> = withContext(AppDispatchers.io) {
        // 目标语言跟随 context（1.0 恒为简体中文，但链路已经参数化）
        val desired = mapLanguage(context.sourceCode)

        if (desired == null) {
            return@withContext if (isAuto(context.sourceCode)) {
                // 还没检测出语言，属于正常等待
                AppResult.Err(
                    AppError.Translation(
                        "等待识别出源语言",
                        TranslationFailureKind.MODEL_DOWNLOADING,
                    ),
                )
            } else {
                AppResult.Err(
                    AppError.Translation(
                        "ML Kit 没有 ${context.sourceCode} 的语言包",
                        TranslationFailureKind.UNSUPPORTED_LANGUAGE,
                    ),
                )
            }
        }

        // 源语言就是目标语言（如中文视频配中文字幕），不用翻
        if (context.isSameLanguage) return@withContext AppResult.Ok(TranslationResult(text))

        // 语言包未就绪：触发后台下载并立刻返回，绝不占用 6 秒超时预算
        if (!ready || activeSource != desired) {
            prefetch(context.sourceCode)
            return@withContext AppResult.Err(
                AppError.Translation(
                    "ML Kit 语言包准备中（$desired -> $target）",
                    TranslationFailureKind.MODEL_DOWNLOADING,
                ),
            )
        }

        val translator = client
            ?: return@withContext AppResult.Err(
                AppError.Translation(
                    "ML Kit 客户端缺失，正在重建",
                    TranslationFailureKind.MODEL_DOWNLOADING,
                ),
            )

        mutex.withLock {
            try {
                val out = translator.translate(text).awaitTask()
                if (out.isBlank()) {
                    AppResult.Err(AppError.Engine("ML Kit 返回空译文"))
                } else {
                    AppResult.Ok(TranslationResult(out))
                }
            } catch (t: Throwable) {
                Log.w(TAG, "ML Kit 翻译失败: $text", t)
                AppResult.Err(AppError.Engine("ML Kit 翻译失败：${t.message}", t))
            }
        }
    }

    /**
     * 确保 [source] -> [target] 可用。
     *
     * 切换语言时先 close 旧 client —— 否则两个语言包会同时驻留内存（每个约 30 MB）。
     * 失败时把状态清空，不留下"毒状态"。
     */
    private suspend fun ensureClient(source: String): AppResult<Unit> = mutex.withLock {
        if (ready && activeSource == source) return@withLock AppResult.Ok(Unit)

        Log.i(TAG, "ML Kit 切换语言：${activeSource ?: "无"} → $source")

        runCatching { client?.close() }
        client = null
        ready = false
        activeSource = null

        try {
            val newClient = Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(target)
                    .build(),
            )

            // ★ 不再 requireWifi()：旧实现只允许 WiFi 下载，用移动数据时语言包
            // 永远下不来，表现为"翻译一直不生效"。下载量约 30 MB，只下一次。
            newClient.downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitTask()

            client = newClient
            activeSource = source
            ready = true
            Log.i(TAG, "ML Kit 语言包就绪 $source -> $target")
            AppResult.Ok(Unit)
        } catch (t: Throwable) {
            runCatching { client?.close() }
            client = null
            ready = false
            activeSource = null
            Log.e(TAG, "ML Kit 语言包下载失败 $source -> $target（无 GMS / 无网络 / 空间不足）", t)
            AppResult.Err(
                AppError.Translation(
                    "离线翻译语言包下载失败：${t.message}",
                    TranslationFailureKind.MODEL_MISSING,
                    t,
                ),
            )
        } finally {
            if (pendingSource == source) pendingSource = null
        }
    }

    private fun isAuto(code: String): Boolean =
        code.isBlank() || code.equals("auto", ignoreCase = true)

    /**
     * 语言码映射。返回 null 表示 ML Kit 没有该语言的语言包。
     * 注意 "auto" 也返回 null，但调用方会先判 isAuto 区分"不知道"和"不支持"。
     */
    private fun mapLanguage(code: String): String? = when (code.lowercase()) {
        "ja", "japanese" -> TranslateLanguage.JAPANESE
        "en", "english" -> TranslateLanguage.ENGLISH
        "ru", "russian" -> TranslateLanguage.RUSSIAN
        "ko", "korean" -> TranslateLanguage.KOREAN
        "zh", "zh-hans", "zh-hant", "chinese" -> TranslateLanguage.CHINESE
        // yue（粤语）ML Kit 没有语言包
        else -> null
    }

    override fun release() {
        prefetchScope.cancel()
        runCatching { client?.close() }
        client = null
        ready = false
        activeSource = null
        pendingSource = null
    }

    override fun close() = release()
}

/** Google Task → Kotlin 挂起函数 */
private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result ->
        if (cont.isActive) cont.resume(result)
    }
    addOnFailureListener { e ->
        if (cont.isActive) cont.resumeWithException(e as? IOException ?: e)
    }
    addOnCanceledListener {
        if (cont.isActive) cont.cancel()
    }
}
