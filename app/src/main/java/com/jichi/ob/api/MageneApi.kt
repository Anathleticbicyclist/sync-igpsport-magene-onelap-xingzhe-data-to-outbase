package com.jichi.ob.api
 
import android.util.Log
import com.jichi.ob.model.ActivityRecord
import com.jichi.ob.model.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
 
/**
 * 迈金/顽鹿 OTM API（2026-08-11 实测跑通）
 *
 * 登录: POST /api/login {"account":手机号,"password":MD5(密码)} → data[0].token (JWT裸用)
 * 列表: POST /api/otm/ride_record/list {"page":n,"limit":20} + Authorization: token
 * 详情: GET /api/otm/ride_record/analysis/{id} → data.ridingRecord.durl (七牛签名直链)
 * 下载: GET durl → FIT文件
 * 刷新: POST /api/token {"token":refresh_token,"from":"web","to":"web"}
 */
class MageneApi {
 
    /** 迈金云端无此记录的FIT文件（2025-10后新记录未上传七牛） */
    class NoFileException(msg: String) : Exception(msg)

    /** v9.6.6: 401 登录失效——唯一需要刷新 token / 重登的异常，其余错误不得触发刷新 */
    class MageneAuthException(msg: String) : Exception(msg)

    /** v9.6.6: 403 风控/业务拒绝——单列标记，不重试、不阻断其他平台同步 */
    class MageneRiskControlException(msg: String) : Exception(msg)

    /** 迈金FIT下载结果: fromFitContent=true表示来自fit_content接口(GCJ-02需转换), false表示来自七牛云直链(WGS84无需转换) */
    data class MageneFitResult(val data: ByteArray, val fromFitContent: Boolean)
 
    companion object {
        private const val TAG = "MageneApi"
        const val LOGIN_URL = "https://otm.onelap.cn/login"
        private const val BASE = "https://otm.onelap.cn"
        private const val PER_PAGE = 20
        /** v9.6.6: capability_list 预导入单轮窗口 20 条，首轮 + 最多追 3 轮 */
        private const val IMPORT_WINDOW = 20
        private const val MAX_IMPORT_ROUNDS = 4

        /** v9.6.6: 是否继续追下一轮——本轮 20 条窗口被填满(inserted==total 且 ≥窗口大小)且未达上限才继续；
         *  窗口没填满（如库里只剩 3 条）说明已导完，不能再追，否则白刷接口触发风控 */
        internal fun shouldContinueImport(round: Int, inserted: Int, total: Int): Boolean =
            round < MAX_IMPORT_ROUNDS && inserted >= IMPORT_WINDOW && inserted == total

        /** v9.6.6: refresh_token 合并——服务端下发非空才覆盖，否则保留旧值 */
        internal fun mergeRefreshToken(old: String?, new: String?): String? =
            new?.takeIf { it.isNotEmpty() } ?: old
    }
 
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
 
    private val JSON = "application/json".toMediaType()
 
