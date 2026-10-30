// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.data.billing

import com.screensub.core.common.AppDispatchers
import com.screensub.core.common.AppError
import com.screensub.core.common.AppResult
import com.screensub.core.common.Log
import com.screensub.core.common.LogTags
import com.screensub.core.common.TranslatorIds
import com.screensub.core.data.billing.BillingSign.toHex
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/**
 * 云端账单状态。
 *
 * ## 两家云到底能拿到什么（2026-10-29 实测定稿）
 *
 * **阿里云**：`GetTranslateReport` 直接给账号级逐日字符数，T+1（当天查不到今天）。
 *
 * **火山**：没有用量类 Action（`translate` 服务下 26 个候选全部 `InvalidActionOrVersion`，
 * 云监控 141 个命名空间里也没有 translate），**但账单明细能拿到字符数**——
 * 只要把 `Unit` 换算对：
 *
 * ```
 * ListBillDetail { BillPeriod, GroupPeriod=1, Product=["translate"],
 *                  IgnoreZero=0, NeedRecordNum=1, Limit=300 }
 * → Result.List[] = { ExpenseDate, Count, Unit, Element, InstanceNo, PayableAmount }
 *    Count="0.002612"  Unit="百万字符"  →  2612 字符
 * ```
 *
 * 三个必须记住的点：
 *  1. **`Unit` 是「百万字符」**，不是字符。`Count` 保留 6 位小数，正好精确到 1 个字符。
 *  2. **免费额度内也会出账单行**（`PayableAmount=0.00` 但行存在）。
 *     2026-10-28 曾据此判定"免费额度内不生成明细行"，那是错的——真实原因是
 *     机器翻译实例 `Translate2000001042242810306` 到 10-27 11:11 才开通，
 *     所以 8/9 月查不到任何行。**不能用"有没有行"判断是否超额，只能看金额。**
 *  3. **T+1 且生成得晚**：10-27 那天的账单行 `TradeTime=2026-10-28 15:09`。
 *     所以今天上午查不到昨天的数是正常的，不能当成"没用量"。
 *
 * 另外两条路已排除：`ListSplitBillDetail`（分账账单）比明细再晚 2 天，
 * 官方建议查 T-5，对实时监测没意义；`ListResourcePackages` 的 `ResourceType`
 * 传任何值都报 `InvalidParam`，免费额度不是资源包。
 */
/**
 * 平台口径的某一天用量。
 *
 * [calls] 是当天的调用次数——它能回答一个本地计数永远答不了的问题：
 * "这个账号除了我们，还有别的东西在调吗"。字符数与调用次数的比值
 * 明显偏离本 App 的单句长度（几十字符）时，就说明有别的程序在用。
 */
data class DayChars(
    /** `yyyy-MM-dd` */
    val day: String,
    val chars: Long,
    val calls: Long,
)

data class CloudBillStatus(
    val engineId: String,
    val monthKey: String,
    /** 账户可用余额（元） */
    val balanceYuan: Double? = null,
    /** 欠费金额（元） */
    val arrearsYuan: Double? = null,
    /** 本月已产生费用（元） */
    val monthCostYuan: Double? = null,
    /**
     * **官方口径的本月字符用量**。
     *
     * 阿里云来自 `GetTranslateReport`，火山来自 `ListBillDetail` 的 `Count × Unit`。
     * 两家都是账号级真实值：包含本 App、其他设备、用户自己在控制台试的调用。
     * 查不到时为 null（而不是 0），让 UI 显示"未知"而不是误导性的"用了 0"。
     */
    val monthChars: Long? = null,
    /** 今日官方字符用量（用于和本地计数对账，发现"别的程序也在用"） */
    val todayChars: Long? = null,    /** 昨日官方字符用量（平台数据 T+1，昨天的数一定已经完整） */
    val yesterdayChars: Long? = null,
    /**
     * **平台口径的每日字符用量**（时间升序）。
     *
     * 阿里云 `GetTranslateReport` + `Group=day` 直接给出，实测能查到昨天
     * （2026-10-27：1648 字符 / 59 次）。这是 UI 展示 7 日图与"昨天用了多少"
     * 的唯一来源——不用本地估算，全部是平台真实值。
     */
    val dailyChars: List<DayChars> = emptyList(),
    /**
     * 为什么没拿到字符明细。
     *
     * 和 [error] 分开：余额查到了但用量没查到，也是常见情况（权限、还没出账），
     * 这时不该把整条状态标成失败，但 UI 必须能告诉用户**具体卡在哪**——
     * "暂时没拿到平台用量"这种话等于没说。
     */
    val usageCharsError: String? = null,
    /** 是否确认已超出免费额度（有费用 / 有欠费 / 官方用量已超阈值） */
    val overFreeQuota: Boolean = false,
    val checkedAt: Long = 0L,
    val error: String? = null,
) {
    val ok: Boolean get() = error == null
}

