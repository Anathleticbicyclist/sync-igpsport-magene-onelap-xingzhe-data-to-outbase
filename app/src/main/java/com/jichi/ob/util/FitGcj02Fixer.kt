package com.jichi.ob.util

/**
 * v7.6.9: 迈金FIT坐标 GCJ-02 → WGS-84 纯Kotlin实现
 *
 * 移植自 assets/magene_fix.js（基于 dwmer0308-a11y/magene-fit-strava-fix 验证方案，
 * 已在 Strava 实测验证能匹配赛段，青岛地区平均偏移约450米）。
 *
 * 用于后台自动同步（Worker 无 WebView 环境），与手动同步的 WebView 版本算法完全一致。
 * 仅处理 record 消息的 lat(field 0)/long(field 1) 半圆坐标并重算 CRC-16/ARC。
 */
object FitGcj02Fixer {

    private val CRC_TABLE = intArrayOf(
        0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
        0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400
    )

    /** 修复 FIT 文件坐标；返回修复后的字节数组；非 FIT/无需修复/出错时返回 null（调用方保留原文件） */
    fun fix(inputData: ByteArray): ByteArray? {
        return try {
            if (inputData.size < 14) return null
            val headerSize = inputData[0].toInt() and 0xFF
            if (headerSize != 12 && headerSize != 14) return null
            val signature = String(byteArrayOf(inputData[8], inputData[9], inputData[10], inputData[11]), Charsets.US_ASCII)
            if (signature != ".FIT") return null

            val dataSize = (inputData[4].toInt() and 0xFF) or ((inputData[5].toInt() and 0xFF) shl 8) or
                ((inputData[6].toInt() and 0xFF) shl 16) or ((inputData[7].toInt() and 0xFF) shl 24)
            if (dataSize <= 0 || headerSize > inputData.size) return null

            // v9.7.0 兼容性增强：
            //  1) 头部声明长度 < 实际长度 → 按声明截断（多余尾巴不影响）；
            //  2) 头部声明长度 > 实际长度（下载被截断的 FIT，平台偶发）→ 不再直接判失败，
            //     按实际可用长度尽力解析，尾部 CRC 缺失时补写，避免整份文件白修；
            //  3) 解析越界/缺定义时立即停止而不是盲跳字节，防止错位把后面的数据改坏。
            val declaredEnd = headerSize + dataSize
            val hasCrc = inputData.size >= declaredEnd + 2
            val data = if (declaredEnd + 2 <= inputData.size) inputData.copyOfRange(0, declaredEnd + 2) else inputData.copyOf()
            val bodyEnd = minOf(declaredEnd, data.size)

            val definitions = HashMap<Int, Definition>()
            var offset = headerSize
            var changed = false

            while (offset < bodyEnd) {
                val recordHeader = data[offset].toInt() and 0xFF
                offset += 1

                if (recordHeader and 0x80 != 0) {
                    // Compressed timestamp header
                    val localMessageType = (recordHeader shr 5) and 0x03
                    val definition = definitions[localMessageType] ?: break
                    if (offset + definition.size + 1 > bodyEnd) break
                    if (patchRecord(data, offset, definition)) changed = true
                    offset += definition.size + 1 // +1 for time offset byte
                } else {
                    val localMessageType = recordHeader and 0x0F
                    val isDefinition = recordHeader and 0x40 != 0
                    val hasDeveloperData = recordHeader and 0x20 != 0

                    if (isDefinition) {
                        val parsed = parseDefinition(data, offset, hasDeveloperData) ?: break
                        definitions[localMessageType] = parsed.first
                        offset = parsed.second
                    } else {
                        val definition = definitions[localMessageType] ?: break
                        if (offset + definition.size > bodyEnd) break
                        if (patchRecord(data, offset, definition)) changed = true
                        offset += definition.size
                    }
                }
            }

            if (!changed) return inputData

            // 重算 CRC-16/ARC
            if (hasCrc) {
                val newCRC = fitCRC(data.copyOfRange(0, data.size - 2))
                data[data.size - 2] = (newCRC and 0xFF).toByte()
                data[data.size - 1] = ((newCRC shr 8) and 0xFF).toByte()
                data
            } else {
                // 截断文件：缺尾部 CRC，补两条
                val newCRC = fitCRC(data)
                data + byteArrayOf((newCRC and 0xFF).toByte(), ((newCRC shr 8) and 0xFF).toByte())
            }
        } catch (e: Exception) {
            null
        }
    }

