package com.jichi.ob

import com.jichi.ob.ui.MergeFragment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v8.7.1 移植单测：合并文件 SAF 内容检测 FIT 头 / GPX XML 。
 * 修复"手动打开 FIT 无法合并、提示未选择"——部分 ROM SAF 返回 provider 内部 id 无扩展名 ，
 * 改为内容检测兜底。
 */
class MergeContentSniffTest {

    @Test
    fun `FIT 文件头 第 8-11 字节为 _FIT 识别成功`() {
        // 构造 12+ 字节且第 8-11 字节 = ".FIT"
        val b = ByteArray(64)
        b[8] = '.'.code.toByte()
        b[9] = 'F'.code.toByte()
        b[10] = 'I'.code.toByte()
        b[11] = 'T'.code.toByte()
        assertTrue(MergeFragment.isFitContent(b))
    }

    @Test
    fun `长度不足 12 的字节数组不误判`() {
        val b = ByteArray(8)
        assertFalse(MergeFragment.isFitContent(b))
    }

    @Test
    fun `非 FIT 内容不误判`() {
        val b = ByteArray(64) // 全 0
        assertFalse(MergeFragment.isFitContent(b))
    }

    @Test
    fun `GPX 头部 XML 识别成功 大小写不敏感 `() {
        assertTrue(MergeFragment.isGpxContent("""<?xml version="1.0"?><gpx creator="jichi" version="1.1">"""))
        assertTrue(MergeFragment.isGpxContent("""<GPX xmlns="http://www.topografix.com/GPX/1/1">"""))
    }

    @Test
    fun `非 GPX 文本不误判`() {
        assertFalse(MergeFragment.isGpxContent("this is a plain text file"))
        assertFalse(MergeFragment.isGpxContent(""))
    }
}
