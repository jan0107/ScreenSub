// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.mt

import com.screensub.core.common.TranslatorIds
import com.screensub.core.common.AppDispatchers
import com.screensub.core.common.AppError
import com.screensub.core.common.AppResult
import com.screensub.core.common.LanguageNames
import com.screensub.core.common.Log
import com.screensub.core.common.LogTags
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容协议的 LLM 翻译（DeepSeek / 通义千问 / OpenAI / 本地 Ollama 都可）。
 *
 * 为什么字幕场景首选 LLM 而不是传统 NMT：
 * - 传统 NMT 逐句独立翻译，日语省略主语时中文会翻得莫名其妙；
 * - LLM 能吃上文，能处理"あいつ"→"他"这种指代，能保留语气；
 * - 成本极低（一集动画对白约 5000 字，DeepSeek 约 0.005 元）。
 *
 * 代价：首 token 延迟 1~3 秒。所以超时必须收紧，超了就跳过——
 * 字幕宁可这一句用原文，也不能卡住整条流水线。
 */
class LlmTranslator(private val config: LlmConfig) : Translator {

    data class LlmConfig(
        /** 例如 https://api.deepseek.com/v1 （末尾不要斜杠） */
        val baseUrl: String,
        val apiKey: String,
        /** 例如 deepseek-chat / qwen-plus / gpt-4o-mini */
        val model: String,
        /**
         * 采样温度。
         *
         * 设 0 而不是 0.2：实测同一句台词中位 607~641ms，比 0.2 的 714ms 稳定且略快，
         * 译文质量没有差别。字幕要的是"每次都给同一句靠谱的话"，
         * 温度越高越容易出现"你肯定是在开玩笑"↔"你一定在逗我吧"这种同句漂移。
         */
        val temperature: Double = 0.0,
        /**
         * 输出上限。一句字幕最多 20 汉字 ≈ 30 token，120 已经留了 4 倍余量。
         * 原来给 400 是怕模型"想"——现在思考已经关掉，不需要那么大的空间，
         * 收紧还能防止偶发的长篇大论把延迟拖长。
         */
        val maxTokens: Int = 120,
        /** 整体读超时。字幕场景不建议超过 8 秒 */
        val readTimeoutMs: Long = 8_000L,
    ) {
        val isUsable: Boolean
            get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
    }

    companion object {
        private const val TAG = LogTags.MT

        /**
         * 字幕翻译专用提示词。
         *
         * 刻意压到 ~100 字符（约 70 token）。原来那版 8 条硬性要求有 266 token，
         * 实测**并不会更快**（prefill 对 flash 模型几乎无感），但成本是四倍。
         * 所以砍掉的是"日语称呼细则""不要音译"这类低频约束，
         * 保留的全是每句都会用到的：只要译文、20 字上限、口语化、保情绪、
         * 不续写、占位符原样保留。
         */
        private const val SYSTEM_PROMPT =
            "你是影视字幕翻译。把台词译成简体中文，只输出译文，不加引号、括号或前缀。" +
                "一行不超过20字，口语化，保留语气和情绪，绝不续写。" +
                "人名用通行译名，语气词不删。⟦T0⟧占位符原样保留。"

        private const val JSON = "application/json; charset=utf-8"

        /**
         * 必须显式关闭「思考」的服务商域名。
         *
         * deepseek-flash / deepseek-v4-pro 都是**混合推理模型**：默认可能先吐一大段
         * reasoning_content（英文思考过程），思考完才吐译文。实测同一句英文台词：
         *
         *   不传参数   ：1.2~5.6 秒，且忽快忽慢——164 个 completion token 里
         *                154 个是思考；deepseek-v4-pro 更极端，400 个 token
         *                全被思考吃光，content 直接返回空字符串
         *   关闭思考   ：0.61~0.93 秒（中位 0.77s），思考 token 恒为 0
         *
         * 字幕上屏队列只等 2000ms，2 秒的思考等于白翻——译文回来时字幕早
         * 已经超时放行成原文了。所以对这类服务商必须强制关掉。
         *
         * `thinking` 是 DeepSeek 的私有扩展字段，别家传了可能 400，
         * 所以按域名判断而不是无脑加。
         */
        private val DISABLE_THINKING_HOSTS = listOf("api.deepseek.com")
    }

