package com.jichi.ob.api

import android.util.Log
import com.jichi.ob.model.ActivityRecord
import com.jichi.ob.model.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * v8.4.0: 悦跑圈 JoyRun（仅下载，实验室灰度）
 *
 * 参考: github.com/yihong0618/running_page/run_page/joyrun_sync.py
 * 流程: 手机号 → 短信验证码 → sid/uid → 列表 → 轨迹点
 * 签名: params按key排序拼接 + salt + uid + sid → MD5大写
 *   - GET: URL 追加 ?signature=signV1&timestamp=xxx
 *   - POST: body 追加 signature=signV1&timestamp=xxx
 *   - header _sign = signV2
 * 注意: 发验证码接口为常见模式推断，待真实账号验证
 */
class JoyRunApi {

    companion object {
        private const val TAG = "JoyRunApi"
        private const val BASE = "https://api.thejoyrun.com"
        private const val SALT1 = "1fd6e28fd158406995f77727b35bf20a"
        private const val SALT2 = "0C077B1E70F5FDDE6F497C1315687F9C"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    data class LoginResult(val sid: String, val uid: String, val nickname: String)

    private fun md5Upper(s: String): String {
        val md = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        return md.joinToString("") { "%02X".format(it) }
    }

    /** 计算签名: params按key字典序拼 k+v，再拼 salt+uid+sid */
    private fun sign(params: Map<String, String>, uid: String, sid: String, salt: String): String {
        val sb = StringBuilder()
        TreeMap(params).forEach { (k, v) -> sb.append(k).append(v) }
        // uid==0 或空时，python 端把 uid/sid 置空
        val u = if (uid.isEmpty() || uid == "0") "" else uid
        val s = if (uid.isEmpty() || uid == "0") "" else sid
        sb.append(salt).append(u).append(s)
        return md5Upper(sb.toString())
    }

    private fun commonHeaders() = mapOf(
        "Accept-Language" to "en_US",
        "User-Agent" to "okhttp/3.10.0",
        "Host" to "api.thejoyrun.com",
        "Connection" to "Keep-Alive",
        "MODELTYPE" to "Xiaomi MI 5",
        "SYSVERSION" to "8.0.0",
        "APPVERSION" to "6.6.0"
    )

    /** 凭证格式: "sid=xxx&uid=xxx" 或 "sid|uid" */
    private fun parseCred(cred: String): Pair<String, String> {
        // 存的格式 sid=xxx&uid=xxx
        val sid = cred.substringAfter("sid=").substringBefore("&").trim()
        val uid = cred.substringAfter("uid=").trim()
        return sid to uid
    }

    /** 发短信验证码。返回 (是否成功, 服务器返回信息) */
    suspend fun sendSms(phone: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val params = mapOf("phoneNumber" to phone)
            val ts = (System.currentTimeMillis() / 1000).toString()
            val withTs = params + ("timestamp" to ts)
            val sig1 = sign(withTs, "", "", SALT1)
            val sig2 = sign(withTs, "", "", SALT2)
            val url = "$BASE/user/login/sendCode.aspx?phoneNumber=$phone&signature=$sig1&timestamp=$ts"
            val req = Request.Builder().url(url).get()
                .apply { commonHeaders().forEach { (k, v) -> addHeader(k, v) } }
                .addHeader("_sign", sig2)
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                Log.i(TAG, "sendSms HTTP ${resp.code}: $body")
                val ret = try { JSONObject(body).optString("ret") } catch (e: Exception) { "" }
                val msg = try { JSONObject(body).optString("msg", "") } catch (e: Exception) { "" }
                // ret=="0" 成功；其余把服务器原话透出，便于排查
                if (ret == "0") true to (msg.ifEmpty { "验证码已发送" })
                else false to (msg.ifEmpty { "HTTP ${resp.code}: $body" })
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendSms", e)
            false to ("异常: ${e.message}")
        }
    }

