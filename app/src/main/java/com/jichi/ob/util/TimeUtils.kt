package com.jichi.ob.util

/**
 * v8.3.8: 时间解析工具（对齐开发体验版 ActivityCache.parseStartTimeMs）。
 * 各平台拉取活动列表时直传 startTimeMs，避免时间=0 导致记录沉底/日期检索失效。
 * 纯静态无状态，不引入 SQLite 缓存。
 */
object TimeUtils {

    /** 容错解析时间字符串 → epoch ms；失败返回 0 */
    fun parseStartTimeMs(s: String?): Long {
        if (s.isNullOrBlank()) return 0L
        val t = s.trim()
        // 纯数字时间戳
        t.toLongOrNull()?.let {
            return if (it > 1_000_000_000_000L) it          // ms
            else if (it > 1_000_000_000L) it * 1000L        // s
            else 0L
        }
        // ISO8601 带时区：2025-09-10T20:33:42+08:00 / ...Z / 带毫秒（空格容错）
        try {
            val iso = t.replace(" ", "T")
            java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli().let { return it }
        } catch (_: Exception) {}
        // ISO8601 无时区：按本地时区解析
        try {
            val iso = t.replace(" ", "T")
            java.time.LocalDateTime.parse(iso)
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli().let { return it }
        } catch (_: Exception) {}
        // 旧格式 yyyy-MM-dd HH:mm:ss / yyyy/MM/dd HH:mm:ss / yyyyMMdd_HHmmss
        val norm = t.replace("/", "-").replace("T", " ")
            .replace("Z", "").trim()
        val m = Regex("""(\d{4})[-](\d{1,2})[-](\d{1,2})[ _](\d{1,2})[:](\d{2})(?:[:](\d{2}))?""").find(norm)
        if (m != null) {
            try {
                val cal = java.util.Calendar.getInstance()
                cal.clear()
                cal.set(
                    m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3].toInt(),
                    m.groupValues[4].toInt(), m.groupValues[5].toInt(),
                    m.groupValues[6].ifBlank { "0" }.toInt()
                )
                return cal.timeInMillis
            } catch (_: Exception) { return 0L }
        }
        // yyyyMMdd（日期）
        val d = Regex("""^(\d{4})(\d{2})(\d{2})$""").find(t)
        if (d != null) {
            try {
                val cal = java.util.Calendar.getInstance()
                cal.clear()
                cal.set(d.groupValues[1].toInt(), d.groupValues[2].toInt() - 1, d.groupValues[3].toInt(), 0, 0, 0)
                return cal.timeInMillis
            } catch (_: Exception) { return 0L }
        }
        return 0L
    }
}
