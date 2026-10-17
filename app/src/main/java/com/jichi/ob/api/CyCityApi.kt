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
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * CyCity API（v9.1.2 移植自开发版 v8.9.1，2026-10 实测可用）
 *
 * 正式版定位：CyCity 仅作为「数据来源（下载）」，数据统一上传到 Outbase；
 * 因此只移植 登录/校验/拉列表/下载FIT 四条链路，上传两步（import + uploadImportData）不移植。
 *
 * 认证: JWT Bearer token（账号密码 login 获取，纯 API 无需浏览器）
 * 登录: POST https://cycity.cc/api/auth/mobile/login
 *        body JSON: {phonenumber, password, grantType:"password"}
 *        → {code:200, data:{access_token, userId, ...}}
 * 列表: GET  https://cycity.cc/api/mobile/cycling/rideRecordsPage?pageNum=1&pageSize=N
 * 下载: POST https://cycity.cc/api/mobile/cycling/exportFitFile?rideId=xxx
 */
class CyCityApi {

    companion object {
        private const val TAG = "CyCityApi"
        private const val BASE = "https://cycity.cc/api"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun ua() = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    data class LoginResult(val token: String, val nickname: String, val userId: String)

    /** 账号密码登录 */
    suspend fun login(phone: String, password: String): LoginResult? = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject()
                .put("phonenumber", phone)
                .put("password", password)
                .put("grantType", "password")
                .toString()
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("$BASE/auth/mobile/login")
                .addHeader("User-Agent", ua())
                .addHeader("Origin", "http://app.cycity.cc")
                .post(body)
                .build()
            client.newCall(req).execute().use { resp ->
                val result = resp.body?.string()?.trim() ?: ""
                Log.d(TAG, "login HTTP ${resp.code}: ${result.take(200)}")
                if (resp.code != 200) return@withContext null
                val json = JSONObject(result)
                if (json.optInt("code", -1) != 200) return@withContext null
                val data = json.optJSONObject("data") ?: return@withContext null
                val token = data.optString("access_token", "")
                if (token.isBlank()) return@withContext null
                LoginResult(token, "CyCity用户", data.optString("userId", ""))
            }
        } catch (e: Exception) {
            Log.e(TAG, "login error", e)
            null
        }
    }

    /** 校验 token：调活动列表，成功返回昵称 */
    suspend fun getUsername(token: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$BASE/mobile/cycling/rideRecordsPage?pageNum=1&pageSize=1")
                .addHeader("User-Agent", ua())
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.code != 200) return@withContext null
                val json = JSONObject(resp.body?.string() ?: "")
                if (json.optInt("code", -1) == 200) "CyCity用户" else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "getUsername error", e)
            null
        }
    }

    /** 拉活动列表。skip/limit 转成 pageNum/pageSize */
    suspend fun getActivities(token: String, skip: Int, limit: Int): List<ActivityRecord> = withContext(Dispatchers.IO) {
        try {
            val pageNum = skip / limit + 1
            val req = Request.Builder()
                .url("$BASE/mobile/cycling/rideRecordsPage?pageNum=$pageNum&pageSize=$limit")
                .addHeader("User-Agent", ua())
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.code != 200) return@withContext emptyList()
                val json = JSONObject(resp.body?.string() ?: "")
                if (json.optInt("code", -1) != 200) return@withContext emptyList()
                val rows = json.optJSONArray("rows") ?: return@withContext emptyList()
                val list = mutableListOf<ActivityRecord>()
                for (i in 0 until rows.length()) {
                    val r = rows.getJSONObject(i)
                    val id = r.optString("id", "")
                    val startStr = r.optString("startTime", "")
                    val distKm = r.optDouble("movingDistance", 0.0)
                    val durSec = r.optInt("movingDuration", 0)
                    list.add(ActivityRecord(
                        id = id,
                        title = "CyCity骑行",
                        startTime = startStr,
                        distance = distKm,
                        duration = durSec,
                        source = DataSource.CYCITY,
                        extra = id
                    ))
                }
                list
            }
        } catch (e: Exception) {
            Log.e(TAG, "getActivities error", e)
            emptyList()
        }
    }

    /** 下载 FIT */
    suspend fun downloadFit(token: String, rideId: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$BASE/mobile/cycling/exportFitFile?rideId=$rideId")
                .addHeader("User-Agent", ua())
                .addHeader("Authorization", "Bearer $token")
                .post(ByteArray(0).toRequestBody())
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.code != 200) {
                    Log.w(TAG, "downloadFit HTTP ${resp.code}")
                    return@withContext null
                }
                val bytes = resp.body?.bytes()
                if (bytes == null || bytes.size < 100) {
                    Log.w(TAG, "downloadFit too small: ${bytes?.size}")
                    return@withContext null
                }
                bytes
            }
        } catch (e: Exception) {
            Log.e(TAG, "downloadFit error", e)
            null
        }
    }
}
