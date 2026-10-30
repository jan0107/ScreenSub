// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File

/**
 * 日志查看页。
 *
 * ## 为什么需要它
 *
 * 荣耀 MagicOS 会把 logcat 缓冲区清空，`adb logcat -d` 里第三方 App 的日志
 * 一条都不剩。所以「详细日志」现在写文件，但用户总不能在电脑上 run-as 看。
 * 这个页面直接读同一个文件，等于把排障能力交到用户手上。
 *
 * 每 800ms 轮询一次文件大小，变了才重读——用户开着字幕时日志在涨，
 * 页面能跟着滚，但不会因为每秒重读整个文件而卡。
 *
 * 只显示最后 500 行：日志滚到几千行之后，翻旧记录没有意义，
 * 而且 LazyColumn 加载几万行也会拖慢。
 */
@Composable
fun LogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val logFile = remember { File(context.filesDir, "logs/app.log") }

    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var lastSize by remember { mutableStateOf(-1L) }
    var enabled by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        while (true) {
            val size = if (logFile.exists()) logFile.length() else -1L
            if (size != lastSize) {
                lastSize = size
                enabled = size >= 0
                lines = runCatching {
                    if (logFile.exists()) {
                        logFile.readLines().takeLast(500)
                    } else {
                        emptyList()
                    }
                }.getOrDefault(emptyList())
                // 有新内容就滚到底，像 tail -f
                if (lines.isNotEmpty()) {
                    listState.scrollToItem(lines.lastIndex)
                }
            }
            delay(800)
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
            Spacer(Modifier.height(10.dp))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("← 返回") }
                Text(
                    "运行日志",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        runCatching { logFile.writeText("") }
                        lines = emptyList()
                        lastSize = -1
                    },
                ) { Text("清空") }
            }

            Text(
                if (enabled) {
                    "${logFile.absolutePath}  ·  ${lines.size} 行（只显示最后 500 行）"
                } else {
                    "文件不存在 —— 请先在上层打开「详细日志」开关"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
            )
            Spacer(Modifier.height(6.dp))

            if (lines.isEmpty()) {
                Text(
                    if (enabled) "暂无日志，去用一下字幕或测试翻译就会产生" else "详细日志未开启",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 20.dp),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        )
                        .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    items(lines) { line ->
                        Text(
                            line,
                            // 等宽字体：日志靠缩进对齐才看得清请求头那一坨
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                            color = when {
                                line.contains(" E/") -> MaterialTheme.colorScheme.error
                                line.contains(" W/") -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }
    }
}
