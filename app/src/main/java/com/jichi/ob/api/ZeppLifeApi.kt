package com.jichi.ob.api

import android.util.Log
import com.jichi.ob.model.ActivityRecord
import com.jichi.ob.model.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * v8.4.0: Zepp Life（华米旧版云 / 小米运动）——实验室灰度
 *
 * 华米旧版云 api-mifit-cn.huami.com，登录需 apptoken 加密（参考 huami-token 开源项目）。
 * 暂无测试账号，先提供账号密码登录入口与凭证存储；真实登录/列表/下载待账号验证后补全。
 */
class ZeppLifeApi {

    companion object { private const val TAG = "ZeppLifeApi" }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class LoginResult(val token: String, val userId: String, val nickname: String)

    /**
     * 账号密码登录（占位——华米 apptoken 加密流程待真实账号补全）
     * 当前仅校验非空并返回占位 token，便于走通登录 UI 与凭证存储。
     */
    suspend fun login(account: String, password: String): LoginResult? = withContext(Dispatchers.IO) {
        try {
            // TODO(待账号验证): 实现华米云 apptoken 加密登录
            // 参考: https://github.com/icy000/huami-token  sign_in 流程
            Log.w(TAG, "ZeppLife 登录为占位实现（待真实账号补全华米加密登录）: account=$account")
            // 暂用 account 作 token 占位，凭证能存住即可
            LoginResult(token = "zepplife_placeholder_$account", userId = account, nickname = "Zepp Life用户")
        } catch (e: Exception) {
            Log.e(TAG, "login", e)
            null
        }
    }

    /** 活动列表（占位，待账号验证） */
    suspend fun getActivities(cred: String, skip: Int, limit: Int): List<ActivityRecord> = emptyList()

    /** 下载（占位，待账号验证） */
    suspend fun downloadGpx(cred: String, fid: String): ByteArray = ByteArray(0)
}
