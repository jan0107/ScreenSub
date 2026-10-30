// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import com.screensub.core.common.AppDispatchers
import com.screensub.core.common.AppError
import com.screensub.core.common.AppResult
import com.screensub.core.common.Log
import com.screensub.core.common.LogTags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 音频采集引擎。两条路径共用同一套读取循环：
 *
 * - [startInternal]：AudioPlaybackCapture，48 kHz 原生采集
 * - [startMic]：麦克风外录，同样是 48 kHz 采集
 *
 * 两条路径都以 48 kHz 进入，统一用 [PcmResampler.downsampleBy3] 降到 16 kHz，
 * 这样下游 ASR 只有一条数据通路，不用关心来源。
 *
 * 线程模型：
 * - 读取循环跑在 [AppDispatchers.audio]（单线程、最高优先级）
 * - AudioRecord.read 是阻塞调用，绝不能放在 IO 线程池里
 * - 回调 [onPcm] 在该线程上同步执行，下游必须快速返回或自行转发
 */
class AudioCaptureEngine(private val context: Context) {

    companion object {
        private const val TAG = LogTags.AUDIO
    }

    // ---------------- 回调 ----------------

    /**
     * 每 20 ms 回调一次。
     * @param samples 16 kHz / 单声道 / PCM16 样本，调用方无需拷贝（已独立分配）
     * @param startSampleIndex16k 该块在 16 kHz 时间轴上的起始采样索引。
     *        这是 SRT 时间戳的唯一真源——用采样数换算，不用系统时间。
     */
    var onPcm: ((samples: ShortArray, startSampleIndex16k: Long) -> Unit)? = null

    var onError: ((AppError) -> Unit)? = null

    /** 每帧峰值电平 dBFS，供自检页画电平条 */
    var onPeakLevel: ((Float) -> Unit)? = null

    /** MediaProjection 被系统/用户撤销（Android 15+ 锁屏也会触发） */
    var onProjectionStopped: (() -> Unit)? = null