/**
 * 字符明细的查询结果。
 *
 * `days == null && error == null` 是**正常状态**：本月还没调用、或者昨天的账单
 * 还没出（火山 T+1 且次日下午才落库）。这种情况下不该报警，也不该显示 0。
 */
private class BillCharsResult(
    val days: List<DayChars>?,
    val error: String?,
)

/** 一家云厂商的账单查询 */
interface CloudBillingProbe {
    val engineId: String
    suspend fun query(credentials: Credentials, monthKey: String): AppResult<CloudBillStatus>

    data class Credentials(
        val accessKeyId: String,
        val secretAccessKey: String,
        val region: String = "",
    )
}

private val http: OkHttpClient by lazy {
    OkHttpClient.Builder()
        // **必须锁死 HTTP/1.1**。
        //
        // 火山 V4 签名的 SignedHeaders 里含 `host`，而 HTTP/2 没有 `host` 头
        // （等价信息在伪头 `:authority` 里）。一旦协商到 h2，服务端按 `host`
        // 去找参与签名的值，取到空字符串 → 算出来的签名必然对不上 →
        // 一律返回 401 SignatureDoesNotMatch，且报错信息不会提示是协议问题。
        //
        // 这个 bug 只在真机上出现：同一对 AK/SK 用 curl / Python(urllib) 调都正常，
        // 因为它们只会说 HTTP/1.1。排查时很容易误判成密钥或算法错。
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
}

// ---------------- 阿里云 ----------------

/**
 * 阿里云：`QueryAccountBalance` 查余额 + `DescribeInstanceBill` 查本月 alimt 费用。
 *
 * 两个接口都已实测可用（返回 `Code=Success` 并带回账号名），
 * 只是当前账号本月没有产生任何费用，所以明细是空的。
 */
object AliyunBillingProbe : CloudBillingProbe {

    private const val TAG = LogTags.DATA
    private const val ENDPOINT = "https://business.aliyuncs.com/"
    private const val VERSION = "2017-12-14"

    /** 机器翻译是独立网关，用量报表要走这个 host + 2018-10-12 版本 */
    private const val MT_ENDPOINT = "https://mt.cn-hangzhou.aliyuncs.com/"
    private const val MT_VERSION = "2018-10-12"

    override val engineId: String = TranslatorIds.ALIYUN

    override suspend fun query(
        credentials: CloudBillingProbe.Credentials,
        monthKey: String,
    ): AppResult<CloudBillStatus> = withContext(AppDispatchers.io) {
        if (credentials.accessKeyId.isBlank() || credentials.secretAccessKey.isBlank()) {
            return@withContext AppResult.Err(AppError.Engine("阿里云未配置 AccessKey"))
        }

        var balance: Double? = null
        var cost: Double? = null
        var lastError: String? = null

        // 1. 余额
        when (val r = call(credentials, mapOf("Action" to "QueryAccountBalance"))) {
            is AppResult.Ok -> {
                val data = r.value.optJSONObject("Data")
                balance = data?.optString("AvailableAmount")?.toDoubleOrNull()
            }

            is AppResult.Err -> lastError = r.error.message
        }

        // 2. 本月机器翻译费用
        when (
            val r = call(
                credentials,
                mapOf(
                    "Action" to "DescribeInstanceBill",
                    "BillingCycle" to monthKey,
                    "ProductCode" to "alimt",
                    "IsBillingItem" to "true",
                    "MaxResults" to "300",
                ),
            )
        ) {
            is AppResult.Ok -> {
                val items = r.value.optJSONObject("Data")?.optJSONArray("Items")
                var sum = 0.0
                if (items != null) {
                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i) ?: continue
                        sum += item.optString("PretaxAmount").toDoubleOrNull() ?: 0.0
                    }
                }
                cost = sum
            }

