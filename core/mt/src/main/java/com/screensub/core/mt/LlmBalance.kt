// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.mt

import com.screensub.core.common.AppDispatchers
import com.screensub.core.common.AppError
import com.screensub.core.common.AppResult
import com.screensub.core.common.Log
import com.screensub.core.common.LogTags
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 一次余额查询的结果 */
data class LlmBalance(
    /** 折人民币的可用余额。非人民币账户按 1:1 显示并在 [currency] 里标注，不做汇率换算 */
    val yuan: Double,
    val currency: String,
    val queriedAt: Long,
)

/**
 * LLM 余额查询。
 *
 * ## 为什么只支持三家
 *
 * "同一个 API Key 就能查余额"的服务商只有 DeepSeek、Kimi(Moonshot)、硅基流动。
 * 通义千问（DashScope）、智谱 GLM、OpenAI 都没有可用的公开接口：
 *   - DashScope / GLM 只能在控制台看
 *   - OpenAI 的 /v1/organization/costs 要组织管理员 Key，且中转站根本不转发
 *
 * 所以 [supports] 返回 false 时，UI 必须如实显示"无余额查询接口"，
 * 转而按 token × 单价估算花费——**不能假装查得到**。
 */
object LlmBalanceProbes {

    private const val TAG = LogTags.MT
    private const val TIMEOUT_SECONDS = 8L

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    fun supports(providerId: String): Boolean = when (providerId) {
        "deepseek", "moonshot", "siliconflow" -> true
        else -> false
    }

    /**
     * 查余额。失败返回 [AppError.Network]，调用方展示"上次成功时间"即可，不要阻塞翻译。
     */
    suspend fun query(providerId: String, config: LlmTranslator.LlmConfig): AppResult<LlmBalance> =
        withContext(AppDispatchers.io) {
            if (!supports(providerId)) {
                return@withContext AppResult.Err(
                    AppError.Network("该服务商无余额查询接口"),
                )
            }
            if (config.apiKey.isBlank()) {
                return@withContext AppResult.Err(AppError.Engine("还没填 API Key"))
            }

            // 各家的余额端点路径不同，但都可以从 baseUrl 去掉 /v1 后拼出来：
            //   deepseek    https://api.deepseek.com/user/balance
            //   moonshot    https://api.moonshot.cn/v1/users/me/balance
            //   siliconflow https://api.siliconflow.cn/v1/user/info
            val root = config.baseUrl.trim().trimEnd('/').removeSuffix("/v1")
            if (root.isBlank()) {
                return@withContext AppResult.Err(AppError.Engine("Base URL 为空"))
            }

            val path = when (providerId) {
                "deepseek" -> "/user/balance"
                "moonshot" -> "/v1/users/me/balance"
                else -> "/v1/user/info"
            }

            val request = Request.Builder()
                .url("$root$path")
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .get()
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        Log.w(TAG, "余额查询 HTTP ${response.code}: ${raw.take(200)}")
                        return@withContext AppResult.Err(
                            AppError.Network("余额查询返回 ${response.code}"),
                        )
                    }
                    parse(providerId, raw)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "余额查询失败（$providerId）", t)
                AppResult.Err(AppError.Network("余额查询失败：${t.message}", t))
            }
        }

    private fun parse(providerId: String, raw: String): AppResult<LlmBalance> = try {
        val json = JSONObject(raw)
        val now = System.currentTimeMillis()

        when (providerId) {
            // {"balance_infos":[{"currency":"CNY","total_balance":"110.00",...}]}
            "deepseek" -> {
                val info = json.optJSONArray("balance_infos")?.let {
                    if (it.length() > 0) it.optJSONObject(0) else null
                }
                val amount = info?.optString("total_balance", "")?.toDoubleOrNull()
                val currency = info?.optString("currency", "CNY").orEmpty().ifBlank { "CNY" }
                if (amount == null) {
                    AppResult.Err(AppError.Engine("DeepSeek 余额解析失败"))
                } else {
                    AppResult.Ok(LlmBalance(amount, currency, now))
                }
            }

            // {"data":{"available_balance":123.45,...}}
            "moonshot" -> {
                val data = json.optJSONObject("data")
                val amount = data?.optDouble("available_balance", Double.NaN)
                    ?.takeIf { !it.isNaN() }
                    ?: data?.optString("available_balance", "")?.toDoubleOrNull()
                if (amount == null) {
                    AppResult.Err(AppError.Engine("Kimi 余额解析失败"))
                } else {
                    AppResult.Ok(LlmBalance(amount, "CNY", now))
                }
            }

            // {"data":{"balance":"12.34","totalBalance":"12.34"}}
            else -> {
                val data = json.optJSONObject("data")
                val amount = data?.optString("totalBalance", "")?.toDoubleOrNull()
                    ?: data?.optString("balance", "")?.toDoubleOrNull()
                if (amount == null) {
                    AppResult.Err(AppError.Engine("硅基流动余额解析失败"))
                } else {
                    AppResult.Ok(LlmBalance(amount, "CNY", now))
                }
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "余额解析异常（$providerId）: ${raw.take(200)}", t)
        AppResult.Err(AppError.Engine("余额解析失败"))
    }
}
