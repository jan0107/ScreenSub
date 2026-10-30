// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.app.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.screensub.app.AppContainer
import com.screensub.app.QuotaNotice
import com.screensub.app.R
import com.screensub.app.ScreenSubApp
import com.screensub.app.ui.MainActivity
import com.screensub.core.asr.AsrEngine
import com.screensub.core.asr.ModelCatalog
import com.screensub.core.asr.SourceLanguage
import com.screensub.core.asr.VadSegmenter
import com.screensub.core.audio.AudioCaptureEngine
import com.screensub.core.audio.AudioSource
import com.screensub.core.audio.SilenceProbe
import com.screensub.core.common.AppError
import com.screensub.core.common.AppResult
import com.screensub.core.common.Log
import com.screensub.core.common.LogTags
import com.screensub.core.common.TimeUtils
import com.screensub.core.data.AppSettings
import com.screensub.core.data.EngineNames
import com.screensub.core.data.QuotaConfig
import com.screensub.core.data.QuotaRepository
import com.screensub.core.data.db.CueEntity
import com.screensub.core.mt.ResilientTranslator
import com.screensub.core.overlay.SubtitleOverlay
import com.screensub.core.overlay.SubtitleStyle
import com.screensub.core.pipeline.PipelineState
import com.screensub.core.pipeline.SubtitleCue
import com.screensub.core.pipeline.SubtitlePipeline
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 字幕会话前台服务。
 *
 * ## 为什么必须是 Service 而不是 Activity
 * 用户切到视频 App 后，我们的 Activity 就在后台了。悬浮窗必须在后台持续更新，
 * 音频采集也必须持续——只有前台服务能保证这两点不被系统回收。
 *
 * ## Android 14+ 的强制顺序
 * `startForeground(type=mediaProjection)` **必须早于** `getMediaProjection()`，
 * 否则直接 SecurityException。这是本项目最容易踩的一个坑。
 *
 * ## 降级的处理
 * 内录可能被目标 App 禁止。本服务在启动后观察静音探针，判定被禁就自动重建
 * 整条流水线并切到麦克风。重建而不是热切换，是因为时间轴不能重置。
 */
class CaptureService : LifecycleService() {

