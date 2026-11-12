package com.jichi.ob

import com.jichi.ob.api.MageneApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * v9.6.6 迈金 OTM「看不到最新运动」修复测试。
 *
 * 覆盖：
 * ① capability_list 预导入的轮次判定（单轮窗口 20 条，inserted==total 才追，最多 3 轮追加）；
 * ② refresh_token 轮换合并规则（服务端下发非空才覆盖，未下发保留旧值）；
 * ③ JWT uid 解析（uid / userId / sub 逐级降级，缺失返回空串不崩溃）。
 */
class MageneOtmTest {

    private fun jwt(payloadJson: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val head = enc.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val body = enc.encodeToString(payloadJson.toByteArray())
        return "$head.$body.c2ln"
    }

    // ===== ① 预导入轮次判定 =====

    @Test
    fun firstRoundFullWindow_shouldContinue() {
        // 首轮窗口 20 条全部导入，可能还有下一批 → 继续追
        assertTrue(MageneApi.shouldContinueImport(1, 20, 20))
    }

    @Test
    fun thirdRoundFullWindow_shouldContinue_toLastRound() {
        // 第 3 轮仍允许追（上限 4 轮 = 首轮 + 3 次追加）
        assertTrue(MageneApi.shouldContinueImport(3, 20, 20))
    }

    @Test
    fun fourthRoundFullWindow_shouldStop() {
        // 已到上限，即使窗口导满也不再追，避免无上限刷接口触发风控
        assertFalse(MageneApi.shouldContinueImport(4, 20, 20))
    }

    @Test
    fun partialWindow_shouldStop() {
        // 窗口只有 3 条、全部导入 → 没有下一批，立即停止
        assertFalse(MageneApi.shouldContinueImport(1, 3, 3))
        // 服务端仍有更多（total 20）但本轮插入 3 条 → 窗口未满，不再追
        assertFalse(MageneApi.shouldContinueImport(1, 3, 20))
    }

    @Test
    fun nothingInserted_shouldStop() {
        assertFalse(MageneApi.shouldContinueImport(1, 0, 0))
        assertFalse(MageneApi.shouldContinueImport(1, 0, 20))
    }

    // ===== ② refresh_token 合并 =====

    @Test
    fun refreshToken_returnedNew_overwritesOld() {
        assertEquals("new_rt", MageneApi.mergeRefreshToken("old_rt", "new_rt"))
    }

    @Test
    fun refreshToken_emptyOrNull_keepsOld() {
        // 服务端未下发新轮换令牌时不能把旧值写空，否则下次彻底刷不了
        assertEquals("old_rt", MageneApi.mergeRefreshToken("old_rt", ""))
        assertEquals("old_rt", MageneApi.mergeRefreshToken("old_rt", null))
    }

    @Test
    fun refreshToken_noOldAndNoneNew_staysNull() {
        assertEquals(null, MageneApi.mergeRefreshToken(null, ""))
    }

    // ===== ③ JWT uid 解析降级 =====

    @Test
    fun uidFromJwt_primaryUidField() {
        assertEquals("13053000", MageneApi().getUidFromToken(jwt("""{"uid":13053000,"exp":1800000000}""")))
    }

    @Test
    fun uidFromJwt_fallbackUserId() {
        assertEquals("u_9527", MageneApi().getUidFromToken(jwt("""{"userId":"u_9527"}""")))
    }

    @Test
    fun uidFromJwt_fallbackSub() {
        assertEquals("sub_1", MageneApi().getUidFromToken(jwt("""{"sub":"sub_1"}""")))
    }

    @Test
    fun uidFromJwt_missingUid_degradeEmpty() {
        // 缺失 uid 时降级发空串，接口照常调用，不抛异常中断同步
        assertEquals("", MageneApi().getUidFromToken(jwt("""{"nickname":"骑行者","exp":1800000000}""")))
    }

    @Test
    fun uidFromJwt_notJwt_returnsEmpty() {
        assertEquals("", MageneApi().getUidFromToken("plain-token-without-dots"))
        assertEquals("", MageneApi().getUidFromToken(""))
    }
}
