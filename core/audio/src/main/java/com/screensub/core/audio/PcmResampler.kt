// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.audio

/**
 * 48 kHz → 16 kHz 定点抽取。
 *
 * 用 3 点盒式平均作为抗混叠低通：
 * 盒式滤波器的 -3 dB 截止频率正好在 fs/6 = 8 kHz，
 * 而 16 kHz 采样的奈奎斯特频率就是 8 kHz，刚好匹配语音场景。
 * 代价是引入 1.5 个输入样本（约 0.03 ms）的群延迟，可忽略。
 */
object PcmResampler {

    /**
     * @param input 48 kHz 输入
     * @param inputLen 实际有效样本数
     * @param output 输出缓冲，容量需 >= inputLen / 3
     * @return 写入 output 的样本数
     */
    fun downsampleBy3(input: ShortArray, inputLen: Int, output: ShortArray): Int {
        val outLen = inputLen / AudioConstants.DOWNSAMPLE_RATIO
        require(output.size >= outLen) {
            "输出缓冲太小：需要 $outLen，实际 ${output.size}"
        }
        var i = 0
        while (i < outLen) {
            val base = i * 3
            // 3 点平均（int 累加不会溢出：3 * 32767 = 98301）
            val sum = input[base].toInt() + input[base + 1].toInt() + input[base + 2].toInt()
            output[i] = (sum / 3).toShort()
            i++
        }
        return outLen
    }

    /**
     * 通用线性插值重采样（备用路径，例如未来接入 44.1 kHz 蓝牙录音）。
     * 不在主链路使用——线性插值抗混叠能力弱，只作兜底。
     */
    fun resampleLinear(
        input: ShortArray,
        inputLen: Int,
        srcRate: Int,
        dstRate: Int,
    ): ShortArray {
        if (srcRate == dstRate) return input.copyOf(inputLen)
        val outLen = (inputLen.toLong() * dstRate / srcRate).toInt()
        val out = ShortArray(outLen)
        val step = srcRate.toDouble() / dstRate
        for (i in 0 until outLen) {
            val pos = i * step
            val idx = pos.toInt().coerceIn(0, inputLen - 1)
            val frac = pos - idx
            val s0 = input[idx].toDouble()
            val s1 = if (idx + 1 < inputLen) input[idx + 1].toDouble() else s0
            out[i] = (s0 + (s1 - s0) * frac).toInt().toShort()
        }
        return out
    }
}
