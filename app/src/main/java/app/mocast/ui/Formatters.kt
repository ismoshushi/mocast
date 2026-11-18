package app.mocast.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

fun formatDate(epochMs: Long): String {
    if (epochMs <= 0) return ""
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return fmt.format(Date(epochMs))
}

fun formatDuration(sec: Int): String {
    if (sec <= 0) return ""
    return formatTime(sec * 1000L)
}

/** 睡眠定时剩余时间：不足 1 小时显示 mm:ss，超过显示 h:mm:ss（复用 formatTime）。 */
fun formatSleepRemaining(ms: Long): String = formatTime(ms)
