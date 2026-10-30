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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 阿里云机器翻译（通用版）。
 *
 * 为什么加它：国内能"每月白拿一大笔字符"的只有阿里云（100 万字符/月）
 * 和火山引擎（200 万字符/月），这两家是本工程仅有的两个国内付费引擎。
 * 100 万字符 ≈ 200 集 25 分钟动画对白，日常够用。
 *
 * ## 协议：RPC 风格 + HMAC-SHA1 签名
 *
 * 跟火山引擎的 V4 签名完全不同：
 *   - 所有参数（公共参数 + 业务参数）**全部塞进 query string**
 *   - 签名就是对这条 query string 做的，所以参数编码必须严格一致
 *   - 用 GET 就够（文本上限 5000 字符，字幕永远达不到）
 *
 * ## 三个最容易签错的地方
 *
 * 1. **percentEncode 不是普通 URLEncoder**：阿里云要求空格编成 `%20` 而不是 `+`、
 *    `*` 要编成 `%2A`、`~` 不能编成 `%7E`。直接拿 URLEncoder 的结果不做这三步替换，
 *    签名必错。
 * 2. **Timestamp 必须是 UTC**，格式 `yyyy-MM-dd'T'HH:mm:ss'Z'`，写成北京时间
 *    会直接被判 `SignatureExpired`。
 * 3. **Signature 本身也要 percentEncode** 才能拼回 URL——签名里会出现 `+` `/` `=`，
 *    不编码的话服务端解出来的签名和它对不上。
 */
class AliyunTranslator(private val config: AliyunConfig) : Translator {

    data class AliyunConfig(
        val accessKeyId: String,
        val accessKeySecret: String,
    ) {
        val isUsable: Boolean
            get() = accessKeyId.isNotBlank() && accessKeySecret.isNotBlank()
    }

    companion object {
        private const val TAG = LogTags.MT

        private const val HOST = "mt.aliyuncs.com"
        private const val ACTION = "TranslateGeneral"
        private const val VERSION = "2018-10-12"

        /** 通用版固定传 general，专业版才有别的值 */
        private const val SCENE = "general"

        /** 纯文本，不要按 HTML 处理（否则字幕里的标点会被转义） */
        private const val FORMAT_TYPE = "text"

        /** 批量接口：一次最多 50 条、单条 ≤1000 字符、总长 ≤8000 字符，QPS 50 */
        private const val BATCH_ACTION = "GetBatchTranslate"

        /** 批量接口必须显式声明版本，通用版是 translate_standard */
        private const val API_TYPE = "translate_standard"
    }

    override val id: String = TranslatorIds.ALIYUN

    override val displayName: String = "阿里云机器翻译"

    override val isOffline: Boolean = false

        // 共享连接池：翻译链会重建，池子不能跟着重建，否则每句都重新握手
    private val http = MtHttp.newClient(
        connectMs = 2_000L,
        readMs = 5_000L,
    )

    override suspend fun prepare(): AppResult<Unit> =
        if (config.isUsable) {
            AppResult.Ok(Unit)
        } else {
            AppResult.Err(AppError.Engine("阿里云未配置 AccessKeyId / AccessKeySecret"))
        }

