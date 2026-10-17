package com.jichi.ob

import com.jichi.ob.model.DataSource
import com.jichi.ob.util.FitTimeShift
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * v9.2.0 时区修正功能测试（移植开发版 v9.2.2 FitTimeShift + applyIgpTimezoneShift）。
 *
 * 覆盖：
 * ① shiftForIgpsport：+8 小时修正当天窗口内 FIT timestamp，并重算 CRC；
 * ② shiftForIgpsportMinus：-8 小时对称修正；
 * ③ 窗口外 timestamp 不修改、非 FIT 数据原样返回；
 * ④ CRC 重算正确性（重算值 == 写入值）；
 * ⑤ timeShiftSettingKey / isSameDayToday 当天-历史判定。
 */
class FitTimeShiftTest {

    /** 构造最小 FIT：14 字节头(.FIT) + 4 字节 timestamp + 2 字节 CRC */
    private fun fitWithTimestamp(ts: Int): ByteArray {
        val bytes = ByteArray(14 + 4 + 2)
        bytes[8] = '.'.code.toByte()
        bytes[9] = 'F'.code.toByte()
        bytes[10] = 'I'.code.toByte()
        bytes[11] = 'T'.code.toByte()
        bytes[14] = (ts and 0xff).toByte()
        bytes[15] = ((ts shr 8) and 0xff).toByte()
        bytes[16] = ((ts shr 16) and 0xff).toByte()
        bytes[17] = ((ts shr 24) and 0xff).toByte()
        return bytes
    }

