// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * 全局调度器。
 *
 * 关键约束：
 * - [audio] 必须是单线程且高优先级：AudioRecord.read 是阻塞调用，
 *   放到 Dispatchers.IO 上会和网络请求抢线程池，表现为掉帧/丢音。
 * - [asr] 必须是独立单线程：sherpa-onnx 的 recognizer 不是线程安全的，
 *   且推理会占满 CPU，绝不能和音频采集共用一个线程。
 */
object AppDispatchers {

    val main: CoroutineDispatcher get() = Dispatchers.Main
    val io: CoroutineDispatcher get() = Dispatchers.IO
    val default: CoroutineDispatcher get() = Dispatchers.Default

    /** 音频采集专用：单线程 + 最高优先级 */
    val audio: CoroutineDispatcher by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "ss-audio").apply { priority = Thread.MAX_PRIORITY }
        }.asCoroutineDispatcher()
    }

    /** 语音识别专用：单线程，隔离推理负载 */
    val asr: CoroutineDispatcher by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "ss-asr").apply { priority = Thread.NORM_PRIORITY + 1 }
        }.asCoroutineDispatcher()
    }

    /** 翻译专用：允许并发（在线引擎可能同时在等网络） */
    val translate: CoroutineDispatcher by lazy {
        Executors.newFixedThreadPool(2) { r ->
            Thread(r, "ss-mt")
        }.asCoroutineDispatcher()
    }
}
