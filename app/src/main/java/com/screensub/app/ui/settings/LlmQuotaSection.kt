// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.screensub.app.ScreenSubApp
import com.screensub.core.common.AppResult
import com.screensub.core.common.TranslatorIds
import com.screensub.core.data.QuotaConfig
import com.screensub.core.mt.LlmBalance
import com.screensub.core.mt.LlmBalanceProbes
import com.screensub.core.mt.LlmPresets
import com.screensub.core.mt.LlmTranslator
import kotlinx.coroutines.launch
import java.util.Locale

/** 余额查询的节流：同一进程内 10 分钟内不重复打接口 */
private var lastBalanceQueryAt = 0L

/**
 * LLM 的余额与预算。
 *
 * 两类服务商区别对待，这点必须诚实：
 *  - 有余额接口（DeepSeek / Kimi / 硅基流动）→ 真实余额 + 「刷新」
 *  - 没有接口（通义 / GLM / OpenAI）→ 明确写"无余额查询接口"，只按 token 估算
 *
 * 假装能查、显示一个编出来的数字，比不显示更糟——用户会拿它当账户余额。
 */
@Composable
internal fun LlmBalanceSection() {
    val context = LocalContext.current
    val container = (context.applicationContext as ScreenSubApp).container
    val settings by container.settings.collectAsState()
    val snapshot by container.quotaRepository.state.collectAsState()
    val scope = rememberCoroutineScope()

    val config = remember(settings.quotaConfigJson) { QuotaConfig.fromJson(settings.quotaConfigJson) }
    val usage = snapshot.engine(TranslatorIds.LLM)
    val preset = LlmPresets.of(settings.llmProvider)
    val canQuery = LlmBalanceProbes.supports(settings.llmProvider)

    var balance by remember { mutableStateOf<LlmBalance?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun refresh(force: Boolean) {
        if (!canQuery || loading) return
        val now = System.currentTimeMillis()
        if (!force && now - lastBalanceQueryAt < 10 * 60 * 1000L) return
        lastBalanceQueryAt = now

        loading = true
        error = null
        scope.launch {
            val cfg = LlmTranslator.LlmConfig(
                baseUrl = settings.llmBaseUrl,
                apiKey = settings.llmApiKey,
                model = settings.llmModel,
            )
            when (val outcome = LlmBalanceProbes.query(settings.llmProvider, cfg)) {
                is AppResult.Ok -> balance = outcome.value
                is AppResult.Err -> error = outcome.error.message
            }
            loading = false
        }
    }

    // 进页面自动查一次（节流），换 Key / 换服务商时强制刷新
    LaunchedEffect(settings.llmProvider, settings.llmApiKey, settings.llmBaseUrl) {
        refresh(force = true)
    }

    SectionCard("余额与预算") {
        if (!canQuery) {
            HintText(
                "该服务商（通义千问 / 智谱 GLM / OpenAI 等）无公开余额查询接口，" +
                    "只能按 token 估算花费。想看余额请去对应控制台。",
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("账户余额", style = MaterialTheme.typography.labelMedium)
                    Text(
                        when {
                            loading -> "查询中…"
                            balance != null -> String.format(
                                Locale.US,
                                "¥ %.2f",
                                balance!!.yuan,
                            )

                            error != null -> "查询失败"
                            else -> "—"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (error != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    if (balance != null) {
                        Text(
                            "${preset.label} · 实时",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (error != null) {
                        Text(
                            error!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                TextButton(onClick = { refresh(force = true) }, enabled = !loading) {
                    Text("刷新")
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // 本月花费 / 预算
        val spent = usage?.monthYuan ?: 0.0
        val budget = config.llmBudgetYuan
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                String.format(Locale.US, "本月花费 ¥ %.2f", spent),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (budget > 0) String.format(Locale.US, "预算 ¥ %.2f", budget) else "未设预算",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        QuotaBar(
            percent = if (budget > 0) (spent / budget).toFloat() else 0f,
            height = 8.dp,
        )
        if (config.llmInPricePerMillion <= 0.0) {
            Spacer(Modifier.height(6.dp))
            HintText(
                "填了单价才能按金额停用。现在只统计 token，不会因为花费触顶而停用。",
            )
        }

        Spacer(Modifier.height(8.dp))

        CompactNumberField(
            label = "本月预算",
            value = budget.toFloat(),
            suffix = "元",
            hint = "填 0 = 不按金额停用",
            onCommit = { container.quotaRepository.setLlmBudget(it.toDouble()) },
        )
        CompactNumberField(
            label = "输入单价",
            value = config.llmInPricePerMillion.toFloat(),
            suffix = "元/百万",
            hint = "按你自己账户的计费标准填，各家各模型都不一样",
            onCommit = {
                container.quotaRepository.setLlmPrices(
                    inPerMillion = it.toDouble(),
                    outPerMillion = config.llmOutPricePerMillion,
                )
            },
        )
        CompactNumberField(
            label = "输出单价",
            value = config.llmOutPricePerMillion.toFloat(),
            suffix = "元/百万",
            hint = null,
            onCommit = {
                container.quotaRepository.setLlmPrices(
                    inPerMillion = config.llmInPricePerMillion,
                    outPerMillion = it.toDouble(),
                )
            },
        )
    }
}
