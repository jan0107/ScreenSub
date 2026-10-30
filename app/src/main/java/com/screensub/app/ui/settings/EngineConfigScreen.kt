// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.screensub.app.ScreenSubApp
import com.screensub.app.ui.EngineKind
import com.screensub.core.common.TranslatorIds
import com.screensub.core.data.AppSettings
import com.screensub.core.mt.LlmPresets
import kotlinx.coroutines.launch

/**
 * 三级页：单个在线翻译引擎的详细配置。
 *
 * 为什么拆出来：设置页原来把 LLM / Azure / 阿里云 的十几个输入框全平铺在一起，
 * 又长又乱；而且几家都填了之后，根本看不出**到底在用哪一个**。
 * 现在设置页只放一行「名字 + 状态 + 开关」，点进来才看细节。
 *
 * 每页顶部都有总开关，和设置页那一行的开关是同一个值——两处随便哪边点都行。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EngineConfigScreen(
    engine: EngineKind,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as ScreenSubApp
    val settings by app.container.settings.collectAsState()
    val scope = rememberCoroutineScope()

    fun save(transform: (AppSettings) -> AppSettings) {
        scope.launch { app.container.settingsStore.update(transform) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(engine.title) },
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

            MasterSwitch(engine, settings, ::save)

            Spacer(Modifier.height(16.dp))

            when (engine) {
                EngineKind.LLM -> LlmConfigBody(settings, ::save)
                EngineKind.AZURE -> AzureConfigBody(settings, ::save)
                EngineKind.ALIYUN -> AliyunConfigBody(settings, ::save)
                EngineKind.VOLCENGINE -> VolcengineConfigBody(settings, ::save)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------- 总开关 ----------------

@Composable
private fun MasterSwitch(
    engine: EngineKind,
    settings: AppSettings,
    save: ((AppSettings) -> AppSettings) -> Unit,
) {
    val enabled = when (engine) {
        EngineKind.LLM -> settings.llmTranslationEnabled
        EngineKind.AZURE -> settings.azureTranslationEnabled
        EngineKind.ALIYUN -> settings.aliyunTranslationEnabled
        EngineKind.VOLCENGINE -> settings.volcengineTranslationEnabled
    }

    SectionCard("开关") {
        SwitchRow(
            title = "启用 ${engine.title}",
            subtitle = if (enabled) "已参与翻译，按设置页的顺序尝试" else "已关闭，即使填了配置也不会被调用",
            checked = enabled,
            onCheckedChange = { v ->
                save {
                    when (engine) {
                        EngineKind.LLM -> it.copy(llmTranslationEnabled = v)
                        EngineKind.AZURE -> it.copy(azureTranslationEnabled = v)
                        EngineKind.ALIYUN -> it.copy(aliyunTranslationEnabled = v)
                        EngineKind.VOLCENGINE -> it.copy(volcengineTranslationEnabled = v)
                    }
                }
            },
        )
    }
}

// ---------------- LLM ----------------

@Composable
private fun LlmConfigBody(
    settings: AppSettings,
    save: ((AppSettings) -> AppSettings) -> Unit,
) {
    val selected = LlmPresets.of(settings.llmProvider)

    SectionCard("模型") {
        Text(
            "选一个只是把地址和模型名快速填进去，下面的输入框随时可以改。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))

        LlmPresets.ALL.forEach { preset ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = preset.id == selected.id,
                        onClick = {
                            // 选预设 = 把地址和模型名写进输入框，用户之后可以随便改
                            save {
                                it.copy(
                                    llmProvider = preset.id,
                                    llmBaseUrl = preset.baseUrl,
                                    llmModel = preset.model,
                                )
                            }
                        },
                    )
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = preset.id == selected.id,
                    onClick = {
                        save {
                            it.copy(
                                llmProvider = preset.id,
                                llmBaseUrl = preset.baseUrl,
                                llmModel = preset.model,
                            )
                        }
                    },
                )
                Column(Modifier.weight(1f)) {
                    Text(preset.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (preset.id == LlmPresets.CUSTOM) {
                            preset.note
                        } else {
                            "${preset.model} · ${preset.note}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(16.dp))

    SectionCard("凭据") {
        // 每家都是独立站点，"Key 从哪来"是新用户第一道坎。
        // 链接跟着当前选中的服务商走，不把七家的地址全堆出来。
        if (selected.keyUrl.isNotBlank()) {
            DocLink("去 ${selected.label} 拿 API Key ›", selected.keyUrl)
        }
        ConfigField(
            label = "API Key",
            value = settings.llmApiKey,
            placeholder = "sk-...",
            onChange = { v -> save { it.copy(llmApiKey = v) } },
        )
        ConfigField(
            label = "Base URL",
            value = settings.llmBaseUrl,
            placeholder = "https://api.deepseek.com",
            onChange = { v -> save { it.copy(llmBaseUrl = v) } },
        )
        ConfigField(
            label = "模型名",
            value = settings.llmModel,
            placeholder = "deepseek-flash",
            onChange = { v -> save { it.copy(llmModel = v) } },
        )

        Spacer(Modifier.height(6.dp))
        HintText(
            "各家模型名改得很勤（DeepSeek 现在就是 deepseek-flash / deepseek-v4-pro），" +
                "预设过期时直接在上面改，不用等我发版。",
        )
    }

    Spacer(Modifier.height(12.dp))

    LlmBalanceSection()

    Spacer(Modifier.height(12.dp))

    QuotaUsageSection(TranslatorIds.LLM)
}

// ---------------- Azure ----------------

@Composable
private fun AzureConfigBody(
    settings: AppSettings,
    save: ((AppSettings) -> AppSettings) -> Unit,
) {
    SectionCard("凭据") {
        Text(
            "Azure 门户 → 翻译资源 → 密钥和终结点。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        ConfigField(
            label = "订阅密钥",
            value = settings.azureKey,
            placeholder = "留空则不启用",
            onChange = { v -> save { it.copy(azureKey = v) } },
        )
        ConfigField(
            label = "区域",
            value = settings.azureRegion,
            placeholder = "global / eastasia",
            onChange = { v -> save { it.copy(azureRegion = v) } },
        )
    }

    Spacer(Modifier.height(12.dp))

    QuotaUsageSection(TranslatorIds.AZURE)

    Spacer(Modifier.height(12.dp))

    SectionCard("说明") {
        HintText("免费层 F0：每月 200 万字符，够译约 400 集动画对白。")
        HintText("开通需要国际信用卡；国内直连偶尔不稳定。")
    }
}

// ---------------- 阿里云 ----------------

@Composable
private fun AliyunConfigBody(
    settings: AppSettings,
    save: ((AppSettings) -> AppSettings) -> Unit,
) {
    SectionCard("凭据") {
        // 阿里云这两步是分开的两篇文档，和火山的一个流程页不同
        Row(verticalAlignment = Alignment.CenterVertically) {
            DocLink("开通服务 ›", ALIYUN_ACTIVATE_DOC)
            Spacer(Modifier.width(16.dp))
            DocLink("创建 AccessKey ›", ALIYUN_AK_DOC)
        }
        Text(
            "阿里云控制台 → 访问控制 RAM → 用户 → 创建 AccessKey。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HintText("别忘了给这个 RAM 用户授予 AliyunMTFullAccess 权限，否则调用会被拒。")
        Spacer(Modifier.height(6.dp))
        ConfigField(
            label = "AccessKeyId",
            value = settings.aliyunAccessKeyId,
            placeholder = "LTAI...",
            onChange = { v -> save { it.copy(aliyunAccessKeyId = v) } },
        )
        ConfigField(
            label = "AccessKeySecret",
            value = settings.aliyunAccessKeySecret,
            placeholder = "只显示一次，创建时记得复制",
            onChange = { v -> save { it.copy(aliyunAccessKeySecret = v) } },
        )
    }

    Spacer(Modifier.height(12.dp))

    QuotaUsageSection(TranslatorIds.ALIYUN)

    Spacer(Modifier.height(12.dp))

    SectionCard("说明") {
        HintText("通用版机器翻译每月 100 万字符免费，一集约 5000 字符，够看 200 集。")
        HintText("超出后约 50 元/百万字符。需要先在控制台开通「机器翻译」，否则报 10010。")
        HintText("QPS 上限 50，字幕场景一秒最多一两句，撞不到。")
    }
}

// ---------------- 火山引擎 ----------------

@Composable
private fun VolcengineConfigBody(
    settings: AppSettings,
    save: ((AppSettings) -> AppSettings) -> Unit,
) {
    SectionCard("凭据") {
        // 注册 → 实名 → 开通 → 拿密钥，官方这个流程页一站到底，别在 App 里复述
        DocLink("首次接入？看官方 6 步开通流程 ›", VOLC_ACCESS_DOC)
        Text(
            "火山引擎控制台 → 机器翻译 → 开通服务 → IAM 访问密钥",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HintText("要的是 AccessKeyId + SecretAccessKey（AKLT 开头那对）。")
        HintText("别填 AppID/Token —— 那是「豆包翻译大模型」的凭据，按 token 计费且额度一次性。")
        // 这对密钥还兼着读账单用量，权限不够会静默回落本地计数。
        // 与其让用户猜"为什么用量页没有平台数据"，不如在填的地方就写清楚。
        HintText(
            "这对密钥还会用来读账单字符用量（自动算本月已用）。需要 IAM 策略 " +
                "BillingCenterBillReadOnlyAccess（费用中心账单只读）；没有该权限也能正常翻译，" +
                "只是用量页会回落到本地计数。账单明细无需任何开通。",
        )
        Spacer(Modifier.height(6.dp))
        ConfigField(
            label = "AccessKeyId",
            value = settings.volcengineAccessKeyId,
            placeholder = "AKLT...",
            onChange = { v -> save { it.copy(volcengineAccessKeyId = v) } },
        )
        ConfigField(
            label = "SecretAccessKey",
            value = settings.volcengineSecretAccessKey,
            placeholder = "只显示一次，创建时记得复制",
            onChange = { v -> save { it.copy(volcengineSecretAccessKey = v) } },
        )
    }

    Spacer(Modifier.height(12.dp))

    QuotaUsageSection(TranslatorIds.VOLCENGINE)

    Spacer(Modifier.height(12.dp))

    SectionCard("说明") {
        HintText("每月 200 万字符免费，按自然月刷新（每月 1 号重置），够看约 400 集。")
        HintText("1 个汉字 / 字母 / 数字 / 符号 / 空格都算 1 个字符，按字符不按 token。")
        HintText("超出后 49 元/百万字符，不会自动停服，建议在控制台设个可用额度预警。")
    }
}

// ---------------- 官方文档入口 ----------------

/**
 * 厂商的"开通 + 拿密钥"指引。
 *
 * 这几个链接是会过期的——路径改了、站点重构了，就会 404。所以都集中放在这里，
 * 而不是散落在各个 HintText 里。改的时候只改这一处。
 */
private const val VOLC_ACCESS_DOC =
    "https://docs.volcengine.com/docs/machine-translation/overview-of-api-access-process?lang=zh"

private const val ALIYUN_ACTIVATE_DOC =
    "https://help.aliyun.com/zh/machine-translation/getting-started/documentation"

private const val ALIYUN_AK_DOC =
    "https://help.aliyun.com/zh/ram/user-guide/create-an-accesskey-pair"
