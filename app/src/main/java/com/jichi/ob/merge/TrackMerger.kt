package com.jichi.ob.merge

import com.jichi.ob.GpxToFitConverter
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * v7.9.7 轨迹合并核心。
 *
 * 算法参考开源 fitactivity（muktihari/fit，Go）的 MergeMany：
 * 1. 按各段开始时间排序（乱序选择也能得到正确顺序）；
 * 2. 以第一条为基础，其余段按时间顺序追加；
 * 3. 段间时间回退/重叠的点剔除，保证最终 Record 时间严格递增；
 * 4. distance 由下游 FIT 生成器按点序重算累计（从 0 开始）；
 * 5. sport 统一为单值（用户可指定，默认取第一段），符合"合并成一条记录"的直觉；
 * 6. 保留点级心率/踏频/功率/海拔，不丢任何数据。
 */
object TrackMerger {

    const val SPORT_RUNNING = 1
    const val SPORT_CYCLING = 2
    const val SPORT_HIKING = 17

    /** 段内大间隙截断阈值：>60s 的暂停/停表/跨天间隔计为 60s（Strava moving-time 口径） */
    private const val MAX_SEG_INTERVAL_S = 60L

    data class Segment(
        val name: String,
        val sport: Int,
        val points: List<GpxToFitConverter.TrackPoint>
    )

    /** v9.7.0: 单段 session 原值（来自设备码表记录，不是点列重算） */
    data class SegmentMeta(val startSec: Long, val distanceM: Double, val timerSec: Long, val ascentM: Double = 0.0)

    /** v9.7.0: 合并后的单圈（多日合并即按天/按段分圈） */
    data class LapInfo(val startTs: Long, val endTs: Long, val distanceM: Double, val timerSec: Long)

    data class MergedResult(
        val points: List<GpxToFitConverter.TrackPoint>,
        val sport: Int,
        val sportName: String,
        val startTime: Long,
        val endTime: Long,
        val totalDurationSec: Long,
        val totalDistanceKm: Double,
        val ascentM: Double,
        val descentM: Double,
        val avgHr: Int,
        val maxHr: Int,
        val avgCad: Int,
        val avgPower: Int,
        val segmentCount: Int,
        val pointCount: Int,
        /** v9.7.0: 各段圈信息；非空时 FIT 写出按圈直填源值 */
        val laps: List<LapInfo> = emptyList(),
        /** v9.7.0: 是否使用了 session 原值口径（false = 仍按点列重算） */
        val fromSourceMeta: Boolean = false
    )

    /**
     * 合并多个 GPX 文件（文件名或标题 → 字节）。
     * @param forcedSport >0 时强制统一为指定类型；否则取第一段类型。
     */
    fun mergeGpxFiles(files: List<Pair<String, ByteArray>>, forcedSport: Int = -1): MergedResult {
        val segments = files.mapNotNull { (name, bytes) ->
            try {
                val pts = GpxToFitConverter.parseGpxBytes(bytes)
                if (pts.isEmpty()) null else Segment(name, detectSport(name), pts)
            } catch (_: Exception) { null }
        }
        if (segments.isEmpty()) throw IllegalArgumentException("所有轨迹均解析失败")
        return mergeSegments(segments, forcedSport)
    }

    /** 合并多个点列表（供 FIT 源等已解析场景）。 */
    fun mergePointGroups(groups: List<List<GpxToFitConverter.TrackPoint>>, forcedSport: Int = -1): MergedResult {
        val segments = groups.mapIndexed { i, pts ->
            Segment("segment${i + 1}", if (i == 0) detectSport("") else -1, pts)
        }.filter { it.points.isNotEmpty() }
        if (segments.isEmpty()) throw IllegalArgumentException("无可合并的轨迹")
        return mergeSegments(segments, forcedSport)
    }

    /** v9.7.0: 带 session 原值的合并入口（metas 与 groups 一一对应，允许 null） */
    fun mergePointGroupsWithMeta(
        groups: List<List<GpxToFitConverter.TrackPoint>>,
        metas: List<SegmentMeta?>,
        forcedSport: Int = -1
    ): MergedResult {
        val pairs = groups.mapIndexed { i, pts -> pts to metas.getOrNull(i) }
            .filter { it.first.isNotEmpty() }
        if (pairs.isEmpty()) throw IllegalArgumentException("无可合并的轨迹")
        val segments = pairs.mapIndexed { i, pair ->
            Segment("segment${i + 1}", if (i == 0) detectSport("") else -1, pair.first)
        }
        return mergeSegments(segments, forcedSport, pairs.map { it.second })
    }

