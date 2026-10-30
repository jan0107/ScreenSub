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
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 谷歌翻译（免 Key，非官方接口）。
 *
 * 接口就是 `translate.googleapis.com/translate_a/single?client=gtx`，
 * 很多开源字幕项目（含 VOSK-Powered-Live-Subtitle）都用这个。
 *
 * ## DNS 必须自己解析
 *
 * 实测：开了代理的手机，系统 DNS 会把 translate.googleapis.com 解析成
 * `28.0.0.57` 这类**假 IP**（代理 fake-IP、荣耀 predns 都会这么干）。
 * 连假 IP 会在 7 毫秒内被拒，看起来像"接口挂了"，其实换真实 IP 立刻 200。
 * 所以这里绕开系统 DNS，用阿里/腾讯的 DoH 拿真实 IP。
 *
 * ## 代价
 *
 * 没有文档、没有 SLA。谷歌随时能改路径或限流，届时本引擎会自禁用
 * （熔断 + 单次失败降级），翻译链继续走 MyMemory / ML Kit。
 */
class GoogleFreeTranslator : Translator {

    companion object {
        private const val TAG = LogTags.MT

        private const val ENDPOINT = "https://translate.googleapis.com/translate_a/single"

        /** 建连超时。国内连谷歌基本必失败，等 3 秒纯属浪费；1.5s 足够判定"不通" */
        private const val TIMEOUT_MS = 1_500L

        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    override val id: String = TranslatorIds.GOOGLE_FREE

    override val displayName: String = "谷歌翻译（免 Key）"

    override val isOffline: Boolean = false

    @Volatile
    private var reachable = false

    @Volatile
    private var probed = false

        private val http = MtHttp.newClient(
        connectMs = TIMEOUT_MS,
        readMs = 4_000L,
        dns = GoogleDohDns(),
    )

    override suspend fun prepare(): AppResult<Unit> = withContext(AppDispatchers.translate) {
        if (!probed) {
            reachable = probe()
            probed = true
            Log.i(
                TAG,
                if (reachable) {
                    "谷歌翻译可用（IP ${runCatching { GoogleDohDns().lookup("translate.googleapis.com").firstOrNull()?.hostAddress }.getOrNull()}）"
                } else {
                    "谷歌翻译不可达"
                },
            )
        }
        if (reachable) AppResult.Ok(Unit) else AppResult.Err(AppError.Network("谷歌翻译不可达"))
    }

    private fun probe(): Boolean = try {
        val url = "$ENDPOINT?client=gtx&sl=en&tl=zh-CN&dt=t&q=hi"
        http.newCall(Request.Builder().url(url).header("User-Agent", UA).build())
            .execute()
            .use { it.isSuccessful }
    } catch (t: Throwable) {
        Log.d(TAG, "谷歌探测失败：${t.javaClass.simpleName}")
        false
    }

    override suspend fun translate(
        text: String,
        context: TranslationContext,
    ): AppResult<TranslationResult> = withContext(AppDispatchers.translate) {
        if (text.isBlank()) return@withContext AppResult.Ok(TranslationResult(""))

        // 探测阶段就不可达 → 立刻失败，不要每句都去撞一次连接超时。
        // 撞超时的代价是这条字幕直接等到期显示原文，等于白白废掉整句。
        if (probed && !reachable) {
            return@withContext AppResult.Err(AppError.Network("谷歌翻译不可达（已跳过）"))
        }

        val sl = code(context.sourceCode)
        val tl = code(context.targetCode)
        val url = "$ENDPOINT?client=gtx&sl=$sl&tl=$tl&dt=t&q=${URLEncoder.encode(text, "UTF-8")}"

        try {
            http.newCall(Request.Builder().url(url).header("User-Agent", UA).build())
                .execute()
                .use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        if (response.code == 429) reachable = false
                        return@withContext AppResult.Err(
                            AppError.Network("谷歌翻译 HTTP ${response.code}"),
                        )
                    }
                    val parsed = parse(raw)
                    if (parsed.isNullOrBlank()) {
                        AppResult.Err(AppError.Engine("谷歌翻译返回解析失败"))
                    } else {
                        AppResult.Ok(TranslationResult(parsed))
                    }
                }
        } catch (t: Throwable) {
            Log.w(TAG, "谷歌翻译异常", t)
            AppResult.Err(AppError.Network("谷歌翻译请求失败：${t.message}", t))
        }
    }

    /**
     * 返回结构：`[[["你好世界","hello world",null,null,10]],null,"en",null,...]`
     *
     * 长句会被切成多段，`[0]` 里每个元素的第一项是一段译文，要按顺序拼接。
     */
    private fun parse(raw: String): String? = runCatching {
        val segments = JSONArray(raw).optJSONArray(0) ?: return null
        val sb = StringBuilder()
        for (i in 0 until segments.length()) {
            val piece = segments.optJSONArray(i)?.optString(0, "").orEmpty()
            if (piece.isNotBlank()) sb.append(piece)
        }
        sb.toString().trim().ifBlank { null }
    }.getOrNull()

    private fun code(code: String): String {
        val c = code.lowercase().substringBefore('-')
        return when {
            c.isBlank() || c == "auto" -> "auto"
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

/**
 * 只给谷歌域名用的 DoH 解析器。
 *
 * 用阿里（223.5.5.5）/ 腾讯（1.12.12.12）/ DNSPod（120.53.53.53）的
 * DoH JSON 接口拿真实 A 记录，绕开系统 DNS 返回的假 IP。
 *
 * OkHttp 用自定义 Dns 时仍以 URL 里的域名做 SNI 和 Host 头，
 * 连的却是我们解析出的真实 IP，证书校验照常通过。
 *
 * 只对 googleapis.com / google.com 生效——其他域名系统 DNS 是好的，
 * 没必要多花一次往返。
 */
private class GoogleDohDns : Dns {

    private companion object {
        val RESOLVERS = listOf(
            "https://223.5.5.5/resolve",
            "https://1.12.12.12/resolve",
            "https://120.53.53.53/resolve",
        )

        const val TTL_MS = 10 * 60 * 1000L

        /** DoH 全灭后的冷却时间 */
        const val NEGATIVE_TTL_MS = 5 * 60 * 1000L

        /** 等单个解析器结果的上限，略大于 DoH 自身超时 */
        const val RESOLVE_WAIT_MS = 1_000L
        const val TAG = LogTags.MT
    }

    private class CacheEntry(val addrs: List<InetAddress>, val expireAt: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /** 只连 IP 字面量，不会再触发 DNS */
    // 900ms 而不是 2s：DoH 在国内基本必挂，等 2 秒只是白等。
    // 三个解析器串行最坏 2.7 秒，配合下面的负缓存，实际只在首次付一次。
    private val dohClient = MtHttp.newClient(connectMs = 900L, readMs = 900L)

    /** DoH 并发查询用的线程池，守护线程，不阻止进程退出 */
    private val pool = java.util.concurrent.Executors.newFixedThreadPool(
        RESOLVERS.size,
    ) { r -> Thread(r, "google-doh").apply { isDaemon = true } }

    /** 负缓存到期时间：DoH 全灭后一段时间内直接跳过，别再逐个等超时 */
    @Volatile
    private var negativeUntil = 0L

    override fun lookup(hostname: String): List<InetAddress> {
        if (!hostname.endsWith("googleapis.com") && !hostname.endsWith("google.com")) {
            return Dns.SYSTEM.lookup(hostname)
        }

        // 上次解析全灭 → 5 分钟内直接走系统 DNS（拿到假 IP 也会 7ms 内被拒，
        // 比重新等三个 DoH 超时快得多）。这条是"探测 6 秒"变"探测一次"的关键。
        if (System.currentTimeMillis() < negativeUntil) {
            return Dns.SYSTEM.lookup(hostname)
        }

        cache[hostname]?.let { entry ->
            if (entry.expireAt > System.currentTimeMillis()) return entry.addrs
        }

        // 三个解析器**并发**查，不再串行。
        // 串行时每个 900ms 超时，最坏 2.7 秒；并发后整体只受最慢那个约束（≈0.9s）。
        // 国内 DoH 通常全灭，节省的这 1.8 秒直接体现在"启动后多久能开始翻译"上。
        val futures = RESOLVERS.map { base ->
            pool.submit<List<InetAddress>> { query(base, hostname) }
        }
        try {
            for (future in futures) {
                val addrs = runCatching {
                    future.get(RESOLVE_WAIT_MS, TimeUnit.MILLISECONDS)
                }.getOrDefault(emptyList())
                if (addrs.isNotEmpty()) {
                    cache[hostname] = CacheEntry(addrs, System.currentTimeMillis() + TTL_MS)
                    Log.d(TAG, "DoH $hostname → ${addrs.joinToString { it.hostAddress.orEmpty() }}")
                    return addrs
                }
            }
        } finally {
            futures.forEach { it.cancel(true) }
        }

        Log.w(TAG, "DoH 全部失败，回退系统 DNS（5 分钟内不再尝试 DoH）")
        negativeUntil = System.currentTimeMillis() + NEGATIVE_TTL_MS
        return Dns.SYSTEM.lookup(hostname)
    }

    private fun query(base: String, hostname: String): List<InetAddress> = try {
        val request = Request.Builder()
            .url("$base?name=$hostname&type=A")
            .header("accept", "application/dns-json")
            .build()

        dohClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val answers = JSONObject(response.body?.string().orEmpty())
                .optJSONArray("Answer") ?: return emptyList()

            val out = ArrayList<InetAddress>(4)
            for (i in 0 until answers.length()) {
                val answer = answers.optJSONObject(i) ?: continue
                if (answer.optInt("type", 1) != 1) continue
                val data = answer.optString("data", "")
                if (data.isBlank()) continue
                runCatching { InetAddress.getByName(data) }.getOrNull()?.let { out.add(it) }
            }
            out
        }
    } catch (t: Throwable) {
        Log.d(TAG, "DoH $base 失败：${t.javaClass.simpleName}")
        emptyList()
    }
}
