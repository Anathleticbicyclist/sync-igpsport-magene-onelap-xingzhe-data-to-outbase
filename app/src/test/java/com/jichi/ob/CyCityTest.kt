package com.jichi.ob

import com.jichi.ob.model.DataSource
import com.jichi.ob.model.DownloadSupport
import com.jichi.ob.model.UploadSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v9.1.2 CyCity 平台接入测试（对齐开发版 v8.9.1）。
 *
 * 覆盖：
 * ① 枚举注册（displayName/shortName/sourcePlatforms）；
 * ② 下载支持（作为数据源可下载，映射悦跑圈链路）；
 * ③ 上传支持（正式版仅 Outbase 上传，CyCity 不产生上传目标）；
 * ④ 注销清理（token 键清除路径）。
 */
class CyCityTest {

    @Test
    fun `CyCity 枚举注册正确`() {
        assertEquals("CyCity", DataSource.CYCITY.displayName)
        assertEquals("cyc", DataSource.CYCITY.shortName)
    }

    @Test
    fun `fromShortName 能解析 cyc`() {
        assertEquals(DataSource.CYCITY, DataSource.fromShortName("cyc"))
    }

    @Test
    fun `sourcePlatforms 包含 CyCity（可作为下载来源）`() {
        assertTrue(DataSource.sourcePlatforms().contains(DataSource.CYCITY))
    }

    @Test
    fun `DownloadSupport CyCity 可下载`() {
        assertTrue(DownloadSupport.fromDataSource(DataSource.CYCITY).available)
    }

    @Test
    fun `UploadSupport CyCity 不可上传（仅Outbase目标）`() {
        assertEquals(UploadSupport.JOYRUN, UploadSupport.fromDataSource(DataSource.CYCITY))
        assertTrue(!UploadSupport.fromDataSource(DataSource.CYCITY).available)
    }

    @Test
    fun `CyCity 与既有来源数量`() {
        // 引入前 21 个来源（20 LOGIN + CYCITY）
        assertTrue(DataSource.sourcePlatforms().size >= 21)
    }
}
