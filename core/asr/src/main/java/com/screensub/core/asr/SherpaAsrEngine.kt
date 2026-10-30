// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.screensub.core.common.AppDispatchers
import com.screensub.core.common.AppError
import com.screensub.core.common.AppResult
import com.screensub.core.common.Log
import com.screensub.core.common.LogTags
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 基于 sherpa-onnx 的离线识别引擎。
 *
 * 用 OfflineRecognizer（不是 OnlineRecognizer）：
 * SenseVoice 和 Whisper 都是非流式模型，强行当流式用只会得到碎片。
 * 准实时靠外层的 VAD 分段 + 两遍法实现，不靠流式解码。
 */
class SherpaAsrEngine(
    private val modelSpec: AsrModelSpec,
    private val modelDir: File,
    private val language: SourceLanguage,
) : AsrEngine {

    companion object {
        private const val TAG = LogTags.ASR
        private const val SAMPLE_RATE = 16_000
        private const val NUM_THREADS = 3
    }

    override val id: String = modelSpec.id

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    @Volatile
    private var loaded: Boolean = false

    override val isReady: Boolean get() = loaded

    override suspend fun prepare(): AppResult<Unit> = withContext(AppDispatchers.asr) {
        if (loaded) return@withContext AppResult.Ok(Unit)

        val modelPath = File(modelDir, modelSpec.encoderFileName)
        val tokensPath = File(modelDir, modelSpec.tokensFileName)

        if (!modelPath.exists() || modelPath.length() == 0L) {
            return@withContext AppResult.Err(
                AppError.ModelNotReady("模型文件不存在：${modelPath.name}，请先在设置里下载"),
            )
        }
        if (!tokensPath.exists()) {
            return@withContext AppResult.Err(
                AppError.ModelNotReady("词表文件不存在：${tokensPath.name}"),
            )
        }

        try {
            val started = System.currentTimeMillis()
            val config = buildConfig(modelPath.absolutePath, tokensPath.absolutePath)
            recognizer = OfflineRecognizer(config = config)
            loaded = true
            Log.i(TAG, "模型加载完成 ${modelSpec.id} 耗时 ${System.currentTimeMillis() - started}ms")
            AppResult.Ok(Unit)
        } catch (t: Throwable) {
            Log.e(TAG, "模型加载失败", t)
            loaded = false
            recognizer = null
            AppResult.Err(AppError.Engine("模型加载失败：${t.message}", t))
        }
    }

    private fun buildConfig(modelPath: String, tokensPath: String): OfflineRecognizerConfig {
        val featureConfig = FeatureConfig(
            sampleRate = SAMPLE_RATE,
            featureDim = 80,
        )

        val modelConfig = when (modelSpec.engineKind) {
            ModelEngineKind.SENSE_VOICE -> OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = modelPath,
                    // "auto" 让模型自己判语言；指定 "ja"/"en" 准确率会略高
                    language = if (language == SourceLanguage.AUTO) "auto" else language.code,
                    // 字幕必须开 ITN：把「さんぴゃくえん」输出成「300円」而不是假名
                    useInverseTextNormalization = true,
                ),
                tokens = tokensPath,
                numThreads = NUM_THREADS,
                provider = "cpu",
                debug = false,
            )

            ModelEngineKind.TRANSDUCER -> {
                // Zipformer 家族：encoder/decoder/joiner 三件套，缺一个都起不来
                val decoderName = requireNotNull(modelSpec.decoderFileName) {
                    "transducer 模型必须提供 decoder 文件名"
                }
                val joinerName = requireNotNull(modelSpec.joinerFileName) {
                    "transducer 模型必须提供 joiner 文件名"
                }
                OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = modelPath,
                        decoder = File(modelDir, decoderName).absolutePath,
                        joiner = File(modelDir, joinerName).absolutePath,
                    ),
                    tokens = tokensPath,
                    numThreads = NUM_THREADS,
                    provider = "cpu",
                    debug = false,
                )
            }

            ModelEngineKind.NEMO_CTC -> OfflineModelConfig(
                // CTC 单文件，非自回归，没有 Whisper 那种"感谢观看"幻觉
                nemo = OfflineNemoEncDecCtcModelConfig(model = modelPath),
                tokens = tokensPath,
                numThreads = NUM_THREADS,
                provider = "cpu",
                debug = false,
            )
        }

        return OfflineRecognizerConfig(
            featConfig = featureConfig,
            modelConfig = modelConfig,
        )
    }

    override suspend fun transcribe(
        samples: FloatArray,
        startMs: Long,
        isFinal: Boolean,
    ): AppResult<AsrResult> = withContext(AppDispatchers.asr) {
        val engine = recognizer
            ?: return@withContext AppResult.Err(AppError.ModelNotReady("识别引擎尚未加载"))

        if (samples.isEmpty()) {
            return@withContext AppResult.Err(AppError.Engine("空音频段"))
        }

        try {
            val started = System.currentTimeMillis()

            val stream = engine.createStream()
            val text: String
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                engine.decode(stream)
                text = engine.getResult(stream).text
            } finally {
                stream.release()
            }

            val elapsed = System.currentTimeMillis() - started
            val durationMs = samples.size * 1000L / SAMPLE_RATE

            AppResult.Ok(
                AsrResult(
                    text = text.trim(),
                    startMs = startMs,
                    endMs = startMs + durationMs,
                    detectedLanguage = language.code,
                    confidence = 1f,
                    isFinal = isFinal,
                    inferenceMs = elapsed,
                ),
            )
        } catch (t: Throwable) {
            Log.e(TAG, "识别失败", t)
            AppResult.Err(AppError.Engine("识别失败：${t.message}", t))
        }
    }

    override fun release() {
        try {
            recognizer?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "释放 recognizer 失败", t)
        }
        recognizer = null
        loaded = false
    }

    override fun close() = release()
}