            is AppResult.Err -> lastError = r.error.message
        }

        if (balance == null && cost == null) {
            return@withContext AppResult.Err(
                AppError.Network(lastError ?: "阿里云账单查询失败"),
            )
        }

        // 3. 官方字符用量报表 —— 这是唯一能拿到"账号级真实字符数"的接口
        val report = queryTranslateReport(credentials, monthKey)
        val daily = report.days
        val dayFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val today = dayFmt.format(java.util.Date())
        val yesterday = dayFmt.format(java.util.Date(System.currentTimeMillis() - 86_400_000L))

        // 阿里云欠费体现在「可用额度」为负；这里用余额 < 0 近似
        val arrears = balance?.takeIf { it < 0 }?.let { -it }

        AppResult.Ok(
            CloudBillStatus(
                engineId = engineId,
                monthKey = monthKey,
                balanceYuan = balance,
                arrearsYuan = arrears,
                monthCostYuan = cost,
                // 报表查不到（网络/权限失败）时给 null 而不是 0，
                // 否则 UI 会显示"本月用了 0 字符"，比显示"未知"更误导
                monthChars = daily?.sumOf { it.chars },
                todayChars = daily?.firstOrNull { it.day == today }?.chars,
                yesterdayChars = daily?.firstOrNull { it.day == yesterday }?.chars,
                dailyChars = daily.orEmpty(),
                usageCharsError = report.error,
                // 有费用 = 免费额度必然已用完（免费额度内不出账单）
                overFreeQuota = (cost ?: 0.0) > 0.0 || (arrears ?: 0.0) > 0.0,
                checkedAt = System.currentTimeMillis(),
                error = lastError,
            ),
        )
    }

    /**
     * `GetTranslateReport`：按天聚合的账号级调用报表，`total` 就是字符数。
     *
     * 官方参数（已核对文档）：`ApiName=translate_standard`（通用版）、`Group=day|hour`、
     * `BeginTime`/`EndTime` 格式 `YYYY-MM-DD HH:mm:ss`。
     *
     * 返回 `Data.result[]`，每项 `{time, total, totalTimes}`。
     * 注意有的网关把 `Data` 序列化成字符串，这里两种都兼容。
     */
    private fun queryTranslateReport(
        credentials: CloudBillingProbe.Credentials,
        monthKey: String,
    ): BillCharsResult {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        val begin = "$monthKey-01 00:00:00"
        val end = fmt.format(java.util.Date())

        val root = when (
            val r = call(
                credentials = credentials,
                params = mapOf(
                    "Action" to "GetTranslateReport",
                    "BeginTime" to begin,
                    "EndTime" to end,
                    "ApiName" to "translate_standard",
                    "Group" to "day",
                ),
                endpoint = MT_ENDPOINT,
                version = MT_VERSION,
            )
        ) {
            is AppResult.Ok -> r.value
            is AppResult.Err -> {
                Log.w(TAG, "阿里云用量报表失败：${r.error.message}")
                return BillCharsResult(null, r.error.message)
            }
        }

        val data = root.opt("Data").let { raw ->
            when (raw) {
                is JSONObject -> raw
                is String -> runCatching { JSONObject(raw) }.getOrNull()
                else -> null
            }
        } ?: return BillCharsResult(null, "报表响应缺少 Data 字段")

        // 服务端会把区间内每一天都列出来（没调用的那天 total=0），
        // 所以这里保留零值行——7 日图需要完整的日期轴，不能只留有数据的天。
        val result = data.optJSONArray("result") ?: return BillCharsResult(emptyList(), null)
        val out = ArrayList<DayChars>(result.length())
        for (i in 0 until result.length()) {
            val row = result.optJSONObject(i) ?: continue
            // time 形如 "2026-10-27 23:59:59"，取日期部分
            val day = row.optString("time", "").take(10)
            if (day.length != 10) continue
            out += DayChars(
                day = day,
                chars = row.optLong("total", 0L),
                calls = row.optLong("totalTimes", 0L),
            )
        }
        out.sortBy { it.day }
        Log.i(
            TAG,
            "阿里云官方用量：${out.size} 天，本月合计 ${out.sumOf { it.chars }} 字符" +
                "，最近一天 ${out.lastOrNull { it.chars > 0 }?.let { "${it.day}=${it.chars}" } ?: "无"}",
        )
        return BillCharsResult(out, null)
    }

    /** RPC 签名：参数全塞 query，HMAC-SHA1，`secret + "&"` 当 key */
    private fun call(
        credentials: CloudBillingProbe.Credentials,
        params: Map<String, String>,
        endpoint: String = ENDPOINT,
        version: String = VERSION,
    ): AppResult<JSONObject> {
        val all = params.toMutableMap().apply {
            put("Format", "JSON")
            put("AccessKeyId", credentials.accessKeyId)
            put("SignatureMethod", "HMAC-SHA1")
            put("SignatureVersion", "1.0")
            put("SignatureNonce", BillingSign.nonce())
            put("Timestamp", BillingSign.isoUtc())
            put("Version", version)
        }

        val canonical = all.keys.sorted().joinToString("&") { k ->
            "${BillingSign.aliyunEncode(k)}=${BillingSign.aliyunEncode(all.getValue(k))}"
        }
        val stringToSign = "GET&%2F&" + BillingSign.aliyunEncode(canonical)
        val signature = BillingSign.base64(
            BillingSign.hmacSha1(
                (credentials.secretAccessKey.trim() + "&").toByteArray(Charsets.UTF_8),
                stringToSign,
            ),
        )

        val query = all.keys.sorted().joinToString("&") { k ->
            "${BillingSign.aliyunEncode(k)}=${BillingSign.aliyunEncode(all.getValue(k))}"
        } + "&Signature=" + BillingSign.aliyunEncode(signature)

        return execute("$endpoint?$query")
    }
}

