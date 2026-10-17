package com.jichi.ob

import com.jichi.ob.api.BrytonApi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v8.8.6 百锐腾移动端下载 FIT 魔数校验测试。
 * 模拟：真实 FIT 头 / SPA HTML 误响应 / 截断数据 / 空数据。
 */
class BrytonFitTest {

    /** 构造一个合法的 FIT 头：12~13 字节（index 8..11）为 ".FIT"，长度≥14 */
    private fun fitHeader(size: Int): ByteArray {
        val bytes = ByteArray(size)
        // FIT 头部 8 字节 + ".FIT"
        bytes[8] = '.'.code.toByte()
        bytes[9] = 'F'.code.toByte()
        bytes[10] = 'I'.code.toByte()
        bytes[11] = 'T'.code.toByte()
        return bytes
    }

    @Test
    fun `真实 FIT 文件通过校验`() {
        assertTrue(BrytonApi.isFitMagic(fitHeader(1024)))
    }

    @Test
    fun `恰好14字节最小FIT头通过校验`() {
        assertTrue(BrytonApi.isFitMagic(fitHeader(14)))
    }

    @Test
    fun `SPA HTML 响应（小于14字节或非FIT魔数）拒绝`() {
        // 网页端误响应：HTML 文本
        val html = "<!DOCTYPE html><html><body>SPA</body></html>".toByteArray()
        assertFalse(BrytonApi.isFitMagic(html))
    }

    @Test
    fun `截断的 FIT（不足14字节）拒绝`() {
        assertFalse(BrytonApi.isFitMagic(fitHeader(13)))
    }

    @Test
    fun `空响应拒绝`() {
        assertFalse(BrytonApi.isFitMagic(ByteArray(0)))
    }

    @Test
    fun `魔数位置错位（index 0 起才匹配）拒绝`() {
        // ".FIT" 出现在文件头而不是 index 8
        val bad = ".FIT".toByteArray() + ByteArray(64)
        assertFalse(BrytonApi.isFitMagic(bad))
    }
}
