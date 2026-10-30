// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.app.ui.settings

import android.content.Intent
import android.widget.Toast
import android.net.Uri
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.screensub.app.ScreenSubApp
import com.screensub.core.common.TranslatorIds
import com.screensub.core.data.EngineNames
import com.screensub.core.data.QuotaConfig
import com.screensub.core.data.QuotaRepository
import java.util.Locale

/** 用量比例对应的颜色：>=100% 红，>=90% 橙，其余主题色 */
@Composable
private fun quotaColor(percent: Float): Color = when {
    percent >= 1f -> MaterialTheme.colorScheme.error
    percent >= 0.9f -> Color(0xFFFB8C00)
    else -> MaterialTheme.colorScheme.primary
}

/** 细进度条。percent 可以 > 1（超限），画的时候截断到 1，颜色负责表达"超了" */
@Composable
internal fun QuotaBar(
    percent: Float,
    height: Dp = 6.dp,
    modifier: Modifier = Modifier,
) {
    val fraction = percent.coerceIn(0f, 1f)
    val shape = RoundedCornerShape(height / 2)
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
    ) {
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(shape)
                    .background(quotaColor(percent)),
            )
        }
    }
}

/** 12.4 万 / 8,300 / 0 —— 超过 1 万就用"万"，否则原样 */
internal fun formatChars(chars: Long): String =
    if (chars >= 10_000) String.format(Locale.US, "%.1f 万", chars / 10_000.0)
    else chars.toString()

/** 万 → 字符 */
private fun wanToChars(wan: Float): Long = (wan * 10_000).toLong()

/**
 * 平台数据同步时刻。
 *
 * 只给"月-日 时:分"，不显示年份和秒——用量面板上这一行是辅助信息，
 * 太长会把"剩余多少"挤掉。
 */
