// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.data.billing

import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 账单接口的签名工具。
 *
 * ## 为什么在这里又写一遍
 *
 * `core:mt` 里已经有阿里云 RPC(HMAC-SHA1) 和火山 V4(HMAC-SHA256) 的签名实现，
 * 但那是**翻译接口**用的（不同 host、不同 service、不同 version）。
 * 而 `core:data` 不能反向依赖 `core:mt`（会把 ML Kit 和 OkHttp 拖进数据层），
 * 所以这里只抽最底层的密码学原语，两家各按自己的规范拼一次。
 *
 * 两边都是纯函数、几行 HMAC，重复的成本远低于引入模块依赖或新建模块。
 */
internal object BillingSign {

    fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    fun hmacSha1(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .toHex()

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /**
     * 阿里云 RPC 专用编码。
     *
     * 和普通 URLEncoder 有三处不同：空格编成 `%20`（不是 `+`）、`*` 要编成 `%2A`、
     * `~` 不编码。少做这三步替换，签名必错。
     */
    fun aliyunEncode(value: String): String =
        URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")

    /** 火山/阿里云都用 `yyyy-MM-dd'T'HH:mm:ss'Z'`（UTC） */
    fun isoUtc(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

    /** 火山 X-Date 用 `yyyyMMdd'T'HHmmss'Z'`（UTC，无分隔符） */
    fun volcDate(): Pair<String, String> {
        val full = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        return full to full.substring(0, 8)
    }

    fun nonce(): String = UUID.randomUUID().toString().replace("-", "")

    fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