// ---------------- 火山引擎 ----------------

/**
 * 火山：`QueryBalanceAcct` 查余额/欠费 + `ListBillOverviewByProd` 查本月费用
 * + `ListBillDetail` 查逐日字符数。
 *
 * ## 教训（2026-10-29）
 *
 * 曾经写成"有账单行 = 已超额"，这是**错的**：免费额度内也会出 `PayableAmount=0.00`
 * 的行（实测 10-27 那天就有一行）。是否超额只能看金额，不能看有没有行。
 */
object VolcengineBillingProbe : CloudBillingProbe {

    private const val TAG = LogTags.DATA
    private const val HOST = "open.volcengineapi.com"
    private const val SERVICE = "billing"
    private const val VERSION = "2022-01-01"
    private const val SIGNED_HEADERS = "content-type;host;x-content-sha256;x-date"

    override val engineId: String = TranslatorIds.VOLCENGINE

    override suspend fun query(
        credentials: CloudBillingProbe.Credentials,
        monthKey: String,
    ): AppResult<CloudBillStatus> = withContext(AppDispatchers.io) {
        if (credentials.accessKeyId.isBlank() || credentials.secretAccessKey.isBlank()) {
            return@withContext AppResult.Err(AppError.Engine("火山未配置 AccessKey"))
        }

        var balance: Double? = null
        var arrears: Double? = null
        var cost: Double? = null
        var lastError: String? = null

        when (val r = call(credentials, "QueryBalanceAcct", JSONObject())) {
            is AppResult.Ok -> {
                val result = r.value.optJSONObject("Result")
                balance = result?.optDouble("AvailableBalance")?.takeIf { !it.isNaN() }
                arrears = result?.optDouble("ArrearsBalance")?.takeIf { !it.isNaN() }
            }

            is AppResult.Err -> lastError = r.error.message
        }

        val body = JSONObject().apply {
            put("BillPeriod", monthKey)
            put("Limit", 300)
            put("Offset", 0)
        }
        when (val r = call(credentials, "ListBillOverviewByProd", body)) {
            is AppResult.Ok -> {
                val list = r.value.optJSONObject("Result")?.optJSONArray("List")
                if (list != null && list.length() > 0) {
                    var sum = 0.0
                    var parsed = false
                    for (i in 0 until list.length()) {
                        val item = list.optJSONObject(i) ?: continue
                        COST_KEYS.firstNotNullOfOrNull { k ->
                            item.optString(k).toDoubleOrNull()
                        }?.let {
                            sum += it
                            parsed = true
                        }
                    }
                    if (parsed) cost = sum
                } else {
                    // 一行都没有 = 本月确实没调用过，费用为 0
                    cost = 0.0
                }
            }

            is AppResult.Err -> lastError = r.error.message
        }

        if (balance == null && cost == null) {
            return@withContext AppResult.Err(
                AppError.Network(lastError ?: "火山账单查询失败"),
            )
        }

        // 4. 按天拉 translate 的字符用量（`Count × Unit`）—— 平台唯一能给出的字符数。
        //    实测能拿到（2026-10-27：Count=0.002612 百万字符 = 2612 字符）。
        //    拿到空就保持 null，由 UI 回落到本地计数，绝不把"没数据"显示成"用了 0"。
        val bill = queryTranslateBillUsage(credentials, monthKey)
        val billChars = bill.days
        val dayFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val today = dayFmt.format(java.util.Date())
        val yesterday = dayFmt.format(java.util.Date(System.currentTimeMillis() - 86_400_000L))

        AppResult.Ok(
            CloudBillStatus(
                engineId = engineId,
                monthKey = monthKey,
                balanceYuan = balance,
                arrearsYuan = arrears,
                monthCostYuan = cost,
                monthChars = billChars?.sumOf { it.chars },
                todayChars = billChars?.firstOrNull { it.day == today }?.chars,
                yesterdayChars = billChars?.firstOrNull { it.day == yesterday }?.chars,
                dailyChars = billChars.orEmpty(),
                usageCharsError = bill.error,
                // 只有「真的产生了费用」或「欠费」才算超出免费额度。
                // 光有账单行不算——0 元的行每天都有，见类注释第 2 条。
                overFreeQuota = (cost ?: 0.0) > 0.0 || (arrears ?: 0.0) > 0.0,
                checkedAt = System.currentTimeMillis(),
                error = lastError,
            ),
        )
    }