    companion object {
        private const val TAG = LogTags.SVC

        const val ACTION_START_INTERNAL = "com.screensub.action.START_INTERNAL"
        const val ACTION_START_MIC = "com.screensub.action.START_MIC"
        const val ACTION_STOP = "com.screensub.action.STOP"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "screensub_session"

        private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
        val state: StateFlow<SessionState> = _state.asStateFlow()

        fun startInternal(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, CaptureService::class.java).apply {
                action = ACTION_START_INTERNAL
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            context.startForegroundService(intent)
        }

        fun startMic(context: Context) {
            val intent = Intent(context, CaptureService::class.java).apply {
                action = ACTION_START_MIC
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, CaptureService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val container: AppContainer
        get() = (application as ScreenSubApp).container

    private lateinit var audioEngine: AudioCaptureEngine
    private lateinit var overlay: SubtitleOverlay
    private var silenceProbe: SilenceProbe? = null

    private var pipeline: SubtitlePipeline? = null
    private var asrEngine: AsrEngine? = null
    private var vadSegmenter: VadSegmenter? = null
    private var translator: ResilientTranslator? = null

    private var watchdogJob: Job? = null
    private var lastCueEndMs: Long = 0L

    /** 最近一次翻译失败原因，供首页状态卡展示 */
    @Volatile
    private var lastTranslationAlert: String? = null

    /** 最近一条额度提示，去重避免同一句话每帧重写 */
    @Volatile
    private var lastQuotaAlert: String? = null

    /** 已经报过"越过免费额度"的引擎，避免重复弹 5 秒提示 */
    private val noLimitNoticeSent = HashSet<String>()

    /** 已经报过 90% 告警的引擎，同一个会话只提醒一次 */
    private val alertedEngines = HashSet<String>()

    /** 当前会话 id，用于把 cue 存进数据库 */
    private var sessionId: Long = 0L

    override fun onCreate() {
        super.onCreate()
        audioEngine = AudioCaptureEngine(this)
        overlay = SubtitleOverlay(this)
        silenceProbe = SilenceProbe(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START_INTERNAL -> handleStartInternal(intent)
            ACTION_START_MIC -> handleStartMic()
            ACTION_STOP -> {
                stopSession()
                stopSelf()
            }

            else -> Log.w(TAG, "未知 action: ${intent?.action}")
        }

        // 不用 START_STICKY：MediaProjection token 是一次性的，
        // 服务被杀后重启也拿不回授权，只会卡在无音频状态。
        return START_NOT_STICKY
    }

    // ---------------- 启动 ----------------

    private fun handleStartInternal(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = IntentCompat.getParcelableExtra(
            intent, EXTRA_RESULT_DATA, Intent::class.java,
        )

        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.e(TAG, "投屏授权失败 resultCode=$resultCode")
            fail("没有获得屏幕捕获授权", canRetry = true)
            stopSelf()
            return
        }

        // 必须先 startForeground 再 getMediaProjection（Android 14+ 硬性要求）
        goForeground(AudioSource.INTERNAL)

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        val projection: MediaProjection? = try {
            projectionManager.getMediaProjection(resultCode, data)
        } catch (t: Throwable) {
            Log.e(TAG, "getMediaProjection 失败", t)
            null
        }

        if (projection == null) {
            fail("无法建立屏幕捕获会话", canRetry = true)
            stopSelf()
            return
        }

        startPipeline(AudioSource.INTERNAL, projection, timeOffsetMs = 0L)
    }

    private fun handleStartMic() {
        goForeground(AudioSource.MIC)
        startPipeline(AudioSource.MIC, projection = null, timeOffsetMs = 0L)
    }

    private fun goForeground(source: AudioSource) {
        val type = if (source == AudioSource.INTERNAL) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification(source),
            type,
        )
    }

    /**
     * 装配并启动整条流水线。
     * @param timeOffsetMs 时间轴偏移。降级重建时传入已产出的时长，保证 SRT 连续。
     */
    private fun startPipeline(
        source: AudioSource,
        projection: MediaProjection?,
        timeOffsetMs: Long,
    ) {
        _state.value = SessionState.Starting
        cleanupPipeline()

        lifecycleScope.launch {
            val settings = container.currentSettings()

            // 1. ASR 引擎
            val engine = container.buildAsrEngine(settings)
            if (engine == null) {
                // 五种语言五个模型，只说"模型未下载"用户不知道该下哪个
                val needed = ModelCatalog.findById(settings.modelId)
                    ?: ModelCatalog.defaultFor(
                        SourceLanguage.fromCode(settings.sourceLanguage),
                    )
                fail(
                    "「${needed.displayName}」还没下载（约 ${needed.approxSizeMb} MB），" +
                        "请在 设置 → 识别 里下载后再开始",
                    canRetry = false,
                )
                stopSelf()
                return@launch
            }

            val asrResult = engine.prepare()
            if (asrResult is AppResult.Err) {
                fail(asrResult.error.message, canRetry = false)
                engine.release()
                stopSelf()
                return@launch
            }

            // 2. VAD
            val vad = container.buildVad()
            if (vad == null) {
                fail("VAD 模型缺失，请重新下载", canRetry = false)
                engine.release()
                stopSelf()
                return@launch
            }

            // 3. 翻译链
            val glossary = container.loadGlossary()
            val mt = container.buildTranslator(settings, glossary)
            val mtPrepared = mt.prepare()
            if (mtPrepared is AppResult.Err) {
                Log.w(TAG, "翻译链无可用引擎，将只显示原文")
                // 开局就没有任何引擎可用 —— 必须马上告诉用户，
                // 否则他会盯着悬浮窗等一集动画
                lastTranslationAlert =
                    (mtPrepared.error as? AppError.Translation)?.message
                        ?: mtPrepared.error.message.ifBlank { "所有翻译引擎都不可用，请更换翻译接口" }
            }

            // 4. 悬浮窗
            if (!overlay.canDrawOverlay()) {
                fail("缺少悬浮窗权限", canRetry = false)
                engine.release()
                vad.release()
                mt.release()
                stopSelf()
                return@launch
            }
            overlay.applyStyle(
                SubtitleStyle(
                    fontSizeSp = settings.subtitleFontSizeSp,
                    textColor = settings.subtitleTextColor,
                    showSourceText = settings.subtitleShowSource,
                    backgroundAlpha = settings.subtitleBackgroundAlpha,
                    maxLines = settings.subtitleMaxLines,
                    bottomMarginDp = settings.subtitleBottomMarginDp,
                    draggable = settings.subtitleDraggable,
                ),
            )
            overlay.show()
            overlay.showAlert(lastTranslationAlert)

            // 5. 音频采集
            silenceProbe?.reset()
            audioEngine.onPeakLevel = { peak ->
                silenceProbe?.feed(peak, com.screensub.core.audio.AudioConstants.FRAME_MS)
            }
            audioEngine.onProjectionStopped = {
                Log.w(TAG, "投屏被撤销，停止会话")
                lifecycleScope.launch {
                    fail("投屏授权被撤销（可能息屏或手动停止），请重新开始", canRetry = true)
                    stopSession()
                    stopSelf()
                }
            }
            audioEngine.onError = { error ->
                Log.e(TAG, "采集错误：${error.message}")
            }

            val audioOutcome = if (projection != null) {
                audioEngine.startInternal(projection)
            } else {
                audioEngine.startMic()
            }

            if (audioOutcome is AppResult.Err) {
                val message = audioOutcome.error.message
                engine.release()
                vad.release()
                mt.release()
                overlay.hide()
                fail(message, canRetry = true)
                stopSelf()
                return@launch
            }

            // 6. 流水线
            val newPipeline = SubtitlePipeline(
                audioEngine = audioEngine,
                asrEngine = engine,
                vadSegmenter = vad,
                translator = mt,
                sourceLanguage = SourceLanguage.fromCode(settings.sourceLanguage),
                startTimeOffsetMs = timeOffsetMs,
            )

            pipeline = newPipeline
            asrEngine = engine
            vadSegmenter = vad
            translator = mt

            // 7. 建会话记录
            sessionId = if (settings.recordHistory) {
                container.database.sessionDao().insert(
                    com.screensub.core.data.db.SessionEntity(
                        startedAt = System.currentTimeMillis(),
                        sourceLanguage = settings.sourceLanguage,
                        modelId = settings.modelId,
                        audioSource = source.name,
                    ),
                )
            } else {
                0L
            }

            // 8. 订阅字幕
            lifecycleScope.launch {
                newPipeline.cues.collect { cue -> handleCue(cue, settings.recordHistory) }
            }
            // 翻译失败告警：走系统通知，因为用户此时人在别的 App 里
            lifecycleScope.launch {
                newPipeline.alerts.collect { alert ->
                    lastTranslationAlert = alert.message
                    overlay.showAlert(alert.message)
                }
            }
            // 额度告警：常驻提示 + 进 App 后弹窗
            // 新会话重新评估，所以先把上轮的记录清掉
            lastQuotaAlert = null
            noLimitNoticeSent.clear()
            alertedEngines.clear()
            lifecycleScope.launch {
                container.quotaRepository.state.collect { snapshot ->
                    handleQuotaState(snapshot, mt, settings)
                }
            }
            lifecycleScope.launch {
                newPipeline.state.collect { pipelineState ->
                    when (pipelineState) {
                        is PipelineState.Running -> {
                            _state.value = SessionState.Running(
                                sourceLabel = source.label,
                                cueCount = pipelineState.cueCount,
                                latencyMs = pipelineState.lastLatencyMs,
                                alert = lastTranslationAlert,
                            )
                        }

                        is PipelineState.Degraded -> {
                            Log.w(TAG, "流水线降级：${pipelineState.reason}")
                        }

                        is PipelineState.Error -> fail(pipelineState.message, canRetry = true)
                        else -> Unit
                    }
                }
            }

            newPipeline.start()
            _state.value = SessionState.Running(source.label, 0L, 0L)

            // 9. 内录禁捕监测
            if (source == AudioSource.INTERNAL && settings.autoFallbackToMic) {
                startSilenceWatchdog()
            }

            Log.i(TAG, "会话已启动，来源=${source.label}")
        }
    }

    // ---------------- 运行时 ----------------

    /**
     * 额度状态变化 → 悬浮窗提示 + 进 App 弹窗。
     *
     * 文案分两种：
     *  - 还有引擎能接管 → 「火山额度已耗尽，已切换到阿里云」（告诉用户谁在干活）
     *  - 一个都没有了   → 「没有可用的翻译引擎」（告诉用户为什么只有原文）
     *
     * "还有没有接管方"必须问 [ResilientTranslator.readyEngineIds]，
     * 而不是问"用户开了哪些引擎" —— 开了但 Key 错/熔断中的引擎接了班也是白接。
     */
    private fun handleQuotaState(
        snapshot: QuotaRepository.UsageSnapshot,
        mt: ResilientTranslator,
        settings: AppSettings,
    ) {
        val cfg = container.quotaRepository.currentConfig()

        // 监测关掉：所有额度提示都撤掉，但统计还在后台继续记
        if (!cfg.quotaMonitorEnabled) {
            if (lastQuotaAlert != null) {
                lastQuotaAlert = null
                overlay.showQuotaAlert(null)
            }
            return
        }

        val blocked = snapshot.engines.values.filter { it.blocked }

        if (blocked.isEmpty()) {
            if (lastQuotaAlert != null) {
                lastQuotaAlert = null
                overlay.showQuotaAlert(null)
            }
        } else {
            val chain = mt.chainEngineIds
            val ready = mt.readyEngineIds.toSet()
            val blockedIds = blocked.map { it.engineId }.toSet()

            val first = blocked.minByOrNull { u ->
                chain.indexOf(u.engineId).let { if (it < 0) Int.MAX_VALUE else it }
            } ?: return

            val idx = chain.indexOf(first.engineId)
            val next = if (idx >= 0) {
                chain.drop(idx + 1).firstOrNull { it in ready && it !in blockedIds }
            } else {
                null
            }

            val blockedName = EngineNames.of(first.engineId)
            val message = if (next != null) {
                "${blockedName}额度已耗尽，已切换到${EngineNames.of(next)}"
            } else {
                "${blockedName}额度已耗尽，没有可用的翻译引擎"
            }

            if (message != lastQuotaAlert) {
                lastQuotaAlert = message
                overlay.showQuotaAlert(message)

                val hasFreeEnabled = settings.googleTranslationEnabled ||
                    settings.myMemoryTranslationEnabled ||
                    settings.offlineTranslationEnabled
                val remaining = (first.quotaChars - first.monthChars).coerceAtLeast(0L)

                container.raiseQuotaNotice(
                    QuotaNotice(
                        title = "${blockedName}额度已耗尽",
                        body = if (next != null) {
                            "现已切换到${EngineNames.of(next)}翻译（本月剩余 ${formatChars(remaining)} 字符）"
                        } else {
                            "没有可用的翻译引擎，字幕只能显示原文"
                        },
                        // 有接管方时不给"启用免费兜底"按钮——那会显得系统在推销
                        canEnableFreeFallback = next == null && !hasFreeEnabled,
                    ),
                )
            }
        }

        // 只提示模式（额度设为 0 = 用户愿意付费）：越过官方免费额度时报一次，5 秒消失
        snapshot.engines.values.forEach { usage ->
            if (usage.engineId in noLimitNoticeSent) return@forEach
            val quota = cfg.engine(usage.engineId)
            if (quota.hasLimit) return@forEach

            val reference = QuotaConfig.DEFAULT_ENGINES[usage.engineId]?.monthlyQuotaChars ?: 0L
            if (reference > 0 && usage.monthChars >= reference) {
                noLimitNoticeSent.add(usage.engineId)
                overlay.showNotice(
                    "${EngineNames.of(usage.engineId)}免费额度已耗尽，已启用预付费额度",
                )
                Log.i(TAG, "${usage.engineId} 已越过免费额度 ${reference}，转入预付费")
            }
        }

        // 告警线（默认 90%）：还没到停用线，但要让用户提前知道
        snapshot.alertThresholdHits.forEach { engineId ->
            if (engineId in alertedEngines) return@forEach
            val usage = snapshot.engine(engineId) ?: return@forEach
            if (usage.blocked) return@forEach

            alertedEngines.add(engineId)
            overlay.showNotice(
                "${EngineNames.of(engineId)}额度已用 " +
                    "${(usage.percent * 100).toInt()}%，即将耗尽",
            )
        }
    }

    /** 12.4 万 / 8,300 这种量级，超过 1 万就用"万" */
    private fun formatChars(chars: Long): String =
        if (chars >= 10_000) String.format("%.1f 万", chars / 10_000.0) else chars.toString()

    private fun handleCue(cue: SubtitleCue, record: Boolean) {
        overlay.updateCue(cue)
        lastCueEndMs = cue.endMs

        // 译文回来了 → 说明翻译已恢复，清掉状态卡上的红字
        if (cue.translatedText.isNotBlank()) {
            lastTranslationAlert = null
            overlay.showAlert(null)
        }

        // 同一条字幕会走两趟：先原文（pendingTranslation=true），译文回来后
        // 用同一 seq 再发一条。只在第二趟落库，避免一句话写两行。
        if (cue.pendingTranslation) return

        if (record && sessionId > 0) {
            lifecycleScope.launch {
                try {
                    container.database.cueDao().insert(
                        CueEntity(
                            sessionId = sessionId,
                            startMs = cue.startMs,
                            endMs = cue.endMs,
                            sourceText = cue.sourceText,
                            translatedText = cue.translatedText,
                            isFinal = cue.isFinal,
                        ),
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "写入字幕记录失败", t)
                }
            }
        }
    }

    /**
     * 内录禁捕监测。
     *
     * 前 5 秒不判定（用户可能还没点播放），之后每 2 秒看一次探针：
     * 连续 3 次判定 BLOCKED 才降级，避免片头纯配乐误伤。
     */
    private fun startSilenceWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = lifecycleScope.launch {
            delay(5_000)

            var blockedStreak = 0
            while (isActive) {
                delay(2_000)

                if (audioEngine.currentSource != AudioSource.INTERNAL) break

                val verdict = silenceProbe?.verdict() ?: break
                when (verdict) {
                    SilenceProbe.Verdict.BLOCKED -> {
                        blockedStreak++
                        Log.w(TAG, "内录疑似被禁（第 $blockedStreak 次）")
                        if (blockedStreak >= 3) {
                            Log.i(TAG, "判定内录被禁止，自动切麦克风外录")
                            fallbackToMic()
                            break
                        }
                    }

                    else -> blockedStreak = 0
                }
            }
        }
    }