    override suspend fun translate(
        text: String,
        context: TranslationContext,
    ): AppResult<TranslationResult> = withContext(AppDispatchers.translate) {
        if (!config.isUsable) {
            return@withContext AppResult.Err(AppError.Engine("阿里云未配置密钥"))
        }
        if (text.isBlank()) return@withContext AppResult.Ok(TranslationResult(""))

        try {
            val params = sortedMapOf<String, String>().apply {
                // ---- 公共参数 ----
                put("Action", ACTION)
                put("Version", VERSION)
                put("Format", "JSON")
                put("AccessKeyId", config.accessKeyId.trim())
                put("SignatureMethod", "HMAC-SHA1")
                put("SignatureVersion", "1.0")
                put("SignatureNonce", UUID.randomUUID().toString())
                put("Timestamp", utcTimestamp())

                // ---- 业务参数 ----
                put("FormatType", FORMAT_TYPE)
                put("Scene", SCENE)
                put("SourceLanguage", mapCode(context.sourceCode, allowAuto = true))
                put("TargetLanguage", mapCode(context.targetCode, allowAuto = false))
                put("SourceText", text)
            }

            val signature = sign(params)
            val query = params.entries.joinToString("&") {
                "${percentEncode(it.key)}=${percentEncode(it.value)}"
            } + "&Signature=${percentEncode(signature)}"

            val request = Request.Builder()
                .url("https://$HOST/?$query")
                .get()
                .build()

            http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    Log.w(TAG, "阿里云 HTTP ${response.code}: ${raw.take(220)}")
                    return@withContext AppResult.Err(
                        AppError.Network("阿里云 HTTP ${response.code}：${aliyunHint(raw, response.code)}"),
                    )
                }

                // 注意：官方文档样例写的是外面包一层 TranslateGeneralResponse，
                // 但**实际返回是扁平的**——Data / Code / RequestId 直接在最外层。
                // 只认文档里那种结构会永远解析不到译文，两种都必须兼容。
                val json = runCatching { JSONObject(raw) }.getOrNull()
                    ?: return@withContext AppResult.Err(
                        AppError.Engine("阿里云返回解析失败：${raw.take(120)}"),
                    )
                val root = json.optJSONObject("TranslateGeneralResponse") ?: json

                // Code 实际返回的是**字符串** "200"，不是数字。
                // optInt 遇到字符串会直接吐默认值，一旦哪天返回 "500" 就会被当成成功。
                val codeRaw = root.opt("Code")
                val code = when (codeRaw) {
                    is Number -> codeRaw.toInt()
                    is String -> codeRaw.trim().toIntOrNull() ?: 200
                    else -> 200
                }

                if (code != 200) {
                    val message = root.optString("Message", "")
                    Log.w(TAG, "阿里云业务错误 $code: $message")
                    return@withContext AppResult.Err(
                        AppError.Network("阿里云 $code：${message.ifBlank { codeHint(code) }}"),
                    )
                }

                val data = root.optJSONObject("Data")
                val translated = data?.optString("Translated", "")?.trim().orEmpty()

                // 阿里云在 Data.WordCount 里回带本次计费字数。有就用它（服务端口径），
                // 没有就留 0 让 ResilientTranslator 按文本长度补算。
                val billed = data?.optString("WordCount", "")?.toIntOrNull() ?: 0

                if (translated.isBlank()) {
                    AppResult.Err(AppError.Engine("阿里云返回空译文"))
                } else {
                    AppResult.Ok(TranslationResult(translated, billedChars = billed))
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "阿里云请求失败", t)
            AppResult.Err(AppError.Network("阿里云请求失败：${t.message}", t))
        }
    }

    // ---------------- 签名 ----------------

    /**
     * RPC 签名（HMAC-SHA1）：
     *   1. 参数按 key 的字典序排好（已用 sortedMap 保证）
     *   2. 每个 key/value 做 percentEncode，用 `&` 连成 CanonicalizedQueryString
     *   3. StringToSign = "GET" + "&" + percentEncode("/") + "&" + percentEncode(上一步)
     *   4. Signature = Base64(HMAC-SHA1(accessKeySecret + "&", StringToSign))
     *
     * 注意第 3 步：`/` 被编码成 `%2F`，整个 query string 也要再编码一次
     * （所以里面的 `=` `&` 会变成 `%3D` `%26`）。这是阿里云 RPC 签名的标志性写法，
     * 漏掉"再编码一次"是最常见的错法。
     */
    /**
     * 批量翻译：走阿里云 `GetBatchTranslate`，一次请求翻多条。
     *
     * ## 为什么用它
     *
     * 字幕按标点切成"一句一条"后，一次识别会产出多条文本。逐条发就是 N 次往返，
     * 移动网络下每条约 300ms，切 3 句就是 900ms。阿里云原生支持批量
     * （一次最多 50 条、总长 8000 字符、QPS 50），压成一次请求后：
     * 请求数不变、延迟一次往返、结果按 `index` 精确对应。
     *
     * ## 和单条接口的差别
     *
     * `TranslatedList` 里的 `index` 就是我们传进去的 key，**不是数组下标**，
     * 所以必须按键回填，不能按顺序硬取——否则一旦服务端乱序返回就会串行。
     */
    override suspend fun translateBatch(
        texts: List<String>,
        context: TranslationContext,
    ): List<AppResult<TranslationResult>> = withContext(AppDispatchers.translate) {
        if (!config.isUsable) {
            return@withContext texts.map {
                AppResult.Err(AppError.Engine("阿里云未配置密钥"))
            }
        }
        val valid = texts.filter { it.isNotBlank() }
        if (valid.isEmpty()) return@withContext texts.map { AppResult.Ok(TranslationResult("")) }

        // key 用下标，返回时按 index 找回原位置
        val sourceJson = JSONObject().apply {
            valid.forEachIndexed { i, t -> put(i.toString(), t) }
        }.toString()

        try {
            val params = sortedMapOf<String, String>().apply {
                put("Action", BATCH_ACTION)
                put("Version", VERSION)
                put("Format", "JSON")
                put("AccessKeyId", config.accessKeyId.trim())
                put("SignatureMethod", "HMAC-SHA1")
                put("SignatureVersion", "1.0")
                put("SignatureNonce", UUID.randomUUID().toString())
                put("Timestamp", utcTimestamp())

                put("FormatType", FORMAT_TYPE)
                put("Scene", SCENE)
                put("ApiType", API_TYPE)
                put("SourceLanguage", mapCode(context.sourceCode, allowAuto = true))
                put("TargetLanguage", mapCode(context.targetCode, allowAuto = false))
                put("SourceText", sourceJson)
            }

            val json = when (val r = send(params)) {
                is AppResult.Ok -> r.value
                is AppResult.Err -> return@withContext valid.map { r }
            }

            val code = json.optString("Code", "")
            if (code.isNotEmpty() && code != "200") {
                val msg = json.optString("Message", code)
                return@withContext valid.map {
                    AppResult.Err(AppError.Network("阿里云批量 $code：$msg"))
                }
            }

            val list = json.optJSONArray("TranslatedList")
                ?: return@withContext valid.map {
                    AppResult.Err(AppError.Engine("阿里云批量返回缺少 TranslatedList"))
                }

            val byIndex = HashMap<Int, TranslationResult>(list.length())
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val idx = item.optString("index", "").toIntOrNull() ?: continue
                val translated = item.optString("translated", "").trim()
                if (translated.isEmpty()) continue
                byIndex[idx] = TranslationResult(
                    text = translated,
                    // wordCount 是原文的计费字符数
                    billedChars = item.optString("wordCount", "").toIntOrNull() ?: 0,
                    detectedLanguage = item.optString("detectedLanguage", "")
                        .trim()
                        .takeIf { it.isNotBlank() },
                )
            }

            valid.indices.map { i ->
                byIndex[i]?.let { AppResult.Ok(it) }
                    ?: AppResult.Err(AppError.Engine("阿里云批量漏回了第 $i 条"))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "阿里云批量请求失败", t)
            valid.map { AppResult.Err(AppError.Network("阿里云批量失败：${t.message}", t)) }
        }
    }

    /** 签名 + 发请求 + 解析外层 JSON，批量与单条共用 */
    private fun send(params: Map<String, String>): AppResult<JSONObject> {
        val signature = sign(params)
        val query = params.entries.joinToString("&") {
            "${percentEncode(it.key)}=${percentEncode(it.value)}"
        } + "&Signature=${percentEncode(signature)}"

        return try {
            http.newCall(Request.Builder().url("https://$HOST/?$query").get().build())
                .execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        Log.w(TAG, "阿里云 HTTP ${response.code}: ${raw.take(220)}")
                        return AppResult.Err(
                            AppError.Network(
                                "阿里云 HTTP ${response.code}：${aliyunHint(raw, response.code)}",
                            ),
                        )
                    }
                    val json = runCatching { JSONObject(raw) }.getOrNull()
                        ?: return AppResult.Err(
                            AppError.Engine("阿里云返回解析失败：${raw.take(120)}"),
                        )
                    // 单条接口外面可能包一层 TranslateGeneralResponse，批量接口是扁平的
                    AppResult.Ok(json.optJSONObject("TranslateGeneralResponse") ?: json)
                }
        } catch (t: Throwable) {
            Log.w(TAG, "阿里云请求失败", t)
            AppResult.Err(AppError.Network("阿里云请求失败：${t.message}", t))
        }
    }

    private fun sign(params: Map<String, String>): String {
        val canonicalQuery = params.entries.joinToString("&") {
            "${percentEncode(it.key)}=${percentEncode(it.value)}"
        }
        val stringToSign = "GET&${percentEncode("/")}&${percentEncode(canonicalQuery)}"

        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec("${config.accessKeySecret.trim()}&".toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val digest = mac.doFinal(stringToSign.toByteArray(Charsets.UTF_8))

        return java.util.Base64.getEncoder().encodeToString(digest)
    }

    /** 阿里云版 RFC3986 编码：空格→%20、*→%2A、~ 保持原样 */
    private fun percentEncode(value: String): String =
        URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")

    private fun utcTimestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

    /** 给用户看的错误提示：签名错/没开通是最常见的两种，直接说怎么办 */
    private fun aliyunHint(raw: String, httpCode: Int): String {
        val body = runCatching { JSONObject(raw) }.getOrNull()
        val code = body?.optString("Code").orEmpty()
        val message = body?.optString("Message").orEmpty()
        return when {
            code.contains("InvalidAccessKeyId", true) -> "AccessKeyId 不存在，检查是否填错或已被禁用"
            code.contains("SignatureDoesNotMatch", true) -> "签名不匹配，检查 AccessKeySecret 是否填错"
            code.contains("Forbidden", true) -> "无权限，确认已开通机器翻译服务"
            message.isNotBlank() -> message
            else -> "HTTP $httpCode"
        }
    }

    private fun codeHint(code: Int): String = when (code) {
        10010 -> "账号未开通机器翻译服务"
        10013 -> "账号未开通服务或已欠费"
        10005 -> "不支持的语言对"
        10011 -> "服务未生效，请稍后重试"
        else -> "未知错误"
    }

    /** 阿里云用两位小写语言码；zh-Hans → zh，auto 原样传 */
    private fun mapCode(code: String, allowAuto: Boolean): String {
        val c = code.lowercase().substringBefore('-')
        return when {
            c.isBlank() || c == "auto" -> if (allowAuto) "auto" else "zh"
            c == "zh" -> "zh"
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
