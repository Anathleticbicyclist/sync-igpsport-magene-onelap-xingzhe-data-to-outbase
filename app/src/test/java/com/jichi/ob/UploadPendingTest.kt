package com.jichi.ob

import com.jichi.ob.api.UploadEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v8.6.5 移植单测：Outbase 上传结果分类 成功 / 已存在跳过 / 待处理 pending / 失败 。
 * pending 语义：已上传但目标端"待处理/未确认入库"——不写已同步记忆，下次同步重试。
 */
class UploadPendingTest {

    @Test
    fun `上传成功判定`() {
        val r = UploadEngine.classifyUploadMsg("上传成功", skipped = false)
        assertTrue(r.success)
        assertFalse(r.skipped)
        assertFalse(r.pending)
    }

    @Test
    fun `已存在跳过判定`() {
        val r = UploadEngine.classifyUploadMsg("已存在，跳过", skipped = true)
        assertFalse(r.success)
        assertTrue(r.skipped)
        assertFalse(r.pending)
    }

    @Test
    fun `待处理不算成功且标记 pending`() {
        val r = UploadEngine.classifyUploadMsg("已上传，待处理", skipped = false)
        assertFalse(r.success)      // 不按成功计——不写已同步记忆
        assertFalse(r.skipped)
        assertTrue(r.pending)       // 下次同步重试
    }

    @Test
    fun `待处理即使含成功字样也不算成功`() {
        val r = UploadEngine.classifyUploadMsg("上传成功，等待处理", skipped = false)
        assertFalse(r.success)
        assertTrue(r.pending)
    }

    @Test
    fun `普通失败判定`() {
        val r = UploadEngine.classifyUploadMsg("网络错误", skipped = false)
        assertFalse(r.success)
        assertFalse(r.pending)
    }
}