    private fun fallbackToMic() {
        val offset = lastCueEndMs
        Log.i(TAG, "降级到外录，时间轴接续于 ${TimeUtils.formatSrtTime(offset)}")

        cleanupPipeline()
        overlay.updateCue(
            SubtitleCue(
                seq = 0,
                startMs = offset,
                endMs = offset,
                sourceText = "",
                translatedText = "已切换到麦克风外录（请外放声音）",
                isFinal = true,
            ),
        )

        lifecycleScope.launch {
            delay(400)
            goForeground(AudioSource.MIC)
            startPipeline(AudioSource.MIC, projection = null, timeOffsetMs = offset)
        }
    }

    // ---------------- 停止 ----------------

    private fun stopSession() {
        watchdogJob?.cancel()
        watchdogJob = null

        cleanupPipeline()
        overlay.hide()
        lastTranslationAlert = null
        lastQuotaAlert = null
        noLimitNoticeSent.clear()
        alertedEngines.clear()

        if (sessionId > 0) {
            lifecycleScope.launch {
                try {
                    container.database.sessionDao().finish(sessionId, System.currentTimeMillis())
                } catch (_: Throwable) {
                }
            }
        }

        _state.value = SessionState.Idle
        Log.i(TAG, "会话已停止")
    }

    private fun cleanupPipeline() {
        try {
            pipeline?.stop()
            pipeline?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "释放流水线失败", t)
        }
        pipeline = null

