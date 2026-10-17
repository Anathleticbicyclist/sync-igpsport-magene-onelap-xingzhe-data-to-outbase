package com.jichi.ob.util

import android.util.Log
import com.jichi.ob.model.DataSource
import java.util.Calendar

/**
 * FIT 时间戳平移工具（v9.2.0 移植自开发版 v9.2.2 / 源头 v8.9.x）
 *
 * 用途：iGPSPORT 等平台服务器按 UTC+8 解析运动时间，部分平台的历史数据可能整体偏 8 小时。
 * 按平台设置（当天/历史分开）在【上传 Outbase 前】对 FIT 时间戳做 ±8 小时平移：
 *   - shiftForIgpsport：+8 小时（FIT timestamp + 28800s）
 *   - shiftForIgpsportMinus：-8 小时（FIT timestamp - 28800s）
 *
 * FIT timestamp 字段基准为 1989-12-31T00:00:00Z 起的秒数；
 * 算法只平移落在「活动当天窗口」内的 timestamp（窗口由 beijingMs 换算，防止误改其他字段）。
 * 平移后必须重算文件尾部 2 字节 CRC，否则 FIT 校验失败。
 */
object FitTimeShift {
    private const val TAG = "FitTimeShift"
    private const val SHIFT_SECONDS = 28800L          // 0x7080 = 8 小时
    private const val FIT_EPOCH_1990 = 631_152_000L   // 0x259d4c00：开发版使用的近似基准（FIT=1989-12-31，差1天，保持兼容）
    private const val DAY_SECONDS = 86_400L           // 0x15180

    /** 半字节 CRC 表（FIT 文件 CRC，poly 0x8408 的 nibble 形式） */
    private val CRC_TABLE = intArrayOf(
        0x0000, 0xcc01, 0xd801, 0x1400, 0xf001, 0x3c00, 0x2800, 0xe401,
        0xa001, 0x6c00, 0x7800, 0xb401, 0x5000, 0x9c01, 0x8801, 0x4400
    )

    /** 上次平移实际替换的时间戳数量（日志/调试用） */
    @Volatile
    var lastReplacedCount: Int = 0
        private set