    /**
     * 按天拉 `translate` 的字符用量，累加成每日明细。
     *
     * 参数按官方文档逐字对齐，三个最容易被忽略：
     *  - `GroupPeriod = 1`：按天聚合。传 0 只有一行账期汇总，做不了 7 日图。
     *  - `IgnoreZero = 0`：**不**忽略折后价为 0 的明细。这是整件事的关键——
     *    免费额度内的行 `PayableAmount=0.00`，一旦开了忽略就全被滤掉，
     *    这就是之前误判"火山拿不到用量"的直接原因之一。
     *  - `NeedRecordNum = 1`：要整数，传布尔会直接 400。
     *
     * **单位换算才是最容易出错的一步**：`Unit` 是「百万字符」，`Count` 是
     * 以百万为单位的数（`0.002612` = 2612 字符），且保留 6 位小数 = 精确到 1 字符。
     *
     * @return [BillCharsResult.days] 为 null 表示"查不到"，调用方应回落到本地计数；
     *         此时 [BillCharsResult.error] 说明原因（null = 纯粹还没出账，不是故障）
     */
    private fun queryTranslateBillUsage(
        credentials: CloudBillingProbe.Credentials,
        monthKey: String,
    ): BillCharsResult {
        val body = JSONObject().apply {
            put("BillPeriod", monthKey)
            // 一天最多一行（每种 Element/Unit 一行），300 够一整月还有余量
            put("Limit", 300)
            put("Offset", 0)
            put("NeedRecordNum", 1)
            put("IgnoreZero", 0)
            put("GroupPeriod", 1)
            put("Product", JSONArray().put("translate"))
        }
        val root = when (val r = call(credentials, "ListBillDetail", body)) {
            is AppResult.Ok -> r.value
            is AppResult.Err -> {
                val msg = r.error.message
                Log.w(TAG, "火山账单明细失败：$msg")
                return BillCharsResult(null, friendlyBillError(msg))
            }
        }
        val result = root.optJSONObject("Result")
        val list = result?.optJSONArray("List") ?: return BillCharsResult(null, "账单响应缺少 List 字段")
        // 一行都没有：要么本月还没调用，要么昨天的账单还没生成（T+1 下午才落库）。
        // 两种情况都不等于"用了 0 字符"，所以返回 null 让 UI 说"未知"。
        // 这里不算错误，error 留空 —— 新账号第一次打开必然是这个状态。
        if (list.length() == 0) return BillCharsResult(null, null)

        val byDay = LinkedHashMap<String, Long>()
        for (i in 0 until list.length()) {
            val row = list.optJSONObject(i) ?: continue
            if (!row.optString("Product").equals("translate", ignoreCase = true)) continue
            val day = row.optString("ExpenseDate").take(10)
            if (day.length != 10) continue
            val count = row.optString("Count").toDoubleOrNull() ?: continue
            // 四舍五入而不是截断：0.0026119999 × 1e6 截断会少 1 个字符
            val chars = (count * unitMultiplier(row.optString("Unit"))).roundToLong()
            byDay[day] = (byDay[day] ?: 0L) + chars
        }
        if (byDay.isEmpty()) {
            return BillCharsResult(null, "账单有 ${list.length()} 行，但都不是 translate 的计费用量")
        }

        Log.i(
            TAG,
            "火山账单用量：${byDay.size} 天，合计 ${byDay.values.sum()} 字符" +
                "（最近 ${byDay.entries.maxByOrNull { it.key }?.let { "${it.key}=${it.value}" } ?: "无"}）",
        )
        return BillCharsResult(
            byDay.entries.sortedBy { it.key }
                .map { DayChars(day = it.key, chars = it.value, calls = 0L) },
            null,
        )
    }