    // ---------------- 内部状态 ----------------

    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.audio)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var audioRecord: AudioRecord? = null
    private var mediaProjection: MediaProjection? = null
    private var loopJob: Job? = null

    @Volatile
    private var running = false

    /** 已输出的 16 kHz 采样总数，用于时间戳 */
    @Volatile
    var totalSamples16k: Long = 0L
        private set

    @Volatile
    var currentSource: AudioSource? = null
        private set

    val isRunning: Boolean get() = running

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection 已被撤销，停止采集")
            stopInternal()
            onProjectionStopped?.invoke()
        }
    }

    // ---------------- 启动 ----------------

    /** 内部音频（需要 MediaProjection token） */
    fun startInternal(projection: MediaProjection): AppResult<Unit> {
        if (running) return AppResult.Err(AppError.Audio("采集已在进行中"))
        if (!hasRecordPermission()) {
            return AppResult.Err(AppError.Permission("缺少录音权限", Manifest.permission.RECORD_AUDIO))
        }

        return try {
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(AudioConstants.CAPTURE_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()

            // 只有 USAGE_MEDIA / USAGE_GAME / USAGE_UNKNOWN 三种可捕获
            // 注意：addMatchingUsage 与 excludeUsage 互斥，不能同时用
            val captureConfig = AudioPlaybackCaptureConfigurationBuilder.build(projection)

            val record = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes())
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return AppResult.Err(AppError.Audio("AudioRecord 初始化失败（内部音频）"))
            }

            mediaProjection = projection
            projection.registerCallback(projectionCallback, mainHandler)

            startLoop(record, AudioSource.INTERNAL)
            Log.i(TAG, "内录已启动")
            AppResult.Ok(Unit)
        } catch (e: SecurityException) {
            Log.e(TAG, "内录启动被拒绝", e)
            AppResult.Err(AppError.Permission("系统拒绝了录音请求", Manifest.permission.RECORD_AUDIO))
        } catch (t: Throwable) {
            Log.e(TAG, "内录启动失败", t)
            AppResult.Err(AppError.Audio("内录启动失败：${t.message}", t))
        }
    }

    /** 麦克风外录（无需 MediaProjection） */
    fun startMic(): AppResult<Unit> {
        if (running) return AppResult.Err(AppError.Audio("采集已在进行中"))
        if (!hasRecordPermission()) {
            return AppResult.Err(AppError.Permission("缺少录音权限", Manifest.permission.RECORD_AUDIO))
        }

        return try {
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(AudioConstants.CAPTURE_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()

            val record = AudioRecord.Builder()
                // VOICE_RECOGNITION：关闭 AGC 与大部分降噪，保留语音频段细节。
                // 不要用 MIC 或 CAMCORDER——前者带强降噪会削掉辅音，后者方向性太强。
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes())
                .build()

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return AppResult.Err(AppError.Audio("AudioRecord 初始化失败（麦克风）"))
            }

            startLoop(record, AudioSource.MIC)
            Log.i(TAG, "外录已启动")
            AppResult.Ok(Unit)
        } catch (e: SecurityException) {
            Log.e(TAG, "外录启动被拒绝", e)
            AppResult.Err(AppError.Permission("系统拒绝了麦克风请求", Manifest.permission.RECORD_AUDIO))
        } catch (t: Throwable) {
            Log.e(TAG, "外录启动失败", t)
            AppResult.Err(AppError.Audio("外录启动失败：${t.message}", t))
        }
    }

    // ---------------- 读取循环 ----------------

    private fun startLoop(record: AudioRecord, source: AudioSource) {
        currentSource = source
        totalSamples16k = 0L
        running = true

        try {
            record.startRecording()
        } catch (t: Throwable) {
            running = false
            record.release()
            onError?.invoke(AppError.Audio("startRecording 失败：${t.message}", t))
            return
        }

        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            running = false
            record.release()
            onError?.invoke(AppError.Audio("录音状态异常，可能被其他应用占用"))
            return
        }

        audioRecord = record

        val frameSamples = AudioConstants.CAPTURE_FRAME_SAMPLES // 960
        val outCapacity = frameSamples / AudioConstants.DOWNSAMPLE_RATIO + 8

        loopJob = scope.launch {
            val inBuf = ShortArray(frameSamples)
            val outBuf = ShortArray(outCapacity)

            try {
                while (running && isActive) {
                    val n = record.read(inBuf, 0, frameSamples, AudioRecord.READ_BLOCKING)

                    if (n <= 0) {
                        when (n) {
                            AudioRecord.ERROR_DEAD_OBJECT -> {
                                Log.e(TAG, "音频设备断开")
                                onError?.invoke(AppError.Audio("音频设备已断开，请重新开始"))
                                break
                            }

                            AudioRecord.ERROR_INVALID_OPERATION,
                            AudioRecord.ERROR_BAD_VALUE,
                            -> {
                                Log.e(TAG, "读取参数错误 code=$n")
                                onError?.invoke(AppError.Audio("音频读取错误 code=$n"))
                                break
                            }

                            else -> continue // ERROR 或短暂无数据，跳过本帧
                        }
                    }

                    val peak = LevelMeter.peakDbfs(inBuf, n)
                    onPeakLevel?.invoke(peak)

                    val outLen = PcmResampler.downsampleBy3(inBuf, n, outBuf)
                    if (outLen > 0) {
                        val startIndex = totalSamples16k
                        totalSamples16k += outLen
                        // 必须拷贝：outBuf 每帧复用，下游是异步消费的
                        onPcm?.invoke(outBuf.copyOf(outLen), startIndex)
                    }
                }
            } catch (t: Throwable) {
                if (running) {
                    Log.e(TAG, "采集循环异常退出", t)
                    onError?.invoke(AppError.Audio("采集异常：${t.message}", t))
                }
            } finally {
                running = false
                releaseRecordOnly()
            }
        }
    }

    // ---------------- 停止 ----------------

    /** 幂等停止。Service 销毁、用户点停止、投屏撤销都走这里。 */
    fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        val wasRunning = running
        running = false

        loopJob?.cancel()
        loopJob = null

        releaseRecordOnly()

        mediaProjection?.let { projection ->
            try {
                projection.unregisterCallback(projectionCallback)
            } catch (_: Throwable) {
            }
            try {
                projection.stop()
            } catch (_: Throwable) {
            }
        }
        mediaProjection = null
        currentSource = null

        if (wasRunning) Log.i(TAG, "采集已停止，共处理 ${totalSamples16k / 16} ms 音频")
    }

    private fun releaseRecordOnly() {
        val rec = audioRecord
        audioRecord = null
        if (rec == null) return
        try {
            if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                rec.stop()
            }
        } catch (_: Throwable) {
        }
        try {
            rec.release()
        } catch (_: Throwable) {
        }
    }

    // ---------------- 工具 ----------------

    private fun hasRecordPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 缓冲区至少 200 ms。
     * 太小 → 混音器写入时溢出丢帧（表现为字幕偶尔缺词）；
     * 太大 → 首句延迟变高。200 ms 是实测的平衡点。
     */
    private fun bufferBytes(): Int {
        val sr = AudioConstants.CAPTURE_SAMPLE_RATE
        val minBytes = AudioRecord.getMinBufferSize(
            sr,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val wanted = sr * 2 * 200 / 1000 // 48000 * 2 bytes * 0.2 s
        return maxOf(minBytes * 2, wanted)
    }
}

/** 单独抽出来，避免主类里 api-level 判断散落 */
private object AudioPlaybackCaptureConfigurationBuilder {
    fun build(projection: MediaProjection): android.media.AudioPlaybackCaptureConfiguration =
        android.media.AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
}
