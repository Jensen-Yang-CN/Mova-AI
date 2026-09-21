package com.mova.sceneai.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 时间/数值的展示格式化。集中在一处，避免各页面各写一套。 */
object Fmt {

    private val timeOfDay = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
    private val monthDay = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.CHINA)

    /** 相对时间：刚刚 / 12 分钟前 / 今天 14:32 / 03-11 14:32 */
    fun relativeTime(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
        val diff = now - epochMillis
        val zone = ZoneId.systemDefault()
        val then = Instant.ofEpochMilli(epochMillis).atZone(zone)
        return when {
            diff < 60_000 -> "刚刚"
            diff < 3_600_000 -> "${diff / 60_000} 分钟前"
            then.toLocalDate() == LocalDate.now(zone) -> "今天 ${then.format(timeOfDay)}"
            else -> then.format(monthDay)
        }
    }

    /** 毫秒 → 人类可读耗时。小于 1 秒用整数毫秒，否则保留两位小数秒。 */
    fun duration(ms: Long?): String {
        if (ms == null || ms <= 0) return "—"
        return if (ms < 1000) "$ms ms" else String.format(Locale.CHINA, "%.2f s", ms / 1000.0)
    }

    /** 字节 → MB 字符串，用于端侧模型体积展示。 */
    fun megabytes(bytes: Long?): String {
        if (bytes == null || bytes <= 0) return "—"
        return String.format(Locale.CHINA, "%.1f MB", bytes / 1024.0 / 1024.0)
    }

    /** 0.9132 → "91%" */
    fun percent(value: Double?): String {
        if (value == null) return "—"
        return "${Math.round(value * 100)}%"
    }
}
