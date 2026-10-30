// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.app.ui.settings

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.screensub.app.ScreenSubApp
import com.screensub.app.ui.EngineKind
import com.screensub.app.util.PermissionHelper
import com.screensub.core.asr.ModelCatalog
import com.screensub.core.asr.SourceLanguage
import com.screensub.core.common.LanguageNames
import com.screensub.core.common.AppResult
import com.screensub.core.common.TranslatorIds
import com.screensub.core.data.AppSettings
import com.screensub.core.data.ModelDownloader
import com.screensub.core.data.QuotaConfig
import com.screensub.core.data.withSourceLanguage
import com.screensub.core.mt.LlmPresets
import com.screensub.core.mt.TargetLanguage
import com.screensub.core.mt.TranslatorFactory
import com.screensub.core.mt.TranslationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenEngineConfig: (EngineKind) -> Unit,
    onOpenLogs: () -> Unit = {},
    onOpenUsage: () -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.applicationContext as ScreenSubApp
    val settings by app.container.settings.collectAsState()
    val scope = rememberCoroutineScope()

    // 额度快照：用量行和两个全局开关都靠它
    val quotaSnapshot by app.container.quotaRepository.state.collectAsState()
    val quotaConfig = remember(settings.quotaConfigJson) {
        QuotaConfig.fromJson(settings.quotaConfigJson)
    }

    /**
     * 引擎行的用量小字。没配 Key 的引擎返回 null —— 面板里就不出现，
     * 免得满屏"0 / 100 万"的空行。
     */
    fun usageLineOf(engineId: String): String? {
        val usage = quotaSnapshot.engine(engineId) ?: return null
        if (!usage.configured) return null
        return if (usage.quotaChars > 0) {
            "已用 ${formatChars(usage.monthChars)} / ${formatChars(usage.quotaChars)}"
        } else {
            "已用 ${formatChars(usage.monthChars)}（未设限）"
        }
    }

    fun usagePercentOf(engineId: String): Float? {
        val usage = quotaSnapshot.engine(engineId) ?: return null
        if (!usage.configured) return null
        return if (usage.quotaChars > 0) usage.percent else 0f
    }

    val downloader = remember(settings.useHfMirror) { app.container.modelDownloader() }
    var downloadedIds by remember { mutableStateOf(setOf<String>()) }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<ModelDownloader.DownloadProgress?>(null) }

    /** 等待确认删除的模型（删除会真的删文件，必须二次确认） */
    var pendingDelete by remember { mutableStateOf<com.screensub.core.asr.AsrModelSpec?>(null) }

    LaunchedEffect(settings.useHfMirror) {
        downloadedIds = ModelCatalog.ALL.filter { downloader.isModelReady(it) }.map { it.id }.toSet()
    }

    var languageExpanded by remember { mutableStateOf(false) }
    // 翻译自测
    var testRunning by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testWarning by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---------- 识别 ----------
            SectionCard("识别") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("源语言", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "一种语言一个专用模型，切换语言会自动换成对应模型",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = { languageExpanded = true }) {
                        Text(SourceLanguage.fromCode(settings.sourceLanguage).label)
                    }
                    DropdownMenu(
                        expanded = languageExpanded,
                        onDismissRequest = { languageExpanded = false },
                    ) {
                        SourceLanguage.entries.forEach { lang ->
                            DropdownMenuItem(
                                text = { Text(lang.label) },
                                onClick = {
                                    languageExpanded = false
                                    scope.launch {
                                        app.container.settingsStore.update { current ->
                                            // 语言决定模型，映射写在 ModelCatalog.defaultFor 里，
                                            // 首页和这里共用同一份，避免两处行为漂移
                                            current.withSourceLanguage(lang)
                                        }
                                    }
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                Text("模型", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))

                ModelCatalog.ALL.forEach { spec ->
                    val isDownloaded = downloadedIds.contains(spec.id)
                    val isDownloading = downloadingId == spec.id

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        spec.displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        "${spec.approxSizeMb} MB · ${
                                            spec.languages.joinToString("/") { it.label }
                                        }",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (isDownloaded) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "已下载 · ${downloader.modelSizeBytes(spec) / 1024 / 1024} MB",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.secondary,
                                        )
                                        TextButton(
                                            onClick = { pendingDelete = spec },
                                            enabled = downloadingId == null,
                                        ) {
                                            Text(
                                                "删除",
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                } else {
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                downloadingId = spec.id
                                                progress = null
                                                downloader.downloadVad { progress = it }
                                                downloader.download(spec) { progress = it }
                                                downloadingId = null
                                                progress = null
                                                downloadedIds = ModelCatalog.ALL
                                                    .filter { downloader.isModelReady(it) }
                                                    .map { it.id }
                                                    .toSet()
                                            }
                                        },
                                        enabled = downloadingId == null,
                                    ) {
                                        Text(if (isDownloading) "下载中" else "下载")
                                    }
                                }
                            }

                            Text(
                                spec.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            if (isDownloading && progress != null) {
                                Spacer(Modifier.height(8.dp))
                                val p = progress!!
                                LinearProgressIndicator(
                                    progress = { p.fraction },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    "${p.fileName}  ${
                                        p.downloadedBytes / 1024 / 1024
                                    } / ${p.totalBytes / 1024 / 1024} MB",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            pendingDelete?.let { spec ->
                val inUse = settings.modelId == spec.id
                AlertDialog(
                    onDismissRequest = { pendingDelete = null },
                    title = { Text("删除模型？") },
                    text = {
                        Text(
                            "将删除「${spec.displayName}」约 " +
                                "${downloader.modelSizeBytes(spec) / 1024 / 1024} MB，" +
                                "之后需要重新下载才能使用。" +
                                if (inUse) {
                                    "\n\n注意：当前语言正在用这个模型，删除后开字幕会提示模型未下载，请先换语言或重新下载。"
                                } else {
                                    ""
                                },
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                downloadingId = null
                                runCatching { downloader.deleteModel(spec) }
                                downloadedIds = ModelCatalog.ALL
                                    .filter { downloader.isModelReady(it) }
                                    .map { it.id }
                                    .toSet()
                                pendingDelete = null
                            },
                        ) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingDelete = null }) { Text("取消") }
                    },
                )
            }

            // ---------- 翻译 ----------
            SectionCard("翻译") {
                Text(
                    "从上到下依次尝试，前面的成功就不会再用后面的。左侧序号就是优先级。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))

                EngineRow(
                    order = 1,
                    name = "LLM 翻译",
                    status = llmStatus(settings),
                    enabled = settings.llmTranslationEnabled,
                    highlighted = settings.llmTranslationEnabled && llmConfigured(settings),
                    onToggle = { v ->
                        scope.launch {
                            app.container.settingsStore.update { it.copy(llmTranslationEnabled = v) }
                        }
                    },
                    onOpen = { onOpenEngineConfig(EngineKind.LLM) },
                    usageLine = usageLineOf(TranslatorIds.LLM),
                    usageProgress = usagePercentOf(TranslatorIds.LLM),
                )
                RowDivider()


                RowDivider()

                EngineRow(
                    order = 2,
                    name = "火山云机器翻译",
                    status = volcengineStatus(settings),
                    enabled = settings.volcengineTranslationEnabled,
                    highlighted = settings.volcengineTranslationEnabled &&
                        settings.volcengineAccessKeyId.isNotBlank(),
                    onToggle = { v ->
                        scope.launch {
                            app.container.settingsStore.update {
                                it.copy(volcengineTranslationEnabled = v)
                            }
                        }
                    },
                    onOpen = { onOpenEngineConfig(EngineKind.VOLCENGINE) },
                    usageLine = usageLineOf(TranslatorIds.VOLCENGINE),
                    usageProgress = usagePercentOf(TranslatorIds.VOLCENGINE),
                )
                RowDivider()

                EngineRow(
                    order = 3,
                    name = "阿里云机器翻译",
                    status = if (settings.aliyunAccessKeyId.isBlank()) {
                        "未配置 · 点此填写 AccessKey（每月 100 万字符免费）"
                    } else if (settings.aliyunTranslationEnabled) {
                        "已启用 · 每月 100 万字符免费"
                    } else {
                        "已配置但已关闭"
                    },
                    enabled = settings.aliyunTranslationEnabled,
                    highlighted = settings.aliyunTranslationEnabled &&
                        settings.aliyunAccessKeyId.isNotBlank(),
                    onToggle = { v ->
                        scope.launch {
                            app.container.settingsStore.update { it.copy(aliyunTranslationEnabled = v) }
                        }
                    },
                    onOpen = { onOpenEngineConfig(EngineKind.ALIYUN) },
                    usageLine = usageLineOf(TranslatorIds.ALIYUN),
                    usageProgress = usagePercentOf(TranslatorIds.ALIYUN),
                )
                RowDivider()

                EngineRow(
                    order = 4,
                    name = "微软 Azure 翻译",
                    status = if (settings.azureKey.isBlank()) {
                        "未配置 · 点此填写订阅密钥（每月 200 万字符免费）"
                    } else if (settings.azureTranslationEnabled) {
                        "已启用 · ${settings.azureRegion}"
                    } else {
                        "已配置但已关闭"
                    },
                    enabled = settings.azureTranslationEnabled,
                    highlighted = settings.azureTranslationEnabled && settings.azureKey.isNotBlank(),
                    onToggle = { v ->
                        scope.launch {
                            app.container.settingsStore.update { it.copy(azureTranslationEnabled = v) }
                        }
                    },
                    onOpen = { onOpenEngineConfig(EngineKind.AZURE) },
                    usageLine = usageLineOf(TranslatorIds.AZURE),
                    usageProgress = usagePercentOf(TranslatorIds.AZURE),
                )
                RowDivider()

                // ---------- 以下免 Key，不用配置 ----------
                EngineRow(
                    order = 5,
                    name = "谷歌翻译",
                    status = "免 Key · 不用配置，国内不可达",
                    enabled = settings.googleTranslationEnabled,
                    highlighted = settings.googleTranslationEnabled,
                    onToggle = { v ->
                        scope.launch {
                            app.container.settingsStore.update { it.copy(googleTranslationEnabled = v) }
                        }
                    },
                )
                RowDivider()

                EngineRow(
                    order = 6,
                    name = "MyMemory",
                    status = "免 Key · 谷歌不通时兜底，每天约 1000 词",
                    enabled = settings.myMemoryTranslationEnabled,
                    highlighted = settings.myMemoryTranslationEnabled,
                    onToggle = { v ->
                        scope.launch {
                            app.container.settingsStore.update { it.copy(myMemoryTranslationEnabled = v) }
                        }
                    },
                )
                RowDivider()

                EngineRow(
                    order = null,
                    name = "ML Kit 离线",
                    status = "免 Key 无限量、断网可用，但逐句机翻，质量最差 · 兜底",
                    enabled = settings.offlineTranslationEnabled,
                    highlighted = settings.offlineTranslationEnabled,
                    onToggle = { v ->
                        scope.launch {
                            app.container.settingsStore.update { it.copy(offlineTranslationEnabled = v) }
                        }
                    },
                )

                Spacer(Modifier.height(12.dp))
                RowDivider()
                Spacer(Modifier.height(6.dp))

                // ---------- 额度监测的两个总开关 ----------
                // 放这里而不是列表之前：放前面会打断 1~6 的序号连续性
                SwitchRow(
                    title = "免费额度监测",
                    subtitle = "当月用量到 ${quotaConfig.alertPercent}% 时告警，到额度时停用该引擎",
                    checked = quotaConfig.quotaMonitorEnabled,
                    onCheckedChange = { v ->
                        scope.launch { app.container.quotaRepository.setMonitorEnabled(v) }
                    },
                )
                // 告警线可调：默认 90%，到线即停，把最后一段留作安全垫
                SliderWithInput(
                    label = "告警线",
                    value = quotaConfig.alertPercent.toFloat(),
                    range = 50f..100f,
                    suffix = "%",
                    decimals = 0,
                    sliderSteps = 10,
                    onValueChange = { v ->
                        scope.launch { app.container.quotaRepository.setAlertPercent(v.toInt()) }
                    },
                )
                Spacer(Modifier.height(4.dp))
                SwitchRow(
                    title = "额度耗尽时自动切换",
                    subtitle = if (quotaConfig.quotaMonitorEnabled) {
                        "仅能自动切换到已启用的翻译引擎"
                    } else {
                        "监测已关闭，无法判断用量，自动切换不可用"
                    },
                    checked = quotaConfig.autoSwitchEnabled,
                    // 监测关着的时候置灰：不知道用了多少，"要不要切"就没有意义
                    enabled = quotaConfig.quotaMonitorEnabled,
                    onCheckedChange = { v ->
                        scope.launch { app.container.quotaRepository.setAutoSwitchEnabled(v) }
                    },
                )

                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = onOpenUsage,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.height(28.dp),
                ) {
                    Text("查看各家用量与 7 日消耗 ›", style = MaterialTheme.typography.labelMedium)
                }

                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            if (testRunning) return@TextButton
                            testRunning = true
                            testResult = null
                            scope.launch {
                                // 结果除了显示到界面，也落一份到 files/last_selftest.txt。
                                // 荣耀会把 logcat 缓冲区清空，出问题时 adb 抓不到任何日志；
                                // 写文件是唯一可靠的排障通道（run-as 就能读）。
                                val r = runTranslationSelfTest(app, settings)
                                testResult = r.text
                                testWarning = r.warning
                                runCatching {
                                    java.io.File(app.filesDir, "last_selftest.txt")
                                        .writeText(r.text)
                                }
                                testRunning = false
                            }
                        },
                        enabled = !testRunning,
                    ) {
                        Text(if (testRunning) "测试中…" else "测试翻译")
                    }
                    Text(
                        "译一句样句，看实际生效的引擎和耗时",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (testResult != null) {
                    Text(
                        testResult!!,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            // ---------- 字幕外观 ----------
            SectionCard("字幕外观") {
                // 滑块旁边配输入框：12~32sp 里每档只差 1sp，纯滑块手指对不准
                SliderWithInput(
                    label = "字号",
                    value = settings.subtitleFontSizeSp,
                    range = 12f..32f,
                    suffix = "sp",
                    decimals = 0,
                    sliderSteps = 19,
                    onValueChange = { v ->
                        scope.launch {
                            app.container.settingsStore.update { it.copy(subtitleFontSizeSp = v) }
                        }
                    },
                )

                Spacer(Modifier.height(10.dp))

                SliderWithInput(
                    label = "背景不透明度",
                    value = settings.subtitleBackgroundAlpha * 100f,
                    // 上限 55 不是随便定的：再高 Android 12+ 会判定悬浮窗"遮挡"，
                    // 拦截穿透触摸，下面播放器的点击就传不进去了
                    range = 0f..55f,
                    suffix = "%",
                    decimals = 0,
                    sliderSteps = 10,
                    onValueChange = { v ->
                        scope.launch {
                            app.container.settingsStore.update {
                                it.copy(subtitleBackgroundAlpha = (v / 100f).coerceAtMost(0.55f))
                            }
                        }
                    },
                )
                HintText("超过 55% 会挡住下面 App 的点击，已做上限保护")

                Spacer(Modifier.height(6.dp))
                Text("文字颜色", style = MaterialTheme.typography.titleMedium)
                SubtitleColorPicker(
                    current = settings.subtitleTextColor,
                    onPick = { argb ->
                        scope.launch {
                            app.container.settingsStore.update { it.copy(subtitleTextColor = argb) }
                        }
                    },
                )

                SwitchRow(
                    title = "同时显示原文",
                    subtitle = "学习用，会占一行空间",
                    checked = settings.subtitleShowSource,
                    onCheckedChange = {
                        scope.launch {
                            app.container.settingsStore.update { s ->
                                s.copy(subtitleShowSource = it)
                            }
                        }
                    },
                )
            }

            // ---------- 稳定性 ----------
            SectionCard("后台保活") {
                Text(
                    "荣耀 MagicOS 会杀后台。建议把下面两项都设好，否则息屏后字幕会断",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { PermissionHelper.requestIgnoreBatteryOptimization(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("关闭电池优化")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { PermissionHelper.openAutoStartSettings(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("打开自启动管理")
                }
            }

            // ---------- 其他 ----------
            SectionCard("下载与调试") {
                SwitchRow(
                    title = "使用 HuggingFace 国内镜像",
                    subtitle = "hf-mirror.com，官方站下不动时打开",
                    checked = settings.useHfMirror,
                    onCheckedChange = {
                        scope.launch {
                            app.container.settingsStore.update { s -> s.copy(useHfMirror = it) }
                        }
                    },
                )
                SwitchRow(
                    title = "记录字幕历史",
                    subtitle = "用于导出 SRT",
                    checked = settings.recordHistory,
                    onCheckedChange = {
                        scope.launch {
                            app.container.settingsStore.update { s -> s.copy(recordHistory = it) }
                        }
                    },
                )
                SwitchRow(
                    title = "详细日志",
                    subtitle = if (settings.verboseLog) {
                        "已开启：日志写入 files/logs/app.log，关闭时会删除该文件"
                    } else {
                        "关闭中：不占空间。需要排查问题时再打开"
                    },
                    checked = settings.verboseLog,
                    onCheckedChange = {
                        scope.launch {
                            app.container.settingsStore.update { s -> s.copy(verboseLog = it) }
                        }
                    },
                )
                if (settings.verboseLog) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onOpenLogs) { Text("查看日志 →") }
                    }
                }
            }

            if (testWarning != null) {
                AlertDialog(
                    onDismissRequest = { testWarning = null },
                    title = { Text("离线语言包未就绪") },
                    text = { Text(testWarning!!) },
                    confirmButton = {
                        TextButton(onClick = { testWarning = null }) { Text("知道了") }
                    },
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "ScreenSub 1.0.0",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

/** LLM 是否已经配好（能真正参与翻译）——三项都得有 */
private fun llmConfigured(settings: AppSettings): Boolean =
    settings.llmBaseUrl.isNotBlank() &&
        settings.llmApiKey.isNotBlank() &&
        settings.llmModel.isNotBlank()

/**
 * LLM 那一行的状态文案。
 *
 * 开关打开后必须把**当前用的是哪家的哪个模型**写出来——这就是用户
 * "填了好几家不知道在跑哪个"的直接答案。
 */
private fun llmStatus(settings: AppSettings): String {
    if (!llmConfigured(settings)) {
        return "未配置 · 点此选模型并填 Key（质量最好，能带上下文）"
    }
    // 开关打开时把"当前到底在用哪个模型"写清楚
    val label = LlmPresets.of(settings.llmProvider).label
    return if (settings.llmTranslationEnabled) {
        "已启用 · $label · ${settings.llmModel}"
    } else {
        "已关闭 · $label · ${settings.llmModel}"
    }
}

/**
 * 火山的状态行。
 *
 * 顺带提醒额度性质：这套是每月 200 万字符**按月刷新**的，
 * 别和火山另一个按 token 计费、额度一次性的「豆包翻译大模型」搞混。
 */
private fun volcengineStatus(settings: AppSettings): String {
    if (settings.volcengineAccessKeyId.isBlank()) {
        return "未配置 · 点此填 AccessKey（200 万字符/月，每月重置）"
    }
    return if (settings.volcengineTranslationEnabled) {
        "已启用 · 200 万字符/月（每月 1 号重置）"
    } else {
        "已配置但已关闭"
    }
}

/**
 * 自测结果：正文 + 需要弹窗提醒的问题。
 */
private data class SelfTestResult(val text: String, val warning: String? = null)

/** 自测覆盖的语种。这四种是字幕场景最常见的（俄语走 Whisper，别的走 SenseVoice）。 */
private val SELF_TEST_LANGUAGES = listOf(
    "en" to "英语", "ja" to "日语", "ko" to "韩语", "ru" to "俄语",
)

/**
 * 测试样句：各语言的"你好"。
 *
 * 刻意用最短的一句：测的是"引擎通不通、多快"，不是译文质量。
 * 长句会让输出糊满屏幕，延迟里还混进了长文本预填充的时间，
 * 反而不如一句短的好比较。
 */
private fun selfTestSample(code: String): String = when (code) {
    "ja" -> "こんにちは"
    "ko" -> "안녕하세요"
    "ru" -> "Привет"
    else -> "Hello"
}

/**
 * 翻译自测：**逐个引擎、逐种语言**各译一句，报出每个组合的耗时。
 *
 * 为什么不走 ResilientTranslator：那样只知道"某一句最终被谁翻了"，
 * 前面失败几次、每次多慢全都看不见。用户想知道的是"我这四家分别多快"，
 * 所以这里直接把链里的引擎拆出来单独调用，互不干扰、互不兜底。
 *
 * 每句单发、不并发：并发会让延迟数字互相污染，测出来没有参考价值。
 */
private suspend fun runTranslationSelfTest(
    app: ScreenSubApp,
    settings: AppSettings,
): SelfTestResult = withContext(Dispatchers.Default) {
    val engines = TranslatorFactory.buildChain(app.container.buildTranslationSettings(settings))
    if (engines.isEmpty()) {
        return@withContext SelfTestResult("没有可用的翻译引擎，先把上面某个引擎的开关打开")
    }

    // 测试固定译为简体中文，不跟随首页的「译文语言」。
    //
    // 理由：这是**引擎能力基准测试**。如果目标语言跟着设置走，
    // 昨天测的是中译、今天切成韩语再测，两次数字和结果根本没法比，
    // 而且用户一看到满屏韩文就以为引擎坏了（实际发生过）。
    // 想看某个引擎翻成外语什么效果，用首页的「手动翻译」双向框更直观。
    val target = TargetLanguage.CHINESE_SIMPLIFIED
    val targetBase = target.substringBefore('-').lowercase()
    val sb = StringBuilder()
    var offlinePackMissing = false

    sb.appendLine("目标语言：${LanguageNames.targetOf(target)}（固定，不跟随首页设置）")
    sb.appendLine("${engines.size} 个引擎 × ${SELF_TEST_LANGUAGES.size} 个语种，每句单发")
    sb.appendLine()

    for (engine in engines) {
        val prep = runCatching { engine.prepare() }.getOrNull()
        if (prep is AppResult.Err) {
            sb.appendLine("【${engine.displayName}】准备失败：${prep.error.message}")
            sb.appendLine()
            continue
        }

        // 预热一次、不计分：把「DNS + TCP + TLS」的一次性成本挪出计时。
        // 不预热的话第一句永远是 700ms 上下，测出来的是建连速度，不是引擎速度。
        // 代价是每个引擎多花几个字符的额度，可以忽略。
        runCatching {
            withTimeoutOrNull(6_000) {
                engine.translate(
                    "hi",
                    TranslationContext(sourceCode = "en", targetCode = target),
                )
            }
        }

        sb.appendLine("【${engine.displayName}】（已预热，不含首次建连）")
        for ((code, label) in SELF_TEST_LANGUAGES) {
            if (code == targetBase) {
                sb.appendLine("  $label　　—　源=目标，跳过")
                continue
            }
            val sample = selfTestSample(code)
            val started = SystemClock.elapsedRealtime()
            val outcome = runCatching {
                withTimeoutOrNull(8_000) {
                    engine.translate(
                        sample,
                        TranslationContext(sourceCode = code, targetCode = target),
                    )
                }
            }.getOrNull()
            val ms = SystemClock.elapsedRealtime() - started

            when (outcome) {
                null -> sb.appendLine("  $label　${ms}ms　超时/异常")
                is AppResult.Ok -> sb.appendLine("  $label　${ms}ms　$sample → ${outcome.value.text}")
                is AppResult.Err -> {
                    if (engine.isOffline && !outcome.error.message.isLocalizedQuota()) {
                        offlinePackMissing = true
                    }
                    sb.appendLine("  $label　　失败 ${ms}ms　${outcome.error.message.take(56)}")
                }
            }
        }
        sb.appendLine()
    }

    engines.forEach { runCatching { it.release() } }

    SelfTestResult(
        text = sb.toString().trim(),
        warning = if (offlinePackMissing) {
            "ML Kit 的语言包由系统自动下载，App 里没有「安装」按钮——" +
                "这是 ML Kit 本身的设计，不是少做了功能。\n\n" +
                "只要手机联网，用离线引擎翻译一次，系统就会在后台开始下载" +
                "（每个语种约 30MB），下完离线也能用。\n\n" +
                "上面其它引擎的测试结果不受影响。"
        } else {
            null
        },
    )
}

/** 离线引擎失败是不是"配额/语言包"这类可忽略的原因 */
private fun String.isLocalizedQuota(): Boolean =
    contains("配额", true) || contains("quota", true)