    private fun md5Hex(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
 
    /**
     * 账号密码直接登录（密码MD5）
     * @return Pair(token, refreshToken)
     */
    suspend fun login(account: String, password: String): Pair<String, String> =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("account", account)
                put("password", md5Hex(password))
            }
            val req = Request.Builder()
                .url("$BASE/api/login")
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0.0.0")
                .addHeader("Origin", BASE)
                .addHeader("Referer", "$BASE/login")
                .post(body.toString().toRequestBody(JSON))
                .build()
            val resp = client.newCall(req).execute()
            val respBody = resp.body?.string() ?: throw Exception("登录空响应")
            val json = JSONObject(respBody)
            val code = json.optInt("code", -1)
            if (code != 200) throw Exception("迈金登录失败: ${json.optString("error", respBody.take(100))}")
            val arr = json.optJSONArray("data") ?: throw Exception("登录响应无data")
            var token = ""
            var refresh = ""
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                if (item.has("token")) {
                    token = item.optString("token", "")
                    refresh = item.optString("refresh_token", "")
                    break
                }
            }
            if (token.isEmpty()) throw Exception("登录响应无token")
            Log.d(TAG, "login ok, token len=${token.length}")
            Pair(token, refresh)
        }
 
    /**
     * token刷新（v9.6.6 改造）
     * 返回 Pair(新token, 新refreshToken?)：新refreshToken为 null 表示服务端未下发，调用方保留旧值。
     * 严格校验 HTTP 200 且业务 code==200，否则视为刷新失败，不返回半成品 token。
     */
    suspend fun refreshToken(refreshToken: String): Pair<String, String?>? =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("token", refreshToken)
                    put("from", "web")
                    put("to", "web")
                }
                val req = Request.Builder()
                    .url("$BASE/api/token")
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0.0.0")
                    .addHeader("Origin", BASE)
                    .post(body.toString().toRequestBody(JSON))
                    .build()
                val resp = client.newCall(req).execute()
                val respBody = resp.body?.string() ?: ""
                if (resp.code != 200) {
                    Log.w(TAG, "refreshToken 刷新失败 HTTP ${resp.code}: ${respBody.take(120)}")
                    return@withContext null
                }
                val json = try { JSONObject(respBody) } catch (e: Exception) {
                    Log.w(TAG, "refreshToken 响应非JSON: ${respBody.take(120)}")
                    return@withContext null
                }
                if (json.optInt("code", -1) != 200) {
                    Log.w(TAG, "refreshToken 业务失败 code=${json.optInt("code", -1)}: ${respBody.take(120)}")
                    return@withContext null
                }
                val data = json.optJSONObject("data")
                val tok = data?.optString("token", "") ?: ""
                if (tok.isEmpty()) {
                    Log.w(TAG, "refreshToken 响应无token")
                    return@withContext null
                }
                // 空/缺省时返回 null，调用方保留旧 refresh_token，避免把轮换令牌写丢
                val newRefresh = data.optString("refresh_token", "").takeIf { it.isNotEmpty() }
                Log.d(TAG, "refreshToken ok, token len=${tok.length}, refresh=${if (newRefresh != null) "更新" else "沿用旧值"}")
                Pair(tok, newRefresh)
            } catch (e: Exception) {
                Log.e(TAG, "refreshToken error", e)
                null
            }
        }
 
    /**
     * 获取活动列表（自动翻页，服务端分页）
     * @param skip 跳过前skip条
     * @param limit 需要的条数
     */
    suspend fun getUsername(token: String): String? = withContext(Dispatchers.IO) {
        // 1) 优先解析JWT payload（迈金登录token是JWT，内含用户信息）
        try {
            val parts = token.split(".")
            if (parts.size >= 2) {
                val payloadB64 = parts[1].replace('-', '+').replace('_', '/')
                val padded = payloadB64 + "=".repeat((4 - payloadB64.length % 4) % 4)
                val decoded = String(android.util.Base64.decode(padded, android.util.Base64.DEFAULT), Charsets.UTF_8)
                val payload = JSONObject(decoded)
                // v7.6.9: 校验JWT过期时间(exp, Unix秒) —— 迈金token是JWT但getUsername之前不校验exp，
                // 导致token已过期仍显示"✅ 登录有效"，同步时才401。exp存在且已过期→判定失效，走刷新。
                val exp = payload.optLong("exp", 0L)
                if (exp > 0) {
                    val nowSec = System.currentTimeMillis() / 1000
                    if (nowSec >= exp) {
                        Log.w(TAG, "getUsername: JWT已过期(exp=$exp, now=$nowSec)")
                        return@withContext null
                    }
                }
                val name = payload.optString("nickname")?.takeIf { it.isNotEmpty() }
                    ?: payload.optString("userName")?.takeIf { it.isNotEmpty() }
                    ?: payload.optString("name")?.takeIf { it.isNotEmpty() }
                    ?: payload.optString("username")?.takeIf { it.isNotEmpty() }
                    ?: payload.optString("account")?.takeIf { it.isNotEmpty() }
                    ?: payload.optString("mobile")?.takeIf { it.isNotEmpty() }
                    ?: "用户${payload.optString("uid").take(6)}"
                return@withContext name
            }
        } catch (e: Exception) { Log.w(TAG, "JWT解析失败: ${e.message}") }
        // 2) 非JWT 或 JWT解析异常 → 用列表接口校验（v7.6.8: 改用确定可用的列表接口而非user/info——
        //    /api/otm/ride_record/list 与 getActivities 同款、验证可用，code==200 即 token 有效）
        try {
            val body = JSONObject().apply {
                put("page", 1)
                put("limit", 1)
            }
            val req = Request.Builder()
                .url("$BASE/api/otm/ride_record/list")
                .addHeader("Authorization", token)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0.0.0")
                .addHeader("Origin", BASE)
                .addHeader("Referer", "$BASE/calendar")
                .post(body.toString().toRequestBody(JSON))
                .build()
            val resp = client.newCall(req).execute()
            val respBody = resp.body?.string() ?: ""
            val json = try { JSONObject(respBody) } catch (e: Exception) { return@withContext null }
            if (resp.code == 200 && json.optInt("code", -1) == 200) "迈金用户" else null
        } catch (e2: Exception) { Log.w(TAG, "getUsername API: ${e2.message}"); null }
    }

    /**
     * v7.6.9: JWT剩余有效期（秒）。<0表示已过期；null表示无法解析（视为无exp信息）
     */
    fun getJwtExpRemainingSec(token: String): Long? {
        return try {
            val parts = token.split(".")
            if (parts.size < 2) return null
            val payloadB64 = parts[1].replace('-', '+').replace('_', '/')
            val padded = payloadB64 + "=".repeat((4 - payloadB64.length % 4) % 4)
            val decoded = String(android.util.Base64.decode(padded, android.util.Base64.DEFAULT), Charsets.UTF_8)
            val exp = JSONObject(decoded).optLong("exp", 0L)
            if (exp <= 0) null else exp - System.currentTimeMillis() / 1000
        } catch (e: Exception) {
            Log.w(TAG, "getJwtExpRemainingSec: ${e.message}")
            null
        }
    }

    // ===== v9.6.6: 迈金 OTM capability_list 预导入 =====
    // 根因：ride_record/list 只返回"已导入 OTM 库"的记录；刚骑完的新记录在 OTM 侧还不存在，
    // 必须先用 capability_list 触发预导入，否则同步永远只看到老记录（表现为"没有需要同步的了"）。

    /** 实例级幂等：同一次同步流程内 capability_list 成功一次后不再重复调用 */
    @Volatile
    private var capabilityImported = false

    /** 从 JWT payload 解析 uid；缺失时降级返回空串（接口仍可调用，由服务端按 token 归属处理） */
    fun getUidFromToken(token: String): String {
        return try {
            val parts = token.split(".")
            if (parts.size < 2) return ""
            // JWT payload 是 base64url，用 JDK 解码器（API 26+ 可用），可脱离 Android 运行时单测
            val decoded = String(java.util.Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
            val payload = JSONObject(decoded)
            val uid = payload.optString("uid", "").ifEmpty { payload.optString("userId", "") }
                .ifEmpty { payload.optString("user_id", "") }
                .ifEmpty { payload.optString("sub", "") }
            if (uid.isEmpty()) Log.w(TAG, "getUidFromToken: JWT中无uid字段，降级发空串")
            uid
        } catch (e: Exception) {
            Log.w(TAG, "getUidFromToken 解析失败: ${e.message}")
            ""
        }
    }

    /**
     * 预导入最新记录（capability_list）
     * 单轮窗口 20 条；inserted==total 表示窗口已导满、可能还有下一批，最多再追 3 轮。
     * @return 本轮累计触发导入的条数
     */
    suspend fun importLatestRecords(token: String): Int = withContext(Dispatchers.IO) {
        if (capabilityImported) {
            Log.d(TAG, "importLatestRecords: 本次流程已预导入，跳过")
            return@withContext 0
        }
        val uid = getUidFromToken(token)
        var imported = 0
        var ok = false
        var round = 0
        while (round < MAX_IMPORT_ROUNDS) {
            round++
            val body = JSONObject().apply { put("uid", uid) }
            val resp = try {
                val req = Request.Builder()
                    .url("$BASE/api/otm/ride_record/capability_list")
                    .addHeader("Authorization", token)   // 裸 token，与列表/详情接口同口径
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0.0.0")
                    .addHeader("Origin", BASE)
                    .addHeader("Referer", "$BASE/calendar")
                    .post(body.toString().toRequestBody(JSON))
                    .build()
                client.newCall(req).execute()
            } catch (e: Exception) {
                Log.w(TAG, "capability_list 网络异常: ${e.message}")
                break
            }
            val respBody = resp.body?.string() ?: ""
            if (resp.code == 401) throw MageneAuthException("迈金登录已过期，请重新登录")
            if (resp.code == 403) throw MageneRiskControlException("迈金拒绝访问(HTTP 403): ${respBody.take(100)}")
            val json = try { JSONObject(respBody) } catch (e: Exception) { null }
            if (json == null) {
                Log.w(TAG, "capability_list 响应非JSON: ${respBody.take(100)}")
                break
            }
            val code = json.optInt("code", -1)
            if (code == 401) throw MageneAuthException("迈金登录已过期，请重新登录")
            if (code == 403) throw MageneRiskControlException("迈金触发风控(403): ${json.optString("error", respBody.take(100))}")
            if (code != 200) {
                Log.w(TAG, "capability_list 业务错误 code=$code: ${json.optString("error", respBody.take(100))}")
                break
            }
            ok = true
            val data = json.optJSONObject("data")
            val inserted = data?.optInt("inserted", 0) ?: 0
            val total = data?.optInt("total", inserted) ?: inserted
            imported += inserted
            Log.d(TAG, "capability_list round=$round window=$IMPORT_WINDOW inserted=$inserted total=$total")
            // 本轮没导满（inserted<total）说明窗口内已全部入库，不必再追
            if (!shouldContinueImport(round, inserted, total)) break
            kotlinx.coroutines.delay(300)
        }
        if (ok) capabilityImported = true
        if (imported > 0) Log.d(TAG, "capability_list 预导入完成，累计 $imported 条")
        imported
    }

    /** 列表前置调用：401 交给上层刷新，403 风控只记日志不阻断，其他异常静默降级 */
    private suspend fun safeImportLatest(token: String) {
        try {
            importLatestRecords(token)
        } catch (e: MageneAuthException) {
            throw e
        } catch (e: MageneRiskControlException) {
            Log.w(TAG, "预导入触发风控，本轮跳过: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "预导入失败(忽略，继续列表): ${e.message}")
        }
    }

    suspend fun getActivities(token: String, skip: Int, limit: Int): List<ActivityRecord> =
        withContext(Dispatchers.IO) {
            // v9.6.6: 严格在 ride_record/list 之前预导入最新记录（实例级幂等，只做一次）
            safeImportLatest(token)
            val result = mutableListOf<ActivityRecord>()
            // 服务端分页: page从 skip/PER_PAGE + 1 开始
            var page = skip / PER_PAGE + 1
            var skipLeft = skip % PER_PAGE  // 首页内需要跳过的条数
            while (result.size < limit) {
                val body = JSONObject().apply {
                    put("page", page)
                    put("limit", PER_PAGE)
                }
                val req = Request.Builder()
                    .url("$BASE/api/otm/ride_record/list")
                    .addHeader("Authorization", token)
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0.0.0")
                    .addHeader("Origin", BASE)
                    .addHeader("Referer", "$BASE/calendar")
                    .post(body.toString().toRequestBody(JSON))
                    .build()
                val resp = client.newCall(req).execute()
                val respBody = resp.body?.string() ?: throw Exception("列表空响应")
                if (resp.code == 401) throw MageneAuthException("迈金登录已过期，请重新登录")
                if (resp.code == 403) throw MageneRiskControlException("迈金拒绝访问(HTTP 403): ${respBody.take(100)}")
                val json = JSONObject(respBody)
                val code = json.optInt("code", -1)
                if (code == 401) throw MageneAuthException("迈金登录已过期，请重新登录")
                if (code == 403) throw MageneRiskControlException("迈金触发风控(403): ${json.optString("error", respBody.take(100))}")
                if (code != 200) throw Exception("迈金列表错误: ${json.optString("error", respBody.take(100))}")
 
                val data = json.optJSONObject("data") ?: break
                val rows = data.optJSONArray("list")
                if (rows == null || rows.length() == 0) break
 
                for (i in 0 until rows.length()) {
                    if (skipLeft > 0) { skipLeft--; continue }
                    val item = rows.getJSONObject(i)
                    val id = item.optString("id", "")
                    if (id.isEmpty()) continue
                    // v9.1.1: 迈金 start_riding_time 为北京时间字符串，optLong 解析返回 0 →
                    // 从字符串解析 startMs（对齐开发版 v8.9.10），否则时间排序/日期检索失效
                    val startTimeRaw = item.optString("start_riding_time", "")
                    val startRaw = item.optLong("start_riding_time", 0L)
                    var startMs = if (startRaw > 1_000_000_000L) (if (startRaw > 1_000_000_000_000L) startRaw else startRaw * 1000L) else 0L
                    if (startMs == 0L && startTimeRaw.isNotBlank()) {
                        try {
                            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                            sdf.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
                            startMs = sdf.parse(startTimeRaw)?.time ?: 0L
                        } catch (_: Exception) {}
                    }
                    // v9.1.1: 迈金API返回UTC时间字符串"2026-09-23 09:49:33"，FileNameGenerator按本地时区解析会差8小时，
                    // 统一转成北京时间字符串（对齐开发版 v8.9.5）
                    val startTime = normalizeToBeijing(startMs, startTimeRaw)
                    var name = item.optString("name", "")
                    if (name == "null") name = ""
                    val title = name.ifEmpty {
                        val d = startTime.take(10)
                        if (d.isNotEmpty()) "$d 骑行" else "骑行 ${id.take(8)}"
                    }
                    result.add(
                        ActivityRecord(
                            id = id,
                            title = title,
                            startTime = startTime,
                            distance = item.optDouble("distance_km", 0.0),
                            duration = item.optInt("time_seconds", 0),
                            source = DataSource.MAGENE,
                            startTimeMs = startMs
                        )
                    )
                    if (result.size >= limit) break
                }
 
                val pagination = data.optJSONObject("pagination")
                val hasMore = pagination?.optBoolean("has_more", false) ?: false
                if (!hasMore || rows.length() < PER_PAGE) break
                page++
                kotlinx.coroutines.delay(150)
            }
            Log.d(TAG, "getActivities: ${result.size} records")
            result
        }

    /** v9.1.1: 把迈金API返回的时间统一转成北京时间字符串"yyyy-MM-dd HH:mm:ss"（对齐开发版 v8.9.5）。
     *  迈金API start_riding_time 返回北京时间字符串，直接格式化，不要再 +8h。 */
    private fun normalizeToBeijing(utcMs: Long, rawStr: String): String {
        if (utcMs > 0) {
            return try {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
                sdf.format(Date(utcMs))
            } catch (_: Exception) { rawStr }
        }
        return rawStr
    }
 
    /**
     * 下载FIT（2026-08-12 修复版）
     * 策略: 详情接口取 durl+fitUrl
     *   ① durl七牛直链（老格式记录可用）
     *   ② 回退 fit_content/{base64(fitUrl)}（官方网页端同款接口，新geo/格式记录必用）
     */
    suspend fun downloadFit(token: String, recordId: String): MageneFitResult =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("$BASE/api/otm/ride_record/analysis/$recordId")
                .addHeader("Authorization", token)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0.0.0")
                .addHeader("Origin", BASE)
                .addHeader("Referer", "$BASE/calendar")
                .get().build()
            val resp = client.newCall(req).execute()
            val respBody = resp.body?.string() ?: throw Exception("详情空响应")
            if (resp.code == 401) throw MageneAuthException("迈金登录已过期，请重新登录")
            if (resp.code == 403) throw MageneRiskControlException("迈金拒绝访问(HTTP 403): ${respBody.take(100)}")
            val json = JSONObject(respBody)
            if (json.optInt("code", -1) == 401) throw MageneAuthException("迈金登录已过期，请重新登录")
            if (json.optInt("code", -1) == 403) throw MageneRiskControlException("迈金触发风控(403): ${respBody.take(100)}")
            if (json.optInt("code", -1) != 200) throw Exception("迈金详情错误: ${respBody.take(100)}")
            val riding = json.optJSONObject("data")?.optJSONObject("ridingRecord")
            val durl = riding?.optString("durl", "") ?: ""
            val fitUrl = riding?.optString("fitUrl", "") ?: ""
 
            // ① durl直链（快，但新格式记录404）
            if (durl.isNotEmpty()) {
                try {
                    val dlReq = Request.Builder()
                        .url(durl)
                        .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0.0.0")
                        .get().build()
                    val dlResp = client.newCall(dlReq).execute()
                    if (dlResp.code == 200) {
                        val bytes = dlResp.body?.bytes()
                        if (bytes != null && isFit(bytes)) {
                            Log.d(TAG, "FIT via durl(七牛云WGS84): ${bytes.size} bytes (id=$recordId)")
                            return@withContext MageneFitResult(bytes, false)
                        }
                    }
                    Log.d(TAG, "durl不可用(HTTP ${dlResp.code})，回退fit_content")
                } catch (e: Exception) {
                    Log.w(TAG, "durl异常: ${e.message}，回退fit_content")
                }
            }
 
            // ② fit_content/{base64(fitUrl)} —— 官方网页端同款，全格式通用
            if (fitUrl.isEmpty()) throw NoFileException("该记录无FIT文件(fitUrl为空，可能是虚拟路线)")
            val b64 = Base64.encodeToString(fitUrl.toByteArray(), Base64.NO_WRAP)
            val fcReq = Request.Builder()
                .url("$BASE/api/otm/ride_record/analysis/fit_content/$b64")
                .addHeader("Authorization", token)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0.0.0")
                .addHeader("Origin", BASE)
                .addHeader("Referer", "$BASE/calendar")
                .get().build()
            val fcResp = client.newCall(fcReq).execute()
            val fcBytes = fcResp.body?.bytes() ?: throw Exception("fit_content空响应")
            if (fcResp.code != 200 || !isFit(fcBytes)) {
                throw NoFileException("FIT获取失败(fit_content HTTP ${fcResp.code}, size=${fcBytes.size})")
            }
            Log.d(TAG, "FIT via fit_content(GCJ-02): ${fcBytes.size} bytes (id=$recordId)")
            MageneFitResult(fcBytes, true)
        }
 
    private fun isFit(bytes: ByteArray): Boolean =
        bytes.size >= 14 && bytes[8] == '.'.code.toByte() && bytes[9] == 'F'.code.toByte()
}
 

