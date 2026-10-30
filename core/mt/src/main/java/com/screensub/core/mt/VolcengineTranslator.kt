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
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 火山引擎机器翻译（传统 NMT）。
 *
 * 每月 **200 万字符免费，按自然月刷新**（每月 1 号重置），
 * 一集 25 分钟动画对白约 5000 字符 ≈ 每月 400 集，日常根本用不完。
 * 超出后 49 元/百万字符，不会自动停服。
 *
 * ## 别和「豆包翻译大模型」搞混
 *
 * 火山把翻译拆成了两个产品，凭据和域名都不同：
 *
 * | | 机器翻译（本类） | 豆包翻译大模型 |
 * |---|---|---|
 * | 域名 | `translate.volcengineapi.com` | `openspeech.bytedance.com` |
 * | 凭据 | **AccessKeyId + SecretAccessKey** | AppID + Access Token |
 * | 认证 | V4 签名（HMAC-SHA256） | 三个请求头，无签名 |
 * | 额度 | **200 万字符/月，每月重置** | 100 万 token，一次性、6 个月过期 |
 *
 * 本项目只用本类这套（额度按月刷新，长期更划算）。
 *
 * ## V4 签名三个必踩的坑
 *
 * 1. **canonical headers 每行都以 `\n` 结尾，包括最后一行**
 * 2. **x-date 必须是 UTC**，短日期取前 8 位用于派生密钥
 * 3. **SecretKey 直接当 HMAC 的 key 用**，不要像阿里云 RPC 签名那样拼 `&` 前缀
 */
class VolcengineTranslator(private val config: VolcengineConfig) : Translator {

    data class VolcengineConfig(
        val accessKeyId: String,
        val secretAccessKey: String,
        /** 地域，机器翻译固定 cn-north-1 */
        val region: String = "cn-north-1",
    ) {
        val isUsable: Boolean
            get() = accessKeyId.isNotBlank() && secretAccessKey.isNotBlank()

        /** AccessKeyId 去掉可能粘进来的空白 */
        val cleanAccessKeyId: String
            get() = accessKeyId.trim()

        /**
         * SecretAccessKey 补齐 base64 padding。
         *
         * 火山的 Secret 是 base64 串，末尾的 `=` 填充在控制台表格里最容易被漏掉
         * （单元格显示成 `WXpjeFpX...` 加省略号，双击选中常少选最后两个字符）。
         *
         * 实测漏掉 `==` 的后果：报 `SignatureDoesNotMatch`，
         * 看起来像"Secret 填错了/密钥无效"，实际只差两个字符——
         * 这种错误光看提示根本猜不到，所以这里直接自动补。
         */
        val cleanSecret: String
            get() {
                val s = secretAccessKey.trim()
                val rem = s.length % 4
                return if (rem == 0) s else s + "=".repeat(4 - rem)
            }
    }

    companion object {
        private const val TAG = LogTags.MT

        private const val HOST = "translate.volcengineapi.com"
        private const val SERVICE = "translate"
        private const val ACTION = "TranslateText"
        private const val VERSION = "2020-06-01"
        private const val CONTENT_TYPE = "application/json"
        private const val ALGORITHM = "HMAC-SHA256"
        private const val SIGNED_HEADERS = "content-type;host;x-content-sha256;x-date"

        /** query 必须参与签名，顺序按参数名字典序 */
        private const val CANONICAL_QUERY = "Action=$ACTION&Version=$VERSION"
    }

    override val id: String = TranslatorIds.VOLCENGINE

    override val displayName: String = "火山云机器翻译"

    override val isOffline: Boolean = false

        // 强制 HTTP/1.1：火山 V4 签名里 SignedHeaders 含 `host`，
    // 而 h2 把 host 换成了伪头 `:authority`，服务端按 host 取值会取到空，
    // 结果是"签名自己算对了、服务端说不匹配"。连接池由 MtHttp 共享。
    private val http = MtHttp.newClient(
        connectMs = 2_000L,
        readMs = 5_000L,
        http1Only = true,
    )

    override suspend fun prepare(): AppResult<Unit> =
        if (config.isUsable) {
            AppResult.Ok(Unit)
        } else {
            AppResult.Err(AppError.Engine("火山引擎未配置 AccessKeyId / SecretAccessKey"))
        }

    override suspend fun translate(
        text: String,
        context: TranslationContext,
    ): AppResult<TranslationResult> = when (val r = request(listOf(text), context)) {
        is AppResult.Ok -> r.value.firstOrNull()
            ?.let { AppResult.Ok(it) }
            ?: AppResult.Err(AppError.Engine("火山返回空译文"))
        is AppResult.Err -> r
    }

