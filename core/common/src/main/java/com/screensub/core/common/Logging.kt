// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.common

import android.util.Log as AndroidLog
import java.io.BufferedWriter
import java.io.File

/**
 * 统一日志。
 *
 * ## 两条输出通道
 *
 * 1. **logcat**：`adb logcat -s SS-Audio` 过滤，开发时最方便
 * 2. **文件**：写到 `files/logs/app.log`
 *
 * 为什么必须有第二条：荣耀 MagicOS 会把 logcat 缓冲区清空，
 * `adb logcat -d` 常常只剩几十行系统日志，第三方 App 的日志一条都没有。
 * 出问题时连"到底报了什么错"都拿不到，只能靠界面上的只言片语猜。
 * 所以这里同时落一份文件——`adb exec-out "run-as 包名 cat files/logs/app.log"` 就能读。
 */
object Log {

    /** 控制 DEBUG 及以上级别是否输出（ERROR/WARN 恒输出） */
    @Volatile
    var enabled: Boolean = false

    private const val PREFIX = "SS"

    /** 超过这个大小就截断重来，避免长期运行把存储写满 */
    private const val MAX_FILE_BYTES = 512 * 1024L

    @Volatile
    private var writer: BufferedWriter? = null

    @Volatile
    private var logFile: File? = null

    private val lock = Any()

    /**
     * 挂上一个日志文件。传 null 表示关闭文件输出。
     *
     * 由 Application 在启动时调用，路径通常是 `filesDir/logs/app.log`。
     */
    fun attachFile(file: File?) {
        synchronized(lock) {
            runCatching { writer?.close() }
            writer = null
            logFile = file
            if (file == null) return
            runCatching {
                file.parentFile?.mkdirs()
                if (file.exists() && file.length() > MAX_FILE_BYTES) {
                    // 简单截断，不做多级轮转：排查问题只关心最近的一段
                    file.writeText("")
                }
                writer = file.bufferedWriter()
            }
        }
    }

    /** 关闭文件输出（设置里关掉「详细日志」时调用，随后由调用方删掉文件） */
    fun detachFile() = attachFile(null)

    fun v(tag: String, msg: String) = log(AndroidLog.VERBOSE, tag, msg, null)
    fun d(tag: String, msg: String) = log(AndroidLog.DEBUG, tag, msg, null)
    fun i(tag: String, msg: String) = log(AndroidLog.INFO, tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = log(AndroidLog.WARN, tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = log(AndroidLog.ERROR, tag, msg, t)

    private fun log(priority: Int, tag: String, msg: String, t: Throwable?) {
        if (!enabled && priority < AndroidLog.WARN) return

        val fullTag = "$PREFIX-$tag".take(23) // Android tag 上限 23 字符
        val fullMsg = if (t == null) msg else "$msg\n${AndroidLog.getStackTraceString(t)}"

        AndroidLog.println(priority, fullTag, fullMsg)
        writeToFile(priority, fullTag, fullMsg)
    }

    private fun writeToFile(priority: Int, tag: String, msg: String) {
        val w = writer ?: return
        val level = when (priority) {
            AndroidLog.ERROR -> "E"
            AndroidLog.WARN -> "W"
            AndroidLog.INFO -> "I"
            AndroidLog.DEBUG -> "D"
            else -> "V"
        }
        val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date())

        synchronized(lock) {
            runCatching {
                w.write("$time $level/$tag: $msg")
                w.newLine()
                w.flush()
                // 写的过程中超限就重开，防止长会话把文件撑爆
                val f = logFile
                if (f != null && f.length() > MAX_FILE_BYTES) {
                    runCatching { w.close() }
                    f.writeText("")
                    writer = f.bufferedWriter()
                }
            }
        }
    }

    /** 当前日志文件路径，UI 上展示给用户看 */
    fun currentLogPath(): String? = logFile?.absolutePath
}

object LogTags {
    const val AUDIO = "Audio"
    const val ASR = "Asr"
    const val MT = "Mt"
    const val PIPE = "Pipe"
    const val OVERLAY = "Overlay"
    const val DATA = "Data"
    const val SVC = "Svc"
    const val UI = "Ui"
}