    /**
     * 把火山的原始报错翻成用户能动手的一句话。
     *
     * 这里是"要不要提示用户去开通什么"的关键判断点：**账单明细不需要开通**，
     * 分账账单才需要（而且延迟 2 天，本项目没用）。所以失败原因只可能是
     * 密钥不对、权限不够、或者根本还没出账，不该让用户去翻控制台开关。
     */
    private fun friendlyBillError(raw: String): String = when {
        raw.contains("403") ||
            raw.contains("AccessDenied", true) ||
            raw.contains("Unauthorized", true) ||
            raw.contains("Forbidden", true) ->
            "这个 AccessKey 没有读取账单的权限，请到 IAM 给它授予 " +
                "BillingCenterBillReadOnlyAccess（费用中心账单只读）"

        raw.contains("SignatureDoesNotMatch", true) || raw.contains("401") ->
            "AccessKey / SecretAccessKey 不匹配，请核对后重新填写"

        raw.contains("AccountNotActivated", true) ->
            "这个火山账号还没完成实名/开通，账单服务不可用"

        else -> raw
    }

    /**
     * 用量单位 → 字符倍数。
     *
     * **顺序不能反**：火山的 `Unit` 是「百万字符」，里面含"万"字，
     * 一旦先判断 `contains("万")` 就会落到 1e4 分支，2612 字符被算成 26 字符。
     */
    private fun unitMultiplier(unit: String): Double = when {
        unit.contains("百万") -> 1_000_000.0
        unit.contains("万") -> 10_000.0
        unit.contains("千") -> 1_000.0
        else -> 1.0
    }

