// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.asr

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.screensub.core.common.Log
import com.screensub.core.common.LogTags
import com.screensub.core.common.TimeUtils

/**
 * 把 sherpa-onnx 的 Silero VAD 封装成"喂 PCM，吐可以上屏的语音段"。
 *
 * ## 为什么不用 VAD 自己的分段
 *
 * 实测（2026-10-29 日志）：连续说话 10.4 秒时，VAD 会**攒满一整段才吐出来**，
 * `maxSpeechDuration = 6.5` 并不生效（吐过 10.4s、11.9s、13.0s 的段）。
 * 后果是用户从开始说话到看见字幕要等十几秒——这是"出字幕慢"的真正来源，
 * 识别本身只要 220ms，翻译 320ms。
 *
 * 所以这里改成**自己管缓冲**：VAD 只用来回答"现在有没有人在说话"，
 * 段的起止、切点全部由本类决定：
 *
 * - 静音累计 450ms → 一句话说完了，整段交出（最自然，延迟最低）
 * - 连续说话到 4.2 秒 → 在**能量最低处**主动切一刀先上屏（不等说完）
 *
 * 顺带修掉一个隐患：原实现把 `segment.start`（sherpa 的采样索引）当作时间基准，
 * 多片切分时还会混入 ms 与采样两种单位，导致下游算出的"落后 xxx ms"不可信。
 * 现在时间基准是本类自己的 [fedSamples]，单调且单位统一（采样）。
 */
class VadSegmenter(vadModelPath: String) {

    companion object {
        private const val TAG = LogTags.ASR
        private const val SAMPLE_RATE = 16_000

        /**
         * 单段最长时长。到点就主动切。
         *
         * 4.2 秒的依据：正常语速一句话 3~6 秒，4.2 能在"一句话说完"和"等太久"
         * 之间取平衡；被切开的尾巴由 pipeline 的续句拼接补回完整语义，
         * 所以切短不会让译文变残。
         */
        const val MAX_SPEECH_SECONDS = 4.2f

        private const val MAX_SPEECH_SAMPLES = (MAX_SPEECH_SECONDS * SAMPLE_RATE).toInt()

        /** 连续静音多久算一句说完（比 VAD 的 0.4s 略松，避免喘息被当成句尾） */
        private const val END_SILENCE_MS = 450
        private const val END_SILENCE_SAMPLES = END_SILENCE_MS * SAMPLE_RATE / 1000

        /** 段首补一点前导音频，避免吞掉首字（纯静音，不会被识别成内容） */
        private const val PRE_PAD_MS = 200
        private const val PRE_PAD_FRAMES = PRE_PAD_MS / 20

        /** 段尾保留一点余音，避免尾音被切掉 */
        private const val TAIL_PAD_SAMPLES = 180 * SAMPLE_RATE / 1000

        /** 短于这个长度的段直接丢弃（噪声、键盘声） */
        private const val MIN_SEGMENT_SAMPLES = 120 * SAMPLE_RATE / 1000

        /** 切点搜索范围：在目标位置前后找能量低谷 */
        private const val SEARCH_BACK_SAMPLES = 900 * SAMPLE_RATE / 1000
        private const val SEARCH_FWD_SAMPLES = 250 * SAMPLE_RATE / 1000
    }

    /** 一段可以上屏的语音 */
    data class SpeechChunk(
        /** 归一化 Float 样本 */
        val samples: FloatArray,
        /** 在 16 kHz 时间轴上的起始采样索引（全局，从会话开始算） */
        val startSampleIndex: Long,
        /** 是否因达到最长时长被切开（尾巴可能不完整，下游需要做续句拼接） */
        val isForcedCut: Boolean,
    ) {
        val durationMs: Long
            get() = TimeUtils.samplesToMs(samples.size, SAMPLE_RATE)
    }

