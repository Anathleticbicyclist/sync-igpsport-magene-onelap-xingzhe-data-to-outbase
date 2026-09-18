package com.jichi.ob.model

/**
 * 活动记录数据模型（多平台通用 v6.5.0）
 * v6.5.0: 新增佳明(COM/CN)、高驰(中国/国际)、Wahoo
 */
data class ActivityRecord(
    val id: String,
    val title: String,
    val startTime: String,
    val distance: Double,   // km
    val duration: Int,      // seconds
    val source: DataSource,
    var extra: String? = null,  // 平台附加信息
    var startTimeMs: Long = 0L  // v8.3.8: 平台直传的毫秒时间戳（最准；0 表示需从 startTime 字符串解析）
)

enum class DataSource(val displayName: String, val shortName: String) {
    IGPSPORT("iGPSPORT", "igp"),
    XINGZHE("行者", "xz"),
    MAGENE("迈金", "mg"),
    BLACKBIRD("黑鸟单车", "bb"),
    BRYTON("百锐腾", "br"),
    OUTBASE("Outbase", "ob"),
    // v6.5.0 新增：佳明(国际/中国)、高驰(中国/国际)、Wahoo
    GARMIN_COM("佳明国际", "gm"),
    GARMIN_CN("佳明中国", "gcn"),
    COROS_CN("高驰中国", "cscn"),
    COROS_INT("高驰国际", "cs"),
    WAHOO("Wahoo", "wo"),
    // v8.2.0 新增：MyWhoosh / Zwift（仅下载数据源）
    MYWHOOSH("MyWhoosh", "mw"),
    ZWIFT("Zwift", "zf"),
    // v8.2.1: Keep（下载数据源，仅下载）
    KEEP("Keep", "kp"),
    // v8.3.8: 咕咚/Zepp/Komoot/松拓（对齐开发版 v8.3.3，仅下载数据源；正式版只上传Outbase）
    CODOON("咕咚", "cd"),
    ZEPP("Zepp", "zp"),
    KOMOT("Komoot", "kt"),
    SUUNTO("松拓", "su");

    companion object {
        /** 可作为"来源(下载)"的平台 */
        fun sourcePlatforms(): List<DataSource> =
            listOf(IGPSPORT, XINGZHE, MAGENE, BLACKBIRD, BRYTON, GARMIN_COM, GARMIN_CN, COROS_CN, COROS_INT, WAHOO, MYWHOOSH, ZWIFT, KEEP, CODOON, ZEPP, KOMOT, SUUNTO)
        fun fromShortName(s: String): DataSource? = entries.find { it.shortName == s }
    }
}

enum class FileKind(val ext: String, val displayName: String) {
    FIT("fit", "FIT"),
    GPX("gpx", "GPX"),
    UNKNOWN("", "未知")
}

/** v8.0.0 正式版: 可作为"来源(下载)"的平台（百锐腾不支持下载） */
enum class DownloadSupport(val available: Boolean, val note: String) {
    IGPSPORT(true, ""),
    XINGZHE(true, ""),
    MAGENE(true, ""),
    BLACKBIRD(true, ""),
    BRYTON(false, "开发中，不支持下载"),
    GARMIN_COM(true, ""),
    GARMIN_CN(true, ""),
    COROS_CN(true, ""),
    COROS_INT(true, ""),
    WAHOO(true, ""),
    MYWHOOSH(true, ""),
    ZWIFT(true, ""),
    KEEP(true, ""),
    CODOON(true, ""),
    ZEPP(true, ""),
    KOMOT(true, ""),
    SUUNTO(true, "");

    companion object {
        fun fromDataSource(ds: DataSource): DownloadSupport = when (ds) {
            DataSource.IGPSPORT -> IGPSPORT
            DataSource.XINGZHE -> XINGZHE
            DataSource.MAGENE -> MAGENE
            DataSource.BLACKBIRD -> BLACKBIRD
            DataSource.BRYTON -> BRYTON
            DataSource.GARMIN_COM -> GARMIN_COM
            DataSource.GARMIN_CN -> GARMIN_CN
            DataSource.COROS_CN -> COROS_CN
            DataSource.COROS_INT -> COROS_INT
            DataSource.WAHOO -> WAHOO
            DataSource.MYWHOOSH -> MYWHOOSH
            DataSource.ZWIFT -> ZWIFT
            DataSource.KEEP -> KEEP
            DataSource.CODOON -> CODOON
            DataSource.ZEPP -> ZEPP
            DataSource.KOMOT -> KOMOT
            DataSource.SUUNTO -> SUUNTO
            else -> BRYTON
        }
    }
}

/** v8.0.0 正式版: 上传目标仅保留 Outbase（多对一） */
enum class UploadSupport(val available: Boolean, val note: String) {
    OUTBASE(true, ""),
    IGPSPORT(false, "正式版仅支持上传到Outbase"),
    XINGZHE(false, "正式版仅支持上传到Outbase"),
    MAGENE(false, "正式版仅支持上传到Outbase"),
    BLACKBIRD(false, "正式版仅支持上传到Outbase"),
    BRYTON(false, "正式版仅支持上传到Outbase"),
    GARMIN_COM(false, "正式版仅支持上传到Outbase"),
    GARMIN_CN(false, "正式版仅支持上传到Outbase"),
    COROS_CN(false, "正式版仅支持上传到Outbase"),
    COROS_INT(false, "正式版仅支持上传到Outbase"),
    WAHOO(false, "正式版仅支持上传到Outbase"),
    MYWHOOSH(false, "正式版仅支持上传到Outbase"),
    ZWIFT(false, "正式版仅支持上传到Outbase"),
    KEEP(false, "正式版仅支持上传到Outbase"),
    CODOON(false, "正式版仅支持上传到Outbase"),
    ZEPP(false, "正式版仅支持上传到Outbase"),
    KOMOT(false, "正式版仅支持上传到Outbase"),
    SUUNTO(false, "正式版仅支持上传到Outbase");

    companion object {
        fun fromDataSource(ds: DataSource): UploadSupport = when (ds) {
            DataSource.OUTBASE -> OUTBASE
            DataSource.IGPSPORT -> IGPSPORT
            DataSource.XINGZHE -> XINGZHE
            DataSource.MAGENE -> MAGENE
            DataSource.BLACKBIRD -> BLACKBIRD
            DataSource.BRYTON -> BRYTON
            DataSource.GARMIN_COM -> GARMIN_COM
            DataSource.GARMIN_CN -> GARMIN_CN
            DataSource.COROS_CN -> COROS_CN
            DataSource.COROS_INT -> COROS_INT
            DataSource.WAHOO -> WAHOO
            DataSource.MYWHOOSH -> MYWHOOSH
            DataSource.ZWIFT -> ZWIFT
            DataSource.KEEP -> KEEP
            DataSource.CODOON -> CODOON
            DataSource.ZEPP -> ZEPP
            DataSource.KOMOT -> KOMOT
            DataSource.SUUNTO -> SUUNTO
        }
    }
}