    /** 手机号+验证码 登录，返回 sid/uid */
    suspend fun loginByPhone(phone: String, code: String): LoginResult? = withContext(Dispatchers.IO) {
        try {
            val params = mapOf("phoneNumber" to phone, "identifyingCode" to code)
            val ts = (System.currentTimeMillis() / 1000).toString()
            val withTs = params + ("timestamp" to ts)
            val sig1 = sign(withTs, "", "", SALT1)
            val sig2 = sign(withTs, "", "", SALT2)
            val url = "$BASE//user/login/phonecode?phoneNumber=$phone&identifyingCode=$code&signature=$sig1&timestamp=$ts"
            val req = Request.Builder().url(url).get()
                .apply { commonHeaders().forEach { (k, v) -> addHeader(k, v) } }
                .addHeader("_sign", sig2)
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                Log.i(TAG, "login HTTP ${resp.code}: ${body.take(300)}")
                val json = JSONObject(body)
                if (json.optString("ret") == "0") {
                    val sid = json.getJSONObject("data").optString("sid")
                    val uid = json.getJSONObject("data").getJSONObject("user").optString("uid")
                    val name = json.getJSONObject("data").getJSONObject("user").optString("nickname", "悦跑圈用户")
                    LoginResult(sid, uid, name)
                } else {
                    Log.w(TAG, "login失败: ${json.optString("msg")}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "login", e)
            null
        }
    }

    private fun authHeaders(uid: String, sid: String): Map<String, String> =
        commonHeaders() + ("ypcookie" to "sid=$sid&uid=$uid")

    /** 拉活动列表（fid 列表 + 元数据） */
    suspend fun getActivities(cred: String, skip: Int = 0, limit: Int = 50): List<ActivityRecord> = withContext(Dispatchers.IO) {
        val (sid, uid) = parseCred(cred)
        if (sid.isEmpty() || uid.isEmpty()) return@withContext emptyList()
        try {
            val params = mapOf("year" to "0")
            val ts = (System.currentTimeMillis() / 1000).toString()
            val withTs = params + ("timestamp" to ts)
            val sig1 = sign(withTs, uid, sid, SALT1)
            val sig2 = sign(withTs, uid, sid, SALT2)
            val form = FormBody.Builder()
                .add("year", "0")
                .add("timestamp", ts)
                .add("signature", sig1)
                .build()
            val req = Request.Builder().url("$BASE/userRunList.aspx").post(form)
                .apply { authHeaders(uid, sid).forEach { (k, v) -> addHeader(k, v) } }
                .addHeader("_sign", sig2)
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                val json = JSONObject(body)
                val arr = json.optJSONArray("datas") ?: JSONArray()
                val out = mutableListOf<ActivityRecord>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val fid = o.optString("fid", "")
                    if (fid.isEmpty()) continue
                    val startSec = o.optLong("starttime", 0L)
                    out.add(ActivityRecord(
                        id = "jr_$fid",
                        title = "悦跑圈跑步 $fid",
                        startTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(startSec * 1000)),
                        distance = o.optDouble("meter", 0.0) / 1000.0,
                        duration = o.optInt("endtime", 0) - startSec.toInt(),
                        source = DataSource.JOYRUN,
                        startTimeMs = startSec * 1000
                    ))
                }
                out
            }
        } catch (e: Exception) {
            Log.e(TAG, "getActivities", e)
            emptyList()
        }
    }

    /** 下载单条轨迹 → GPX 字节 */
    suspend fun downloadGpx(cred: String, fid: String): ByteArray = withContext(Dispatchers.IO) {
        val (sid, uid) = parseCred(cred)
        try {
            val params = mapOf("fid" to fid, "wgs" to "1")
            val ts = (System.currentTimeMillis() / 1000).toString()
            val withTs = params + ("timestamp" to ts)
            val sig1 = sign(withTs, uid, sid, SALT1)
            val sig2 = sign(withTs, uid, sid, SALT2)
            val form = FormBody.Builder()
                .add("fid", fid)
                .add("wgs", "1")
                .add("timestamp", ts)
                .add("signature", sig1)
                .build()
            val req = Request.Builder().url("$BASE/Run/GetInfo.aspx").post(form)
                .apply { authHeaders(uid, sid).forEach { (k, v) -> addHeader(k, v) } }
                .addHeader("_sign", sig2)
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                val json = JSONObject(body)
                val rr = json.optJSONObject("runrecord") ?: return@use ByteArray(0)
                val content = rr.optString("content", "")
                val points = parsePoints(content)
                val startSec = rr.optLong("starttime", 0L)
                buildGpx(points, startSec, fid)
            }
        } catch (e: Exception) {
            Log.e(TAG, "downloadGpx", e)
            ByteArray(0)
        }
    }

    /** content 形如 -[lat*1e6,lng*1e6]-[...]... ，替换 ]-[ → ],[ 后解析 */
    private fun parsePoints(content: String): List<Pair<Double, Double>> {
        if (content.isEmpty()) return emptyList()
        return try {
            val norm = content.replace("]-[", "],[")
            val arr = JSONArray("[$norm]")
            val out = mutableListOf<Pair<Double, Double>>()
            for (i in 0 until arr.length()) {
                val p = arr.getJSONArray(i)
                out.add(p.getDouble(0) / 1e6 to p.getDouble(1) / 1e6)
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "parsePoints: ${e.message}")
            emptyList()
        }
    }

    private fun buildGpx(points: List<Pair<Double, Double>>, startSec: Long, fid: String): ByteArray {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"OBA-Sync\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("<trk><name>悦跑圈 $fid</name><trkseg>\n")
        points.forEach { (lat, lon) ->
            sb.append("<trkpt lat=\"").append(lat).append("\" lon=\"").append(lon).append("\"/>\n")
        }
        sb.append("</trkseg></trk></gpx>")
        return sb.toString().toByteArray()
    }
}
