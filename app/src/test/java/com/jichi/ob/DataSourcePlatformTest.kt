package com.jichi.ob

import com.jichi.ob.model.DataSource
import com.jichi.ob.model.DownloadSupport
import com.jichi.ob.model.UploadSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v8.8.6 平台模型完整性测试。
 * 覆盖：来源清单（含新增 iGPSPORT国际）、shortName 唯一性、
 * 目标侧只允许 Outbase 上传（正式版铁律）、来源侧全部可下载（含百锐腾移动端/igp国际）。
 */
class DataSourcePlatformTest {

    @Test
    fun `来源清单包含全部21个来源含iGPSPORT国际且顺序在igp之后`() {
        val list = DataSource.sourcePlatforms()
        assertEquals(21, list.size)
        assertTrue(list.contains(DataSource.IGPSPORT_INT))
        assertTrue(list.contains(DataSource.CYCITY))
        // IGPSPORT_INT 紧跟在 IGPSPORT 之后
        assertEquals(DataSource.IGPSPORT, list[0])
        assertEquals(DataSource.IGPSPORT_INT, list[1])
    }

    @Test
    fun `来源清单不含Outbase（目标是固定卡不占来源位）`() {
        assertFalse(DataSource.sourcePlatforms().contains(DataSource.OUTBASE))
    }

    @Test
    fun `全部来源 shortName 唯一且 displayName 非空`() {
        val names = DataSource.sourcePlatforms().map { it.shortName }
        assertEquals(names.size, names.toSet().size)
        DataSource.sourcePlatforms().forEach {
            assertTrue("${it.displayName} shortName 为空", it.shortName.isNotBlank())
            assertTrue("${it.displayName} displayName 为空", it.displayName.isNotBlank())
        }
    }

    @Test
    fun `iGPSPORT国际 shortName 为 igpi 且可反向解析`() {
        assertEquals("igpi", DataSource.IGPSPORT_INT.shortName)
        assertEquals(DataSource.IGPSPORT_INT, DataSource.fromShortName("igpi"))
    }

    @Test
    fun `目标上传：只有 Outbase 可用（正式版铁律）`() {
        assertTrue(UploadSupport.fromDataSource(DataSource.OUTBASE).available)
        // 所有来源（含新加的国际区）都不能作为上传目标
        DataSource.sourcePlatforms().forEach { ds ->
            assertFalse("${ds.displayName} 不应可上传", UploadSupport.fromDataSource(ds).available)
        }
    }

    @Test
    fun `来源下载：全部20个来源都支持下载（含百锐腾与igp国际）`() {
        DataSource.sourcePlatforms().forEach { ds ->
            assertTrue("${ds.displayName} 应支持下载", DownloadSupport.fromDataSource(ds).available)
        }
    }

    @Test
    fun `百锐腾下载备注标明移动端REST`() {
        assertTrue(DownloadSupport.BRYTON.available)
        assertTrue(DownloadSupport.BRYTON.note.contains("移动端"))
    }
}
