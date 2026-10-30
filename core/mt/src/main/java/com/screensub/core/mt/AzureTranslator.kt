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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Azure AI Translator。
 *
 * 免费层 F0：每月 200 万字符，够译约 400 集动画对白——这是所有在线 NMT 里
 * 免费额度最实用的一个。质量优于 ML Kit，但不如 LLM（没有上下文能力）。
 *
 * 注意：需要国际信用卡开通，且国内网络直连可能不稳定。
 */
class AzureTranslator(private val config: AzureConfig) : Translator {

    data class AzureConfig(
        val subscriptionKey: String,
        /** 资源所在区域，如 eastasia / global。留空则用 global */
        val region: String = "global",
        /** 免费层用默认 endpoint 即可 */
        val endpoint: String = "https://api.cognitive.microsofttranslator.com",
    ) {
        val isUsable: Boolean get() = subscriptionKey.isNotBlank()
    }

    companion object {
        private const val TAG = LogTags.MT
        private const val API_VERSION = "3.0"
    }

    override val id: String = TranslatorIds.AZURE

    override val displayName: String = "微软 Azure 翻译"

    override val isOffline: Boolean = false

        private val http = MtHttp.newClient(
        connectMs = 800L,
        readMs = 1_500L,
        writeMs = 1_500L,
    )

    override suspend fun prepare(): AppResult<Unit> =
        if (config.isUsable) {
            AppResult.Ok(Unit)
        } else {
            AppResult.Err(AppError.Engine("Azure 未配置订阅密钥"))
        }

    override suspend fun translate(
        text: String,
        context: TranslationContext,
    ): AppResult<TranslationResult> = withContext(AppDispatchers.translate) {
        if (!config.isUsable) {
            return@withContext AppResult.Err(AppError.Engine("Azure 未配置订阅密钥"))
        }

        try {
            val from = normalizeCode(context.sourceCode)
            // 目标语言跟随 context（1.0 恒为 zh-Hans），不再写死常量
            val to = context.targetCode.ifBlank { TargetLanguage.CHINESE_SIMPLIFIED }
            val url = "${config.endpoint.trimEnd('/')}/translate" +
                "?api-version=$API_VERSION&from=$from&to=$to"

            val payload = JSONArray().put(JSONObject().put("Text", text)).toString()

            val request = Request.Builder()
                .url(url)
                .addHeader("Ocp-Apim-Subscription-Key", config.subscriptionKey)
                .addHeader("Ocp-Apim-Subscription-Region", config.region)
                .addHeader("Content-Type", "application/json")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    Log.w(TAG, "Azure HTTP ${response.code}: ${raw.take(200)}")
                    return@withContext AppResult.Err(AppError.Network("Azure 返回 ${response.code}"))
                }

                val translated = runCatching {
                    JSONArray(raw)
                        .getJSONObject(0)
                        .getJSONArray("translations")
                        .getJSONObject(0)
                        .getString("text")
                }.getOrNull()

                if (translated.isNullOrBlank()) {
                    AppResult.Err(AppError.Engine("Azure 返回解析失败"))
                } else {
                    // Azure 响应里没有逐句计费字数，留 0 让链按文本长度补算
                    AppResult.Ok(TranslationResult(translated))
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Azure 翻译异常", t)
            AppResult.Err(AppError.Network("Azure 请求失败：${t.message}", t))
        }
    }

    private fun normalizeCode(code: String): String = when (code.lowercase()) {
        "auto", "" -> "" // 留空表示让 Azure 自动检测
        "zh-hans", "zh" -> "zh-Hans"
        else -> code.lowercase()
    }

    override fun release() {
        try {
            http.dispatcher.executorService.shutdown()
        } catch (_: Throwable) {
        }
    }

    override fun close() = release()
}
