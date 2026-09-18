package com.jichi.ob

import java.util.Calendar

/**
 * v8.0.0 轨迹合并&透明贴纸 限时体验控制
 * 9月30号之前可用（含当日），系统时间过期后自动失效
 * v8.1.3: 从备份文档恢复（此前误删，按 MainActivity.openMerge 注释语义重建）
 */
object MergeTrial {

    /** 限时截止：2026-09-30 23:59:59 */
    private val DEADLINE: Long by lazy {
        val c = Calendar.getInstance()
        c.clear()
        c.set(2026, Calendar.SEPTEMBER, 30, 23, 59, 59)
        c.timeInMillis
    }

    /** 是否在限时体验期内 */
    fun isAvailable(): Boolean = System.currentTimeMillis() <= DEADLINE

    /** 剩余可用天数（0 表示今天最后一天/已过期） */
    fun remainingDays(): Int {
        val now = System.currentTimeMillis()
        if (now > DEADLINE) return 0
        val ms = DEADLINE - now
        return ((ms + 86399999L) / 86400000L).toInt()
    }
}
