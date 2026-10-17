package com.jichi.ob

import com.jichi.ob.api.IgpsportApi
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v8.8.6 iGPSPORT 国际区登录响应 token 解析测试。
 * intervalssync 返回 data.access_token；兼容 data.accessToken / 顶层两种结构。
 */
class IgpsportIntTest {

    @Test
    fun `标准结构 data access_token 提取`() {
        val json = JSONObject("""{"code":0,"data":{"access_token":"tok123"}}""")
        assertEquals("tok123", IgpsportApi.parseAccessToken(json))
    }

    @Test
    fun `data accessToken 命名兼容`() {
        val json = JSONObject("""{"code":0,"data":{"accessToken":"tok456"}}""")
        assertEquals("tok456", IgpsportApi.parseAccessToken(json))
    }

    @Test
    fun `顶层 access_token 兼容`() {
        val json = JSONObject("""{"code":0,"access_token":"tok789"}""")
        assertEquals("tok789", IgpsportApi.parseAccessToken(json))
    }

    @Test
    fun `顶层 accessToken 兼容`() {
        val json = JSONObject("""{"code":0,"accessToken":"tokABC"}""")
        assertEquals("tokABC", IgpsportApi.parseAccessToken(json))
    }

    @Test
    fun `登录失败无 token 返回空串`() {
        val json = JSONObject("""{"code":40001,"message":"invalid password"}""")
        assertEquals("", IgpsportApi.parseAccessToken(json))
    }

    @Test
    fun `data 为空对象时回退顶层`() {
        val json = JSONObject("""{"code":0,"data":{},"access_token":"tokFALLBACK"}""")
        assertEquals("tokFALLBACK", IgpsportApi.parseAccessToken(json))
    }

    @Test
    fun `国际区端点常量指向 prod_en`() {
        assertTrue(IgpsportApi.LOGIN_URL_INT.contains("prod.en.igpsport.com"))
    }
}
