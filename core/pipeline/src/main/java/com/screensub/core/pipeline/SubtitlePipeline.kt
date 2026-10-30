// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.pipeline

import android.os.SystemClock
import com.screensub.core.asr.AsrEngine
import com.screensub.core.asr.AsrResult
import com.screensub.core.asr.SourceLanguage
import com.screensub.core.asr.VadSegmenter
import com.screensub.core.audio.AudioCaptureEngine
import com.screensub.core.common.AppDispatchers
import com.screensub.core.common.AppError
import com.screensub.core.common.AppResult
import com.screensub.core.common.Log
import com.screensub.core.common.LogTags
import com.screensub.core.mt.ResilientTranslator
import com.screensub.core.mt.TranslationContext
import com.screensub.core.mt.TranslationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * 字幕流水线总控：把「音频 → VAD → 识别 → 翻译 → 字幕」串起来。
 *
 * ## 两遍法（识别侧）
 *
 * SenseVoice / Whisper 都是非流式模型，必须等一段音频完整才能识别。
 * 但字幕不能等一句话说完才出现，否则画面早过去了。
 *
 * - **第一遍**：VAD 到 3 秒强制切断 → 立刻识别 → 出「暂定字幕」
 * - **第二遍**：等到真正静音（句子结束）→ 把前面所有碎片拼成整句重跑 → 覆盖暂定字幕
 *
 * ## 三线程模型（本类最关键的并发设计）
 *
 * ```
 *  ss-audio   每 20 ms 喂 PCM → VAD → chunkQueue（16 槽，满了丢最旧）
 *  ss-asr     串行消费 chunkQueue：识别 → 立刻发字幕（译文留空）→ 翻译任务入队
 *  ss-mt      串行消费 translateQueue（1 槽，满了丢最旧）：翻译 → 用同一 seq 更新字幕
 * ```
 *
 * **翻译绝对不能和识别放在同一个循环里。** 早期版本就是这么写的，后果是：
 * 一句译文慢 800 ms，后面的识别就整体后移 800 ms，队列越积越长，
 * 端到端延迟单调增长——表现就是"越看越不同步"。现在翻译在旁路，
 * 识别永远不等它；译文迟到只会让那一条字幕多显示一会儿原文。
 *
 * ## 为什么丢弃过期任务
 *
 * translateQueue 只有 1 个槽位且 DROP_OLDEST：新句进来时，还在排队的旧句译文直接被顶掉。
 * 翻译线程拿到任务后还要再校验一次 `job.seq == 当前 cueSeq`，
 * 因为翻译过程中可能已经出了新句——过期的译文绝不能覆盖新句子。
 */