    private fun readLe32(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or
                ((data[offset + 1].toInt() and 0xff) shl 8) or
                ((data[offset + 2].toInt() and 0xff) shl 16) or
                ((data[offset + 3].toInt() and 0xff) shl 24)

    /** 重算 CRC 的公开验证途径：再走一遍修正（CRC 写入逻辑在修正内）——用已知字节手算 */
    private fun crcOf(data: ByteArray): Int {
        val crcTable = intArrayOf(
            0x0000, 0xcc01, 0xd801, 0x1400, 0xf001, 0x3c00, 0x2800, 0xe401,
            0xa001, 0x6c00, 0x7800, 0xb401, 0x5000, 0x9c01, 0x8801, 0x4400
        )
        var crc = 0
        for (i in 0 until data.size - 2) {
            val v = data[i].toInt() and 0xff
            crc = ((crc shr 4) and 0xfff) xor crcTable[crc and 0xf]
            crc = crc xor crcTable[v and 0xf]
            crc = ((crc shr 4) and 0xfff) xor crcTable[crc and 0xf]
            crc = crc xor crcTable[(v shr 4) and 0xf]
        }
        return crc and 0xffff
    }

    /** 基准时间：使 startSec 落在 2025-10 附近窗口 */
    private val beijingMs = 1_760_000_000_000L
    private val startSec = beijingMs / 1000 - 631_152_000L
    private val inWindowTs = startSec + 3600            // 当天窗口内
    private val outWindowTs = startSec - 86400 * 30L    // 30 天前，窗口外

    // ===== ① +8 小时修正 =====

    @Test
    fun `加八小时修正当天窗口内时间戳`() {
        val src = fitWithTimestamp(inWindowTs.toInt())
        val out = FitTimeShift.shiftForIgpsport(src, beijingMs)
        assertEquals((inWindowTs + 28800).toInt(), readLe32(out, 14))
        assertEquals(1, FitTimeShift.lastReplacedCount)
    }

    @Test
    fun `加八小时修正后重算CRC`() {
        val src = fitWithTimestamp(inWindowTs.toInt())
        val out = FitTimeShift.shiftForIgpsport(src, beijingMs)
        val expected = crcOf(out)
        val actual = (out[out.size - 2].toInt() and 0xff) or ((out[out.size - 1].toInt() and 0xff) shl 8)
        assertEquals("重算CRC应与写入CRC一致", expected, actual)
    }

    // ===== ② -8 小时对称修正 =====

    @Test
    fun `减八小时修正当天窗口内时间戳`() {
        val src = fitWithTimestamp(inWindowTs.toInt())
        val out = FitTimeShift.shiftForIgpsportMinus(src, beijingMs)
        assertEquals((inWindowTs - 28800).toInt(), readLe32(out, 14))
        assertEquals(1, FitTimeShift.lastReplacedCount)
    }

    @Test
    fun `减八小时修正后重算CRC`() {
        val src = fitWithTimestamp(inWindowTs.toInt())
        val out = FitTimeShift.shiftForIgpsportMinus(src, beijingMs)
        val expected = crcOf(out)
        val actual = (out[out.size - 2].toInt() and 0xff) or ((out[out.size - 1].toInt() and 0xff) shl 8)
        assertEquals(expected, actual)
    }

    // ===== ③ 窗口外 / 非 FIT =====

    @Test
    fun `窗口外时间戳不被修改`() {
        val src = fitWithTimestamp(outWindowTs.toInt())
        val out = FitTimeShift.shiftForIgpsport(src, beijingMs)
        assertEquals(outWindowTs.toInt(), readLe32(out, 14))
        assertEquals(0, FitTimeShift.lastReplacedCount)
        // CRC 不应被改动（未替换）
        assertEquals(src[src.size - 2], out[out.size - 2])
        assertEquals(src[src.size - 1], out[out.size - 1])
    }

    @Test
    fun `非FIT数据原样返回`() {
        val junk = "hello world this is not fit at all".toByteArray()
        val out = FitTimeShift.shiftForIgpsport(junk, beijingMs)
        assertEquals(junk.toList(), out.toList())
        assertEquals(0, FitTimeShift.lastReplacedCount)
    }

    @Test
    fun `短于十四字节的FIT头原样返回`() {
        val short = ByteArray(10) { it.toByte() }
        short[8] = '.'.code.toByte()
        short[9] = 'F'.code.toByte()
        val out = FitTimeShift.shiftForIgpsport(short, beijingMs)
        assertEquals(short.toList(), out.toList())
    }

    // ===== ④ 多次替换多时间戳 =====

    @Test
    fun `多个窗口内时间戳全部修正`() {
        val bytes = ByteArray(14 + 8 + 2)
        bytes[8] = '.'.code.toByte(); bytes[9] = 'F'.code.toByte()
        bytes[10] = 'I'.code.toByte(); bytes[11] = 'T'.code.toByte()
        val ts1 = inWindowTs + 100
        val ts2 = inWindowTs + 200
        bytes[14] = (ts1 and 0xff).toByte(); bytes[15] = ((ts1 shr 8) and 0xff).toByte()
        bytes[16] = ((ts1 shr 16) and 0xff).toByte(); bytes[17] = ((ts1 shr 24) and 0xff).toByte()
        bytes[18] = (ts2 and 0xff).toByte(); bytes[19] = ((ts2 shr 8) and 0xff).toByte()
        bytes[20] = ((ts2 shr 16) and 0xff).toByte(); bytes[21] = ((ts2 shr 24) and 0xff).toByte()

        val out = FitTimeShift.shiftForIgpsport(bytes, beijingMs)
        assertEquals((ts1 + 28800).toInt(), readLe32(out, 14))
        assertEquals((ts2 + 28800).toInt(), readLe32(out, 18))
        assertEquals(2, FitTimeShift.lastReplacedCount)
    }

    // ===== ⑤ 设置键 / 当天判定 =====

    @Test
    fun `当天活动使用same_day键`() {
        val now = System.currentTimeMillis()
        val key = FitTimeShift.timeShiftSettingKey(DataSource.KEEP, now)
        assertEquals("KEEP_same_day", key)
    }

    @Test
    fun `历史活动使用history键`() {
        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, -3)
        }
        val key = FitTimeShift.timeShiftSettingKey(DataSource.XINGZHE, cal.timeInMillis)
        assertEquals("XINGZHE_history", key)
    }

    @Test
    fun `零毫秒返回history键不崩溃`() {
        val key = FitTimeShift.timeShiftSettingKey(DataSource.MAGENE, 0L)
        assertEquals("MAGENE_history", key)
    }

    @Test
    fun `isSameDayToday当天为真`() {
        assertTrue(FitTimeShift.isSameDayToday(System.currentTimeMillis()))
    }

    @Test
    fun `isSameDayToday历史为假`() {
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -10) }
        assertFalse(FitTimeShift.isSameDayToday(cal.timeInMillis))
    }

    @Test
    fun `isSameDayToday零值为假`() {
        assertFalse(FitTimeShift.isSameDayToday(0L))
    }

    // ===== ⑥ 修正不修改输入字节（返回副本）=====

    @Test
    fun `修正不修改原始输入`() {
        val src = fitWithTimestamp(inWindowTs.toInt())
        val snapshot = src.toList()
        FitTimeShift.shiftForIgpsport(src, beijingMs)
        assertEquals(snapshot, src.toList())
    }

    @Test
    fun `加八再减八还原时间戳`() {
        val src = fitWithTimestamp(inWindowTs.toInt())
        val plus = FitTimeShift.shiftForIgpsport(src, beijingMs)
        val back = FitTimeShift.shiftForIgpsportMinus(plus, beijingMs)
        assertEquals(inWindowTs.toInt(), readLe32(back, 14))
        assertNotEquals(src.toList(), plus.toList())
    }
}