    /**
     * 批量翻译：一次请求带多条 `TextList`（火山上限 16 条 / 5000 字符）。
     *
     * 切句后一次识别会产出多条文本，逐条发就是 N 次往返（每条约 300ms）。
     * 火山原生支持批量，压成一次请求后：请求数不变、延迟一次往返、结果按顺序对应。
     * 计费按字符累计，切分不增加字符总量。
     */
    override suspend fun translateBatch(
        texts: List<String>,
        context: TranslationContext,
    ): List<AppResult<TranslationResult>> {
        if (texts.isEmpty()) return emptyList()
        val valid = texts.filter { it.isNotBlank() }
        if (valid.isEmpty()) return texts.map { AppResult.Ok(TranslationResult("")) }
        return when (val r = request(valid, context)) {
            is AppResult.Ok -> r.value.map { AppResult.Ok(it) }
            is AppResult.Err -> valid.map { r }
        }
    }

    private suspend fun request(
        texts: List<String>,
        context: TranslationContext,
    ): AppResult<List<TranslationResult>> = withContext(AppDispatchers.translate) {
        if (!config.isUsable) {
            return@withContext AppResult.Err(AppError.Engine("火山引擎未配置密钥"))
        }
        val nonBlank = texts.map { it.trim() }.filter { it.isNotEmpty() }
        if (nonBlank.isEmpty()) {
            return@withContext AppResult.Ok(texts.map { TranslationResult("") })
        }

        try {
            // auto 时火山要求**不传** SourceLanguage 字段（传空串会 400）
            val payload = JSONObject().apply {
                val src = mapCode(context.sourceCode, allowAuto = true)
                if (src.isNotBlank()) put("SourceLanguage", src)
                put("TargetLanguage", mapCode(context.targetCode, allowAuto = false))
                put("TextList", JSONArray(nonBlank))
            }.toString()

            val xDate = utcDateTime()
            val shortDate = xDate.substring(0, 8)
            val payloadHash = sha256Hex(payload)

            val mediaType = CONTENT_TYPE.toMediaType()
            val requestBody = payload.toRequestBody(mediaType)

            // OkHttp 会给 body 的 Content-Type **自动补 `; charset=utf-8`**，
            // 而 BridgeInterceptor 最终发出去的就是 body 上的这个值。
            // 签名必须签实际发送值——否则我们自己按 "application/json" 算，
            // 服务端按收到的 "application/json; charset=utf-8" 算，永远对不上。
            // 这就是之前"签名一模一样却一直 SignatureDoesNotMatch"的真正原因。
            val actualContentType = requestBody.contentType()?.toString() ?: CONTENT_TYPE
            val signature = sign(payloadHash, xDate, shortDate, actualContentType)
            val scope = "$shortDate/${config.region}/$SERVICE/request"
            val authorization = "$ALGORITHM Credential=${config.cleanAccessKeyId}/$scope, " +
                "SignedHeaders=$SIGNED_HEADERS, Signature=$signature"

            val request = Request.Builder()
                .url("https://$HOST/?$CANONICAL_QUERY")
                .addHeader("Authorization", authorization)
                .addHeader("Content-Type", actualContentType)
                .addHeader("X-Date", xDate)
                .addHeader("X-Content-Sha256", payloadHash)
                .post(requestBody)
                .build()

            http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()

                // 响应全量记账。火山的业务错误在 HTTP 200 里，
                // 只有把 header + body 都留下，才能判断是签名、限流还是没开通。
                Log.w(
                    TAG,
                    "火山响应 ← ${response.code}\n" +
                        response.headers.joinToString("\n") { "  ${it.first}: ${it.second}" } +
                        "\n  body: $raw",
                )

                if (!response.isSuccessful) {
                    Log.w(TAG, "火山 HTTP ${response.code}: ${raw.take(220)}")
                    return@withContext AppResult.Err(
                        AppError.Network("火山引擎 HTTP ${response.code}：${hint(raw)}"),
                    )
                }

                val root = runCatching { JSONObject(raw) }.getOrNull()
                    ?: return@withContext AppResult.Err(
                        AppError.Engine("火山返回解析失败：${raw.take(120)}"),
                    )

                // 业务错误走 HTTP 200，错误在 ResponseMetadata.Error 里
                val error = root.optJSONObject("ResponseMetadata")?.optJSONObject("Error")
                if (error != null) {
                    val code = error.optString("Code", "Unknown")
                    val message = error.optString("Message", "")
                    Log.w(TAG, "火山业务错误 $code: $message")
                    // 签名类错误把实际请求内容带出来，否则根本没法定位差异
                    val diag = if (code.contains("Signature", true)) {
                        " [date=$xDate sig=${signature.take(12)} body=$payload]"
                    } else {
                        ""
                    }
                    return@withContext AppResult.Err(
                        AppError.Network(
                            "火山引擎 $code：${message.ifBlank { codeHint(code) }}$diag",
                        ),
                    )
                }

                // 批量响应按入参顺序返回，逐条取译文 / 源语言 / 计费字符
                val list = root.optJSONArray("TranslationList")
                    ?: return@withContext AppResult.Err(
                        AppError.Engine("火山返回里没有 TranslationList"),
                    )
                val results = ArrayList<TranslationResult>(list.length())
                for (i in 0 until list.length()) {
                    val item = list.optJSONObject(i) ?: continue
                    val translated = item.optString("Translation", "").orEmpty().trim()
                    if (translated.isEmpty()) continue
                    // 官方计费字符数：Extra.input_characters，与账单 Count×Unit 同源，
                    // 所以本地累加它就能和平台账单口径一致（实时停用只能靠这个）
                    val billed = item.optJSONObject("Extra")
                        ?.optString("input_characters", "")
                        ?.trim()
                        ?.toIntOrNull()
                        ?: 0
                    results.add(
                        TranslationResult(
                            text = translated,
                            billedChars = billed,
                            detectedLanguage = item.optString("DetectedSourceLanguage", "")
                                .trim()
                                .takeIf { it.isNotBlank() },
                        ),
                    )
                }
                if (results.isEmpty()) {
                    return@withContext AppResult.Err(AppError.Engine("火山返回空译文"))
                }
                return@withContext AppResult.Ok(results)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "火山请求失败", t)
            AppResult.Err(AppError.Network("火山请求失败：${t.message}", t))
        }
    }

    /**
     * V4 四步：
     *   1. 规范请求（方法 / 路径 / 参与签名的 query / 规范头 / 头列表 / 请求体哈希）
     *   2. 待签串（算法 / X-Date / 凭证范围 / 规范请求哈希）
     *   3. 从 Secret Key 四级派生（date → region → service → request）
     *   4. HMAC 待签串得签名
     */
    private fun sign(
        payloadHash: String,
        xDate: String,
        shortDate: String,
        contentType: String,
    ): String {
        // 每一行都以 \n 结尾，最后一行也不能例外
        // 注意 contentType 是**实际发出去的值**，不能写死常量
        val canonicalHeaders = "content-type:$contentType\n" +
            "host:$HOST\n" +
            "x-content-sha256:$payloadHash\n" +
            "x-date:$xDate\n"

        val canonicalRequest = listOf(
            "POST",
            "/",
            CANONICAL_QUERY,
            canonicalHeaders,
            SIGNED_HEADERS,
            payloadHash,
        ).joinToString("\n")

        val credentialScope = "$shortDate/${config.region}/$SERVICE/request"
        val stringToSign = listOf(
            ALGORITHM,
            xDate,
            credentialScope,
            sha256Hex(canonicalRequest),
        ).joinToString("\n")

        val kDate = hmacSha256(config.cleanSecret.toByteArray(Charsets.UTF_8), shortDate)
        val kRegion = hmacSha256(kDate, config.region)
        val kService = hmacSha256(kRegion, SERVICE)
        val kSigning = hmacSha256(kService, "request")

        return hmacSha256(kSigning, stringToSign).toHex()
    }

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .toHex()

    private fun hmacSha256(key: ByteArray, text: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(text.toByteArray(Charsets.UTF_8))
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /** 火山要求 `yyyyMMdd'T'HHmmss'Z'`，UTC */
    private fun utcDateTime(): String =
        SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

    /** HTTP 非 2xx 时从响应体里再挖一次错误信息（火山也可能用 4xx 返回业务错误） */
    private fun hint(raw: String): String {
        val error = runCatching { JSONObject(raw) }
            .getOrNull()
            ?.optJSONObject("ResponseMetadata")
            ?.optJSONObject("Error")
        val code = error?.optString("Code").orEmpty()
        val message = error?.optString("Message").orEmpty()
        return message.ifBlank {
            if (code.isNotBlank()) codeHint(code) else "请检查密钥与服务开通状态"
        }
    }

    /**
     * 火山官方的错误码对照（来自文本翻译接口文档），翻译成人话。
     *
     * 这几个码都会走 HTTP 200，真正的错误在 `ResponseMetadata.Error` 里，
     * 所以必须显式处理，否则用户只会看到一句"翻译失败"。
     */
    private fun codeHint(code: String): String = when (code) {
        "-400" -> "请求参数错误，检查语言代码是否受支持"
        "-415" -> "这个语言方向不支持，换一门源语言试试"
        "-429" -> "请求过于频繁，稍等几秒（字幕速度过快时偶发）"
        "-500" -> "火山服务内部错误，重试即可"
        else -> when {
            code.startsWith("1000") -> "接口调用失败（错误码 $code）"
            code.contains("Signature", true) -> "签名校验失败，检查 SecretAccessKey"
            code.contains("InvalidAccessKey", true) -> "AccessKeyId 无效"
            code.contains("NotOpen", true) -> "未开通机器翻译服务"
            code.contains("Quota", true) -> "免费额度已用尽"
            else -> "未知错误"
        }
    }

    /** 火山语言码：`zh` / `en` / `ja` / `ru` / `ko`。目标语言只支持简体，传 zh。 */
    private fun mapCode(code: String, allowAuto: Boolean): String {
        val c = code.lowercase().substringBefore('-')
        return when {
            c.isBlank() || c == "auto" -> if (allowAuto) "" else "zh"
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
