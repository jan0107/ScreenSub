// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import java.io.File
import com.screensub.app.ScreenSubApp
import com.screensub.app.service.CaptureService
import com.screensub.core.common.Log
import com.screensub.app.ui.diagnostics.DiagnosticsScreen
import com.screensub.app.ui.home.HomeScreen
import com.screensub.app.ui.settings.EngineConfigScreen
import com.screensub.app.ui.settings.SettingsScreen
import com.screensub.app.ui.usage.UsageScreen
import com.screensub.app.util.PermissionHelper

/** 页面路由。层级：Home → Settings → EngineConfig。 */
sealed interface Route {
    data object Home : Route
    data object Settings : Route
    data object Diagnostics : Route

    /** 日志查看页（详细日志打开后可实时看输出） */
    data object Logs : Route

    /** 翻译用量：各引擎本月用量、最近 7 日、额度与校准 */
    data class Usage(val fromSettings: Boolean = false) : Route

    /** 三级页：某个在线翻译引擎的详细配置 */
    data class EngineConfig(val engine: EngineKind) : Route
}

/** 有独立配置页的在线引擎 */
enum class EngineKind {
    LLM,
    AZURE,
    ALIYUN,
    VOLCENGINE;

    val title: String
        get() = when (this) {
            LLM -> "LLM 翻译"
            AZURE -> "微软 Azure 翻译"
            ALIYUN -> "阿里云机器翻译"
            VOLCENGINE -> "火山云机器翻译"
        }
}

/** 返回上一级。一级页面返回 null，交给系统处理 */
private fun Route.parent(): Route? = when (this) {
    Route.Home -> null
    Route.Settings -> Route.Home
    Route.Diagnostics -> Route.Home
    Route.Logs -> Route.Settings
    // 用量页有两个入口（首页卡 / 设置页），退回哪一层取决于从哪进来的
    is Route.Usage -> if (this.fromSettings) Route.Settings else Route.Home
    is Route.EngineConfig -> Route.Settings
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // targetSdk 36 强制 edge-to-edge，必须显式调用
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            ScreenSubTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    var route by remember { mutableStateOf<Route>(Route.Home) }

    // 二级页面必须拦返回键：否则按返回会直接退到桌面（Activity 结束），
    // 用户以为 App 崩了。三级页面要退回它自己的上级，不是一路回首页。
    BackHandler(enabled = route != Route.Home) {
        route = route.parent() ?: Route.Home
    }

    // 「详细日志」开关直接决定文件日志的生死：
    //   开 → 挂上 files/logs/app.log 并开始写
    //   关 → 摘掉写入并删除文件（避免日积月累把存储吃满）
    // 荣耀会清空 logcat，文件是唯一可靠的排查通道，所以开关必须是真开关。
    val app = context.applicationContext as ScreenSubApp
    val settings by app.container.settings.collectAsState()
    LaunchedEffect(settings.verboseLog) {
        val logFile = File(context.filesDir, "logs/app.log")
        if (settings.verboseLog) {
            Log.attachFile(logFile)
            Log.i("App", "详细日志已开启 → ${logFile.absolutePath}")
        } else {
            Log.detachFile()
            runCatching { if (logFile.exists()) logFile.delete() }
        }
    }

    // 通知权限：进首页就要，否则前台服务通知不显示
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(Unit) {
        if (!PermissionHelper.hasNotification(context)) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    when (route) {
        Route.Home -> HomeScreen(
            onOpenSettings = { route = Route.Settings },
            onOpenDiagnostics = { route = Route.Diagnostics },
            onOpenUsage = { route = Route.Usage(fromSettings = false) },
        )

        Route.Settings -> SettingsScreen(
            onBack = { route = Route.Home },
            onOpenEngineConfig = { engine -> route = Route.EngineConfig(engine) },
            onOpenLogs = { route = Route.Logs },
            onOpenUsage = { route = Route.Usage(fromSettings = true) },
        )

        Route.Logs -> LogScreen(onBack = { route = Route.Settings })

        is Route.Usage -> UsageScreen(onBack = { route = route.parent() ?: Route.Home })

        Route.Diagnostics -> DiagnosticsScreen(onBack = { route = Route.Home })

        is Route.EngineConfig -> {
            val current = route as Route.EngineConfig
            EngineConfigScreen(
                engine = current.engine,
                onBack = { route = Route.Settings },
            )
        }
    }
}

/**
 * 启动字幕的三段式权限流程。
 *
 * 顺序不能乱：
 *   1. 悬浮窗权限（没有它字幕窗加不进去）
 *   2. 录音权限（内录也要求 RECORD_AUDIO）
 *   3. 屏幕捕获授权（每次会话都要重新弹，token 不可复用）
 */
@Composable
fun rememberStartSubtitleFlow(): (isInternal: Boolean) -> Unit {
    val context = LocalContext.current

    var pendingInternal by remember { mutableStateOf<Boolean?>(null) }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val internal = pendingInternal
        pendingInternal = null
        if (result.resultCode == Activity.RESULT_OK && result.data != null && internal == true) {
            CaptureService.startInternal(context, result.resultCode, result.data!!)
        } else if (internal == false) {
            CaptureService.startMic(context)
        }
    }

    val recordLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val internal = pendingInternal
        if (!granted) {
            pendingInternal = null
            return@rememberLauncherForActivityResult
        }
        when (internal) {
            true -> {
                // 录音权限拿到后，再要屏幕捕获授权
                val mpm = context.getSystemService(MediaProjectionManager::class.java)
                projectionLauncher.launch(mpm.createScreenCaptureIntent())
            }

            false -> {
                pendingInternal = null
                CaptureService.startMic(context)
            }

            null -> Unit
        }
    }

    return remember(context) {
        { isInternal: Boolean ->
            pendingInternal = isInternal
            when {
                !PermissionHelper.canDrawOverlay(context) -> {
                    PermissionHelper.openOverlaySettings(context)
                }

                !PermissionHelper.hasRecordAudio(context) -> {
                    recordLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }

                isInternal -> {
                    val mpm = context.getSystemService(MediaProjectionManager::class.java)
                    projectionLauncher.launch(mpm.createScreenCaptureIntent())
                }

                else -> CaptureService.startMic(context)
            }
        }
    }
}
