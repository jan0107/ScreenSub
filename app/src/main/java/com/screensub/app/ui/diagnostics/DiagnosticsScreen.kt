// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.app.ui.diagnostics

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.screensub.core.audio.AudioCaptureEngine
import com.screensub.core.audio.LevelMeter
import com.screensub.core.common.AppResult
import kotlinx.coroutines.delay

/**
 * 兼容性自检页。
 *
 * 这是整个项目最重要的一页——它回答一个决定产品形态的问题：
 * **你常用的视频 App 到底允不允许内录？**
 *
 * 系统没有公开 API 能查这个，只能实测：
 * 打开一个视频 App 播放，切回这里点"测试内录"，
 * 看电平条动不动、判定结论是什么。
 *
 * 建议对每个平台都测一遍，结果记下来。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    var musicActive by remember { mutableStateOf(false) }
    var volume by remember { mutableIntStateOf(0) }
    var maxVolume by remember { mutableIntStateOf(15) }

    var peakDbfs by remember { mutableFloatStateOf(-100f) }
    var silentRunMs by remember { mutableLongStateOf(0L) }
    var testing by remember { mutableStateOf(false) }
    var testSource by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("还没测试，先播放视频再点下面的按钮") }

    val engineHolder = remember { arrayOfNulls<AudioCaptureEngine>(1) }

    // AudioManager 状态实时刷新
    LaunchedEffect(Unit) {
        while (true) {
            musicActive = audioManager.isMusicActive
            volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            delay(200)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            engineHolder[0]?.stop()
            engineHolder[0] = null
        }
    }

    fun startTest(projection: MediaProjection?, label: String) {
        engineHolder[0]?.stop()

        silentRunMs = 0L
        peakDbfs = -100f

        val engine = AudioCaptureEngine(context)
        engine.onPeakLevel = { peak ->
            peakDbfs = peak
            silentRunMs = if (peak > -60f) 0L else silentRunMs + 20L
        }

        val outcome = if (projection != null) {
            engine.startInternal(projection)
        } else {
            engine.startMic()
        }

        when (outcome) {
            is AppResult.Ok -> {
                engineHolder[0] = engine
                testing = true
                testSource = label
                statusText = "测试中（$label）… 让它跑 10 秒看看"
            }

            is AppResult.Err -> {
                testing = false
                statusText = "启动失败：${outcome.error.message}"
            }
        }
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // 到这里只证明"系统弹窗正常、用户愿意授权"。
            //
            // Android 14(API 34) 起，MediaProjection 只能在**已经 startForeground 的
            // 前台服务**里 getMediaProjection，否则必抛 SecurityException。
            // 自检页是个普通页面，没有前台服务，硬取只会得到一个假的"失败"——
            // 早期版本就是这么误报的。
            //
            // 真正的采集放行在 CaptureService 里（它先 startForeground 再取 token）。
            // 自检到此为止，把结论说清楚，别让用户以为设备不支持内录。
            statusText = buildString {
                append("投屏授权通道正常：系统弹窗已弹出、授权已通过。\n\n")
                append("内录的实际音质与是否被目标 App 拦截，")
                append("需要在前台服务里才能验证——请回首页点「开始字幕」，")
                append("播放视频后看悬浮窗有没有字幕出现。")
            }
        } else {
            statusText = "授权被取消。收不到投屏授权就无法内录，只能改用麦克风。"
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("兼容性自检") },
                navigationIcon = {
                    IconButton(onClick = {
                        engineHolder[0]?.stop()
                        engineHolder[0] = null
                        onBack()
                    }) {
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
            // 设备信息
            InfoCard("设备") {
                InfoRow("系统", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                InfoRow("机型", "${Build.MANUFACTURER} ${Build.MODEL}")
                InfoRow("ABI", Build.SUPPORTED_ABIS.firstOrNull() ?: "未知")
            }

            // 播放状态
            InfoCard("当前播放") {
                InfoRow(
                    "媒体播放中",
                    if (musicActive) "是" else "否（请先打开视频 App 播放）",
                    highlight = musicActive,
                )
                InfoRow("媒体音量", "$volume / $maxVolume")
            }

            // 电平
            InfoCard("采集电平") {
                Text(
                    text = if (testing) "当前峰值 ${peakDbfs.toInt()} dBFS" else "未在测试",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(18.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(9.dp),
                        ),
                ) {
                    val fraction = LevelMeter.dbfsToFraction(peakDbfs)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(18.dp)
                            .background(
                                when {
                                    fraction > 0.7f -> Color(0xFFFF5A5F)
                                    fraction > 0.4f -> Color(0xFFFFB020)
                                    else -> MaterialTheme.colorScheme.secondary
                                },
                                RoundedCornerShape(9.dp),
                            ),
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = "连续静音 ${silentRunMs / 1000} 秒",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 结论
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = verdictColor(
                        testing = testing,
                        peak = peakDbfs,
                        silentMs = silentRunMs,
                        musicActive = musicActive,
                    ).copy(alpha = 0.14f),
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        text = verdictText(
                            testing = testing,
                            peak = peakDbfs,
                            silentMs = silentRunMs,
                            musicActive = musicActive,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = verdictColor(
                            testing = testing,
                            peak = peakDbfs,
                            silentMs = silentRunMs,
                            musicActive = musicActive,
                        ),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 操作
            Button(
                onClick = {
                    val mpm = context.getSystemService(MediaProjectionManager::class.java)
                    projectionLauncher.launch(mpm.createScreenCaptureIntent())
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("测试内部音频捕获")
            }

            OutlinedButton(
                onClick = { startTest(null, "外录") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("测试麦克风外录")
            }

            if (testing) {
                OutlinedButton(
                    onClick = {
                        engineHolder[0]?.stop()
                        engineHolder[0] = null
                        testing = false
                        statusText = "已停止测试"
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("停止测试")
                }
            }

            Text(
                text = "怎么用：先在别的 App 里播放视频，然后切回来点「测试内部音频捕获」，" +
                    "观察电平条是否跳动。如果不跳但媒体在播放、音量也正常，说明该 App 禁止了内录，" +
                    "只能改用麦克风外录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

private fun verdictText(
    testing: Boolean,
    peak: Float,
    silentMs: Long,
    musicActive: Boolean,
): String = when {
    !testing -> "等待测试"
    peak > -50f -> "内录正常，能抓到音频"
    !musicActive -> "没检测到媒体播放"
    silentMs >= 3000L -> "判定：该 App 禁止了内部音频捕获"
    else -> "正在观察…"
}

private fun verdictColor(
    testing: Boolean,
    peak: Float,
    silentMs: Long,
    musicActive: Boolean,
): Color = when {
    !testing -> Color(0xFF8A8A9A)
    peak > -50f -> Color(0xFF3DD6C8)
    !musicActive -> Color(0xFFFFB020)
    silentMs >= 3000L -> Color(0xFFFF5A5F)
    else -> Color(0xFFFFB020)
}

@Composable
private fun InfoCard(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, highlight: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (highlight) {
                MaterialTheme.colorScheme.secondary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
