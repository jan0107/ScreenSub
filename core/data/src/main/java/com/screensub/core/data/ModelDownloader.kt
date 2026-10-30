// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.data

import com.screensub.core.asr.AsrModelSpec
import com.screensub.core.asr.ModelCatalog
import com.screensub.core.asr.ModelFile
import com.screensub.core.common.AppDispatchers
import com.screensub.core.common.AppError
import com.screensub.core.common.AppResult
import com.screensub.core.common.Log
import com.screensub.core.common.LogTags
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * 模型下载器。
 *
 * 设计取舍：
 * - **不用 DownloadManager**：它把文件丢到公共下载目录，拿回来还要拷一次；
 *   而且无法做流式进度回调。直接 OkHttp 写到 filesDir 更简单。
 * - **断点续传**：SenseVoice 228 MB、Whisper 360 MB，宿舍网一断就得重来是不可接受的。
 *   用 Range 请求 + 已下载字节数续传。
 * - **大小校验**：不引入 sha256（要额外算一遍很慢），只比对最终文件大小。
 *   下载损坏的概率远低于中断的概率，大小对得上就认为 OK。
 * - **HF 镜像**：huggingface.co 在国内经常龟速，hf-mirror.com 可救急。
 */
class ModelDownloader(
    private val context: android.content.Context,
    private val useHfMirror: Boolean = false,
) {

    companion object {
        private const val TAG = LogTags.DATA

        private const val HF_ORIGIN = "https://huggingface.co"
        private const val HF_MIRROR = "https://hf-mirror.com"
    }

    data class DownloadProgress(
        val fileName: String,
        val fileIndex: Int,
        val fileTotal: Int,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) {
        val fraction: Float
            get() = if (totalBytes <= 0) 0f
            else (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 模型统一放在 filesDir/models/<modelId>/ 下 */
    fun modelDir(spec: AsrModelSpec): File =
        File(context.filesDir, "models/${spec.id}").apply { mkdirs() }

    fun vadModelFile(): File = File(context.filesDir, "models/silero_vad.onnx")

    fun isModelReady(spec: AsrModelSpec): Boolean =
        spec.files.all { file ->
            val local = File(modelDir(spec), file.fileName)
            local.exists() && local.length() > 0
        }

    fun isVadReady(): Boolean = vadModelFile().let { it.exists() && it.length() > 0 }

    /**
     * 下载整套模型。已存在的文件会跳过（所以可以反复调用做"修复"）。
     */
    suspend fun download(
        spec: AsrModelSpec,
        onProgress: (DownloadProgress) -> Unit = {},
    ): AppResult<Unit> = withContext(AppDispatchers.io) {
        try {
            spec.files.forEachIndexed { index, file ->
                val outcome = downloadSingle(
                    file = file,
                    targetDir = modelDir(spec),
                    fileIndex = index,
                    fileTotal = spec.files.size,
                    onProgress = onProgress,
                )
                if (outcome is AppResult.Err) return@withContext outcome
            }

            // 全下完再校验一遍
            if (!isModelReady(spec)) {
                return@withContext AppResult.Err(
                    AppError.Network("模型下载完成但校验失败，请重试"),
                )
            }
            pruneStaleFiles(spec)
            Log.i(TAG, "模型 ${spec.id} 就绪")
            AppResult.Ok(Unit)
        } catch (t: Throwable) {
            Log.e(TAG, "模型下载失败", t)
            AppResult.Err(AppError.Network("下载失败：${t.message}", t))
        }
    }

    /** 单独下载 Silero VAD（629 KB，很小） */
    suspend fun downloadVad(
        onProgress: (DownloadProgress) -> Unit = {},
    ): AppResult<Unit> = withContext(AppDispatchers.io) {
        val outcome = downloadSingle(
            file = ModelCatalog.SILERO_VAD,
            targetDir = File(context.filesDir, "models").apply { mkdirs() },
            fileIndex = 0,
            fileTotal = 1,
            onProgress = onProgress,
        )
        outcome
    }

    /**
     * 下载单个文件，**按优先级逐个源尝试**。
     *
     * 为什么要有多个源：主源是 HuggingFace（国内靠 hf-mirror 中转），备源是自建的
     * ModelScope 镜像。镜像站是第三方服务，限速、抽风、下架都可能发生，
     * 只挂一个源等于把"能不能用"赌在别人身上。
     *
     * 换源时**保留已下载的部分**接着续传：两个源的字节内容完全一致，
     * 实测 ModelScope 跳转后 `Range` 照样生效，所以不必重下。
     */
    private suspend fun downloadSingle(
        file: ModelFile,
        targetDir: File,
        fileIndex: Int,
        fileTotal: Int,
        onProgress: (DownloadProgress) -> Unit,
    ): AppResult<Unit> {
        val target = File(targetDir, file.fileName)

        // 已完整下载过
        if (target.exists() && target.length() == file.sizeBytes) {
            Log.d(TAG, "${file.fileName} 已存在（${target.length()} 字节），跳过")
            onProgress(
                DownloadProgress(
                    file.fileName, fileIndex, fileTotal,
                    file.sizeBytes, file.sizeBytes,
                ),
            )
            return AppResult.Ok(Unit)
        }

        var lastError: AppError = AppError.Network("没有可用的下载源")

        file.urls.forEachIndexed { index, rawUrl ->
            val url = rewriteUrl(rawUrl)
            val tag = "源 ${index + 1}/${file.urls.size}"
            Log.i(TAG, "下载 ${file.fileName} 尝试$tag：$url")
            when (val outcome = fetch(url, file, target, fileIndex, fileTotal, onProgress)) {
                is AppResult.Ok -> return outcome
                is AppResult.Err -> {
                    lastError = outcome.error
                    // 失败不清空已下载部分：换源继续 Range 续传，省流量也省时间
                    Log.w(TAG, "${file.fileName} $tag 失败：${outcome.error.message}，换下一个源")
                }
            }
        }

        return AppResult.Err(lastError)
    }

    /** 从**一个** URL 把文件拉下来（含续传、进度回调、大小校验） */
    private fun fetch(
        url: String,
        file: ModelFile,
        target: File,
        fileIndex: Int,
        fileTotal: Int,
        onProgress: (DownloadProgress) -> Unit,
    ): AppResult<Unit> {
        val existingBytes = if (target.exists()) target.length() else 0L

        val requestBuilder = Request.Builder().url(url)
        if (existingBytes > 0) {
            requestBuilder.addHeader("Range", "bytes=$existingBytes-")
        }

        http.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                return AppResult.Err(
                    AppError.Network("下载 ${file.fileName} 失败：HTTP ${response.code}"),
                )
            }

            val body = response.body
                ?: return AppResult.Err(AppError.Network("响应体为空"))

            val totalBytes = when {
                // 续传：总长 = 已有 + 剩余
                response.code == 206 -> existingBytes + body.contentLength()
                else -> body.contentLength()
            }

            // 服务器不认 Range（返回 200）时必须从头写，否则文件会错位
            val append = existingBytes > 0 && response.code == 206
            RandomAccessFile(target, "rw").use { raf ->
                if (!append) raf.setLength(0)
                raf.seek(if (append) existingBytes else 0L)

                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var written = if (append) existingBytes else 0L
                    var lastReport = 0L

                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        raf.write(buffer, 0, read)
                        written += read

                        // 每 512 KB 报一次进度，避免刷爆 UI
                        if (written - lastReport > 512 * 1024) {
                            lastReport = written
                            onProgress(
                                DownloadProgress(
                                    file.fileName, fileIndex, fileTotal,
                                    written, if (totalBytes > 0) totalBytes else 0L,
                                ),
                            )
                        }
                    }
                }
            }
        }

        // 大小校验：不符就把这次算作失败，交给下一个源重试。
        // 以前只记日志就放行，结果是"下了一半的文件"被当成成功，
        // 后面加载模型才炸，错误现场离真凶很远。
        val actual = target.length()
        if (file.sizeBytes > 0 && actual != file.sizeBytes) {
            Log.w(TAG, "${file.fileName} 大小不符：期望 ${file.sizeBytes}，实际 $actual")
            return AppResult.Err(
                AppError.Network("${file.fileName} 下载不完整（$actual/${file.sizeBytes} 字节）"),
            )
        }

        onProgress(
            DownloadProgress(
                file.fileName, fileIndex, fileTotal, actual, actual,
            ),
        )
        return AppResult.Ok(Unit)
    }

    private fun rewriteUrl(url: String): String =
        if (useHfMirror && url.startsWith(HF_ORIGIN)) {
            url.replaceFirst(HF_ORIGIN, HF_MIRROR)
        } else {
            url
        }

    /** 删除模型，释放空间 */
    fun deleteModel(spec: AsrModelSpec) {
        modelDir(spec).deleteRecursively()
    }

    /**
     * 删掉目录里**不属于当前模型定义**的旧文件。
     *
     * 为什么会残留：模型的文件名改过一次（`sense_voice.int8.onnx` → `model.int8.onnx`，
     * `sense_voice_tokens.txt` → `tokens.txt`），下载器只按新文件名判存在，
     * 旧文件就永远留在那儿。实测设备上 SenseVoice 目录白占了 228MB——同一个模型存了两份。
     *
     * 只在**下载完整之后**调用：此时需要的文件都在，剩下的就是孤儿。
     */
    private fun pruneStaleFiles(spec: AsrModelSpec) {
        val expected = spec.files.map { it.fileName }.toSet()
        val dir = modelDir(spec)
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name !in expected) {
                val mb = f.length() / 1024 / 1024
                if (f.delete()) {
                    Log.i(TAG, "清理旧模型文件 ${spec.id}/${f.name}，释放 ${mb}MB")
                }
            }
        }
    }

    fun modelSizeBytes(spec: AsrModelSpec): Long =
        modelDir(spec).walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