    /** 当前配置的服务商是否属于「默认会思考、需要显式关掉」的那类 */
    private fun shouldDisableThinking(): Boolean =
        DISABLE_THINKING_HOSTS.any { config.baseUrl.contains(it, ignoreCase = true) }

    override val id: String = TranslatorIds.LLM

    override val displayName: String = config.model.ifBlank { "LLM 在线翻译" }

    override val isOffline: Boolean = false

        private val http = MtHttp.newClient(
        // 首句要建连 + TLS 握手，800ms 在移动网络下太紧，会白丢很多句
        connectMs = 1_500L,
        readMs = config.readTimeoutMs,
        writeMs = 2_000L,
    )

    override suspend fun prepare(): AppResult<Unit> =
        if (config.isUsable) {
            Log.i(
                TAG,
                "LLM：${config.model} @ ${config.baseUrl}，" +
                    if (shouldDisableThinking()) "已关闭思考模式" else "沿用服务商默认思考策略",
            )
            warmUp()
            AppResult.Ok(Unit)
        } else {
            AppResult.Err(AppError.Engine("LLM 配置不完整（baseUrl / apiKey / model）"))
        }

    /**
     * 预热 TLS 连接。
     *
     * 首句请求要新建 TCP + TLS 握手，实测占 200~400ms（关掉思考后翻译本体
     * 只要 600~900ms，握手能占到四成）。而上屏队列只等 2000ms，
     * 首句几乎必然压线超时，表现就是"第一句总是先出英文再跳中文"。
     *
     * 在 prepare（服务启动时、用户还在等投屏授权）先打一发 GET /models：
     * 不消耗任何 token，只为了把 DNS 解析 / TCP / TLS 全摊到用户看不见的时候。
     * OkHttp 的连接池按 host 复用，正式请求直接走这条现成的连接。
     */
    private suspend fun warmUp() = withContext(AppDispatchers.translate) {
        try {
            withTimeoutOrNull(3_000) {
                val request = Request.Builder()
                    .url("${config.baseUrl.trimEnd('/')}/models")
                    .addHeader("Authorization", "Bearer ${config.apiKey}")
                    .get()
                    .build()
                http.newCall(request).execute().use { it.body?.string() }
                Log.d(TAG, "LLM 连接预热完成")
            }
        } catch (t: Throwable) {
            // 预热失败完全无所谓，正式请求会自己重新建连
            Log.d(TAG, "LLM 预热失败（不影响使用）：${t.message}")
        }
    }

    override suspend fun translate(
        text: String,
        context: TranslationContext,
    ): AppResult<TranslationResult> = withContext(AppDispatchers.translate) {
        if (!config.isUsable) {
            return@withContext AppResult.Err(AppError.Engine("LLM 配置不完整"))
        }

        try {
            val body = buildRequestBody(text, context)
            val request = Request.Builder()
                .url("${config.baseUrl.trimEnd('/')}/chat/completions")
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .addHeader("Content-Type", JSON)
                .post(body.toRequestBody(JSON.toMediaType()))
                .build()

            http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    val code = response.code
                    Log.w(TAG, "LLM HTTP $code: ${raw.take(200)}")
                    // 402 / 余额 / 额度 这类响应**不能断定是没钱**——也可能是服务端
                    // 临时故障或账号风控。所以这里只给"可能"的提示，让用户去控制台确认，
                    // 而不是武断地停用。真正"余额见底"的停用交给主动的余额查询接口。
                    val hint = when {
                        code == 402 ||
                            raw.contains("insufficient", ignoreCase = true) ||
                            raw.contains("balance", ignoreCase = true) ||
                            raw.contains("quota", ignoreCase = true) ||
                            raw.contains("余额", ignoreCase = true) ->
                            "可能是账户余额不足，也可能是服务临时中断（HTTP 402）"
                        code == 429 -> "请求过于频繁（HTTP 429 限流）"
                        code == 401 || code == 403 -> "API Key 无效或无权限（HTTP $code）"
                        else -> "服务返回 HTTP $code"
                    }
                    return@withContext AppResult.Err(AppError.Network("LLM $hint"))
                }

                val content = parseContent(raw)
                if (content.isBlank()) {
                    AppResult.Err(AppError.Engine("LLM 返回空译文"))
                } else {
                    // LLM 按 token 计费，必须拿响应里的 usage 才知道真实花费。
                    // 之前这里直接把 usage 丢了，导致"本月花了多少钱"永远算不出来。
                    val usage = parseUsage(raw)
                    AppResult.Ok(
                        TranslationResult(
                            text = sanitize(content),
                            tokensIn = usage.first,
                            tokensOut = usage.second,
                        ),
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "LLM 翻译异常: $text", t)
            AppResult.Err(AppError.Network("LLM 请求失败：${t.message}", t))
        }
    }

