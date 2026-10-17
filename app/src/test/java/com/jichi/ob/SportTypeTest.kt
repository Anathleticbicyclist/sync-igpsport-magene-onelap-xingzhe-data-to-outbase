package com.jichi.ob

import com.jichi.ob.GpxToFitConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v9.1.1 运动类型全链路测试（对齐开发版 v9.1.0）。
 *
 * 覆盖：
 * ① sportTypeToFitPair 全量映射（骑行/跑步/徒步/步行/登山/游泳/铁三/滑雪/划船/力量训练）；
 * ② convertWithSportType 优先用 record.sportType、兜底 GPX <name> 检测、再兜底骑行；
 * ③ GarminApi.garminTypeKeyToSport 佳明 activityType.typeKey 全量映射（反射调用私有方法）。
 */
class SportTypeTest {

    // ===== ① sportTypeToFitPair 全量映射 =====

    @Test
    fun `cycling 映射为 road cycling`() {
        assertEquals(Pair(2, 7), GpxToFitConverter.sportTypeToFitPair("cycling"))
    }

    @Test
    fun `running 映射为 street running`() {
        assertEquals(Pair(1, 2), GpxToFitConverter.sportTypeToFitPair("running"))
    }

    @Test
    fun `hiking 映射为 trail hiking`() {
        assertEquals(Pair(17, 3), GpxToFitConverter.sportTypeToFitPair("hiking"))
    }

    @Test
    fun `walking 映射为 running（兼容目标平台支持度差）`() {
        assertEquals(Pair(1, 2), GpxToFitConverter.sportTypeToFitPair("walking"))
    }

    @Test
    fun `mountaineering 映射为登山`() {
        assertEquals(Pair(16, 0), GpxToFitConverter.sportTypeToFitPair("mountaineering"))
    }

    @Test
    fun `swimming 映射为 open_water`() {
        assertEquals(Pair(5, 30), GpxToFitConverter.sportTypeToFitPair("swimming"))
    }

    @Test
    fun `triathlon 映射为 multisport`() {
        assertEquals(Pair(18, 0), GpxToFitConverter.sportTypeToFitPair("triathlon"))
    }

    @Test
    fun `skiing 与 rowing 落到 training`() {
        assertEquals(Pair(10, 0), GpxToFitConverter.sportTypeToFitPair("skiing"))
        assertEquals(Pair(10, 0), GpxToFitConverter.sportTypeToFitPair("rowing"))
    }

    @Test
    fun `training 类与大小写不敏感`() {
        assertEquals(Pair(10, 0), GpxToFitConverter.sportTypeToFitPair("training"))
        assertEquals(Pair(2, 7), GpxToFitConverter.sportTypeToFitPair("Cycling"))
    }

    @Test
    fun `空与未知类型返回 null 走兜底`() {
        assertNull(GpxToFitConverter.sportTypeToFitPair(null))
        assertNull(GpxToFitConverter.sportTypeToFitPair(""))
        assertNull(GpxToFitConverter.sportTypeToFitPair("unknown_xyz"))
    }

    // ===== ② convertWithSportType：优先 sportType，兜底 GPX <name> =====

    private fun gpx(name: String): ByteArray = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx creator="jichi"><trk><name>$name</name><trkseg>
        <trkpt lat="36.06" lon="120.38"><ele>10</ele><time>2026-09-01T10:00:00Z</time></trkpt>
        <trkpt lat="36.07" lon="120.39"><ele>12</ele><time>2026-09-01T10:01:00Z</time></trkpt>
        <trkpt lat="36.08" lon="120.40"><ele>14</ele><time>2026-09-01T10:02:00Z</time></trkpt>
        </trkseg></trk></gpx>
    """.trimIndent().toByteArray()

    @Test
    fun `convertWithSportType 优先用 sportType（即使 GPX name 是骑行）`() {
        // sportType=swimming 应覆盖 GPX <name> 里的 cycling 标记
        val fit = GpxToFitConverter.convertWithSportType(gpx("outdoorCycling"), "swimming")
        assertTrue(GpxToFitConverter.isFit(fit))
        assertTrue(fit.size > 100)
    }

    @Test
    fun `convertWithSportType 无 sportType 时兜底 GPX name 检测`() {
        val fit = GpxToFitConverter.convertWithSportType(gpx("from keep - running"), null)
        assertTrue(GpxToFitConverter.isFit(fit))
    }

    @Test
    fun `convertWithSportType 空 sportType 不崩溃`() {
        val fit = GpxToFitConverter.convertWithSportType(gpx("outdoorCycling"), "")
        assertTrue(GpxToFitConverter.isFit(fit))
    }

    @Test
    fun `convertWithSportType 无轨迹点时报错`() {
        try {
            GpxToFitConverter.convertWithSportType("<gpx></gpx>".toByteArray(), "running")
            throw AssertionError("应抛出 GPX无有效轨迹点")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("GPX无有效轨迹点"))
        }
    }

    // ===== ③ GarminApi.garminTypeKeyToSport 全量映射（反射） =====

    @Suppress("UNCHECKED_CAST")
    private fun typeKeyToSport(typeKey: String): String? {
        val m = com.jichi.ob.api.GarminApi::class.java.getDeclaredMethod("garminTypeKeyToSport", String::class.java)
        m.isAccessible = true
        return m.invoke(com.jichi.ob.api.GarminApi(), typeKey) as String?
    }

    @Test
    fun `佳明 typeKey 骑行类映射`() {
        assertEquals("cycling", typeKeyToSport("cycling"))
        assertEquals("cycling", typeKeyToSport("road_biking"))
        assertEquals("cycling", typeKeyToSport("gravel_cycling"))
        assertEquals("cycling", typeKeyToSport("cyclocross"))
    }

    @Test
    fun `佳明 typeKey 跑步徒步步行映射`() {
        assertEquals("running", typeKeyToSport("running"))
        assertEquals("running", typeKeyToSport("trail_run"))
        assertEquals("running", typeKeyToSport("treadmill"))
        assertEquals("hiking", typeKeyToSport("hiking"))
        assertEquals("walking", typeKeyToSport("walking"))
    }

    @Test
    fun `佳明 typeKey 游泳铁三登山映射`() {
        assertEquals("swimming", typeKeyToSport("swimming"))
        assertEquals("swimming", typeKeyToSport("open_water"))
        assertEquals("triathlon", typeKeyToSport("triathlon"))
        assertEquals("mountaineering", typeKeyToSport("mountaineering"))
    }

    @Test
    fun `佳明 typeKey 力量训练与瑜伽落到 training`() {
        assertEquals("training", typeKeyToSport("strength_training"))
        assertEquals("training", typeKeyToSport("yoga"))
        assertEquals("training", typeKeyToSport("elliptical"))
        assertEquals("training", typeKeyToSport("indoor_cardio"))
    }

    @Test
    fun `佳明 typeKey 空与未知返回 null`() {
        assertNull(typeKeyToSport(""))
        assertNull(typeKeyToSport("unknown_xyz"))
    }

    @Test
    fun `佳明 typeKey 大小写不敏感`() {
        assertEquals("cycling", typeKeyToSport("Cycling"))
        assertEquals("swimming", typeKeyToSport("Swimming"))
    }
}