    /**
     * FIT CRC（FIT 规范 CRC-16，nibble 查表法，与开发版一致）。
     * 对除尾部 2 字节外的数据计算。
     */
    private fun fitCrc(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            val v = b.toInt() and 0xff
            crc = ((crc shr 4) and 0xfff) xor CRC_TABLE[crc and 0xf]
            crc = crc xor CRC_TABLE[v and 0xf]
            crc = ((crc shr 4) and 0xfff) xor CRC_TABLE[crc and 0xf]
            crc = crc xor CRC_TABLE[(v shr 4) and 0xf]
        }
        return crc and 0xffff
    }

    private fun isFitData(data: ByteArray): Boolean =
        data.size >= 14 && data[8] == '.'.code.toByte() && data[9] == 'F'.code.toByte()

    /**
     * +8 小时：把落在活动当天窗口内的 FIT timestamp 全部 +28800s，并重算 CRC。
     * 与开发版一致：逐字节滑动扫描（未命中 i+=1，命中后跳过 4 字节），
     * 命中窗口为闭区间 [startSec, endSec]。
     * @param fitData FIT 文件字节
     * @param beijingMs 活动开始时间（毫秒，北京时间）
     */
    fun shiftForIgpsport(fitData: ByteArray, beijingMs: Long): ByteArray {
        lastReplacedCount = 0
        if (!isFitData(fitData)) return fitData

        val startSec = beijingMs / 1000 - FIT_EPOCH_1990
        val endSec = startSec + DAY_SECONDS
        Log.d(TAG, "偏移FIT时间戳: 范围[$startSec, $endSec] 每个+28800")

        val out = fitData.copyOf()
        var i = 0
        var replaced = 0
        while (i <= out.size - 4) {
            val ts = (out[i].toInt() and 0xff) or
                    ((out[i + 1].toInt() and 0xff) shl 8) or
                    ((out[i + 2].toInt() and 0xff) shl 16) or
                    ((out[i + 3].toInt() and 0xff) shl 24)
            if (ts >= startSec && ts <= endSec) {
                val shifted = ts + SHIFT_SECONDS.toInt()
                out[i] = (shifted and 0xff).toByte()
                out[i + 1] = ((shifted shr 8) and 0xff).toByte()
                out[i + 2] = ((shifted shr 16) and 0xff).toByte()
                out[i + 3] = ((shifted shr 24) and 0xff).toByte()
                replaced++
                i += 4
            } else {
                i += 1
            }
        }
        Log.d(TAG, "共替换 $replaced 处时间戳")
        lastReplacedCount = replaced
        if (replaced > 0) {
            val crc = fitCrc(out.copyOfRange(0, out.size - 2))
            out[out.size - 2] = (crc and 0xff).toByte()
            out[out.size - 1] = ((crc shr 8) and 0xff).toByte()
        }
        return out
    }

    /**
     * -8 小时：把落在活动当天窗口内的 FIT timestamp 全部 -28800s，并重算 CRC。
     */
    fun shiftForIgpsportMinus(fitData: ByteArray, beijingMs: Long): ByteArray {
        lastReplacedCount = 0
        if (!isFitData(fitData)) return fitData

        val startSec = beijingMs / 1000 - FIT_EPOCH_1990
        val endSec = startSec + DAY_SECONDS
        Log.d(TAG, "偏移FIT时间戳: 范围[$startSec, $endSec] 每个-28800")

        val out = fitData.copyOf()
        var i = 0
        var replaced = 0
        while (i <= out.size - 4) {
            val ts = (out[i].toInt() and 0xff) or
                    ((out[i + 1].toInt() and 0xff) shl 8) or
                    ((out[i + 2].toInt() and 0xff) shl 16) or
                    ((out[i + 3].toInt() and 0xff) shl 24)
            if (ts >= startSec && ts <= endSec) {
                val shifted = ts - SHIFT_SECONDS.toInt()
                out[i] = (shifted and 0xff).toByte()
                out[i + 1] = ((shifted shr 8) and 0xff).toByte()
                out[i + 2] = ((shifted shr 16) and 0xff).toByte()
                out[i + 3] = ((shifted shr 24) and 0xff).toByte()
                replaced++
                i += 4
            } else {
                i += 1
            }
        }
        Log.d(TAG, "共替换 $replaced 处时间戳")
        lastReplacedCount = replaced
        if (replaced > 0) {
            val crc = fitCrc(out.copyOfRange(0, out.size - 2))
            out[out.size - 2] = (crc and 0xff).toByte()
            out[out.size - 1] = ((crc shr 8) and 0xff).toByte()
        }
        return out
    }

    /**
     * 时区修正设置键：source.name() + ("_same_day" / "_history")（与开发版 TimeZoneSettingsActivity 一致）。
     * 当天/历史判定：活动开始时间（本地时区）与"今天"是否为同一天。
     */
    fun timeShiftSettingKey(source: DataSource, startTimeMs: Long): String {
        val suffix = if (isSameDayToday(startTimeMs)) "_same_day" else "_history"
        return source.name + suffix
    }

    /** 活动开始时间与当前"今天"（本地时区）同年月日 → 当天 */
    fun isSameDayToday(startTimeMs: Long): Boolean {
        if (startTimeMs <= 0) return false
        val today = Calendar.getInstance()
        val act = Calendar.getInstance().apply { timeInMillis = startTimeMs }
        return today.get(Calendar.YEAR) == act.get(Calendar.YEAR) &&
                today.get(Calendar.MONTH) == act.get(Calendar.MONTH) &&
                today.get(Calendar.DAY_OF_MONTH) == act.get(Calendar.DAY_OF_MONTH)
    }
}