    private val COST_KEYS = listOf(
        "PayableAmount", "PretaxAmount", "TotalAmount", "Amount", "OriginalAmount",
    )

    /** V4 签名：四级派生 date → region → service → request */
    private fun call(
        credentials: CloudBillingProbe.Credentials,
        action: String,
        bodyJson: JSONObject,
    ): AppResult<JSONObject> {
        val payload = bodyJson.toString()
        val payloadHash = BillingSign.sha256Hex(payload)
        val (xDate, shortDate) = BillingSign.volcDate()
        val region = credentials.region.ifBlank { "cn-north-1" }
        val canonicalQuery = "Action=$action&Version=$VERSION"

        val canonicalHeaders = "content-type:application/json\n" +
            "host:$HOST\n" +
            "x-content-sha256:$payloadHash\n" +
            "x-date:$xDate\n"
        val canonicalRequest = listOf(
            "POST",
            "/",
            canonicalQuery,
            canonicalHeaders,
            SIGNED_HEADERS,
            payloadHash,
        ).joinToString("\n")

        // 火山的 Secret 是 base64，末尾 `==` 漏了会报 SignatureDoesNotMatch
        val pad = (4 - credentials.secretAccessKey.length % 4) % 4
        val secret = credentials.secretAccessKey.trim() + "=".repeat(pad)

        val scope = "$shortDate/$region/$SERVICE/request"
        val stringToSign = listOf(
            "HMAC-SHA256",
            xDate,
            scope,
            BillingSign.sha256Hex(canonicalRequest),
        ).joinToString("\n")

        val kDate = BillingSign.hmacSha256(secret.toByteArray(Charsets.UTF_8), shortDate)
        val kRegion = BillingSign.hmacSha256(kDate, region)
        val kService = BillingSign.hmacSha256(kRegion, SERVICE)
        val kSigning = BillingSign.hmacSha256(kService, "request")
        val signature = BillingSign.hmacSha256(kSigning, stringToSign).toHex()

        val authorization =
            "HMAC-SHA256 Credential=${credentials.accessKeyId.trim()}/$scope, " +
                "SignedHeaders=$SIGNED_HEADERS, Signature=$signature"

        val request = Request.Builder()
            .url("https://$HOST/?$canonicalQuery")
            .addHeader("Authorization", authorization)
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Date", xDate)
            .addHeader("X-Content-Sha256", payloadHash)
            // **必须用 ByteArray.toRequestBody**：String.toRequestBody 会给没有 charset
            // 的 MediaType 自动补上 "; charset=utf-8"，而我们签名时写的是
            // `content-type:application/json`。一字之差，服务端重算的签名必然不符，
            // 返回 401 SignatureDoesNotMatch——报错信息完全不会提示是这里的问题。
            .post(
                payload.toByteArray(Charsets.UTF_8)
                    .toRequestBody("application/json".toMediaType()),
            )
            .build()

        return try {
            http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val msg = runCatching {
                        JSONObject(raw).optJSONObject("ResponseMetadata")
                            ?.optJSONObject("Error")?.optString("Message")
                    }.getOrNull()
                    Log.w(TAG, "火山账单 HTTP ${response.code}: ${raw.take(200)}")
                    AppResult.Err(AppError.Network("火山账单 ${response.code}：${msg ?: ""}"))
                } else {
                    AppResult.Ok(JSONObject(raw))
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "火山账单请求失败", t)
            AppResult.Err(AppError.Network("火山账单请求失败：${t.message}", t))
        }
    }
}

/** 统一的 HTTP 执行（阿里云用 URL query 签名，body 为空） */
private fun execute(url: String): AppResult<JSONObject> = try {
    http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
        val raw = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val msg = runCatching { JSONObject(raw).optString("Message") }.getOrNull()
            AppResult.Err(AppError.Network("账单查询 ${response.code}：${msg ?: ""}"))
        } else {
            AppResult.Ok(JSONObject(raw))
        }
    }
} catch (t: Throwable) {
    AppResult.Err(AppError.Network("账单查询失败：${t.message}", t))
}