    private fun mergeSegments(
        segments: List<Segment>,
        forcedSport: Int,
        metas: List<SegmentMeta?> = emptyList()
    ): MergedResult {
        // 1) 剔除空段，按开始时间排序
        val valid = segments.filter { it.points.isNotEmpty() }
        val sorted = valid.sortedBy { it.points.firstOrNull()?.ts ?: 0L }

        // 2) sport 统一：forced 优先；否则取第一段（自动识别失败默认骑行）
        val sport = when {
            forcedSport > 0 -> forcedSport
            else -> sorted.firstOrNull()?.sport?.takeIf { it > 0 } ?: SPORT_CYCLING
        }

        // v9.7.0（对齐开发版 v9.6.9）：只要任一段带 session 原值，就整体走"取原值 + 原始时间轴"口径：
        //   1) 时间戳不再压缩，段内 / 段间一律用原始 getTs()，只保证严格递增（多日合并的真实时间轴得以保留）；
        //   2) 每段距离 / 计时 / 爬升取设备 session 原值，不再由 GPS 点列重算；
        //   3) 每段生成一个 lap，多日合并即"按天分圈"，lap 距离 / 计时直填源段原值。
        // 任一段都没有 session 原值时，完全沿用旧口径（压缩暂停、点列重算），行为与 v9.6.6 一致。
        val srcMode = metas.any { it != null }

        // 3) 拼接点（srcMode：原始时间轴；否则：段间清零 + 段内大间隙截断）
        val merged = ArrayList<GpxToFitConverter.TrackPoint>()
        val segStartIdx = ArrayList<Int>() // 每段首点在 merged 中的索引（统计时跳过段间"跳飞"距离）
        val laps = ArrayList<LapInfo>()
        var cursorTs = -1L // 上段末点的时间戳
        for ((si, seg) in sorted.withIndex()) {
            val meta = metas.getOrNull(si)
            var prevRawTs = -1L
            var segFirst = true
            var segStartTs = -1L
            var segEndTs = -1L
            var segHaversineM = 0.0
            var segPrev: GpxToFitConverter.TrackPoint? = null
            for (p in seg.points) {
                val newTs: Long = if (srcMode) {
                    // 原始时间轴：有原始时间戳就用原始值，只保证严格递增
                    val raw = if (p.ts > 0L) p.ts else (if (cursorTs < 0) 1L else cursorTs + 1)
                    if (raw > cursorTs) raw else cursorTs + 1
                } else when {
                    p.ts <= 0L -> if (cursorTs < 0) 1L else cursorTs + 1
                    prevRawTs < 0L -> {
                        // 段首：第一段保留原始起点，其余段紧接上段末点
                        prevRawTs = p.ts
                        if (cursorTs < 0) p.ts else cursorTs + 1
                    }
                    else -> {
                        // 段内：保留原始时间差，但 >MAX_SEG_INTERVAL_S 的大间隙截断
                        val v = cursorTs + (p.ts - prevRawTs).coerceAtMost(MAX_SEG_INTERVAL_S)
                        prevRawTs = p.ts
                        v
                    }
                }
                if (newTs > cursorTs) {
                    if (segFirst) { segStartIdx.add(merged.size); segFirst = false; segStartTs = newTs }
                    segEndTs = newTs
                    merged.add(p.copy(ts = newTs))
                    cursorTs = newTs
                }
                val sp = segPrev
                if (sp != null) segHaversineM += haversineKm(sp.lat, sp.lon, p.lat, p.lon) * 1000.0
                segPrev = p
            }
            // v9.7.0: 每段一圈；优先级 session 原值 > 点列兜底
            if (segStartTs > 0 && segEndTs >= segStartTs) {
                val lapDistM = if (meta != null && meta.distanceM > 0) meta.distanceM else segHaversineM
                val lapTimerS = if (meta != null && meta.timerSec > 0) meta.timerSec
                               else (segEndTs - segStartTs).coerceAtLeast(1L)
                laps.add(LapInfo(segStartTs, segEndTs, lapDistM, lapTimerS))
            }
        }
        if (merged.isEmpty()) throw IllegalArgumentException("合并后无有效轨迹点")
        // 非原值口径不进圈信息，保持 v9.6.6 单圈输出
        if (!srcMode) laps.clear()

        // 4) 统计（含累加距离、累计爬升/下降、心率）
        //    点列统计只按段内累计：段首点重置 prev，段间"跳飞"（不同地点记录相连）不计入总距离
        var haversineM = 0.0
        var ascent = 0.0
        var descent = 0.0
        var hrSum = 0L; var hrCount = 0L; var maxHr = 0
        var cadSum = 0L; var cadCount = 0L
        var powerSum = 0L; var powerCount = 0L
        var prev: GpxToFitConverter.TrackPoint? = null
        var prevEle: Double? = null
        val segStartSet = segStartIdx.toHashSet()
        for ((i, p) in merged.withIndex()) {
            if (i in segStartSet) { prev = null; prevEle = null }
            val pr = prev
            if (pr != null) haversineM += haversineKm(pr.lat, pr.lon, p.lat, p.lon) * 1000.0
            val pe = prevEle
            if (pe != null) {
                val d = p.ele - pe
                if (d > 0) ascent += d else descent += -d
            }
            if (p.hr > 0) { hrSum += p.hr; hrCount++; if (p.hr > maxHr) maxHr = p.hr }
            if (p.cad > 0) { cadSum += p.cad; cadCount++ }
            if (p.power > 0) { powerSum += p.power; powerCount++ }
            prev = p
            prevEle = if (p.ele > 0) p.ele else null
        }

        // v9.7.0: 原值口径优先取各段 session 原值（里程 / 计时 / 爬升），点列值仅作兜底
        val lapDistM = laps.fold(0.0) { acc, l -> acc + l.distanceM }
        val lapTimerS = laps.fold(0L) { acc, l -> acc + l.timerSec }
        var srcAscent = 0.0
        if (srcMode) for (m in metas) if (m != null && m.ascentM > 0) srcAscent += m.ascentM
        val totalDistanceM = if (srcMode && lapDistM > 0) lapDistM else haversineM
        val totalDuration = if (srcMode && lapTimerS > 0) lapTimerS
                            else (merged.last().ts - merged.first().ts).coerceAtLeast(1L)
        if (srcMode && srcAscent > 0) ascent = srcAscent

        val startTs = merged.first().ts.takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000)
        val endTs = merged.last().ts.takeIf { it > 0 } ?: (startTs + merged.size)

