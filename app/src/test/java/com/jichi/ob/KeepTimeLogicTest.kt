package com.jichi.ob

import com.jichi.ob.api.KeepApi
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v8.7.0 移植单测：Keep 轨迹点时间戳换算。
 * 覆盖 [1e9,1e11) 区间 epoch 秒判定 旧逻辑误判为相对毫秒 → 偏移约 20.6 天 。
 */
class KeepTimeLogicTest {

    private val start = 1_700_000_000_000L // 模拟 startTimeMs 2023-11 附近绝对毫秒 

    @Test
    fun `epoch 秒被正确识别为绝对时间`() {
        // 1.75e9 秒 ≈ epoch 秒 10 位 → 应 ×1000 转绝对毫秒
        val epochSec = 1_750_000_000L
        val abs = KeepApi.convertKeepTs(epochSec, start)
        assertEquals(1_750_000_000_000L, abs)
    }

    @Test
    fun `绝对毫秒原样返回`() {
        val absMs = 1_750_000_000_123L // >= 1e11
        assertEquals(absMs, KeepApi.convertKeepTs(absMs, start))
    }

    @Test
    fun `相对毫秒用 startTime 推算`() {
        val relMs = 200_000_000L // 2e8 ms ≈ 2.3 天内的相对毫秒
        assertEquals(start + relMs, KeepApi.convertKeepTs(relMs, start))
    }

    @Test
    fun `相对秒 5400s 转毫秒并推算`() {
        val relSec = 5_400L
        assertEquals(start + 5_400_000L, KeepApi.convertKeepTs(relSec, start))
    }

    @Test
    fun `非正值返回 0`() {
        assertEquals(0L, KeepApi.convertKeepTs(0L, start))
        assertEquals(0L, KeepApi.convertKeepTs(-1L, start))
    }

    @Test
    fun `1e9 边界两侧行为正确`() {
        // 999_999_999 9 位，相对毫秒级 → startTime 推算
        assertEquals(start + 999_999_999L, KeepApi.convertKeepTs(999_999_999L, start))
        // 1_000_000_000 10 位，epoch 秒 → ×1000
        assertEquals(1_000_000_000_000L, KeepApi.convertKeepTs(1_000_000_000L, start))
    }
}
