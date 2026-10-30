// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.mt

import com.screensub.core.common.TranslatorIds
import com.screensub.core.common.AppDispatchers
import com.screensub.core.common.AppError
import com.screensub.core.common.AppResult
import com.screensub.core.common.Log
import com.screensub.core.common.LogTags
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * MyMemory（免 Key，正在运营的公共翻译 API）。
 *
 * 定位是**兜底**：谷歌接口不可达或限流时顶上，保证在线翻译不会整条失效。
 *
 * ## 已知限制
 *
 * - 不支持源语言自动检测（必须给定语言），auto 时本引擎直接跳过
 * - 匿名额度很小（每天约 1000 词），一集动画约 5000 词，跑完整集会中途耗尽。
 *   耗尽时接口仍返回 HTTP 200，只是在 body 里标 `quotaFinished`，
 *   所以必须解析 body 才判断得出，光看状态码会误判成功
 * - 质量比谷歌一档，明显好于 ML Kit，但句子长了会翻得生硬
 */
class MyMemoryTranslator : Translator {

    companion object {
        private const val TAG = LogTags.MT
        private const val ENDPOINT = "https://api.mymemory.translated.net/get"
    }

    override val id: String = TranslatorIds.MYMEMORY

    override val displayName: String = "MyMemory（免 Key）"

    override val isOffline: Boolean = false

    @Volatile
    private var exhausted = false

        private val http = MtHttp.newClient(
        connectMs = 2_500L,
        readMs = 5_000L,
    )

    override suspend fun prepare(): AppResult<Unit> =
        if (exhausted) {
            AppResult.Err(AppError.Network("MyMemory 额度已用尽"))
        } else {
            AppResult.Ok(Unit)
        }

    override suspend fun translate(
        text: String,
        context: TranslationContext,
    ): AppResult<TranslationResult> = withContext(AppDispatchers.translate) {
        if (text.isBlank()) return@withContext AppResult.Ok(TranslationResult(""))
        if (exhausted) return@withContext AppResult.Err(AppError.Network("MyMemory 额度已用尽"))

        val src = code(context.sourceCode)
            ?: return@withContext AppResult.Err(
                AppError.Translation("MyMemory 不支持源语言自动检测", com.screensub.core.common.TranslationFailureKind.UNSUPPORTED_LANGUAGE),
            )
        val dst = code(context.targetCode) ?: "zh-CN"

        val url = "$ENDPOINT?q=${URLEncoder.encode(text, "UTF-8")}" +
            "&langpair=${URLEncoder.encode("$src|$dst", "UTF-8")}"

        try {
            http.newCall(Request.Builder().url(url).build())
                .execute()
                .use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext AppResult.Err(
                            AppError.Network("MyMemory HTTP ${response.code}"),
                        )
                    }

                    val json = runCatching { JSONObject(raw) }.getOrNull()
                        ?: return@withContext AppResult.Err(AppError.Engine("MyMemory 返回解析失败"))

                    val status = json.optInt("responseStatus", 200)
                    if (status != 200 || json.optBoolean("quotaFinished", false)) {
                        exhausted = true
                        Log.w(TAG, "MyMemory 额度用尽（status=$status），本次会话不再使用")
                        return@withContext AppResult.Err(
                            AppError.Network("MyMemory 额度用尽（$status）"),
                        )
                    }

                    val translated = json
                        .optJSONObject("responseData")
                        ?.optString("translatedText", "")
                        ?.trim()

                    // 超额度或查词失败时它会回一段 "MYMEMORY WARNING: ..." 文本
                    if (translated.isNullOrBlank() || translated.contains("MYMEMORY WARNING", true)) {
                        AppResult.Err(AppError.Engine("MyMemory 未返回译文"))
                    } else {
                        AppResult.Ok(TranslationResult(translated))
                    }
                }
        } catch (t: Throwable) {
            Log.w(TAG, "MyMemory 请求失败", t)
            AppResult.Err(AppError.Network("MyMemory 请求失败：${t.message}", t))
        }
    }

    /** MyMemory 不支持 auto，必须给确定语言 */
    private fun code(code: String): String? {
        val c = code.lowercase().substringBefore('-')
        return when {
            c.isBlank() || c == "auto" -> null
            c == "zh" -> "zh-CN"
            else -> c
        }
    }

    override fun release() {
        try {
            http.dispatcher.executorService.shutdown()
        } catch (_: Throwable) {
        }
    }

    override fun close() = release()
}