        return MergedResult(
            points = merged,
            sport = sport,
            sportName = sportName(sport),
            startTime = startTs,
            endTime = endTs,
            totalDurationSec = totalDuration,
            totalDistanceKm = totalDistanceM / 1000.0,
            ascentM = ascent,
            descentM = descent,
            avgHr = if (hrCount > 0) (hrSum / hrCount).toInt() else 0,
            maxHr = maxHr,
            avgCad = if (cadCount > 0) (cadSum / cadCount).toInt() else 0,
            avgPower = if (powerCount > 0) (powerSum / powerCount).toInt() else 0,
            segmentCount = sorted.size,
            pointCount = merged.size,
            laps = laps,
            fromSourceMeta = srcMode
        )
    }

    /** 生成合并后的标准 GPX（1.1，带 <type> 运动类型 + gpxtpx 心率/踏频/功率扩展）。 */
    fun buildGpx(result: MergedResult, title: String): ByteArray {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"鸡翅幸哲迈进OB\" xmlns=\"http://www.topografix.com/GPX/1/1\" ")
        sb.append("xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\" ")
        sb.append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
        sb.append("xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
        sb.append("<trk>\n")
        sb.append("<name>").append(esc(title)).append("</name>\n")
        sb.append("<type>").append(sportName(result.sport)).append("</type>\n")
        sb.append("<trkseg>\n")
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        for (p in result.points) {
            sb.append("<trkpt lat=\"").append(trimNum(p.lat)).append("\" lon=\"").append(trimNum(p.lon)).append("\">\n")
            if (p.ele > 0) sb.append("<ele>").append(trimNum(p.ele)).append("</ele>\n")
            if (p.ts > 0) sb.append("<time>").append(fmt.format(Date(p.ts * 1000))).append("</time>\n")
            if (p.hr > 0 || p.cad > 0 || p.power > 0) {
                sb.append("<extensions><gpxtpx:TrackPointExtension>\n")
                if (p.hr > 0) sb.append("<gpxtpx:hr>").append(p.hr).append("</gpxtpx:hr>\n")
                if (p.cad > 0) sb.append("<gpxtpx:cad>").append(p.cad).append("</gpxtpx:cad>\n")
                if (p.power > 0) sb.append("<gpxtpx:power>").append(p.power).append("</gpxtpx:power>\n")
                sb.append("</gpxtpx:TrackPointExtension></extensions>\n")
            }
            sb.append("</trkpt>\n")
        }
        sb.append("</trkseg>\n</trk>\n</gpx>\n")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    fun sportName(sport: Int): String = when (sport) {
        SPORT_RUNNING -> "running"
        SPORT_HIKING -> "hiking"
        else -> "cycling"
    }

    /** 从文件名/标题关键词识别运动类型（与 GpxToFitConverter 口径一致）。 */
    fun detectSportFromName(name: String): Int = detectSport(name)

    private fun detectSport(name: String): Int {
        val n = name.lowercase()
        return when {
            n.contains("cycling") || n.contains("riding") || n.contains("bike") || n.contains("骑行") || n.contains("公路") || n.contains("山地") -> SPORT_CYCLING
            n.contains("hiking") || n.contains("walking") || n.contains("trail") || n.contains("徒步") || n.contains("登山") || n.contains("健走") -> SPORT_HIKING
            n.contains("running") || n.contains("run") || n.contains("跑步") -> SPORT_RUNNING
            else -> -1
        }
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0088
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    private fun trimNum(v: Double): String = if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else String.format(Locale.US, "%.7f", v)

    private fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}