package com.jichi.ob

import com.jichi.ob.merge.TrackMerger
import com.jichi.ob.util.FitGcj02Fixer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v9.7.0 回归测试：多日合并「取各段 session 原值 + 按段分圈」口径，
 * 以及坐标转换对截断 FIT 的容错（不得抛异常 / 不得整份误判）。
 */
class TrackMergeSourceTest {

    private fun tp(lat: Double, lon: Double, ts: Long) =
        GpxToFitConverter.TrackPoint(lat = lat, lon = lon, ele = 100.0, ts = ts)

    /** 有源值：总里程 = 各段原值之和，计时 = 各段原值之和，圈数 = 段数，时间轴保持原始 */
    @Test
    fun sourceMetaOverridesDistanceDurationAndLaps() {
        val seg1 = listOf(tp(30.0, 120.0, 1000L), tp(30.001, 120.001, 1010L))
        val seg2 = listOf(tp(31.0, 121.0, 2000L), tp(31.001, 121.001, 2010L))
        val metas = listOf(
            TrackMerger.SegmentMeta(1000L, 5000.0, 600L),
            TrackMerger.SegmentMeta(2000L, 3000.0, 400L)
        )
        val r = TrackMerger.mergePointGroupsWithMeta(listOf(seg1, seg2), metas)

        assertTrue(r.fromSourceMeta)
        assertEquals(8.0, r.totalDistanceKm, 0.001)   // (5000 + 3000) / 1000
        assertEquals(1000L, r.totalDurationSec)        // 600 + 400
        assertEquals(2, r.laps.size)
        assertEquals(5000.0, r.laps[0].distanceM, 0.001)
        assertEquals(3000.0, r.laps[1].distanceM, 0.001)
        assertEquals(600L, r.laps[0].timerSec)
        assertEquals(400L, r.laps[1].timerSec)
        // 原始时间轴：第二段首点仍是 2000s，没有被压缩成"上段末点 +1s"
        assertEquals(1000L, r.points.first().ts)
        assertEquals(2000L, r.points[2].ts)
        assertEquals(8.0, r.totalDistanceKm, 0.001)   // 跨段"跳飞"距离不计入
    }

    /** 无源值：完全沿用旧口径（单圈、点列重算），保证 v9.6.6 行为不被改变 */
    @Test
    fun withoutMetaKeepsLegacyBehaviour() {
        val seg1 = listOf(tp(30.0, 120.0, 1000L), tp(30.001, 120.001, 1010L))
        val seg2 = listOf(tp(31.0, 121.0, 1020L), tp(31.001, 121.001, 1030L))
        val r = TrackMerger.mergePointGroups(listOf(seg1, seg2))

        assertFalse(r.fromSourceMeta)
        assertTrue(r.laps.isEmpty())
        // 段间紧接（+1s），不是原始 1020s
        assertEquals(1011L, r.points[2].ts)
    }

    /** 截断 FIT（头部声明远大于实际长度）：不得抛异常，也不得返回空 */
    @Test
    fun fixerToleratesTruncatedFit() {
        val bytes = ByteArray(20)
        bytes[0] = 14
        bytes[1] = 0x10
        bytes[4] = -24; bytes[5] = 3   // 声明 dataSize = 1000
        bytes[8] = '.'.code.toByte(); bytes[9] = 'F'.code.toByte()
        bytes[10] = 'I'.code.toByte(); bytes[11] = 'T'.code.toByte()

        val out = FitGcj02Fixer.fix(bytes)
        assertNotNull(out)
        // 没有任何 record 可修 → 原样返回，文件长度不变
        assertEquals(bytes.size, out!!.size)
    }
}