    private fun buildRequestBody(text: String, context: TranslationContext): String {
        val messages = JSONArray()

        messages.put(
            JSONObject().apply {
                put("role", "system")
                put("content", SYSTEM_PROMPT)
            },
        )

        val userContent = buildString {
            // 明确告知语种，能显著减少"日文汉字被当中文原样输出"这类问题。
            // 注意只加在 user message 里，system prompt 保持稳定（部分供应商对固定前缀有缓存计费）
            val srcLabel = LanguageNames.of(context.sourceCode)
            if (srcLabel.isNotBlank() && srcLabel != context.sourceCode) {
                appendLine("源语言：$srcLabel")
                appendLine("目标语言：${LanguageNames.targetOf(context.targetCode)}")
                appendLine()
            }

            val prev = context.previousSourceText
            if (!prev.isNullOrBlank()) {
                appendLine("上一句：$prev")
                val prevTranslated = context.previousTranslatedText
                if (!prevTranslated.isNullOrBlank()) {
                    appendLine("上一句译文：$prevTranslated")
                }
                appendLine()
            }
            append("本句：$text")
        }

        messages.put(
            JSONObject().apply {
                put("role", "user")
                put("content", userContent)
            },
        )

        return JSONObject().apply {
            put("model", config.model)
            put("messages", messages)
            put("temperature", config.temperature)
            put("max_tokens", config.maxTokens)
            put("stream", false)
            // 字幕场景绝对不能让它先"想一想"：思考一次 1~3 秒，
            // 比上屏队列的耐心长一倍，译文永远赶不上（详见 DISABLE_THINKING_HOSTS）
            if (shouldDisableThinking()) {
                put("thinking", JSONObject().put("type", "disabled"))
            }
        }.toString()
    }

    private fun parseContent(raw: String): String = try {
        JSONObject(raw)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .optString("content", "")
            .trim()
    } catch (t: Throwable) {
        Log.w(TAG, "解析 LLM 响应失败: ${raw.take(200)}", t)
        ""
    }

    /** 读 usage.prompt_tokens / completion_tokens，缺失时返回 (0, 0) */
    private fun parseUsage(raw: String): Pair<Int, Int> = try {
        val usage = JSONObject(raw).optJSONObject("usage")
        val inTokens = usage?.optInt("prompt_tokens", 0) ?: 0
        val outTokens = usage?.optInt("completion_tokens", 0) ?: 0
        inTokens to outTokens
    } catch (_: Throwable) {
        // 部分中转站不回 usage，按 0 处理，UI 会显示"无 token 数据"而不是崩
        0 to 0
    }

    /** 去掉模型偶尔多嘴加上的引号、前缀 */
    private fun sanitize(text: String): String {
        var result = text.trim()
        result = result.removePrefix("译文：").removePrefix("翻译：").removePrefix("中文：")
        result = result.trim()
        if (result.length >= 2 &&
            ((result.startsWith("「") && result.endsWith("」")) ||
                (result.startsWith("\"") && result.endsWith("\"")) ||
                (result.startsWith("“") && result.endsWith("”")))
        ) {
            result = result.substring(1, result.length - 1).trim()
        }
        return result
    }

    override fun release() {
        try {
            http.dispatcher.executorService.shutdown()
        } catch (_: Throwable) {
        }
    }

    override fun close() = release()
}
