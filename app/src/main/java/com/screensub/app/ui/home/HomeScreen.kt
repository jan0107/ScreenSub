// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.CircularProgressIndicator
import com.screensub.core.asr.SourceLanguage
import com.screensub.core.common.AppResult
import com.screensub.core.common.LanguageNames
import com.screensub.app.ui.settings.QuotaOverviewCard
import com.screensub.core.data.AppSettings
import com.screensub.core.data.withSourceLanguage
import com.screensub.core.mt.TargetLanguage
import com.screensub.core.mt.TranslationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Troubleshoot
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import com.screensub.app.ScreenSubApp
import com.screensub.app.service.CaptureService
import com.screensub.app.service.SessionState
import com.screensub.app.ui.rememberStartSubtitleFlow
import com.screensub.app.util.PermissionHelper
import com.screensub.core.asr.ModelCatalog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenUsage: () -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.applicationContext as ScreenSubApp
    val settings by app.container.settings.collectAsState()
    val sessionState by CaptureService.state.collectAsState()
    val quotaNotice by app.container.quotaNotice.collectAsState()
    val quotaSnapshot by app.container.quotaRepository.state.collectAsState()
    // 排序在仓库里做（告警优先 → 使用中 → 最近用过 → 链顺序），这里只取结果
    val quotaUsages = remember(quotaSnapshot) { app.container.quotaRepository.sortedForDisplay() }
    val startFlow = rememberStartSubtitleFlow()
    val scope = rememberCoroutineScope()

    var audioSource by remember(settings.audioSource) { mutableStateOf(settings.audioSource) }

    val contextActivity = context
    var missingPermissions by remember { mutableStateOf(PermissionHelper.missingCritical(contextActivity)) }

    // 模型没下载时不要启动服务 —— 服务里第一步就会因为拿不到引擎而退出，
    // 用户只会看到"未运行"，根本不知道是缺模型。
    var showModelDialog by remember { mutableStateOf(false) }

    // 启动时自愈：只纠正**确实用不了**的组合（模型不认识当前语言）。
    //
    // 原来这里写的是"和默认模型不一致就改回默认"，那等于把用户的手动选择当成错误：
    // 用户在韩语下选了 Zipformer，一回家就被改回 SenseVoice，看起来像"选什么都没用"。
    // 现在判据改成"这个模型支不支持当前语言"，支持就尊重用户的选择。
    LaunchedEffect(settings.sourceLanguage, settings.modelId) {
        val lang = SourceLanguage.fromCode(settings.sourceLanguage)
        val spec = ModelCatalog.findById(settings.modelId)
        val usable = spec != null && spec.supports(lang)
        if (!usable) {
            app.container.settingsStore.update { it.withSourceLanguage(lang) }
        }
    }

    val isRunning = sessionState is SessionState.Running || sessionState is SessionState.Starting

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(20.dp))

            // ---------- 标题栏 ----------
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ScreenSub",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = "实时字幕 · 生肉救星",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onOpenDiagnostics) {
                    Icon(
                        Icons.Default.Troubleshoot,
                        contentDescription = "兼容性自检",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "设置",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // ---------- 状态卡 ----------
            StatusCard(sessionState)

            Spacer(Modifier.height(20.dp))

            // ---------- 翻译用量概览 ----------
            // 只在有可统计的引擎时出现；重启后台时会瞬时为空，那就先不占位
            if (quotaUsages.isNotEmpty()) {
                Text(
                    text = "翻译用量",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(8.dp))
                QuotaOverviewCard(usages = quotaUsages, onOpen = onOpenUsage)
                Spacer(Modifier.height(20.dp))
            }

            // ---------- 音频来源 ----------
            Text(
                text = "音频来源",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SourceChip(
                    modifier = Modifier.weight(1f),
                    selected = audioSource == "internal",
                    title = "内部音频",
                    subtitle = "音质好，个别 App 禁抓",
                    enabled = !isRunning,
                    onClick = {
                        audioSource = "internal"
                        scope.launch {
                            app.container.settingsStore.update { it.copy(audioSource = "internal") }
                        }
                    },
                )
                SourceChip(
                    modifier = Modifier.weight(1f),
                    selected = audioSource == "mic",
                    title = "麦克风",
                    subtitle = "需外放，别戴耳机",
                    enabled = !isRunning,
                    onClick = {
                        audioSource = "mic"
                        scope.launch {
                            app.container.settingsStore.update { it.copy(audioSource = "mic") }
                        }
                    },
                )
            }

            Spacer(Modifier.height(16.dp))

            // ---------- 翻译语言 ----------
            // 源语言写在这里而不是只在设置页：换片源（英语剧→日漫）是最常见的操作，
            // 每次都要进设置翻三层太反人类。
            Text(
                "翻译语言",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))

            LanguageChips(
                label = "源语言",
                options = SourceLanguage.entries.map { it.code to it.label },
                current = settings.sourceLanguage,
                enabled = !isRunning,
                onSelect = { code ->
                    scope.launch {
                        app.container.settingsStore.update {
                            it.withSourceLanguage(SourceLanguage.fromCode(code))
                        }
                    }
                },
            )
            Spacer(Modifier.height(10.dp))

            // 同一语言有多个模型时，让用户直接在这里选。
            // 放首页而不是设置页：换片源时常要跟着换模型，进设置翻两层太绕；
            // 而且设置页那个入口每次回来都会被"自愈"逻辑改回去，体感是"选了没用"。
            val currentLang = SourceLanguage.fromCode(settings.sourceLanguage)
            val modelCandidates = ModelCatalog.ALL.filter { it.supports(currentLang) }
            if (modelCandidates.size > 1) {
                Spacer(Modifier.height(10.dp))
                LanguageChips(
                    label = "识别模型",
                    options = modelCandidates.map { it.id to it.displayName },
                    current = settings.modelId,
                    enabled = !isRunning,
                    onSelect = { id ->
                        scope.launch {
                            app.container.settingsStore.update { it.copy(modelId = id) }
                        }
                    },
                )
            }

            Spacer(Modifier.height(10.dp))

            LanguageChips(
                label = "译文语言",
                options = TargetLanguage.SUPPORTED.map { it to LanguageNames.targetOf(it) },
                current = settings.targetLanguage,
                enabled = !isRunning,
                onSelect = { code ->
                    scope.launch {
                        app.container.settingsStore.update { it.copy(targetLanguage = code) }
                    }
                },
            )

            Spacer(Modifier.height(16.dp))

            // ---------- 权限提醒 ----------
            if (missingPermissions.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f),
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            text = "还缺权限：${missingPermissions.joinToString("、")}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "点下面的按钮会依次引导你授权",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // ---------- 主按钮 ----------
            Button(
                onClick = {
                    if (isRunning) {
                        CaptureService.stop(context)
                    } else {
                        // 先确认模型就绪，再走权限流程。顺序反了会白弹一次投屏授权。
                        scope.launch {
                            val downloader = app.container.modelDownloader()
                            val spec = ModelCatalog.findById(settings.modelId)
                                ?: ModelCatalog.SENSE_VOICE
                            val ready = downloader.isModelReady(spec) && downloader.isVadReady()
                            if (!ready) {
                                showModelDialog = true
                            } else {
                                missingPermissions = PermissionHelper.missingCritical(context)
                                startFlow(audioSource == "internal")
                            }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                ),
            ) {
                Text(
                    text = if (isRunning) "停止字幕" else "开始字幕",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (isRunning) Color.White else MaterialTheme.colorScheme.onPrimary,
                )
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = if (audioSource == "internal") {
                    "开始后请切换到视频 App 并点击播放。首次会弹出「开始录制或投放」授权，每次使用都需要确认一次。"
                } else {
                    "请把手机音量调到 60%~80% 并外放，不要戴耳机。环境越安静识别越准。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))

            // ---------- 手动翻译 ----------
            // 不用开字幕、不用投屏，随手验一句翻译效果。
            // 排查"这个引擎到底行不行"比开一整轮字幕快得多。
            QuickTranslateCard(app, settings)
        }

        if (showModelDialog) {
            // 弹窗内容必须跟着"当前语言需要哪个模型"走：
            // 中英要 X-ASR（168MB），俄语要 GigaAM（226MB），日语 153MB，韩语 73MB。
            // 写死一个模型名和体积，会让换语言的人以为自己下错了东西。
            // 用**用户实际选中的**模型：他在下拉里选了 Zipformer，
            // 弹窗就该报 Zipformer 的体积，而不是默认那个
            val neededSpec = ModelCatalog.findById(settings.modelId)
                ?: ModelCatalog.defaultFor(SourceLanguage.fromCode(settings.sourceLanguage))
            AlertDialog(
                onDismissRequest = { showModelDialog = false },
                title = { Text("还没下载识别模型") },
                text = {
                    Text(
                        "当前源语言「${LanguageNames.of(settings.sourceLanguage)}」需要：" +
                            "${neededSpec.displayName}，约 ${neededSpec.approxSizeMb} MB。\n\n" +
                            neededSpec.description + "\n\n" +
                            "去设置页点「下载」，下完回来就能用了。",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showModelDialog = false
                            onOpenSettings()
                        },
                    ) {
                        Text("去下载")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showModelDialog = false }) {
                        Text("稍后")
                    }
                },
            )
        }

        // 额度提示：看视频时悬浮窗已经提示过一次，用户切回 App 后在这里给完整说明。
        // 只有"确实没退路"时才建议开免费引擎，有引擎接管时不该推销。
        quotaNotice?.let { notice ->
            AlertDialog(
                onDismissRequest = { app.container.dismissQuotaNotice() },
                title = { Text(notice.title) },
                text = { Text(notice.body) },
                confirmButton = {
                    if (notice.canEnableFreeFallback) {
                        TextButton(
                            onClick = {
                                app.container.dismissQuotaNotice()
                                scope.launch {
                                    app.container.settingsStore.update {
                                        it.copy(
                                            googleTranslationEnabled = true,
                                            myMemoryTranslationEnabled = true,
                                        )
                                    }
                                }
                            },
                        ) {
                            Text("启用免费翻译兜底")
                        }
                    } else {
                        TextButton(onClick = { app.container.dismissQuotaNotice() }) {
                            Text("知道了")
                        }
                    }
                },
                dismissButton = {
                    if (notice.canEnableFreeFallback) {
                        TextButton(onClick = { app.container.dismissQuotaNotice() }) {
                            Text("知道了")
                        }
                    } else {
                        TextButton(
                            onClick = {
                                app.container.dismissQuotaNotice()
                                onOpenSettings()
                            },
                        ) {
                            Text("配置引擎")
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun StatusCard(state: SessionState) {
    val (title, detail, dotColor) = when (state) {
        is SessionState.Idle -> Triple(
            "未运行",
            "点下方按钮开始",
            MaterialTheme.colorScheme.onSurfaceVariant,
        )

        is SessionState.Starting -> Triple(
            "正在准备…",
            "加载模型，首次约需几秒",
            MaterialTheme.colorScheme.tertiary,
        )

        is SessionState.Running -> Triple(
            if (state.alert != null) "运行中 · 翻译异常" else "运行中 · ${state.sourceLabel}",
            state.alert
                ?: "已出 ${state.cueCount} 条字幕 · 延迟 ${state.latencyMs / 1000.0}秒",
            if (state.alert != null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.secondary
            },
        )

        is SessionState.Failed -> Triple(
            "出错了",
            state.message,
            MaterialTheme.colorScheme.error,
        )
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(dotColor, CircleShape),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SourceChip(
    selected: Boolean,
    title: String,
    subtitle: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
            selectedLabelColor = MaterialTheme.colorScheme.primary,
        ),
        label = {
            // 固定两行副标题的高度，两个 chip 视觉上才等齐
            Column(
                modifier = Modifier
                    .padding(vertical = 6.dp)
                    .height(46.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        },
    )
}

/**
 * 横向滚动的语言单选条。
 *
 * 不用下拉菜单：语言就那么几种，平铺点一下最快，而且当前选中项一眼可见
 * （下拉要点开才知道选了什么）。加了横向滚动，以后扩到十几种也不会挤。
 */
@Composable
private fun LanguageChips(
    label: String,
    options: List<Pair<String, String>>,
    current: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (code, name) ->
            FilterChip(
                selected = code == current,
                onClick = { if (enabled) onSelect(code) },
                enabled = enabled,
                label = { Text(name, style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
}

/**
 * 手动翻译：输入一段文字，用当前翻译链翻一次。
 *
 * 存在的理由：想验证"火山到底翻得怎么样"时，开字幕 + 投屏 + 等几十秒太慢。
 * 这里粘一句就能看结果，连引擎报错都能直接显示出来。
 */
@Composable
private fun QuickTranslateCard(app: ScreenSubApp, settings: AppSettings) {
    val srcCode = settings.sourceLanguage
    val dstCode = settings.targetLanguage
    val srcName = LanguageNames.of(srcCode)
    val dstName = LanguageNames.targetOf(dstCode)

    var leftText by remember { mutableStateOf("") }
    var rightText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    // 防抖：边打字边发请求既慢又烧配额，停手 600ms 再翻
    var job by remember { mutableStateOf<Job?>(null) }

    /**
     * 双向翻译。
     *
     * 为什么两边都要能输入：实际使用里"中文→外语"和"外语→中文"是一样常见的，
     * 只做一个方向的话，用户想把中文翻成日语就得先去首页改译文语言。
     * 这里哪边改就翻到另一边，方向由"哪个框在编辑"决定。
     *
     * 关键：回填另一边用的是直接赋值，不走 onValueChange，
     * 所以不会触发对方再翻回来形成死循环。
     */
    fun translate(fromLeft: Boolean) {
        job?.cancel()
        val input = (if (fromLeft) leftText else rightText).trim()
        if (input.isBlank()) {
            if (fromLeft) rightText = "" else leftText = ""
            error = null
            return
        }
        job = scope.launch {
            delay(600)
            busy = true
            error = null
            val from = if (fromLeft) srcCode else dstCode
            val to = if (fromLeft) dstCode else srcCode
            val result = withContext(Dispatchers.IO) {
                val translator = app.container.buildTranslator(settings, emptyList())
                try {
                    val prepared = translator.prepare()
                    if (prepared is AppResult.Err) {
                        AppResult.Err(prepared.error)
                    } else {
                        translator.translate(input, TranslationContext(from, to))
                    }
                } catch (t: Throwable) {
                    AppResult.Err(
                        com.screensub.core.common.AppError.Engine("翻译异常：${t.message}"),
                    )
                } finally {
                    runCatching { translator.release() }
                }
            }
            when (result) {
                is AppResult.Ok -> if (fromLeft) rightText = result.value.text else leftText = result.value.text
                is AppResult.Err -> error = result.error.message
            }
            busy = false
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("手动翻译", style = MaterialTheme.typography.titleMedium)
            Text(
                "$srcName ⇄ $dstName　·　两边都能输入，自动互译",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = leftText,
                onValueChange = {
                    leftText = it
                    translate(true)
                },
                label = { Text(srcName, style = MaterialTheme.typography.labelSmall) },
                placeholder = { Text("输入$srcName", style = MaterialTheme.typography.bodySmall) },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    if (busy) "翻译中…" else "↕",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = rightText,
                onValueChange = {
                    rightText = it
                    translate(false)
                },
                label = { Text(dstName, style = MaterialTheme.typography.labelSmall) },
                placeholder = { Text("输入$dstName", style = MaterialTheme.typography.bodySmall) },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
                // 用输入框而不是 Text 显示译文：输入框天然支持长按选中/复制，
                // Text 还要额外包 SelectionContainer，用户复制不了才是真别扭
                trailingIcon = {
                    if (rightText.isNotBlank()) {
                        TextButton(
                            onClick = { clipboard.setText(AnnotatedString(rightText)) },
                            contentPadding = PaddingValues(horizontal = 6.dp),
                        ) { Text("复制", style = MaterialTheme.typography.labelSmall) }
                    }
                },
            )

            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    error!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
