package com.jichi.ob.merge

import com.garmin.fit.Decode
import com.garmin.fit.MesgBroadcaster
import com.garmin.fit.RecordMesg
import com.garmin.fit.RecordMesgListener
import com.garmin.fit.SessionMesg
import com.garmin.fit.SessionMesgListener
import com.garmin.fit.Sport
import com.garmin.fit.SportMesg
import com.garmin.fit.SportMesgListener
import com.jichi.ob.GpxToFitConverter
import java.io.ByteArrayInputStream

/**
 * v7.9.9 FIT 解析器：基于 Garmin 官方 FIT SDK（com.garmin:fit）。
 *
 * 背景：自研二进制解析器对真实码表文件（compressed timestamp、多 local type、
 * 字段类型组合）脆弱，且黑鸟等平台下载的损坏/截断文件无法解析。改用官方 SDK：
 * - 兼容所有标准 FIT 设备文件（佳明/迈金/黑鸟/iGPSPORT/Wahoo 等）
 * - 自动处理 compressed timestamp、无效坐标、字段缺失
 * - decode 返回 false 或 record 为空 → 明确诊断为"文件损坏/无轨迹"
 *
 * 外部接口保持与旧实现一致：parse(bytes) → FitResult?（null=解析失败）
 */
object FitParser {

    data class FitResult(
        val points: List<GpxToFitConverter.TrackPoint>,
        val sport: Int,
        val title: String
    )

    /**
     * v9.7.0（对齐开发版 v9.6.9）：单段 FIT 的 session 原始指标。
     * 合并时直接取码表记录的里程/计时/爬升原值，而不是按 GPS 点列重算——
     * 点列重算会因简化、漂移、跨段跳飞而系统性偏低或偏高。
     */
    data class SessionMeta(
        val startSec: Long,
        val distanceM: Double,
        val timerSec: Long,
        val ascentM: Double
    )

    /** 读取 FIT 的 session 原始指标；读不到返回 null（调用方回落到点列重算）。 */
    fun parseSessionMeta(bytes: ByteArray): SessionMeta? {
        return try {
            if (bytes.size < 14) return null
            if (bytes[8].toInt() and 0xFF != '.'.code || bytes[9].toInt() and 0xFF != 'F'.code) return null

            var meta: SessionMeta? = null
            val decode = Decode()
            val bc = MesgBroadcaster(decode)
            decode.addListener(bc as com.garmin.fit.MesgListener)
            bc.addListener(object : SessionMesgListener {
                override fun onMesg(mesg: SessionMesg) {
                    if (meta != null) return
                    val start = mesg.startTime?.timestamp?.let { it + 631065600L } ?: 0L
                    val dist = (mesg.totalDistance ?: 0f).toDouble()
                    val timer = (mesg.totalTimerTime ?: 0f).toDouble()
                    val ascent = mesg.totalAscent?.toDouble() ?: 0.0
                    // 里程或计时为 0 视为设备未记录，交由点列重算兜底
                    if (dist <= 0.0 || timer <= 0.0) return
                    meta = SessionMeta(start, dist, timer.toLong(), ascent)
                }
            })
            try {
                decode.read(ByteArrayInputStream(bytes).buffered())
            } catch (_: Exception) { return meta }
            meta
        } catch (_: Exception) { null }
    }

    /** 解析 FIT 字节。返回 null 表示解析失败（文件损坏或非 FIT）。 */
    fun parse(bytes: ByteArray): FitResult? {
        return try {
            if (bytes.size < 14) return null
            // FIT 魔数 ".FIT"（byte8..11）
            if (bytes[8].toInt() and 0xFF != '.'.code || bytes[9].toInt() and 0xFF != 'F'.code) return null

            val records = ArrayList<RecordMesg>(4096)
            var sport = -1
            val decode = Decode()
            val bc = MesgBroadcaster(decode)
            // 重要：SDK 21.x 的 MesgBroadcaster(decode) 构造器不会自动注册到 Decode，
            // 必须显式 addListener，否则收不到任何消息（实测 records=0）
            decode.addListener(bc as com.garmin.fit.MesgListener)
            bc.addListener(object : RecordMesgListener {
                override fun onMesg(mesg: RecordMesg) { records.add(mesg) }
            })
            bc.addListener(object : SportMesgListener {
                override fun onMesg(mesg: SportMesg) {
                    val s = mesg.sport
                    if (s != null && s != Sport.INVALID) sport = s.value.toInt()
                }
            })
            bc.addListener(object : SessionMesgListener {
                override fun onMesg(mesg: SessionMesg) {
                    if (sport == -1) {
                        val s = mesg.sport
                        if (s != null && s != Sport.INVALID) sport = s.value.toInt()
                    }
                }
            })

            val ok = try {
                decode.read(ByteArrayInputStream(bytes).buffered())
            } catch (_: Exception) { false }

            if (!ok) return null

            // 收集有效坐标点（Garmin SDK 已把无效坐标 0x80000000 转为 null）
            val pts = ArrayList<GpxToFitConverter.TrackPoint>(records.size)
            var lastTs = 0L
            for (r in records) {
                val lat = r.positionLat ?: continue
                val lon = r.positionLong ?: continue
                val ele = r.altitude?.toDouble() ?: 0.0
                // Garmin DateTime.getTimestamp() 返回 FIT epoch 秒（偏移 631065600），转 Unix 秒
                val ts = r.timestamp?.getTimestamp()?.let { it + 631065600L } ?: 0L
                if (ts > 0) lastTs = ts
                pts.add(
                    GpxToFitConverter.TrackPoint(
                        lat = lat * 180.0 / 2147483648.0,
                        lon = lon * 180.0 / 2147483648.0,
                        ele = if (ele > 0) ele else 0.0,
                        ts = if (ts > 0) ts else lastTs,
                        hr = (r.heartRate ?: 0).toInt().coerceIn(0, 255),
                        cad = (r.cadence ?: 0).toInt().coerceIn(0, 255),
                        power = (r.power ?: 0).toInt().coerceIn(0, 65535)
                    )
                )
            }
            if (pts.isEmpty()) {
                // v9.7.0: 室内骑行台（顽鹿 / 迈金台子等）导出的 FIT 通常不含 GPS 坐标，
                // 原逻辑会直接判"无轨迹"而整段丢弃。这里在"有 record 但无坐标"时，
                // 按时间轴 + 功率/心率/踏频构造点列（坐标置 0,0），让合并与预览可用。
                // 上传链路走原始 FIT 文件，不经过这里的点列重建，因此不受影响。
                if (records.isEmpty()) return null
                for (r in records) {
                    val ele = r.altitude?.toDouble() ?: 0.0
                    val ts = r.timestamp?.getTimestamp()?.let { it + 631065600L } ?: 0L
                    if (ts > 0) lastTs = ts
                    pts.add(
                        GpxToFitConverter.TrackPoint(
                            lat = 0.0,
                            lon = 0.0,
                            ele = if (ele > 0) ele else 0.0,
                            ts = if (ts > 0) ts else lastTs,
                            hr = (r.heartRate ?: 0).toInt().coerceIn(0, 255),
                            cad = (r.cadence ?: 0).toInt().coerceIn(0, 255),
                            power = (r.power ?: 0).toInt().coerceIn(0, 65535)
                        )
                    )
                }
                if (pts.isEmpty()) return null
            }
            FitResult(pts, sport, "")
        } catch (_: Exception) { null }
    }
}
