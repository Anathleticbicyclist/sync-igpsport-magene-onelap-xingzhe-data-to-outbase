package com.jichi.ob.api
 
import android.util.Log
import com.jichi.ob.model.ActivityRecord
import com.jichi.ob.model.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
 
/**
 * iGPSPORT API（成熟方案，沿用 v3.0 验证版本）
 * 认证: Authorization: Bearer {token}（WebView localStorage persist:app-store → global.token）
 */
class IgpsportApi {
 
    /** v6.3.6: 探测iGPSPORT活动时间字段，支持多种字段名 */
    private fun probeTimeField(item: org.json.JSONObject): String {
        val keys = listOf("StartTime","startTime","start_time","RideDate","rideDate","ride_date",
            "SportTime","sportTime","BeginTime","beginTime","RideTime","rideTime",
            "createTime","CreateTime","start_date","startDate","Date","date","Time","time",
            "StartTimeStr","startTimeStr","StartDate")
        for (k in keys) {
            val v = item.optString(k, "")
            if (v.isNotEmpty()) return v
        }
        return ""
    }

    companion object {
        private const val TAG = "IgpsportApi"
        private const val FIT_EPOCH = 631065600000L // FIT epoch 1989-12-31 → unix ms 偏移
        const val LOGIN_URL = "https://login.passport.igpsport.cn/login?lang=zh-Hans"
        private const val BASE = "https://prod.zh.igpsport.com/service"
        private const val ACTIVITY_URL = "$BASE/web-gateway/web-analyze/activity/queryMyActivity"
        private const val DOWNLOAD_URL = "$BASE/web-gateway/web-analyze/activity/getDownloadUrl"
        private const val PER_PAGE = 20  // iGPSPORT每页最多20
        // v8.8.6: iGPSPORT 国际区（prod.en.igpsport.com，参考开源项目 intervalssync 的 region 配置）
        const val LOGIN_URL_INT = "https://prod.en.igpsport.com/service/auth/account/login"
        private const val BASE_EN = "https://prod.en.igpsport.com/service"
        private const val ACTIVITY_URL_EN = "$BASE_EN/web-gateway/web-analyze/activity/queryMyActivity"
        private const val DOWNLOAD_URL_EN = "$BASE_EN/web-gateway/web-analyze/activity/getDownloadUrl"
        private const val GATEWAY_EN = "$BASE_EN/web-gateway/web-analyze/activity"
        private const val USER_INFO_URL_EN = "$BASE_EN/mobile/api/User/UserInfo"

        /**
         * v8.8.6: 国际区登录响应 token 提取（可单测）。
         * 兼容 data.access_token / data.accessToken / 顶层 access_token / accessToken 四种结构。
         */
        fun parseAccessToken(json: org.json.JSONObject): String {
            val data = json.optJSONObject("data")
            val fromData = data?.optString("access_token", data.optString("accessToken", "")) ?: ""
            return fromData.ifEmpty {
                json.optString("access_token", json.optString("accessToken", ""))
            }
        }
    }
 
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
 
    private fun authHeaders(token: String) = mapOf(
        "Authorization" to "Bearer $token",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0.0.0",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "zh-CN,zh;q=0.9",
        "Origin" to "https://app.igpsport.cn",
        "Referer" to "https://app.igpsport.cn/"
    )
 
