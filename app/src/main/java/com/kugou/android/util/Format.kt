package com.kugou.android.util

import java.util.Locale

fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val m = s / 60
    val sec = s % 60
    return if (m >= 60) {
        String.format(Locale.US, "%d:%02d:%02d", m / 60, m % 60, sec)
    } else {
        String.format(Locale.US, "%d:%02d", m, sec)
    }
}

/** 取标题首字符用于圆形占位封面 */
fun artLetter(title: String?): String {
    val t = title?.trim().orEmpty()
    return when {
        t.isEmpty() -> "♪"
        t[0].isLetterOrDigit() -> t.substring(0, 1).uppercase(Locale.getDefault())
        else -> "♪"
    }
}
