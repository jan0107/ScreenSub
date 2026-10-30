// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.app.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/** 权限检查与跳转。全部做异常兜底，国产 ROM 的设置页入口经常不一样。 */
object PermissionHelper {

    fun hasRecordAudio(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun hasNotification(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun canDrawOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 返回所有还缺的关键权限名，UI 据此提示 */
    fun missingCritical(context: Context): List<String> = buildList {
        if (!hasRecordAudio(context)) add("录音")
        if (!hasNotification(context)) add("通知")
        if (!canDrawOverlay(context)) add("悬浮窗")
    }

    /** 跳悬浮窗授权页。部分 ROM 无此页，兜底跳应用详情。 */
    fun openOverlaySettings(context: Context) {
        val intents = listOf(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(
                Uri.parse("package:${context.packageName}"),
            ),
        )
        launchFirstAvailable(context, intents)
    }

    /** 跳电池优化白名单 */
    fun requestIgnoreBatteryOptimization(context: Context) {
        val intents = listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(
                Uri.parse("package:${context.packageName}"),
            ),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        )
        launchFirstAvailable(context, intents)
    }

    /**
     * 荣耀/华为系的自启动管理页。
     * 这些页面没有公开的 Intent action，只能靠猜 component，全部 try-catch。
     */
    fun openAutoStartSettings(context: Context) {
        val candidates = listOf(
            "com.hihonor.systemmanager",
            "com.huawei.systemmanager",
        ).flatMap { pkg ->
            listOf(
                Intent().setComponent(
                    android.content.ComponentName(
                        pkg, "$pkg.startupmgr.appcontrol.activity.StartupAppControlActivity",
                    ),
                ),
                Intent().setComponent(
                    android.content.ComponentName(
                        pkg, "$pkg.startupmgr.appcontrol.activity.StartupAppControlActivityEx",
                    ),
                ),
                Intent().setComponent(
                    android.content.ComponentName(
                        pkg, "$pkg.optimize.process.ProtectActivity",
                    ),
                ),
            )
        }

        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(
            Uri.parse("package:${context.packageName}"),
        )

        launchFirstAvailable(context, candidates + fallback)
    }

    private fun launchFirstAvailable(context: Context, intents: List<Intent>) {
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            } catch (_: Throwable) {
                // 试下一个
            }
        }
    }
}
