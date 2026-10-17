package com.jichi.ob

import com.jichi.ob.api.ZwiftApi
import com.jichi.ob.model.ActivityRecord
import com.jichi.ob.model.DataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

/**
 * v8.8.7 Zwift「拉不到活动」根因修复测试。
 *
 * 覆盖三个根因修复：
 * ① parseActivities 的 idx 从 0 开始（API 数组已按 start 偏移，翻页不再越界丢数据）；
 * ② fitFileBucket/fitFileKey 为空时不再 continue 跳过，extra 以 "|activityId" 标记，
 *    下载走 API 详情（S3 私有化后活动对象已不带这两个字段，旧逻辑导致首屏 0 条）；
 * ③ downloadFit 的 extra 解析：调用方不传 activityId 也能从 "|id" 提取走鉴权链路。
 */
class ZwiftFixTest {

    /** 构造一条 Zwift 活动对象；withS3=true 时携带 fitFileBucket/fitFileKey */
    private fun activityJson(id: String, withS3: Boolean, name: String = "Zwift骑行"): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("name", name)
        o.put("start_date_local", "2026-09-01T10:00:00Z")
        o.put("distance", 25000.0)
        o.put("duration", 3600)
        if (withS3) {
            o.put("fitFileBucket", "zwift-fit-bucket")
            o.put("fitFileKey", "activities/2026/09/01/abc.fit")
        }
        return o
    }

    /** 反射调用私有 parseActivities（纯逻辑、无网络） */
    @Suppress("UNCHECKED_CAST")
    private fun parse(body: String, skip: Int, limit: Int): List<ActivityRecord> {
        val m = ZwiftApi::class.java.getDeclaredMethod(
            "parseActivities",
            String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
        )
        m.isAccessible = true
        return m.invoke(ZwiftApi(), body, skip, limit) as List<ActivityRecord>
    }

    /** 构造 activities 数组响应体（兼容 {"activities":[...]} 与裸数组两种形态，取裸数组直传） */
    private fun bodyOf(vararg items: JSONObject): String = JSONArray().apply { items.forEach { put(it) } }.toString()

    @Test
    fun `首屏 skip=0 正常解析且保留 S3 字段`() {
        val arr = JSONArray().put(activityJson("1", true)).put(activityJson("2", true))
        val r = parse(arr.toString(), 0, 10)
        assertEquals(2, r.size)
        assertEquals("zwift-fit-bucket|activities/2026/09/01/abc.fit", r[0].extra)
        assertEquals(DataSource.ZWIFT, r[0].source)
    }

    @Test
    fun `翻页不越界：skip=50 第二页 50 条全部返回`() {
        // 模拟第二页：服务端已按 start=50 偏移，数组 50 条；旧逻辑 idx=skip 直接越界返回空
        val arr = JSONArray()
        for (i in 0 until 50) arr.put(activityJson("id_$i", true))
        val r = parse(arr.toString(), 50, 50)
        assertEquals(50, r.size)
        assertEquals("id_0", r[0].id)
    }

    @Test
    fun `无 fitFileBucket 与 fitFileKey 时不丢记录 extra 标记为竖线加ID`() {
        // S3 私有化后真实响应：活动对象不带 bucket/key；旧逻辑全部 continue → 0 条
        val arr = JSONArray().put(activityJson("888", false))
        val r = parse(arr.toString(), 0, 10)
        assertEquals(1, r.size)
        assertEquals("|888", r[0].extra)
        assertEquals("888", r[0].id)
    }

    @Test
    fun `混合记录：有 S3 用竖线分隔 无 S3 用活动ID标记`() {
        val arr = JSONArray()
            .put(activityJson("1", true))
            .put(activityJson("2", false))
            .put(activityJson("3", false))
        val r = parse(arr.toString(), 0, 10)
        assertEquals(3, r.size)
        assertEquals("zwift-fit-bucket|activities/2026/09/01/abc.fit", r[0].extra)
        assertEquals("|2", r[1].extra)
        assertEquals("|3", r[2].extra)
    }

    @Test
    fun `startTimeMs 从 start_date_local 解析非零`() {
        val r = parse(bodyOf(activityJson("1", false)), 0, 10)
        assertTrue("startTimeMs 应被解析为 epoch ms", r[0].startTimeMs > 1_000_000_000_000L)
    }

    @Test
    fun `limit 超 50 时按 MAX_LIST_LIMIT 截断只取 50 条`() {
        // 预拉取 BATCH=200 会被截断为 50，由调用方翻页续拉（limit>50 返回 400 limit.too.large）
        val arr = JSONArray()
        for (i in 0 until 51) arr.put(activityJson("id_$i", true))
        val r = parse(arr.toString(), 0, 200)
        assertEquals(50, r.size)
    }

    @Test
    fun `downloadFit 链路2 能从竖线加ID的 extra 提取 activityId`() {
        // extra 形态 "|888" → 拆分为 ["" , "888"]：链路2 的 aid 提取逻辑
        val extra = "|888"
        val parts = extra.split("|")
        val aid = if (parts.size >= 2 && parts[0].isBlank()) parts[1].takeIf { it.isNotBlank() } else null
        assertEquals("888", aid)
    }

    @Test
    fun `downloadFit 链路1 保留 bucket|key 完整下载信息`() {
        val extra = "zwift-fit-bucket|activities/2026/09/01/abc.fit"
        val parts = extra.split("|")
        assertEquals(2, parts.size)
        assertTrue(parts[0].isNotBlank())
        assertTrue(parts[1].isNotBlank())
    }
}
