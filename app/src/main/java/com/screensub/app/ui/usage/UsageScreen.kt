// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.app.ui.usage

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.screensub.app.ScreenSubApp
import com.screensub.app.ui.settings.HintText
import com.screensub.app.ui.settings.SectionCard
import com.screensub.app.ui.settings.QuotaUsageSection
import com.screensub.app.ui.settings.formatChars

/**
 * 用量二级页。
 *
 * 与三级配置页的区别：这里横向对比所有引擎（谁在用、谁快用完），
 * 三级页聚焦单家（改额度、做校准）。所以这里复用同一套
 * [QuotaUsageSection]，但只读不改结构，避免两处实现的数字口径不一致。
 *
 * 排序直接用仓库给的 [QuotaRepository.sortedForDisplay]：
 * 跨过告警线的排最前 → 正在使用的 → 最近用过的 → 链顺序。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as ScreenSubApp).container
    val snapshot by container.quotaRepository.state.collectAsState()
    val usages = remember(snapshot) { container.quotaRepository.sortedForDisplay() }

    // 进页面就拉一次平台数据。这是用户最想看到"准确数字"的时刻，
    // 不能让他等 30 分钟的定时轮询；拉回来会 push 新快照，界面自动刷新。
    LaunchedEffect(Unit) { container.refreshCloudUsage() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("翻译用量") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            if (usages.isEmpty()) {
                SectionCard("还没有可统计的引擎") {
                    HintText("用量只统计填了 API 密钥的付费引擎。")
                    HintText("去「设置 → 翻译」里给阿里云或火山填上密钥，这里就会出现它们。")
                }
                Spacer(Modifier.height(24.dp))
                return@Column
            }

            Text(
                "只列出已填写密钥的引擎；没填的不监测、也不显示。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            usages.forEach { usage ->
                QuotaUsageSection(usage.engineId, showEngineName = true)
                Spacer(Modifier.height(20.dp))
            }

            SectionCard("说明") {
                HintText(
                    "字符数由本机按实际发送的文本累计，与厂商账单通常相差 0.5% 以内。" +
                        "用户在别处用掉的额度看不到，可在各家区块的「基准校准」里把控制台的已用量抄进来。",
                )
                HintText("当月累计 = 本地计数 + 校准基准；基准只对当前月有效，换月自动失效。")
                HintText(
                    "当前总量 ${formatChars(snapshot.engines.values.sumOf { it.monthChars })} 字符" +
                        "，统计于 ${snapshot.monthKey.ifBlank { "本月" }}。",
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