class SubtitlePipeline(
    private val audioEngine: AudioCaptureEngine,
    private val asrEngine: AsrEngine,
    private val vadSegmenter: VadSegmenter,
    private val translator: ResilientTranslator,
    private val sourceLanguage: SourceLanguage = SourceLanguage.AUTO,
    /**
     * 时间轴偏移（毫秒）。
     * 内录被禁降级到外录时会重建流水线，新流水线的采样计数从 0 开始，
     * 必须把这个偏移加回去，否则 SRT 时间戳会突然跳回 0。
     */
    private val startTimeOffsetMs: Long = 0L,
) {

    companion object {
        private const val TAG = LogTags.PIPE

        /** 识别队列容量。超过就丢最旧的（丢的通常是暂定结果，可接受） */
        private const val QUEUE_CAPACITY = 16

        /**
         * 暂定字幕的翻译期限。
         * 暂定结果几秒后就会被整句覆盖，为它多等没有意义——
         * 超时直接放弃，宁可这几秒显示原文。
         */
        private const val PROVISIONAL_DEADLINE_MS = 900L

        /**
         * 一条字幕最多为了等译文压多久。
         *
         * 上屏是**按句序排队**的：译文没回来就先不发下一条。
         * 语速快的时候，等超过这个时限就放弃译文直接上原文，
         * 否则延迟会越积越多。所以极限情况下仍可能看到原文，但不会卡住。
         *
         * ## 为什么是 2000ms（原 1400ms）
         *
         * 1400ms 是按"谷歌/ML Kit 300~700ms"定的。但主力引擎换成 LLM 之后
         * 这个前提不成立了：关掉思考的 deepseek-flash 实测 0.77~1.36 秒
         * （首句含 TLS 握手 1359ms，稳态 ~1000ms），1400ms 只剩 41ms 余量，
         * 等于每句都在赌——只要抖一下就以原文上屏，再靠补屏跳成中文，
         * 观感上是"先英文后中文"的闪烁。
         *
         * 窗口必须匹配主力引擎的固有延迟，而不是比它短。
         * 上限仍受 [MAX_BUFFER] 约束（队列超 3 条直接放行最早的），
         * 所以调大不会导致延迟无限累积。
         */
        private const val MAX_HOLD_MS = 2_000L

        /** 排队上限。超过就不等了，把最早的放出去 */
        private const val MAX_BUFFER = 6

        /**
         * 一条字幕的阅读时间：底 600ms + 每字 45ms，夹在 900~2200ms 之间。
         *
         * 为什么按字数而不是固定值：一句「嗯。」和一句「如果这是你第一次来这里，
         * 不要忘记点击下面的订阅按钮」需要的时间差三倍。固定 900ms 会让长句
         * 还没读完就被顶掉，而短句又白白占着屏幕。
         *
         * 为什么需要这个下限：一次识别会产出多条字幕（一句的多个分句，或两三句话），
         * 它们是**同一毫秒**进来的。之前整批一次性发出去，第一条只活 2 毫秒——
         * 日志里有译文、屏幕上一闪而过，用户原话是"第一行会快速掠过"。
         */
        private const val DWELL_BASE_MS = 600L
        private const val DWELL_PER_CHAR_MS = 45L
        private const val DWELL_MIN_MS = 900L
        private const val DWELL_MAX_MS = 2_200L

        /**
         * 同时翻译几句。
         *
         * 1 路的问题：上屏是按句序排队的，前一句没翻完后面的都得等。
         * 单句 0.8~1.3 秒，而真人快语速能到 1 秒 1 句，队列只会越积越长，
         * 表现为"开头还能对上，几分钟后字幕已经落后十几秒"。
         * 2 路够用：吞吐翻倍，成本仍可忽略。
         */
        private const val MT_CONCURRENCY = 2

        /**
         * 一句最多攒多长音频就必须收口。
         *
         * 有背景音乐的片源里，音乐energy 会让 VAD 一直判"还在说话"，
         * 迟迟不出现静音点 → pendingSamples 一路堆到几十秒，
         * 最后整句识别时一次性喂 30 秒音频，ASR 要跑好几秒，
         * 表现就是"每隔半分钟卡一次、字幕越拖越远"。
         * 超过 8 秒就强制收口当整句处理，把单次识别耗时锁在可控范围。
         */
        private const val MAX_PENDING_MS = 6_500L

        /**
         * 整句最多攒这么久，超了就强制翻译。
         *
         * 7 秒的依据：正常一句话 3~5 秒，7 秒足够覆盖绝大多数整句；
         * 再长就是"一口气念一大段"，等下去用户会以为字幕坏了。
         * 截断点落在能量低谷（词间），所以即使切开也是两个像样的分句。
         */
        private const val ACC_MAX_MS = 8_500L
    }

    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.default)

    /** VAD 切出的语音段。audio 线程 trySend，asr 线程串行消费 */
    private val chunkQueue = Channel<VadSegmenter.SpeechChunk>(
        capacity = QUEUE_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 待翻译任务。asr 线程 trySend，mt 线程串行消费 */
    private val translateQueue = Channel<TranslateJob>(
        // 4 而不是 1：容量太小会在语速快时把还没上屏的句子直接丢掉，
        // 表现为"这句只有原文"。真正的兜底是 MAX_HOLD_MS。
        capacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * 上屏队列（按 seq 升序）。
     *
     * 识别一有结果就塞进来，但**只有译文到位（或等超时）才真正发给 overlay**。
     * 这样字幕永远按句序出现、而且出现时就是中文——
     * 早期版本"先发原文、译文补一条"的做法在语速快时会连续出现只有原文的句子。
     */
    private class BufferItem(val cue: SubtitleCue, val addedAt: Long)

    private val displayQueue = ArrayList<BufferItem>(MAX_BUFFER + 1)

    private data class TranslateJob(
        /** 一批里每条字幕的 seq，与 [texts] 一一对应 */
        val seqs: List<Long>,
        val texts: List<String>,
        val isFinal: Boolean,
        val prevSourceText: String?,
        val prevTranslatedText: String?,
    )

    private val _cues = MutableSharedFlow<SubtitleCue>(
        replay = 1,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 字幕流。overlay 订阅它更新界面。同一 seq 可能收到两条（先原文、后译文） */
    val cues: SharedFlow<SubtitleCue> = _cues.asSharedFlow()

    /**
     * 翻译失败告警。
     *
     * 不复用 [PipelineState.Degraded]：那条状态会在下一句到来时被立刻改回 Running，
     * StateFlow 又是 conflated 的，订阅方很可能一次都看不到。
     * 告警是事件，不是状态，用 SharedFlow 单独发。
     */
    private val _alerts = MutableSharedFlow<AppError.Translation>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val alerts: SharedFlow<AppError.Translation> = _alerts.asSharedFlow()

    private val _state = MutableStateFlow<PipelineState>(PipelineState.Idle)
    val state: StateFlow<PipelineState> = _state.asStateFlow()

    /** 同一句话被 3 秒切成的多个碎片 */
    private val pendingSamples = ArrayList<FloatArray>(4)
    private var pendingStartMs = 0L


    /**
     * 队列中「整句」的最远结束采样位置。
     * 用来判断一个暂定碎片是否已经过时：如果后面已经排了整句，
     * 这片暂定结果刚识别完就会被覆盖，花了 ASR 时间反而拖慢整句。
     */
    @Volatile
    private var lastFinalEndSampleIndex: Long = -1L

    private val cueSeq = AtomicLong(0)

    @Volatile
    private var lastCue: SubtitleCue? = null

    /**
     * 最近一条**真正发到 overlay** 的字幕。
     *
     * 和 [lastCue] 不是一回事：lastCue 是"最近识别出的"，这条是"屏幕上正在显示的"。
     * 译文迟到时靠它判断屏幕上还是不是这句——是就把中文补上去，不是就别瞎改。
     */
    @Volatile
    private var lastEmittedCue: SubtitleCue? = null

    /** 上一次真正上屏的时刻，用来保证每条字幕的停留时间 */
    private var lastEmitAt: Long = 0L

    /** 会话开始的墙钟时间，用来算"落后实时多少毫秒" */
    private var sessionWallStartMs = 0L

    @Volatile
    private var running = false

    private var asrWorkerJob: Job? = null
    private var translateWorkerJob: Job? = null

    /** 定时检查上屏队列的超时条目——没有新事件时也要能放行 */
    private var sweeperJob: Job? = null

    /** 最近几条例句的译文，只用于给下一句做上下文（LLM 靠它保持人称/译名一致） */
    private val translationCache = HashMap<Long, String>(8)

    /**
     * 句级译文缓存：**原文句子 → 译文**。
     *
     * 为什么需要：VAD 在连续说话时会先出一段"碎片"（final=false），几秒后再出包含它的
     * "整句"（final=true）。两段文本常常**一模一样**，却各翻了一遍——既多花 400ms，
     * 又让译文闪变一次。
     *
     * 有了这张表，整句到达时逐句查表：命中的立刻上屏（0ms），只把真正没翻过的送去批量。
     * 同一句原文的译文本来就该一样，复用不降质量，只省时间和额度。
     *
     * 容量 32：够覆盖"碎片 + 整句"的回溯窗口（一句到下一句之间最多十来秒）。
     */
    private val sentenceCache = HashMap<String, String>(32)

    // ---------------- 生命周期 ----------------

    /**
     * 开始流水线。调用前必须已完成：
     *  - asrEngine.prepare()
     *  - translator.prepare()
     *  - audioEngine 已 start（内录或外录）
     */
    fun start() {
        if (running) {
            Log.w(TAG, "流水线已在运行")
            return
        }
        running = true
        pendingSamples.clear()
        cueSeq.set(0L)
        lastCue = null
        lastFinalEndSampleIndex = -1L
        sessionWallStartMs = SystemClock.elapsedRealtime()

        _state.value = PipelineState.Running(
            sourceLabel = audioEngine.currentSource?.label ?: "未知",
        )

        // 接管音频回调。这个回调在 audio 单线程上跑，必须极快返回。
        audioEngine.onPcm = { samples, startIndex ->
            handlePcm(samples, startIndex)
        }

        asrWorkerJob = scope.launch { consumeLoop() }
        // 并发 2 路消费翻译队列。
        // 单路时一句翻 0.8~1.3 秒，下一句只能干等；连续快语速时队列越积越长，
        // 字幕就越拖越远。多开一路能让吞吐翻倍，把"排队延迟"这块削掉。
        // 代价只是同时多发一个 API 请求（DeepSeek flash 一集才几分钱）。
        translateWorkerJob = scope.launch {
            repeat(MT_CONCURRENCY) {
                launch { translateLoop() }
            }
        }
        sweeperJob = scope.launch {
            while (running) {
                kotlinx.coroutines.delay(120)
                flushDisplay()
            }
        }

        Log.i(TAG, "流水线已启动，来源=${audioEngine.currentSource}")
    }

    fun stop() {
        if (!running) return
        running = false
        audioEngine.onPcm = null

        // 把尾音吐出来处理掉，避免最后一句丢失
        try {
            val tail = vadSegmenter.flush()
            tail.forEach { chunkQueue.trySend(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "flush VAD 失败", t)
        }

        asrWorkerJob?.cancel()
        asrWorkerJob = null
        translateWorkerJob?.cancel()
        translateWorkerJob = null
        sweeperJob?.cancel()
        sweeperJob = null

        synchronized(displayQueue) { displayQueue.clear() }
        pendingSamples.clear()
        lastEmittedCue = null
        lastEmitAt = 0L
        _state.value = PipelineState.Idle
        Log.i(TAG, "流水线已停止，共 ${cueSeq.get()} 条字幕")
    }

    fun release() {
        stop()
        vadSegmenter.release()
        asrEngine.release()
        translator.release()
        chunkQueue.close()
        translateQueue.close()
    }

    // ---------------- 音频进来 ----------------

    private fun handlePcm(samples: ShortArray, startSampleIndex: Long) {
        if (!running) return
        try {
            // VAD 处理 320 个样本约 0.3ms，放 audio 线程可以接受
            val chunks = vadSegmenter.accept(samples, samples.size)
            for (chunk in chunks) {
                // 记录"后面已有整句"——processChunk 会据此跳过更早的暂定碎片
                if (!chunk.isForcedCut) {
                    lastFinalEndSampleIndex = chunk.startSampleIndex + chunk.samples.size
                }
                val sent = chunkQueue.trySend(chunk)
                if (sent.isFailure) {
                    Log.w(TAG, "识别队列溢出，丢弃一段 ${chunk.durationMs}ms")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "VAD 处理异常", t)
        }
    }

    // ---------------- 上屏队列 ----------------

    /** 识别出结果 → 排队等译文 */
    /**
     * 一条字幕该停留多久。
     *
     * 按**上一条**的字数算——决定"什么时候可以换下一条"的是屏幕上正在显示的那条，
     * 而不是即将上屏的那条。
     */
    private fun dwellMsFor(text: String): Long =
        (DWELL_BASE_MS + DWELL_PER_CHAR_MS * text.length)
            .coerceIn(DWELL_MIN_MS, DWELL_MAX_MS)

    private fun enqueueDisplay(cue: SubtitleCue) {
        synchronized(displayQueue) {
            // 整句到了：还没上屏的暂定碎片直接作废，它们马上就被这句覆盖
            if (cue.isFinal) {
                displayQueue.removeAll { !it.cue.isFinal }
            }
            displayQueue.add(BufferItem(cue, SystemClock.elapsedRealtime()))
        }
        flushDisplay()
    }

    /** 译文回来 → 回填对应条目；已经上屏的则直接补屏 */
    private fun resolveTranslation(seq: Long, translated: String?, mtMs: Long) {
        val text = translated.orEmpty()

        // 译文入缓存（给下一句做上下文），无论这句还在不在队列里都要存
        if (text.isNotBlank()) {
            synchronized(translationCache) {
                translationCache[seq] = text
                if (translationCache.size > 8) {
                    translationCache.keys.firstOrNull()?.let { translationCache.remove(it) }
                }
            }
        }

        var lateCue: SubtitleCue? = null

        synchronized(displayQueue) {
            val index = displayQueue.indexOfFirst { it.cue.seq == seq }

            if (index >= 0) {
                val item = displayQueue[index]
                val base = item.cue
                displayQueue[index] = BufferItem(
                    base.copy(
                        translatedText = text,
                        pendingTranslation = false,
                        latencyMs = base.latencyMs + mtMs,
                    ),
                    item.addedAt,
                )
            } else if (text.isNotBlank()) {
                // 条目已经不在队列里 = 它因为超过 MAX_HOLD_MS 被放行、以原文上屏了。
                //
                // 早期版本这里直接 return，注释写的是"译文来晚了也没用"——这是错的。
                // 在线引擎（尤其 LLM）动辄 0.8~3 秒，超过上屏窗口几乎是常态，
                // 于是**每一句译文都被丢掉**，屏幕上永远只有原文。
                // 只要屏幕上显示的还是这一句（seq 没被下一句顶掉），就把中文补上去。
                val shown = lastEmittedCue
                if (shown != null && shown.seq == seq) {
                    lateCue = shown.copy(
                        translatedText = text,
                        pendingTranslation = false,
                        latencyMs = shown.latencyMs + mtMs,
                    )
                }
            }
        }

        val patch = lateCue
        if (patch != null) {
            Log.d(TAG, "译文迟到，补屏 #$seq（翻译耗时 ${mtMs}ms）")
            lastEmittedCue = patch
            _cues.tryEmit(patch)
        }

        flushDisplay()
    }

    /** 把队首能放行的条目依次发给 overlay */
    private fun flushDisplay() {
        val now = SystemClock.elapsedRealtime()
        val cue: SubtitleCue = synchronized(displayQueue) {
            if (displayQueue.isEmpty()) return

            val head = displayQueue.first()
            val resolved = !head.cue.pendingTranslation
            val expired = now - head.addedAt >= MAX_HOLD_MS
            val overflow = displayQueue.size > MAX_BUFFER

            // 还没轮到它：没译文、也没等超时
            if (!resolved && !expired && !overflow) return

            // 同一条字幕补译文 → 原地刷新，不受停留时间限制
            val sameCue = lastEmittedCue?.seq == head.cue.seq
            if (!sameCue) {
                // 积压越多切得越快，但不能为了"看得清"让字幕落在语音后面太远。
                // 分档而不是一刀切 0ms：一刀切会让快语速时整批字幕一闪而过，
                // 用户看到的就是"三行同时弹出来，第一行掠过"。
                val backlog = displayQueue.size
                val base = dwellMsFor(lastEmittedCue?.sourceText.orEmpty())
                val minGap = when {
                    backlog > 10 -> 0L
                    backlog > 6 -> base / 3
                    backlog > 3 -> (base * 2 / 3).coerceAtLeast(450L)
                    else -> base
                }
                if (now - lastEmitAt < minGap && !(overflow && backlog > 10)) return
            }

            displayQueue.removeAt(0)
            head.cue
        }

        lastEmitAt = now
        lastEmittedCue = cue
        _cues.tryEmit(cue)
    }

    // ---------------- 识别：串行消费 ----------------

    private suspend fun consumeLoop() {
        for (chunk in chunkQueue) {
            if (!running) break
            try {
                processChunk(chunk)
            } catch (t: Throwable) {
                Log.e(TAG, "处理语音段失败", t)
            }
        }
    }

    private suspend fun processChunk(chunk: VadSegmenter.SpeechChunk) {
        val chunkStartMs = samplesToMs(chunk.startSampleIndex)

        // **在音频层攒整句**，而不是"识别完再拼文本"。
        //
        // 这一步是关键，试错过：X-ASR 对每一段音频都会在末尾补一个句号
        // （它看到的是"一句话结束了"），所以先切碎再识别，得到的文本永远
        // "看起来完整"——拿它判断半句、拿它拼句都会被骗。
        // 只有让 ASR 一次看到完整句子，标点才是真的，译文才读得通
        // （参考译文那句 155 字符的长句就是这么来的）。
        //
        // VadSegmenter 的 4.2 秒硬切只是"说太久先收一下"，不是识别单位。
        if (pendingSamples.isEmpty()) pendingStartMs = chunkStartMs
        pendingSamples.add(chunk.samples)

        val pendingMs = totalPendingSamples() * 1000L / 16_000L
        val said = !chunk.isForcedCut        // 静音 450ms 自然收口 = 这句话说完了
        val tooLong = pendingMs >= ACC_MAX_MS

        if (!said && !tooLong) {
            Log.v(TAG, "整句累积 ${pendingMs}ms，等说完再识别")
            return
        }
        if (tooLong && !said) {
            Log.d(TAG, "整句攒到 ${pendingMs}ms，不等了，先识别")
        }

        val audio: FloatArray = concatPending()
        val audioStartMs: Long = pendingStartMs
        pendingSamples.clear()
        val isFinal = true

        val asrStarted = SystemClock.elapsedRealtime()
        val asrOutcome = asrEngine.transcribe(audio, audioStartMs, isFinal)
        val asrMs = SystemClock.elapsedRealtime() - asrStarted

        val asrResult: AsrResult = when (asrOutcome) {
            is AppResult.Ok -> asrOutcome.value
            is AppResult.Err -> {
                Log.w(TAG, "识别失败（${asrMs}ms）：${asrOutcome.error.message}")
                if (asrOutcome.error is AppError.ModelNotReady) {
                    _state.value = PipelineState.Error(asrOutcome.error.message)
                }
                return
            }
        }

        if (asrResult.isEmpty) return

        // 落后实时多少毫秒：现在的时间 - 音频应有的时间
        val audioEndMs = asrResult.endMs - startTimeOffsetMs
        val lagMs = (SystemClock.elapsedRealtime() - sessionWallStartMs) - audioEndMs

        // 按标点切句：一次识别常常包含 2~3 句（连续说话时只能按上限硬切），
        // 整段塞给用户就是"两句话挤一行"。ASR 输出自带标点，正好拿来当句子边界。
        //
        // 音频已经是整句了，直接按标点切成多条字幕（一条一句）
        val sentences = SentenceSplitter.split(asrResult.text)
            .ifEmpty { listOf(asrResult.text) }

        val textStartMs = asrResult.startMs
        val totalChars = sentences.sumOf { it.length }.coerceAtLeast(1)
        val spanMs = (asrResult.endMs - textStartMs).coerceAtLeast(0L)

        val previous = lastCue
        var prevSrc = previous?.sourceText
        var prevTrans = previous?.let { prev ->
            synchronized(translationCache) { translationCache[prev.seq] }
        }

        val seqs = ArrayList<Long>(sentences.size)
        // 没命中缓存的句子才需要真正发请求
        val missSeqs = ArrayList<Long>(sentences.size)
        val missTexts = ArrayList<String>(sentences.size)
        var reused = 0
        var consumed = 0

        for (sentence in sentences) {
            val seq = cueSeq.incrementAndGet()

            // 时间轴按字符数比例分配：整段的音频区间被这几句均摊，
            // 这样"上一句/当前句"的切换时机和说话进度大致对得上。
            val startFrac = consumed.toDouble() / totalChars
            consumed += sentence.length
            val endFrac = consumed.toDouble() / totalChars
            val cueStart = textStartMs + (spanMs * startFrac).toLong()
            val cueEnd = textStartMs + (spanMs * endFrac).toLong()

            // 先发字幕（译文留空 → overlay 自动回退显示原文）
            val cue = SubtitleCue(
                seq = seq,
                startMs = cueStart,
                endMs = cueEnd,
                sourceText = sentence,
                translatedText = "",
                isFinal = isFinal,
                latencyMs = lagMs,
                pendingTranslation = true,
            )
            lastCue = cue
            enqueueDisplay(cue)
            seqs.add(seq)

            // 碎片阶段翻过的句子直接复用译文，立即可见（0ms），不再重复请求
            val cached = synchronized(sentenceCache) { sentenceCache[sentence] }
            if (cached != null) {
                reused++
                resolveTranslation(seq, cached, 0L)
            } else {
                missSeqs.add(seq)
                missTexts.add(sentence)
            }

            prevSrc = sentence
            prevTrans = null
        }

        // 计数器无条件恢复成 Running：翻译失败留下的 Degraded 不应该让字幕条数冻住
        _state.value = PipelineState.Running(
            sourceLabel = audioEngine.currentSource?.label ?: "未知",
            lastLatencyMs = lagMs,
            cueCount = cueSeq.get(),
        )

        // 一次识别 = 一个批量翻译任务：N 句压成一次请求，
        // 请求数不变、延迟一次往返，结果按顺序回填（见 ResilientTranslator.translateBatch）
        if (missSeqs.isNotEmpty()) translateQueue.trySend(
            TranslateJob(
                seqs = missSeqs,
                texts = missTexts,
                isFinal = isFinal,
                prevSourceText = previous?.sourceText,
                prevTranslatedText = previous?.let { prev ->
                    synchronized(translationCache) { translationCache[prev.seq] }
                },
            ),
        )

        Log.d(
            TAG,
            "字幕 ${seqs.size} 条 切出 ${sentences.size} 句 " +
                "复用译文 $reused 句 待翻 ${missSeqs.size} 句 final=$isFinal " +
                "识别 ${asrMs}ms 落后 ${lagMs}ms 「${asrResult.text.take(200)}」",
        )
    }

    // ---------------- 翻译：旁路串行消费 ----------------

    private suspend fun translateLoop() {
        for (job in translateQueue) {
            if (!running) break
            try {
                runTranslation(job)
            } catch (t: Throwable) {
                Log.e(TAG, "翻译任务异常", t)
            }
        }
    }

    private suspend fun runTranslation(job: TranslateJob) {
        val context = TranslationContext(
            sourceCode = sourceLanguage.code,
            previousSourceText = job.prevSourceText,
            previousTranslatedText = job.prevTranslatedText,
        )

        val started = SystemClock.elapsedRealtime()
        // 整批走一次 translateBatch：引擎内部会压成一次 HTTP 请求
        val outcomes: List<AppResult<TranslationResult>>? = if (job.isFinal) {
            translator.translateBatch(job.texts, context)
        } else {
            // 暂定结果只给很短的机会，超时就让它按原文上屏
            withTimeoutOrNull(PROVISIONAL_DEADLINE_MS) {
                translator.translateBatch(job.texts, context)
            }
        }
        val mtMs = SystemClock.elapsedRealtime() - started

        if (outcomes == null) {
            Log.v(TAG, "暂定翻译超时（${mtMs}ms），整批按原文上屏 ${job.seqs}")
            job.seqs.forEach { resolveTranslation(it, null, mtMs) }
            return
        }

        var firstError: AppError? = null
        var okCount = 0

        outcomes.forEachIndexed { index, outcome ->
            val seq = job.seqs.getOrNull(index) ?: return@forEachIndexed
            when (outcome) {
                is AppResult.Ok -> {
                    okCount++
                    val translated = outcome.value.text
                    Log.d(TAG, "译文 #$seq 用时 ${mtMs}ms「${translated.take(120)}」")
                    // 写回句级缓存：下一次同样的原文（碎片→整句）就能直接命中
                    val source = job.texts.getOrNull(index)
                    if (!source.isNullOrBlank() && translated.isNotBlank()) {
                        synchronized(sentenceCache) {
                            sentenceCache[source] = translated
                            if (sentenceCache.size > 32) {
                                sentenceCache.keys.firstOrNull()?.let { sentenceCache.remove(it) }
                            }
                        }
                    }
                    resolveTranslation(seq, translated, mtMs)
                }

                is AppResult.Err -> {
                    // 约定：翻译失败也必须有输出，这条按原文上屏
                    resolveTranslation(seq, null, mtMs)
                    if (firstError == null) firstError = outcome.error
                }
            }
        }

        // 全批失败才告警/降级；部分成功不打扰用户（缺的那句显示原文已是兜底）
        if (okCount == 0 && job.isFinal) {
            val error = firstError ?: AppError.Engine("翻译失败")
            Log.w(TAG, "翻译失败 ${job.seqs}（${mtMs}ms）：${error.message}")
            _alerts.tryEmit(
                (error as? AppError.Translation) ?: AppError.Translation(error.message),
            )
            if (_state.value is PipelineState.Running) {
                _state.value = PipelineState.Degraded(
                    reason = error.message,
                    stillRunning = true,
                )
            }
        }
    }

    // ---------------- 续句拼接 ----------------

    /**
     * 把续句前缀和本段文本拼起来。
     *
     * 难点是**重叠**：VAD 会把同一句话先出一遍碎片、再出一遍整句，
     * 直接首尾相接就会得到 "…faster, uh, and that means that cars can go a lot
     * faster, uh and it's just easier…" 这种重复两遍的文本。
     *
     * 所以先做三层判断：整段包含 → 直接取新的；词级尾部重叠 → 去掉重复部分；
     * 都没有重叠（正常续句）→ 直接拼。
     */
    private fun mergeContinuation(carry: String, next: String): String {
        val a = carry.trim()
        val b = next.trim()
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        if (b.length >= a.length && b.startsWith(a, ignoreCase = true)) return b

        val aw = a.split(" ")
        val bw = b.split(" ")
        val maxK = minOf(6, aw.size, bw.size)
        for (k in maxK downTo 1) {
            val left = aw.takeLast(k).joinToString(" ").lowercase().trim(',', '.', '?', '!')
            val right = bw.take(k).joinToString(" ").lowercase().trim(',', '.', '?', '!')
            if (left == right) {
                return (aw + bw.drop(k)).joinToString(" ")
            }
        }
        return SentenceSplitter.join(a, b)
    }

    // ---------------- 采样与缓冲工具 ----------------

    /** 采样位置 → 毫秒（16kHz 单声道） */
    private fun samplesToMs(samples: Long): Long = samples * 1000L / 16_000L

    /** 待收口的音频总采样数 */
    private fun totalPendingSamples(): Int = pendingSamples.sumOf { it.size }

    /**
     * 把待收口的碎片拼成一整段，交给识别引擎。
     *
     * 连续说话时 VAD 会切出多个 3 秒碎片，它们必须**一起**识别才能拿到完整句子；
     * 分开识别再拼接，会在半句处断字（"the small" / "bell icon" 就是这么被劈开的）。
     */
    private fun concatPending(): FloatArray {
        val total = totalPendingSamples()
        val out = FloatArray(total)
        var offset = 0
        for (part in pendingSamples) {
            part.copyInto(out, offset)
            offset += part.size
        }
        return out
    }
}