    /** GCJ-02 → WGS-84 公开转换（供 Keep 等 GCJ-02 数据源在构建 GPX 时转换坐标）。
     *  非中国大陆坐标直接返回原值。返回 Pair(lat, lon)。 */
    fun gcj02ToWgs84(lat: Double, lon: Double): Pair<Double, Double> {
        if (lat.isNaN() || lon.isNaN()) return lat to lon
        if (!isInChina(lat, lon)) return lat to lon
        return gcj02ToWgs84Exact(lat, lon)
    }

    private class FieldDef(val number: Int, val size: Int, val baseType: Int, val offset: Int)
    private class Definition(val littleEndian: Boolean, val fields: List<FieldDef>, val size: Int)

    private fun fitCRC(data: ByteArray): Int {
        var crc = 0
        for (i in data.indices) {
            val byte = data[i].toInt() and 0xFF
            var tmp = CRC_TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[byte and 0xF]
            tmp = CRC_TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[(byte shr 4) and 0xF]
        }
        return crc and 0xFFFF
    }

    private fun readU16(data: ByteArray, offset: Int, littleEndian: Boolean): Int {
        return if (littleEndian) {
            (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
        } else {
            ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
        }
    }

    private fun readI32(data: ByteArray, offset: Int, littleEndian: Boolean): Int {
        return if (littleEndian) {
            (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or ((data[offset + 3].toInt() and 0xFF) shl 24)
        } else {
            ((data[offset].toInt() and 0xFF) shl 24) or ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
        }
    }

    private fun writeI32(data: ByteArray, offset: Int, value: Int, littleEndian: Boolean) {
        if (littleEndian) {
            data[offset] = (value and 0xFF).toByte()
            data[offset + 1] = ((value shr 8) and 0xFF).toByte()
            data[offset + 2] = ((value shr 16) and 0xFF).toByte()
            data[offset + 3] = ((value shr 24) and 0xFF).toByte()
        } else {
            data[offset] = ((value shr 24) and 0xFF).toByte()
            data[offset + 1] = ((value shr 16) and 0xFF).toByte()
            data[offset + 2] = ((value shr 8) and 0xFF).toByte()
            data[offset + 3] = (value and 0xFF).toByte()
        }
    }

    private fun semicirclesToDegrees(value: Int): Double = value * (180.0 / 2147483648.0)
    private fun degreesToSemicircles(value: Double): Int = Math.round(value * 2147483648.0 / 180.0).toInt()

    private fun isInChina(lat: Double, lon: Double): Boolean =
        72.004 <= lon && lon <= 137.8347 && 0.8293 <= lat && lat <= 55.8271

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y
        ret += 0.2 * Math.sqrt(Math.abs(x))
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320.0 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y
        ret += 0.1 * Math.sqrt(Math.abs(x))
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return ret
    }

    private fun wgs84ToGcj02(lat: Double, lon: Double): Pair<Double, Double> {
        val a = 6378245.0
        val ee = 0.00669342162296594323
        var dLat = transformLat(lon - 105.0, lat - 35.0)
        var dLon = transformLon(lon - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * Math.PI
        var magic = Math.sin(radLat)
        magic = 1 - ee * magic * magic
        val sqrtMagic = Math.sqrt(magic)
        dLat = (dLat * 180.0) / ((a * (1 - ee)) / (magic * sqrtMagic) * Math.PI)
        dLon = (dLon * 180.0) / (a / sqrtMagic * Math.cos(radLat) * Math.PI)
        return (lat + dLat) to (lon + dLon)
    }

    /** GCJ-02 → WGS-84 二分迭代精确解（与 magene_fix.js 一致） */
    private fun gcj02ToWgs84Exact(lat: Double, lon: Double): Pair<Double, Double> {
        var minLat = lat - 0.02; var maxLat = lat + 0.02
        var minLon = lon - 0.02; var maxLon = lon + 0.02
        var currentLat = lat; var currentLon = lon
        for (i in 0 until 30) {
            currentLat = (minLat + maxLat) / 2
            currentLon = (minLon + maxLon) / 2
            val (convLat, convLon) = wgs84ToGcj02(currentLat, currentLon)
            val deltaLat = convLat - lat
            val deltaLon = convLon - lon
            if (Math.abs(deltaLat) < 1e-8 && Math.abs(deltaLon) < 1e-8) break
            if (deltaLat > 0) maxLat = currentLat else minLat = currentLat
            if (deltaLon > 0) maxLon = currentLon else minLon = currentLon
        }
        return currentLat to currentLon
    }

    private fun parseDefinition(data: ByteArray, startOffset: Int, hasDeveloperData: Boolean): Pair<Definition, Int>? {
        // v9.7.0: 全部读取前做越界判断，越界返回 null（调用方立即停止解析），不再靠异常兜底
        if (startOffset + 5 > data.size) return null
        var offset = startOffset + 1 // 跳过 Reserved
        val architecture = data[offset].toInt() and 0xFF
        offset += 1
        val littleEndian = architecture == 0
        offset += 2 // global message number
        val fieldCount = data[offset].toInt() and 0xFF
        offset += 1
        if (offset + fieldCount * 3 > data.size) return null
        val fields = mutableListOf<FieldDef>()
        var messageOffset = 0
        for (i in 0 until fieldCount) {
            val number = data[offset].toInt() and 0xFF
            val size = data[offset + 1].toInt() and 0xFF
            val baseType = data[offset + 2].toInt() and 0xFF
            offset += 3
            fields.add(FieldDef(number, size, baseType, messageOffset))
            messageOffset += size
        }
        if (hasDeveloperData) {
            if (offset + 1 > data.size) return null
            val devFieldCount = data[offset].toInt() and 0xFF
            offset += 1
            if (offset + devFieldCount * 3 > data.size) return null
            offset += devFieldCount * 3
        }
        return Definition(littleEndian, fields, messageOffset) to offset
    }

    /** 改写单条 record 的 lat/lon；返回是否发生修改 */
    private fun patchRecord(data: ByteArray, dataOffset: Int, definition: Definition): Boolean {
        val fieldsByNumber = definition.fields.associateBy { it.number }
        val latField = fieldsByNumber[0] ?: return false
        val lonField = fieldsByNumber[1] ?: return false
        if (latField.size != 4 || lonField.size != 4) return false
        // v9.7.0: 读写前越界保护（截断文件走到尾部时不再越界读）
        if (dataOffset + latField.offset + 4 > data.size) return false
        if (dataOffset + lonField.offset + 4 > data.size) return false

        val latRaw = readI32(data, dataOffset + latField.offset, definition.littleEndian)
        val lonRaw = readI32(data, dataOffset + lonField.offset, definition.littleEndian)
        if (latRaw == 0x7FFFFFFF || latRaw == -0x80000000 || lonRaw == 0x7FFFFFFF || lonRaw == -0x80000000) return false

        val lat = semicirclesToDegrees(latRaw)
        val lon = semicirclesToDegrees(lonRaw)
        if (!lat.isFinite() || !lon.isFinite()) return false
        if (!isInChina(lat, lon)) return false

        val (fixedLat, fixedLon) = gcj02ToWgs84Exact(lat, lon)
        val fixedLatRaw = degreesToSemicircles(fixedLat)
        val fixedLonRaw = degreesToSemicircles(fixedLon)
        if (fixedLatRaw != latRaw || fixedLonRaw != lonRaw) {
            writeI32(data, dataOffset + latField.offset, fixedLatRaw, definition.littleEndian)
            writeI32(data, dataOffset + lonField.offset, fixedLonRaw, definition.littleEndian)
            return true
        }
        return false
    }
}