    private val vad: Vad = Vad(
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = vadModelPath,
                threshold = 0.45f,
                // 0.4 是折中：能抓到句间停顿，又不至于被换气打断。
                // 注意这个值现在只影响"有没有人说话"的判断，
                // 真正的句尾由本类的 END_SILENCE_MS 决定。
                minSilenceDuration = 0.40f,
                minSpeechDuration = 0.20f,
                windowSize = 512,
                maxSpeechDuration = MAX_SPEECH_SECONDS,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
            provider = "cpu",
            debug = false,
        ),
    )

    /** 累计喂入的采样数（全局时间轴，单调递增） */
    private var fedSamples: Long = 0L

    /** 当前这句话的所有帧（含前导与尾部静音） */
    private val utterance = ArrayList<FloatArray>(256)

    /** 当前这段的起始采样索引；-1 表示还没开始 */
    private var utteranceStart: Long = -1L

    /** 当前这段累计的连续静音采样数 */
    private var silenceSamples: Int = 0

    /** 最近几帧，用于给下一段补前导音频 */
    private val history = ArrayDeque<FloatArray>(PRE_PAD_FRAMES + 1)

    /** 当前是否处于"已开始一段"的状态（自检页显示"正在听"也用这个） */
    @Volatile
    private var speaking = false

    /**
     * 喂入一帧 PCM16。返回本次新产生的可上屏语音段（通常 0 或 1 个）。
     *
     * 必须由单一消费者按顺序调用，Vad 内部不是线程安全的。
     */
    fun accept(samples: ShortArray, length: Int): List<SpeechChunk> {
        if (length <= 0) return emptyList()

        val floats = FloatArray(length)
        for (i in 0 until length) {
            floats[i] = samples[i] / 32768.0f
        }

        // sherpa-onnx v1.13.5 的 Kotlin API：acceptWaveform 只接受 float[]，
        // 采样率在构造 VadModelConfig 时已指定（16000），这里不能再传。
        vad.acceptWaveform(floats)
        // 我们不用 VAD 自己的分段（只借它回答"现在有没有人在说话"），
        // 但它内部会把切好的段排进队列。不 pop 的话一集视频下来队列一直涨，
        // 内存只增不减。
        while (!vad.empty()) vad.pop()
        val speech = vad.isSpeechDetected()
        speaking = speech
        fedSamples += length

        val out = ArrayList<SpeechChunk>(1)
        if (speech) {
            appendSpeechFrame(floats, out)
        } else {
            appendSilenceFrame(floats, out)
        }
        return out
    }

    /** 会话结束时把尾部残留吐出来 */
    fun flush(): List<SpeechChunk> {
        val out = ArrayList<SpeechChunk>(1)
        if (utteranceStart >= 0 && utterance.isNotEmpty()) {
            emitAll(forced = false)?.let { out.add(it) }
        } else {
            resetUtterance()
        }
        return out
    }

    /** 当前是否处于说话状态（自检页显示"正在听"） */
    fun isSpeechDetected(): Boolean = speaking

    fun release() {
        try {
            vad.release()
        } catch (t: Throwable) {
            Log.w(TAG, "释放 VAD 失败", t)
        }
    }

    // ---------------- 内部：攒段与切段 ----------------

    private fun appendSpeechFrame(frame: FloatArray, out: MutableList<SpeechChunk>) {
        if (utteranceStart < 0) {
            // 开始新的一段：把前导静音补上，避免吞掉第一个字
            val pre = ArrayList<FloatArray>(history.size)
            for (h in history) pre.add(h)
            utterance.clear()
            utterance.addAll(pre)
            utteranceStart = (fedSamples - frame.size - pre.sumOf { it.size }).coerceAtLeast(0L)
            history.clear()
        }

        utterance.add(frame)
        silenceSamples = 0

        // 说太久了：先切一刀上屏，别让用户干等
        if (fedSamples - utteranceStart >= MAX_SPEECH_SAMPLES) {
            cutAtQuietPoint()?.let { out.add(it) }
        }
    }

    private fun appendSilenceFrame(frame: FloatArray, out: MutableList<SpeechChunk>) {
        if (utteranceStart < 0) {
            // 没在说话：只保留最近几帧，供下一段做前导
            history.addLast(frame)
            while (history.size > PRE_PAD_FRAMES) history.removeFirst()
            return
        }

        utterance.add(frame)
        silenceSamples += frame.size

        // 静音够长：一句说完了，整段交出（这是最自然、延迟最低的收口方式）
        if (silenceSamples >= END_SILENCE_SAMPLES) {
            emitAll(forced = false)?.let { out.add(it) }
        }
    }

    /**
     * 在能量最低处把当前段切一半，前半段上屏，后半段留作下一段。
     *
     * 为什么不用固定位置切：VAD 按窗口对齐切，不看词边界，实测会把
     * "the small bell icon" 切成 "…the small" + "bell icon…" 两条残句。
     * 人说话词与词之间有能量低谷，切在那里几乎不会断词。
     */
    private fun cutAtQuietPoint(): SpeechChunk? {
        val total = utterance.sumOf { it.size }
        if (total < MIN_SEGMENT_SAMPLES) {
            resetUtterance()
            return null
        }
        val buf = concat()

        val target = MAX_SPEECH_SAMPLES
        val from = (target - SEARCH_BACK_SAMPLES).coerceAtLeast(1)
        val to = (target + SEARCH_FWD_SAMPLES).coerceAtMost(buf.size - 1)
        val cut = if (from >= to) target.coerceIn(1, buf.size - 1) else findQuietPoint(buf, from, to)

        val head = buf.copyOfRange(0, cut)
        val tail = buf.copyOfRange(cut, buf.size)
        val start = utteranceStart

        utterance.clear()
        utterance.add(tail)
        utteranceStart = start + cut
        silenceSamples = 0

        Log.v(
            TAG,
            "主动切段 ${TimeUtils.samplesToMs(head.size, SAMPLE_RATE)}ms" +
                "（余 ${TimeUtils.samplesToMs(tail.size, SAMPLE_RATE)}ms 待续）" +
                " @${start * 1000L / SAMPLE_RATE}ms",
        )
        return SpeechChunk(head, start, isForcedCut = true)
    }

    /** 把当前段整体交出（去掉多余的尾部静音） */
    private fun emitAll(forced: Boolean): SpeechChunk? {
        val total = utterance.sumOf { it.size }
        val start = utteranceStart
        if (total < MIN_SEGMENT_SAMPLES) {
            resetUtterance()
            return null
        }

        // 尾部静音只留 TAIL_PAD，多出来的去掉——它只会让识别变慢
        val keepSilence = TAIL_PAD_SAMPLES.coerceAtMost(silenceSamples)
        val drop = (silenceSamples - keepSilence).coerceAtLeast(0)
        val buf = concat()
        val usable = (buf.size - drop).coerceAtLeast(1)
        val samples = if (usable < buf.size) buf.copyOfRange(0, usable) else buf

        resetUtterance()

        Log.v(
            TAG,
            "切出 ${TimeUtils.samplesToMs(samples.size, SAMPLE_RATE)}ms 段" +
                " @${start * 1000L / SAMPLE_RATE}ms forced=$forced",
        )
        return SpeechChunk(samples, start, forced)
    }

    private fun resetUtterance() {
        utterance.clear()
        utteranceStart = -1L
        silenceSamples = 0
        history.clear()
    }

    private fun concat(): FloatArray {
        val total = utterance.sumOf { it.size }
        val out = FloatArray(total)
        var offset = 0
        for (part in utterance) {
            part.copyInto(out, offset)
            offset += part.size
        }
        return out
    }

    /**
     * 在 [from, to) 中找能量最低的 50ms 窗口，返回其中点。
     *
     * 步长 25ms（半窗重叠），保证不会漏掉很窄的低谷。
     */
    private fun findQuietPoint(samples: FloatArray, from: Int, to: Int): Int {
        val win = 50 * SAMPLE_RATE / 1000
        val step = win / 2
        val hi = (to).coerceAtMost(samples.size - win)
        if (from + win >= hi) return from

        var bestPos = (from + hi) / 2
        var bestEnergy = Float.MAX_VALUE
        var i = from
        while (i <= hi) {
            var sum = 0.0
            var j = i
            while (j < i + win) {
                val v = samples[j]
                sum += v * v
                j++
            }
            val energy = (sum / win).toFloat()
            if (energy < bestEnergy) {
                bestEnergy = energy
                bestPos = i + step
            }
            i += step
        }
        return bestPos.coerceIn(1, samples.size - 1)
    }
}