private fun clockLabel(at: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm", Locale.US).format(java.util.Date(at))

/**
 * 付费时到底是**哪家平台**在收钱。
 *
 * 必须点名厂商和单价，否则用户会以为是我们 App 收费——这是最容易引起投诉的误会。
 */
private fun vendorBillingName(engineId: String): String = when (engineId) {
    TranslatorIds.VOLCENGINE -> "火山引擎（49 元/百万字符）"
    TranslatorIds.ALIYUN -> "阿里云（约 50 元/百万字符）"
    TranslatorIds.AZURE -> "Azure（按其官方费率）"
    else -> "翻译服务商"
}

/**
 * 最近 7 日柱状图。
 *
 * 不引图表库：7 根柱子而已，引一个 Vico/MPAndroidChart 要多 1~2 MB，
 * 而且这两个库都要 Month/Day 的专属 API。这里用 weight + 高度百分比实现，
 * 缺数据的日期补零（调库那侧保证一定给 7 条），柱子数恒定才不会左右跳。
 */
@Composable
internal fun WeekBarChart(
    days: List<QuotaRepository.DayUsage>,
    modifier: Modifier = Modifier,
    /** true = 按金额画（LLM），false = 按字符画 */
    useYuan: Boolean = false,
) {
    if (days.isEmpty()) return
    fun value(d: QuotaRepository.DayUsage): Long =
        if (useYuan) (d.yuan * 10_000).toLong() else d.chars

    // 一条数据都没有时不要画一排空柱子——那片空白会让人以为界面坏了
    if (days.all { value(it) <= 0L }) {
        Text(
            "最近 7 日还没有用量",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = modifier.padding(vertical = 12.dp),
        )
        return
    }

    val max = days.maxOf { value(it) }.coerceAtLeast(1L)
    val accent = MaterialTheme.colorScheme.primary

    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            days.forEachIndexed { index, day ->
                val v = value(day)
                val ratio = (v.toFloat() / max).coerceIn(0f, 1f)
                // 有数据但极少时给 2dp 保底，否则柱子看不见、像没数据
                val barHeight = when {
                    v <= 0L -> 0.dp
                    ratio * 34f < 2f -> 2.dp
                    else -> (ratio * 34f).dp
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height(barHeight)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            if (index == days.lastIndex) accent else accent.copy(alpha = 0.32f),
                        ),
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            days.forEachIndexed { index, day ->
                Text(
                    // 只显示"日"，7 列放不下完整日期
                    day.dayKey.takeLast(2),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (index == days.lastIndex) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 紧凑数字输入框（58×36dp）。
 *
 * 不用 OutlinedTextField：它有 56dp 硬性最小高度 + 16dp 内留白，
 * 一个 "200" 会比左边的标签还显眼。单位放在框**外面**，
 * 塞进去会被挤成两行。
 */
@Composable
internal fun CompactNumberField(
    label: String,
    value: Float,
    suffix: String,
    hint: String? = null,
    onCommit: (Float) -> Unit,
) {
    val display = remember(value) {
        if (value == value.toInt().toFloat()) {
            value.toInt().toString()
        } else {
            String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
        }
    }
    var text by remember(value) { mutableStateOf(display) }

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .width(64.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.30f),
                        shape = RoundedCornerShape(9.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { input ->
                        val cleaned = input.filter { it.isDigit() || it == '.' }
                        text = cleaned
                        cleaned.toFloatOrNull()?.let(onCommit)
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done,
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                )
            }
            Spacer(Modifier.width(5.dp))
            Text(
                suffix,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.widthIn(min = 56.dp),
            )
        }
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 三级配置页里的「本月用量」区块。
 *
 * 直接取 container 而不是层层传回调：本项目没有 DI 框架，
 * 每个页面本来都是 `context.applicationContext as ScreenSubApp` 拿容器，
 * 保持一致比自己发明一套回调参数更好维护。
 */
@Composable
internal fun QuotaUsageSection(
    engineId: String,
    /** 用量页里要多引擎并列，卡片标题必须写引擎名，否则两个「本月用量」分不清谁是谁 */
    showEngineName: Boolean = false,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as ScreenSubApp).container
    val settings by container.settings.collectAsState()
    val snapshot by container.quotaRepository.state.collectAsState()

    val config = remember(settings.quotaConfigJson) { QuotaConfig.fromJson(settings.quotaConfigJson) }
    val usage = snapshot.engine(engineId)
    val quota = config.engine(engineId)

    var pendingProbeEnable by remember { mutableStateOf(false) }
    var showOverAlertDialog by remember { mutableStateOf(false) }
    var showPaidDialog by remember { mutableStateOf(false) }

    // LLM 按 token/金额计费，字符口径对它完全没意义：
    // 显示"0 字符 / 不限"和一张全空的 7 日图，比不显示还糟。
    val isTokenEngine = engineId == TranslatorIds.LLM

    SectionCard(
        if (showEngineName) EngineNames.of(engineId) else "本月用量",
    ) {
        if (usage == null || !usage.configured) {
            Text(
                "填写 API 密钥后才能统计用量。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }

        // 平台账单查到欠费：这是本地计数看不见的部分（别的程序、历史遗留）导致的，
        // 已经强制停用，必须显式告诉用户，否则他会以为"明明还有额度怎么不翻"
        if (usage.platformArrears) {
            Text(
                "平台账单显示欠费" +
                    (usage.platformArrearsYuan?.let { String.format(Locale.US, " ¥%.2f", it) } ?: "") +
                    "，已自动停用该引擎。请在控制台核对并充值后重启字幕。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(10.dp))
        }

        if (isTokenEngine) {
            // 余额见底：直接点破，用户才知道该充值（而不是继续撞网络失败）
            val llmBalance = usage.platformBalanceYuan
            if (llmBalance != null && llmBalance <= 0.0) {
                Text(
                    "账户余额已用尽，已自动停用该引擎。请到服务商控制台充值后重开。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(10.dp))
            }
            Text(
                "本月已用",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    String.format(Locale.US, "¥ %.2f", usage.monthYuan),
                    style = MaterialTheme.typography.headlineSmall,
                    color = quotaColor(usage.percent),
                )
                Text(
                    if (usage.budgetYuan > 0) {
                        String.format(Locale.US, " / ¥ %.2f", usage.budgetYuan)
                    } else {
                        " / 未设预算"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (usage.budgetYuan > 0) {
                        String.format(Locale.US, "%.0f%%", usage.percent * 100)
                    } else {
                        "不限"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = quotaColor(usage.percent),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "输入 ${usage.tokensIn} token · 输出 ${usage.tokensOut} token（按你填的单价估算）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            QuotaBar(percent = if (usage.budgetYuan > 0) usage.percent else 0f, height = 8.dp)
            Spacer(Modifier.height(12.dp))

            Text("最近 7 日", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            WeekBarChart(usage.last7, useYuan = true)

            Spacer(Modifier.height(14.dp))
            HintText(
                if (showEngineName) {
                    "预算与单价在「设置 → 翻译 → LLM 翻译」里设。"
                } else {
                    "预算和单价在下面的「余额与预算」里设。"
                },
            )
            return@SectionCard
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (usage.officialChars != null) "本月已用（平台）" else "本月已用",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        formatChars(usage.monthChars),
                        style = MaterialTheme.typography.headlineSmall,
                        color = quotaColor(usage.percent),
                    )
                    Text(
                        if (quota.hasLimit) " / ${formatChars(quota.monthlyQuotaChars)}" else " / 不限",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
            }
            Text(
                if (quota.hasLimit) {
                    String.format(Locale.US, "%.0f%%", usage.percent * 100)
                } else {
                    "未设限"
                },
                style = MaterialTheme.typography.labelLarge,
                color = quotaColor(usage.percent),
            )
        }

        Spacer(Modifier.height(6.dp))
        QuotaBar(percent = if (quota.hasLimit) usage.percent else 0f, height = 8.dp)

        // 到告警线就停：最后一段留作安全垫，必须用户显式点「允许」才放开。
        // 这样默认状态下永远不会因为本地数字不准而多花一分钱。
        val quotaUsedUp = quota.hasLimit && usage.monthChars >= quota.monthlyQuotaChars
        if (quotaUsedUp && !usage.paidApproved) {
            // 额度真的用完了：这时候用户要的是"我知道要花钱，但我还要用"
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "本月额度已用完，已停止使用该引擎",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { showPaidDialog = true },
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text("继续（自费）", style = MaterialTheme.typography.labelLarge)
                }
            }
        } else if (quota.hasLimit && !usage.overAlertApproved && !usage.paidApproved &&
            usage.percent >= config.alertPercent / 100f
        ) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "已达 ${config.alertPercent}%，已停止使用该引擎",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { showOverAlertDialog = true },
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text("继续用剩余", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        // 已付费授权时给一条常驻提示，免得用户忘了自己开过、月底看到账单发懵
        if (quota.hasLimit && usage.paidApproved) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "已允许超出额度继续使用，" + vendorBillingName(engineId) + "会计费",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { container.quotaRepository.revokePaid(engineId) },
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text("恢复保护", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        // 平台口径：两家都能给账号级真实字符数（含其他设备、其他程序）——
        // 阿里云 `GetTranslateReport`，火山 `ListBillDetail` 的 Count×Unit。
        // 都是 T+1；火山更晚，账单行要到次日 15 点后才落库。
        Spacer(Modifier.height(8.dp))
        val official = usage.officialChars
        if (official != null) {
            // 剩余按"本地/官方取大"算，和停用判定同一个口径。
            // 只用官方值会把今天已经用掉的部分又还回去，显示得比实际宽裕。
            val remain = (quota.monthlyQuotaChars - usage.monthChars).coerceAtLeast(0L)
            // 账单里最后一天有数据的日期 = 平台数据的截止日。
            // 火山的截止日经常是「前天」，直接写"昨日"会让用户以为丢了一天。
            val cutoff = usage.officialDaily.lastOrNull()?.day
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildString {
                        append("本月 ").append(formatChars(official))
                        usage.officialYesterday?.let { append(" · 昨日 ").append(formatChars(it)) }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                if (quota.hasLimit) {
                    Text(
                        "剩余 ${formatChars(remain)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (remain < quota.monthlyQuotaChars / 10) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Text(
                buildString {
                    append("账单口径，截止 ").append(cutoff ?: "—")
                    append("（厂商 T+1，今天的量明天才出")
                    if (engineId == TranslatorIds.VOLCENGINE) append("；火山还要等次日下午落库")
                    append("）")
                    usage.officialAt?.let { append(" · ").append(clockLabel(it)).append(" 同步") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // 说清楚为什么这里不是平台数字，否则用户会以为是我们算错了。
            // 有明确错误就把原因原样摆出来——只说"暂时没拿到"用户无法判断
            // 是该去改密钥，还是干等一天。
            val why = usage.officialError
            HintText(
                if (why != null) {
                    "没拿到平台用量：$why。" +
                        "以下为本地逐次记账，用的是厂商每次返回的计费字符数。"
                } else {
                    "平台用量还没出账（厂商 T+1，火山要到次日下午）。" +
                        "以下为本地逐次记账，用的是厂商每次返回的计费字符数；" +
                        "也可以把控制台的「本月已用」填到下方「基准校准」。"
                },
            )
            // 平台只把用量放在控制台网页里（需要登录态，OpenAPI 不提供）。
            // 与其让用户自己找，不如直接给一个能点开的入口。
            val consoleUrl = when (engineId) {
                TranslatorIds.VOLCENGINE -> "https://console.volcengine.com/translate/usage"
                TranslatorIds.ALIYUN -> "https://mt.console.aliyun.com/overview"
                else -> null
            }
            if (consoleUrl != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(consoleUrl))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text("打开控制台 ›", style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.width(18.dp))
                    // 复制 + 点一下，比手输「0.2619 万」省事得多。
                    // 黏贴的内容不管是 "2,619" 还是整行「本月使用量（字符）2,619」都能解析。
                    TextButton(
                        onClick = {
                            val raw = runCatching {
                                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                                cm?.primaryClip?.takeIf { it.itemCount > 0 }
                                    ?.getItemAt(0)?.coerceToText(context)?.toString()
                            }.getOrNull()
                            val chars = raw
                                ?.filter { it.isDigit() }
                                ?.toLongOrNull()
                            if (chars != null && chars > 0) {
                                container.quotaRepository
                                    .applyOfficialUsage(engineId, chars, yesterdayChars = null)
                                Toast.makeText(
                                    context,
                                    "已按控制台用量 $chars 字符校准",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                Toast.makeText(
                                    context,
                                    "剪贴板里没有数字。请在控制台用量页复制「本月使用量」",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text("读取剪贴板用量", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Text("最近 7 日", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        WeekBarChart(usage.last7)

        Spacer(Modifier.height(14.dp))

        CompactNumberField(
            label = "额度",
            value = quota.monthlyQuotaChars / 10_000f,
            suffix = "万字符/月",
            hint = "填 0 = 不设限（只提示不停用，可能产生费用）",
            onCommit = { wan -> container.quotaRepository.setQuotaChars(engineId, wanToChars(wan)) },
        )

        CompactNumberField(
            label = "基准校准",
            value = quota.baselineChars / 10_000f,
            suffix = "万字符",
            hint = "本地只从本次安装开始计数。把控制台的「本月已用」抄进来一次，" +
                "之后本地在这个数上继续累加；换月自动失效",
            onCommit = { wan -> container.quotaRepository.calibrate(engineId, wanToChars(wan)) },
        )

        Spacer(Modifier.height(8.dp))
        SwitchRow(
            title = "接近耗尽仍探测",
            subtitle = if (quota.hasLimit) {
                "这会让切换更加平滑，但会消耗两个字符额度"
            } else {
                "未设置额度，探测无意义"
            },
            checked = quota.allowProbeNearLimit,
            enabled = quota.hasLimit,
            onCheckedChange = { want ->
                // 打开方向要二次确认（会真的花掉额度），关闭方向直接生效
                if (want) pendingProbeEnable = true
                else container.quotaRepository.setAllowProbeNearLimit(engineId, false)
            },
        )
    }

    // 额度用完后的付费授权：必须说清"钱是厂商扣的、我们不收"
    if (showPaidDialog) {
        AlertDialog(
            onDismissRequest = { showPaidDialog = false },
            title = { Text("额度已用完，继续使用会开始付费") },
            text = {
                Text(
                    "本月 ${formatChars(quota.monthlyQuotaChars)} 免费额度已用完。\n\n" +
                        "继续使用将由「${vendorBillingName(engineId)}」计费，" +
                        "费用从你本人的云账号扣除，与 ScreenSub 无关——" +
                        "本 App 不收费，也不经手你的账单。\n\n" +
                        "该授权仅本月有效，下月自动恢复额度保护。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        container.quotaRepository.approvePaid(engineId)
                        showPaidDialog = false
                    },
                ) { Text("我接受，继续用") }
            },
            dismissButton = {
                TextButton(onClick = { showPaidDialog = false }) { Text("取消") }
            },
        )
    }

    // 放开告警线以上的余量：必须用户点「允许」，本月有效，下月重新问
    if (showOverAlertDialog) {
        val remain = (quota.monthlyQuotaChars - (usage?.monthChars ?: 0L)).coerceAtLeast(0L)
        AlertDialog(
            onDismissRequest = { showOverAlertDialog = false },
            title = { Text("继续使用剩余额度？") },
            text = {
                Text(
                    "本月额度 ${formatChars(quota.monthlyQuotaChars)}，" +
                        "已用 ${formatChars((usage?.monthChars ?: 0L))}，" +
                        "达到 ${config.alertPercent}% 告警线后已停止。\n\n" +
                        "继续使用会花掉剩下的 ${formatChars(remain)}。额度全部用完后仍会停止，不会超额。" +
                        "该授权本月有效，下月重新询问。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        container.quotaRepository.approveOverAlert(engineId)
                        showOverAlertDialog = false
                    },
                ) { Text("允许") }
            },
            dismissButton = {
                TextButton(onClick = { showOverAlertDialog = false }) { Text("取消") }
            },
        )
    }

    if (pendingProbeEnable) {
        AlertDialog(
            onDismissRequest = { pendingProbeEnable = false },
            title = { Text("开启「接近耗尽仍探测」？") },
            text = {
                Text(
                    "开启后，当月度额度接近耗尽时，仍会发送一次探测请求（2 个字符），" +
                        "以便提前确认引擎还能不能用。\n\n" +
                        "这可能让你已用的字符数略超免费额度，超出部分按平台定价计费。",
                )
            },
            // 默认焦点放在「拒绝」上，避免手滑直接花钱
            dismissButton = {
                TextButton(onClick = { pendingProbeEnable = false }) { Text("拒绝") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingProbeEnable = false
                        container.quotaRepository.setAllowProbeNearLimit(engineId, true)
                    },
                ) {
                    Text("允许")
                }
            },
        )
    }
}

/**
 * 首页的用量概览卡。
 *
 * 最多两行：这里不是仪表盘，"谁在干活" + "谁快用完"这两条信息最要紧，
 * 其余的进用量页看。传进来的列表已按 5.0.2.1 的规则排好序。
 */
@Composable
internal fun QuotaOverviewCard(
    usages: List<QuotaRepository.EngineUsage>,
    onOpen: () -> Unit,
) {
    if (usages.isEmpty()) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            usages.take(2).forEach { usage ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        EngineNames.of(usage.engineId),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.widthIn(min = 88.dp),
                    )
                    Text(
                        // LLM 按 token/金额计费，走字符口径会显示成 "0"，看着像没工作
                        if (usage.engineId == TranslatorIds.LLM) {
                            String.format(Locale.US, "已用 ¥ %.2f", usage.monthYuan) +
                                if (usage.budgetYuan > 0) {
                                    String.format(Locale.US, " / ¥ %.2f", usage.budgetYuan)
                                } else {
                                    ""
                                }
                        } else if (usage.quotaChars > 0) {
                            "已用 ${formatChars(usage.monthChars)} / ${formatChars(usage.quotaChars)}"
                        } else {
                            "已用 ${formatChars(usage.monthChars)}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (usage.blocked) {
                        Text(
                            "已停用",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (usage.isActive) {
                        Text(
                            "● 使用中",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else if (usage.percent >= 0.9f) {
                        Text(
                            "⚠ ${String.format(Locale.US, "%.0f%%", usage.percent * 100)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFFB8C00),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                QuotaBar(
                    percent = if (usage.quotaChars > 0) usage.percent else 0f,
                    height = 3.dp,
                )
                Spacer(Modifier.height(8.dp))
            }

            TextButton(
                onClick = onOpen,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                modifier = Modifier.height(24.dp),
            ) {
                Text("查看全部用量 ›", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
