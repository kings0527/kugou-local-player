package com.leling.music

import android.media.audiofx.Equalizer

/**
 * 常用 EQ 预设。10 段 ISO 频段：31/62/125/250/500/1k/2k/4k/8k/16k Hz
 * 取值温和（±5dB），避免失真、省电（不额外耗电，仅调节系统 DSP）。
 */
object EqPresets {
    val ISO_BANDS = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    val NAMES = listOf("标准", "流行", "摇滚", "爵士", "古典", "舞曲", "电子", "低音增强", "人声")

    private val GAINS: Map<String, DoubleArray> = mapOf(
        "标准" to doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
        "流行" to doubleArrayOf(-1.0, 0.0, 1.0, 3.0, 4.0, 3.0, 1.0, 0.0, -1.0, -2.0),
        "摇滚" to doubleArrayOf(4.0, 3.0, 2.0, 0.0, -1.0, -1.0, 0.0, 2.0, 3.0, 4.0),
        "爵士" to doubleArrayOf(3.0, 2.0, 1.0, 2.0, 0.0, -1.0, -1.0, 0.0, 2.0, 3.0),
        "古典" to doubleArrayOf(4.0, 3.0, 2.0, 1.0, 0.0, 0.0, 0.0, 1.0, 2.0, 4.0),
        "舞曲" to doubleArrayOf(5.0, 4.0, 3.0, 1.0, 0.0, -1.0, -1.0, 1.0, 3.0, 5.0),
        "电子" to doubleArrayOf(4.0, 2.0, -1.0, -2.0, -1.0, 0.0, 2.0, 4.0, 5.0, 5.0),
        "低音增强" to doubleArrayOf(6.0, 5.0, 3.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
        "人声" to doubleArrayOf(0.0, 0.0, 1.0, 2.0, 4.0, 4.0, 2.0, 0.0, -1.0, -1.0),
    )

    fun gainsOf(name: String): DoubleArray = GAINS[name] ?: GAINS["标准"]!!

    /** 找到距目标频段最近的均衡器频段下标 */
    private fun nearestBand(eq: Equalizer, isoHz: Int): Short {
        var best: Short = 0
        var bestDist = Double.MAX_VALUE
        for (i in 0 until eq.numberOfBands) {
            val d = Math.abs(Math.log(eq.getCenterFreq(i.toShort()).toDouble()) - Math.log(isoHz.toDouble()))
            if (d < bestDist) { bestDist = d; best = i.toShort() }
        }
        return best
    }

    /**
     * 应用预设。必须在播放器 audioSessionId 有效后调用；
     * 失败（设备不支持）时静默返回，不影响播放。
     */
    fun apply(eq: Equalizer, name: String) {
        try {
            val gains = gainsOf(name)
            val range = eq.bandLevelRange // [min, max] 单位 mB (Short)
            for (i in ISO_BANDS.indices) {
                val band = nearestBand(eq, ISO_BANDS[i])
                val target = (gains[i] * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt())
                eq.setBandLevel(band, target.toShort())
            }
            eq.enabled = true
        } catch (_: Throwable) {
            // 设备/DSP 不支持时忽略
        }
    }
}