    /**
     * v7.6.0: 校验iGPSPORT token真实性并返回用户名
     * 关键：user/info接口可能对有效token也返回403（不同网关服务），不能据此判失效。
     * 用getActivities同款可靠接口queryMyActivity校验token；用户名从JWT payload解析。
     * @return null 表示token确实失效（HTTP 401/403 或业务码非0）
     */
    suspend fun getUsername(token: String): String? = withContext(Dispatchers.IO) {
        // 1) 用queryMyActivity真实校验token（该接口同步全链路已验证可靠）
        val valid = try {
            val url = "$ACTIVITY_URL?pageNo=1&pageSize=1&reqType=0&sort=1"
            val req = Request.Builder().url(url)
                .apply { authHeaders(token).forEach { (k, v) -> addHeader(k, v) } }
                .get().build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            if (resp.code == 401 || resp.code == 403) false
            else if (resp.code != 200) {
                Log.w(TAG, "getUsername queryMyActivity HTTP ${resp.code}")
                false
            } else {
                val json = try { JSONObject(body) } catch (e: Exception) { null }
                json?.optInt("code", -1) == 0
            }
        } catch (e: Exception) {
            // 网络异常：保守判定有效（避免误判失效触发重新登录），同步时会自行报错引导
            Log.e(TAG, "getUsername 校验网络异常，保守判定有效", e)
            true
        }
        if (!valid) return@withContext null

        // 2) token有效 → 从JWT payload解析用户名（不校验exp，仅取显示名）
        try {
            val parts = token.split(".")
            if (parts.size >= 2) {
                val payloadB64 = parts[1].replace('-', '+').replace('_', '/')
                val padded = payloadB64 + "=".repeat((4 - payloadB64.length % 4) % 4)
                val decoded = String(android.util.Base64.decode(padded, android.util.Base64.DEFAULT), Charsets.UTF_8)
                val payload = JSONObject(decoded)
                payload.optString("nickname")?.takeIf { it.isNotEmpty() }
                    ?: payload.optString("userName")?.takeIf { it.isNotEmpty() }
                    ?: payload.optString("name")?.takeIf { it.isNotEmpty() }
                    ?: payload.optString("username")?.takeIf { it.isNotEmpty() }
                    ?: payload.optString("account")?.takeIf { it.isNotEmpty() }
                    ?: "用户${payload.optString("userId").take(6)}"
            } else "用户"
        } catch (e: Exception) { Log.e(TAG, "getUsername JWT解析失败", e); "用户" }
    }
    suspend fun getActivities(token: String, offset: Int, limit: Int): List<ActivityRecord> =
        withContext(Dispatchers.IO) {
            val result = mutableListOf<ActivityRecord>()
            // 服务端不支持offset参数，拉取 offset+limit 条后丢弃前offset条
            val need = offset + limit
            var currentPage = 1
            var fetched = 0
            while (fetched < need) {
                val url = "$ACTIVITY_URL?pageNo=$currentPage&pageSize=$PER_PAGE&reqType=0&sort=1"
                val req = Request.Builder().url(url)
                    .apply { authHeaders(token).forEach { (k, v) -> addHeader(k, v) } }
                    .get().build()
                val resp = client.newCall(req).execute()
                val code = resp.code
                val body = resp.body?.string() ?: throw Exception("空响应 (HTTP $code)")
                if (code == 401) throw Exception("iGPSPORT登录已过期，请重新登录")
                if (code != 200) throw Exception("iGPSPORT HTTP $code: ${body.take(200)}")
                val json = JSONObject(body)
                val apiCode = json.optInt("code", -1)
                if (apiCode != 0) {
                    val msg = json.optString("message", json.optString("data", "Unknown"))
                    if (msg.contains("token", true) || msg.contains("登录")) throw Exception("iGPSPORT登录已过期，请重新登录")
                    throw Exception("iGPSPORT API error: $msg (code=$apiCode)")
                }
                val data = json.optJSONObject("data") ?: json
                val rows = data.optJSONArray("rows") ?: data.optJSONArray("list") ?: break
                if (rows.length() == 0) break
 
                for (i in 0 until rows.length()) {
                    fetched++
                    if (fetched <= offset) continue
                    val item = rows.getJSONObject(i)
                    val rideId = item.optString("RideId", item.optString("rideId", item.optString("id", "")))
                    // v6.3.6 调试：用Log.w输出第一个活动的所有字段和时间值，排查文件名时间unknown
                    if (fetched == offset + 1) {
                        val allKeys = item.names()?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.joinToString(",") } ?: "(无)"
                        val timeKeys = listOf("StartTime","startTime","start_time","RideDate","rideDate","ride_date",
                            "SportTime","sportTime","BeginTime","beginTime","RideTime","rideTime",
                            "createTime","CreateTime","start_date","startDate","Date","date","Time","time",
                            "StartTimeStr","startTimeStr","StartDate","startDate")
                        val timeVals = timeKeys.mapNotNull { k ->
                            val v = item.optString(k, "")
                            if (v.isNotEmpty()) "$k=$v" else null
                        }
                        Log.w(TAG, "===== iGPSPORT首活动调试 ===== keys=$allKeys")
                        Log.w(TAG, "===== 时间字段: ${timeVals.joinToString(", ") ?: "(全部为空)"} =====")
                    }
                    if (rideId.isEmpty()) continue
                    val downloadUrl = item.optString("DownloadUrl",
                        item.optString("downloadUrl",
                            item.optString("FileUrl", item.optString("fileUrl", ""))))
                    // 距离字段探测：>=1000视为米，否则视为公里
                    var dist = item.optDouble("Distance", -1.0)
                    if (dist < 0) dist = item.optDouble("distance", -1.0)
                    if (dist < 0) dist = item.optDouble("sportDistance", 0.0)
                    val distKm = if (dist >= 1000) dist / 1000.0 else dist
                    // v6.3.6: 用辅助函数探测时间字段，避免嵌套括号
                    val startTime = probeTimeField(item)
                    result.add(
                        ActivityRecord(
                            id = rideId,
                            title = item.optString("Title", item.optString("title", item.optString("name", "骑行"))),
                            startTime = startTime,
                            distance = distKm,
                            duration = item.optInt("Duration", item.optInt("duration", item.optInt("movingTime", 0))),
                            source = DataSource.IGPSPORT,
                            extra = downloadUrl.ifEmpty { null }
                        )
                    )
                    // v6.3.7 修复：添加limit条后立即break，否则for循环遍历完当前页20条全部添加（limit不生效）
                    if (result.size >= limit) break

                }
                if (result.size >= limit || rows.length() < PER_PAGE) break
                currentPage++
                kotlinx.coroutines.delay(200)
            }
            Log.d(TAG, "getActivities total: ${result.size}")
            result
        }
 
    fun parseFitStartTimeMs(bytes: ByteArray): Long {
        try {
            if (bytes.size < 14 || bytes[8] != '.'.code.toByte() || bytes[9] != 'F'.code.toByte() ||
                bytes[10] != 'I'.code.toByte() || bytes[11] != 'T'.code.toByte()) return 0
            val headerSize = bytes[0].toInt() and 0xFF
            val dataSize = ((bytes[4].toLong() and 0xFF) or ((bytes[5].toLong() and 0xFF) shl 8) or
                    ((bytes[6].toLong() and 0xFF) shl 16) or ((bytes[7].toLong() and 0xFF) shl 24))
            if (headerSize <= 0 || headerSize >= bytes.size) return 0
            var pos = headerSize
            val end = minOf(bytes.size.toLong(), headerSize + dataSize).toInt()
            var firstRecordTs = 0L
            val localDefs = HashMap<Int, Pair<Int, List<IntArray>>>() // localType -> (globalNum, [(fieldNum, size, baseType)])
            var guard = 0
            while (pos + 1 <= end && guard < 200000) {
                guard++
                val hdr = bytes[pos].toInt() and 0xFF
                val localType = hdr and 0x0F
                val isDef = (hdr and 0x40) != 0
                if (isDef) {
                    if (pos + 5 > end) break
                    val globalNum = (bytes[pos + 1].toInt() and 0xFF) or ((bytes[pos + 2].toInt() and 0xFF) shl 8)
                    val fieldCount = bytes[pos + 3].toInt() and 0xFF
                    var p = pos + 4
                    val fields = ArrayList<IntArray>(fieldCount)
                    for (i in 0 until fieldCount) {
                        if (p + 3 > end) break
                        val fn = bytes[p].toInt() and 0xFF
                        val size = bytes[p + 1].toInt() and 0xFF
                        val bt = bytes[p + 2].toInt() and 0xFF
                        fields.add(intArrayOf(fn, size, bt))
                        p += 3
                    }
                    localDefs[localType] = globalNum to fields
                    pos = p
                } else {
                    val def = localDefs[localType] ?: break
                    val (globalNum, fields) = def
                    var p = pos + 1
                    for ((fn, size, _) in fields) {
                        if (p + size > end) break
                        if (size == 4 && (fn == 253 || (globalNum == 0 && fn == 4))) {
                            val v = (bytes[p].toLong() and 0xFF) or ((bytes[p + 1].toLong() and 0xFF) shl 8) or
                                    ((bytes[p + 2].toLong() and 0xFF) shl 16) or ((bytes[p + 3].toLong() and 0xFF) shl 24)
                            if (v > 0x10000000L) { // 有效时间（FIT epoch 起算秒）
                                val ms = v * 1000L + FIT_EPOCH
                                if (globalNum == 0) return ms // file_id.time_created 最权威
                                if (firstRecordTs == 0L && globalNum == 20) firstRecordTs = ms
                            }
                        }
                        p += size
                    }
                    pos = p
                }
            }
            return firstRecordTs
        } catch (_: Exception) { return 0 }
    }

    suspend fun downloadFitFile(token: String, rideId: String, activityDownloadUrl: String? = null): ByteArray =
        withContext(Dispatchers.IO) {
            val downloadUrl: String
            if (!activityDownloadUrl.isNullOrEmpty()) {
                downloadUrl = activityDownloadUrl
            } else {
                val req = Request.Builder().url("$DOWNLOAD_URL/$rideId")
                    .apply { authHeaders(token).forEach { (k, v) -> addHeader(k, v) } }
                    .get().build()
                val resp = client.newCall(req).execute()
                val body = resp.body?.string() ?: throw Exception("下载URL空响应")
                val json = JSONObject(body)
                downloadUrl = when (val data = json.opt("data")) {
                    is String -> data
                    is JSONObject -> data.optString("url", data.optString("downloadUrl", ""))
                    else -> json.optString("url", json.optString("downloadUrl", ""))
                }
                if (downloadUrl.isEmpty()) throw Exception("无下载URL (rideId=$rideId)")
            }
 
            val dlReq = Request.Builder().url(downloadUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0.0.0")
                .addHeader("Accept", "*/*")
                .addHeader("Referer", "https://app.igpsport.cn/")
                .get().build()
            val dlResp = client.newCall(dlReq).execute()
            val bytes = dlResp.body?.bytes() ?: throw Exception("FIT下载空响应")
            if (dlResp.code != 200) throw Exception("iGPSPORT FIT下载失败 HTTP ${dlResp.code}")
            if (bytes.size < 14 || bytes[8] != '.'.code.toByte() || bytes[9] != 'F'.code.toByte()) {
                Log.w(TAG, "FIT文件头可能无效 (size=${bytes.size})")
            }
            Log.d(TAG, "FIT downloaded: ${bytes.size} bytes (rideId=$rideId)")
            bytes
        }

    // ================= v8.8.6: iGPSPORT 国际区 =================
    // 参考开源项目 intervalssync（github.com/jorge-huxley/intervalssync，MIT）：
    // 国际区（prod.en.igpsport.com）走移动 API 登录（appId=igpsport-web 账号密码），
    // access_token 作 Bearer；列表 queryMyActivity（rows[].RideId）；下载先 queryActivityDetail 取 fitUrl，
    // 失败回退 getDownloadUrl。
    private fun authHeadersInt(token: String) = mapOf(
        "Authorization" to "Bearer $token",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0.0.0",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en,en-US;q=0.9"
    )

    /** 国际区账号密码登录（移动 API），返回 access_token；失败返回 null */
    suspend fun loginInternational(email: String, password: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("appId", "igpsport-web")
                    put("username", email)
                    put("password", password)
                }
                val req = Request.Builder()
                    .url(LOGIN_URL_INT)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("User-Agent", "okhttp/4.12.0")
                    .post(okhttp3.RequestBody.create("application/json; charset=utf-8".toMediaType(), body.toString()))
                    .build()
                val resp = client.newCall(req).execute()
                val text = resp.body?.string() ?: return@withContext null
                if (resp.code != 200) {
                    Log.w(TAG, "iGPSPORT国际登录 HTTP ${resp.code}: ${text.take(200)}")
                    return@withContext null
                }
                val json = JSONObject(text)
                val token = parseAccessToken(json)
                if (token.isEmpty()) {
                    Log.w(TAG, "iGPSPORT国际登录无access_token: ${json.optString("message", text.take(100))}")
                    return@withContext null
                }
                token
            } catch (e: Exception) {
                Log.w(TAG, "iGPSPORT国际登录异常: ${e.message}")
                null
            }
        }

    /** 国际区用户名（UserInfo 尽力而为，失败返回 null） */
    suspend fun getUsernameInternational(token: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(USER_INFO_URL_EN)
                .apply { authHeadersInt(token).forEach { (k, v) -> addHeader(k, v) } }
                .get().build()
            val resp = client.newCall(req).execute()
            if (resp.code != 200) return@withContext null
            val json = JSONObject(resp.body?.string() ?: return@withContext null)
            val data = json.optJSONObject("data") ?: json
            data.optString("nickName", data.optString("name", data.optString("userName", "")))
                .ifEmpty { null }
        } catch (e: Exception) {
            Log.w(TAG, "iGPSPORT国际用户名获取失败: ${e.message}")
            null
        }
    }

    /** 国际区活动列表（queryMyActivity，rows[].RideId，时间用 rideDate/startTime） */
    suspend fun getActivitiesInternational(token: String, offset: Int, limit: Int): List<ActivityRecord> =
        withContext(Dispatchers.IO) {
            val result = mutableListOf<ActivityRecord>()
            val need = offset + limit
            var currentPage = 1
            var fetched = 0
            while (fetched < need) {
                val url = "$ACTIVITY_URL_EN?pageNo=$currentPage&pageSize=$PER_PAGE&reqType=0&sort=1"
                val req = Request.Builder().url(url)
                    .apply { authHeadersInt(token).forEach { (k, v) -> addHeader(k, v) } }
                    .get().build()
                val resp = client.newCall(req).execute()
                val code = resp.code
                val body = resp.body?.string() ?: throw Exception("空响应 (HTTP $code)")
                if (code == 401) throw Exception("iGPSPORT国际登录已过期，请重新登录")
                if (code != 200) throw Exception("iGPSPORT国际 HTTP $code: ${body.take(200)}")
                val json = JSONObject(body)
                val apiCode = json.optInt("code", -1)
                if (apiCode != 0) {
                    val msg = json.optString("message", json.optString("data", "Unknown"))
                    if (msg.contains("token", true) || msg.contains("login", true) || msg.contains("Login", true))
                        throw Exception("iGPSPORT国际登录已过期，请重新登录")
                    throw Exception("iGPSPORT国际 API error: $msg (code=$apiCode)")
                }
                val data = json.optJSONObject("data") ?: json
                val rows = data.optJSONArray("rows") ?: data.optJSONArray("list") ?: break
                if (rows.length() == 0) break
                for (i in 0 until rows.length()) {
                    fetched++
                    if (fetched <= offset) continue
                    val item = rows.getJSONObject(i)
                    val rideId = item.optString("RideId", item.optString("rideId", item.optString("id", "")))
                    if (rideId.isEmpty()) continue
                    val downloadUrl = item.optString("DownloadUrl",
                        item.optString("downloadUrl", item.optString("FileUrl", item.optString("fileUrl", ""))))
                    var dist = item.optDouble("Distance", -1.0)
                    if (dist < 0) dist = item.optDouble("distance", -1.0)
                    val distKm = if (dist >= 1000) dist / 1000.0 else dist
                    val startTime = probeTimeField(item)
                    result.add(
                        ActivityRecord(
                            id = rideId,
                            title = item.optString("Title", item.optString("title", item.optString("name", "骑行"))),
                            startTime = startTime,
                            distance = distKm,
                            duration = item.optInt("Duration", item.optInt("duration", item.optInt("movingTime", 0))),
                            source = DataSource.IGPSPORT_INT,
                            extra = downloadUrl.ifEmpty { null }
                        )
                    )
                    if (result.size >= limit) break
                }
                if (result.size >= limit || rows.length() < PER_PAGE) break
                currentPage++
                kotlinx.coroutines.delay(200)
            }
            Log.d(TAG, "getActivitiesInternational total: ${result.size}")
            result
        }

    /** 国际区 FIT 下载：先 queryActivityDetail 取 fitUrl，回退 getDownloadUrl，再下载字节 */
    suspend fun downloadFitInternational(token: String, rideId: String): ByteArray =
        withContext(Dispatchers.IO) {
            var downloadUrl = ""
            val detailReq = Request.Builder().url("$GATEWAY_EN/queryActivityDetail/$rideId")
                .apply { authHeadersInt(token).forEach { (k, v) -> addHeader(k, v) } }
                .get().build()
            runCatching {
                val resp = client.newCall(detailReq).execute()
                val json = JSONObject(resp.body?.string() ?: "{}")
                val data = json.optJSONObject("data")
                when (val f = data?.opt("fitUrl")) {
                    is String -> f
                    is JSONObject -> f.optString("url", "")
                    else -> ""
                }
            }.onSuccess { if (it.isNotEmpty()) downloadUrl = it }
            if (downloadUrl.isEmpty()) {
                val fbReq = Request.Builder().url("$DOWNLOAD_URL_EN/$rideId")
                    .apply { authHeadersInt(token).forEach { (k, v) -> addHeader(k, v) } }
                    .get().build()
                val resp = client.newCall(fbReq).execute()
                val json = JSONObject(resp.body?.string() ?: throw Exception("下载URL空响应"))
                downloadUrl = when (val data = json.opt("data")) {
                    is String -> data
                    is JSONObject -> data.optString("url", data.optString("downloadUrl", ""))
                    else -> json.optString("url", json.optString("downloadUrl", ""))
                }
            }
            if (downloadUrl.isEmpty()) throw Exception("iGPSPORT国际无下载URL (rideId=$rideId)")
            val dlReq = Request.Builder().url(downloadUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0.0.0")
                .addHeader("Accept", "*/*")
                .get().build()
            val dlResp = client.newCall(dlReq).execute()
            val bytes = dlResp.body?.bytes() ?: throw Exception("FIT下载空响应")
            if (dlResp.code != 200) throw Exception("iGPSPORT国际 FIT下载失败 HTTP ${dlResp.code}")
            Log.d(TAG, "iGPSPORT国际 FIT downloaded: ${bytes.size} bytes (rideId=$rideId)")
            bytes
        }
}
 