        try {
            audioEngine.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "停止采集失败", t)
        }

        try {
            asrEngine?.release()
        } catch (_: Throwable) {
        }
        asrEngine = null

        try {
            vadSegmenter?.release()
        } catch (_: Throwable) {
        }
        vadSegmenter = null

        try {
            translator?.release()
        } catch (_: Throwable) {
        }
        translator = null
    }

    override fun onDestroy() {
        cleanupPipeline()
        overlay.hide()
        // 不要把 Failed 状态覆盖掉 —— 用户需要看到"为什么没起来"。
        // 只在正常停止/运行中时才归零。
        if (_state.value !is SessionState.Failed) {
            _state.value = SessionState.Idle
        }
        super.onDestroy()
    }

    // ---------------- 通知 ----------------

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(source: AudioSource): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CaptureService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText("来源：${source.label}")
            .setSmallIcon(R.drawable.ic_notif_session)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.notif_action_stop), stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun fail(message: String, canRetry: Boolean) {
        Log.e(TAG, "会话失败：$message")
        _state.value = SessionState.Failed(message, canRetry)
    }
}

/** 会话状态，UI 直接观察这个。 */
sealed interface SessionState {

    data object Idle : SessionState

    data object Starting : SessionState

    data class Running(
        val sourceLabel: String,
        val cueCount: Long,
        val latencyMs: Long,
        /**
         * 最近的翻译失败原因。不为 null 时首页状态卡会标红显示它，
         * 作为系统通知之外的第二条路——通知被系统折叠或用户划掉时还能看到。
         */
        val alert: String? = null,
    ) : SessionState

    data class Failed(
        val message: String,
        val canRetry: Boolean = true,
    ) : SessionState
}
