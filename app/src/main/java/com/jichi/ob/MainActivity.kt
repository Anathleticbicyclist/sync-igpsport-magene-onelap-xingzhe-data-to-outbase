package com.jichi.ob

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.Log
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import android.widget.GridLayout
import android.text.SpannableString
import android.text.Spannable
import android.text.style.AbsoluteSizeSpan
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.jichi.ob.api.BlackbirdApi
import com.jichi.ob.api.BrytonApi
import com.jichi.ob.api.CorosApi
import com.jichi.ob.api.GarminApi
import com.jichi.ob.api.GarminOAuthHelper
import com.jichi.ob.api.WahooApi
import com.jichi.ob.api.WahooOAuth2Service
import com.jichi.ob.api.IgpsportApi
import com.jichi.ob.api.MageneApi
import com.jichi.ob.api.MyWhooshApi
import com.jichi.ob.api.KeepApi
import com.jichi.ob.api.ZwiftApi
import com.jichi.ob.api.OutbaseApi
import com.jichi.ob.api.UploadEngine
import com.jichi.ob.api.XingzheApi
import com.jichi.ob.api.CodoonApi
import com.jichi.ob.api.ZeppApi
import com.jichi.ob.api.KomootApi
import com.jichi.ob.api.SuuntoApi
import com.jichi.ob.model.ActivityRecord
import com.jichi.ob.model.DownloadSupport
import com.jichi.ob.model.DataSource
import com.jichi.ob.model.UploadSupport
import com.jichi.ob.ui.LoginWebActivity
import com.jichi.ob.util.PrefsManager
import com.jichi.ob.util.FileNameGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "JichiOB"
        // 动态读取BuildConfig版本号，保证启动日志与当前版本一一对应
        private val APP_VERSION = "v${BuildConfig.VERSION_NAME}"
        private const val APP_EDITION = "正式版"
        // v7.6.2: 存储目录（Fragment共用）
        val SAVE_DIR: File by lazy {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "迈向Ob")
        }
    }

    private lateinit var prefs: PrefsManager
    private lateinit var igpsportApi: IgpsportApi
    private lateinit var xingzheApi: XingzheApi
    private lateinit var mageneApi: MageneApi
    private lateinit var blackbirdApi: BlackbirdApi
    private lateinit var brytonApi: BrytonApi
    private lateinit var outbaseApi: OutbaseApi
    private lateinit var garminApi: GarminApi
    private lateinit var mywhooshApi: MyWhooshApi
    private lateinit var zwiftApi: ZwiftApi
    private lateinit var keepApi: KeepApi
    private lateinit var codoonApi: CodoonApi
    private lateinit var zeppApi: ZeppApi
    private lateinit var komootApi: KomootApi
    private lateinit var suuntoApi: SuuntoApi
    private var lastZeppLoginAttempt = 0L
    private lateinit var corosApi: CorosApi
    private lateinit var wahooApi: WahooApi
    private lateinit var uploadEngine: UploadEngine

    // v7.6.2: 四页面Fragment引用
    private lateinit var loginFragment: com.jichi.ob.ui.LoginFragment
    private lateinit var settingsFragment: com.jichi.ob.ui.SyncSettingsFragment
    private lateinit var syncFragment: com.jichi.ob.ui.SyncFragment
    private lateinit var aboutFragment: com.jichi.ob.ui.AboutFragment
    private lateinit var mergeFragment: com.jichi.ob.ui.MergeFragment
    private lateinit var createTaskFragment: com.jichi.ob.ui.CreateTaskFragment

    private var syncJob: Job? = null
    private var autoSyncJob: Job? = null
    private var taskJob: Job? = null
    private var taskActive = false
    internal val isTaskRunning: Boolean get() = taskActive
    private lateinit var fixWebView: android.webkit.WebView
    private var fixJsReady = false
    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) appendLog("✅ 通知权限已授予") else appendLog("⚠️ 通知权限被拒绝，后台同步通知可能不显示")
    }

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                val type = data.getStringExtra(LoginWebActivity.RESULT_LOGIN_TYPE) ?: ""
                val token = data.getStringExtra(LoginWebActivity.RESULT_TOKEN) ?: ""
                val sid = data.getStringExtra(LoginWebActivity.RESULT_SESSION_ID) ?: ""
                val extra = data.getStringExtra(LoginWebActivity.RESULT_EXTRA) ?: ""
                when (type) {
                    LoginWebActivity.TYPE_IGPSPORT -> if (token.length > 20) {
                        prefs.saveIgpsportToken(token); appendLog("✅ iGPSPORT登录成功"); fetchUsernameAfterLogin(DataSource.IGPSPORT)
                    }
                    LoginWebActivity.TYPE_XINGZHE -> if (sid.length > 10) {
                        prefs.saveXingzheSessionId(sid)
                        if (extra.isNotEmpty()) prefs.saveXingzheCsrf(extra)
                        appendLog("✅ 行者登录成功"); fetchUsernameAfterLogin(DataSource.XINGZHE)
                    }
                    LoginWebActivity.TYPE_MAGENE -> if (token.length > 20) {
                        prefs.saveMageneToken(token)
                        if (extra.isNotEmpty()) prefs.saveMageneRefreshToken(extra)
                        appendLog("✅ 迈金登录成功"); fetchUsernameAfterLogin(DataSource.MAGENE)
                    }
                    LoginWebActivity.TYPE_BLACKBIRD -> if (sid.length > 5) {
                        prefs.saveBlackbirdCookie(sid); appendLog("✅ 黑鸟单车登录成功(cookie ${sid.length}字节)"); fetchUsernameAfterLogin(DataSource.BLACKBIRD)
                    } else appendLog("⚠️ 黑鸟单车cookie异常，请重新登录")
                    LoginWebActivity.TYPE_BRYTON -> {
                        // v6.2.4: 百锐腾凭证格式 token;userId;cookie（Meteor登录态在localStorage）
                        val parts = sid.split(";")
                        val tok = parts.getOrNull(0) ?: ""
                        val uid = parts.getOrNull(1) ?: ""
                        val ck = parts.drop(2).joinToString(";")
                        if (tok.length > 10 && uid.isNotEmpty()) {
                            prefs.saveBrytonToken(tok); prefs.saveBrytonUserId(uid); prefs.saveBrytonCookie(ck)
                            appendLog("✅ 百锐腾登录成功(Meteor token ${tok.length}B, userId=$uid)")
                            fetchUsernameAfterLogin(DataSource.BRYTON)
                        } else if (ck.length > 5) {
                            prefs.saveBrytonCookie(ck); appendLog("✅ 百锐腾登录成功(cookie ${ck.length}字节)")
                            fetchUsernameAfterLogin(DataSource.BRYTON)
                        } else appendLog("⚠️ 百锐腾cookie异常，请重新登录")
                    }
                    LoginWebActivity.TYPE_OUTBASE -> if (sid.length > 10) {
                        prefs.saveOutbaseSessionId(sid)
                        prefs.saveGatewayCookies(extra)
                        appendLog("✅ Outbase登录成功"); fetchUsernameAfterLogin(DataSource.OUTBASE)
                    }
                    LoginWebActivity.TYPE_GARMIN_COM -> if (token.length > 20) {
                        prefs.saveGarminComToken(token)
                        prefs.saveGarminComCookie("")
                        appendLog("✅ 佳明国际登录成功(mobile SSO+DI Token)"); fetchUsernameAfterLogin(DataSource.GARMIN_COM)
                        // v8.2.0: 佳明风控引导——已生成长期刷新凭证，提醒勿频繁重新登录
                        com.jichi.ob.util.GarminLoginHint.show(this, "佳明国际")
                    } else appendLog("⚠️ 佳明国际登录失败: 未获取到token")
                    LoginWebActivity.TYPE_GARMIN_CN -> if (token.length > 20) {
                        prefs.saveGarminCnToken(token)
                        prefs.saveGarminCnCookie("")
                        appendLog("✅ 佳明中国登录成功(JWT_WEB+session)"); fetchUsernameAfterLogin(DataSource.GARMIN_CN)
                        // v8.2.0: 佳明风控引导——已生成长期刷新凭证，提醒勿频繁重新登录
                        com.jichi.ob.util.GarminLoginHint.show(this, "佳明中国")
                    } else appendLog("⚠️ 佳明中国登录失败: 未获取到token")
                    LoginWebActivity.TYPE_COROS_CN -> if (sid.length > 10) {
                        prefs.saveCorosCnToken(sid)
                        appendLog("✅ 高驰中国登录成功"); fetchUsernameAfterLogin(DataSource.COROS_CN)
                    } else appendLog("⚠️ 高驰中国登录失败: 未捕获到token")
                    LoginWebActivity.TYPE_COROS_INT -> if (sid.length > 10) {
                        prefs.saveCorosIntToken(sid)
                        appendLog("✅ 高驰国际登录成功"); fetchUsernameAfterLogin(DataSource.COROS_INT)
                    } else appendLog("⚠️ 高驰国际登录失败: 未捕获到token")
                    LoginWebActivity.TYPE_WAHOO -> {
                        // v7.1.3: Wahoo 返回 OAuth2 授权码，优先用内置生产凭证，其次用用户配置的凭证
                        val clientId = if (com.jichi.ob.api.WahooApi.isBuiltinConfigured()) com.jichi.ob.api.WahooApi.BUILTIN_CLIENT_ID else prefs.getWahooClientId()
                        val clientSecret = if (com.jichi.ob.api.WahooApi.isBuiltinConfigured()) com.jichi.ob.api.WahooApi.BUILTIN_CLIENT_SECRET else prefs.getWahooClientSecret()
                        if (sid.length > 5 && !clientId.isNullOrEmpty() && !clientSecret.isNullOrEmpty()) {
                            val code = sid
                            lifecycleScope.launch(Dispatchers.IO) {
                                val fresh = wahooApi.exchangeToken(code, clientId, clientSecret)
                                runOnUiThread {
                                    if (fresh != null) {
                                        prefs.saveWahooToken(fresh.first)
                                        prefs.saveWahooRefresh(fresh.second)
                                        appendLog("✅ Wahoo登录成功"); fetchUsernameAfterLogin(DataSource.WAHOO)
                                    } else appendLog("⚠️ Wahoo token换取失败")
                                    loginFragment.updateStatus()
                                }
                            }
                        } else appendLog("⚠️ Wahoo登录失败: 未捕获到授权码或未配置凭证")
                    }
                    LoginWebActivity.TYPE_SUUNTO -> {
                        // v8.3.8: Suunto 返回 OAuth2 授权码 → 换 token（需三凭证）
                        val clientId = if (com.jichi.ob.api.SuuntoApi.isBuiltinConfigured()) com.jichi.ob.api.SuuntoApi.BUILTIN_CLIENT_ID else prefs.getSuuntoClientId()
                        val clientSecret = if (com.jichi.ob.api.SuuntoApi.isBuiltinConfigured()) com.jichi.ob.api.SuuntoApi.BUILTIN_CLIENT_SECRET else prefs.getSuuntoClientSecret()
                        val subKey = suuntoSubscriptionKey()
                        if (sid.length > 5 && !clientId.isNullOrEmpty() && !clientSecret.isNullOrEmpty() && !subKey.isNullOrEmpty()) {
                            val code = sid
                            lifecycleScope.launch(Dispatchers.IO) {
                                val fresh = suuntoApi.exchangeCode(code, clientId, clientSecret, subKey)
                                runOnUiThread {
                                    if (fresh != null) {
                                        prefs.saveSuuntoToken(fresh.accessToken)
                                        prefs.saveSuuntoRefresh(fresh.refreshToken)
                                        appendLog("✅ 松拓登录成功"); fetchUsernameAfterLogin(DataSource.SUUNTO)
                                    } else appendLog("⚠️ 松拓token换取失败")
                                    loginFragment.updateStatus()
                                }
                            }
                        } else appendLog("⚠️ 松拓登录失败: 未捕获到授权码或未配置凭证")
                    }
                }
                loginFragment.updateStatus()
            }
        } catch (e: Exception) {
            Log.e(TAG, "login result error", e)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // v7.5.7: 全局崩溃捕获，堆栈写入文件，下次启动显示在日志中定位闪退
        val crashFile = File(cacheDir, "last_crash.txt")
        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                crashFile.writeText("时间: $time\n线程: ${thread.name}\n\n$sw")
            } catch (_: Exception) {}
            oldHandler?.uncaughtException(thread, throwable)
        }
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)
            prefs = PrefsManager(this)
            igpsportApi = IgpsportApi()
            xingzheApi = XingzheApi()
            mageneApi = MageneApi()
            blackbirdApi = BlackbirdApi()
            brytonApi = BrytonApi()
            outbaseApi = OutbaseApi()
            garminApi = GarminApi()
            garminApi.initWebView(this)  // v6.7.3: 国际版用WebView绕过Cloudflare
            com.jichi.ob.api.GarminApi.setAppContext(this)  // v8.2.0: 佳明429风控冷却持久化
            mywhooshApi = MyWhooshApi()
            zwiftApi = ZwiftApi()
            keepApi = KeepApi()
            codoonApi = CodoonApi()
            zeppApi = ZeppApi()
            komootApi = KomootApi()
            suuntoApi = SuuntoApi()
            CodoonApi.gcjConvertEnabled = prefs.isCodoonGcjConvertEnabled()
            ZeppApi.gcjConvertEnabled = prefs.isZeppGcjConvertEnabled()
            corosApi = CorosApi()
            wahooApi = WahooApi()
            uploadEngine = UploadEngine(this)
            if (!SAVE_DIR.exists()) SAVE_DIR.mkdirs()
            migrateLegacyStorageDir()
            initFragments()
            initFixWebView()
            requestNotificationPermission()
            appendLog("🚴 迈向Ob($APP_EDITION) $APP_VERSION 启动")
            appendLog("🎯 让运动数据自由流动")
            appendLog("📱 Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLog("📂 存储目录: ${SAVE_DIR.absolutePath}")
            appendLog("💾 已同步记录: ${prefs.getSyncedCount()} 条")
            // v7.5.9: 启动登录检测（异步，不阻塞界面；v8.4.0 对齐开发版，可在登录页关闭自动检测）
            if (prefs.isAutoCheckLogin()) checkAllLogins()
            // v7.5.7: 显示上次崩溃信息（如果有）
            if (crashFile.exists()) {
                try {
                    val crashText = crashFile.readText()
                    appendLog("⚠️ 检测到上次崩溃:\n$crashText")
                    crashFile.delete()
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "onCreate failed", e)
        }
    }

    /** v8.4.0: 平台登录统一分发（登录页程序化卡片调用；对齐开发版 openPlatformLogin） */
    internal fun openPlatformLogin(ds: DataSource) {
        when (ds) {
            DataSource.IGPSPORT -> openLogin(LoginWebActivity.TYPE_IGPSPORT, com.jichi.ob.api.IgpsportApi.LOGIN_URL)
            DataSource.XINGZHE -> openLogin(LoginWebActivity.TYPE_XINGZHE, com.jichi.ob.api.XingzheApi.LOGIN_URL)
            DataSource.MAGENE -> openLogin(LoginWebActivity.TYPE_MAGENE, com.jichi.ob.api.MageneApi.LOGIN_URL)
            DataSource.BLACKBIRD -> openLogin(LoginWebActivity.TYPE_BLACKBIRD, com.jichi.ob.api.BlackbirdApi.LOGIN_URL)
            DataSource.BRYTON -> openLogin(LoginWebActivity.TYPE_BRYTON, com.jichi.ob.api.BrytonApi.LOGIN_URL)
            DataSource.OUTBASE -> openLogin(LoginWebActivity.TYPE_OUTBASE, com.jichi.ob.api.OutbaseApi.LOGIN_URL)
            DataSource.GARMIN_COM -> openLogin(LoginWebActivity.TYPE_GARMIN_COM, com.jichi.ob.api.GarminApi.LOGIN_URL_COM)
            DataSource.GARMIN_CN -> openGarminCnLogin()
            DataSource.COROS_CN -> openLogin(LoginWebActivity.TYPE_COROS_CN, com.jichi.ob.api.CorosApi.LOGIN_URL_CN)
            DataSource.COROS_INT -> openLogin(LoginWebActivity.TYPE_COROS_INT, com.jichi.ob.api.CorosApi.LOGIN_URL_INT)
            DataSource.WAHOO -> openWahooLogin()
            DataSource.MYWHOOSH -> openMywhooshLogin()
            DataSource.ZWIFT -> openZwiftLogin()
            DataSource.KEEP -> openKeepLogin()
            DataSource.CODOON -> openCodoonLogin()
            DataSource.ZEPP -> openZeppLogin()
            DataSource.KOMOT -> openKomootLogin()
            DataSource.SUUNTO -> openSuuntoLogin()
        }
    }

    /** v8.4.0: 清空佳明风控冷却（登录页详情弹窗调用） */
    internal fun clearGarminCooldown(ds: DataSource) {
        if (ds != DataSource.GARMIN_CN && ds != DataSource.GARMIN_COM) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("清空风控")
            .setMessage("若清空风控后强行尝试登录，可能增加冷却时间，你确定清空吗？")
            .setPositiveButton("确定清空") { _, _ ->
                com.jichi.ob.api.GarminApi.clearAllCooldownFor(ds)
                android.widget.Toast.makeText(this, "${ds.displayName}：风控冷却缓存已清空，可重新登录", android.widget.Toast.LENGTH_SHORT).show()
                appendLog("🧹 ${ds.displayName} 风控冷却已清空")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    internal fun openLogin(type: String, url: String) {
        appendLog("🔐 打开登录页...")
        val intent = Intent(this, LoginWebActivity::class.java)
        intent.putExtra(LoginWebActivity.EXTRA_LOGIN_TYPE, type)
        intent.putExtra(LoginWebActivity.EXTRA_URL, url)
        loginLauncher.launch(intent)
    }

    /** v7.4.5: Wahoo 登录——恢复WahooOAuth2Service后台自动化登录（v7.3.0验证通过的方案），SCOPES含workouts_write支持上传 */
    internal fun openWahooLogin() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val emailInput = android.widget.EditText(this).apply {
            hint = "Wahoo邮箱"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(prefs.getWahooEmail() ?: "")
        }
        val passwordInput = android.widget.EditText(this).apply {
            hint = "Wahoo密码"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        }
        layout.addView(emailInput)
        layout.addView(passwordInput)
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Wahoo直接登录")
            .setMessage("后台自动完成OAuth2授权（含workouts_write上传权限）")
            .setView(layout)
            .setPositiveButton("登录") { _, _ ->
                val email = emailInput.text.toString().trim()
                val password = passwordInput.text.toString()
                if (email.isEmpty() || password.isEmpty()) {
                    appendLog("⚠️ 请输入邮箱和密码")
                    return@setPositiveButton
                }
                prefs.saveWahooEmail(email)
                appendLog("🔐 Wahoo直接登录中...")
                WahooOAuth2Service.debugLogCallback = { msg -> runOnUiThread { appendLog(msg) } }
                lifecycleScope.launch(Dispatchers.IO) {
                    // v7.5.4: 登录前先尝试复用已有token，避免每次登录都新建token导致"Too many unrevoked access tokens"上限
                    val clientId = if (com.jichi.ob.api.WahooApi.isBuiltinConfigured()) com.jichi.ob.api.WahooApi.BUILTIN_CLIENT_ID else prefs.getWahooClientId()
                    val clientSecret = if (com.jichi.ob.api.WahooApi.isBuiltinConfigured()) com.jichi.ob.api.WahooApi.BUILTIN_CLIENT_SECRET else prefs.getWahooClientSecret()
                    val savedToken = prefs.getWahooToken()
                    val savedRefresh = prefs.getWahooRefresh()
                    var reused: Pair<String, String>? = null
                    if (!savedToken.isNullOrEmpty() && !clientId.isNullOrEmpty() && !clientSecret.isNullOrEmpty()) {
                        appendLog("🔎 检测到已保存的Wahoo令牌，尝试复用（避免新建token超限）...")
                        reused = wahooApi.getUsableTokenOrNull(savedToken, savedRefresh, clientId, clientSecret)
                        if (reused != null) {
                            appendLog("✅ 复用已有Wahoo令牌成功，无需重新授权")
                        } else {
                            appendLog("ℹ️ 已有令牌已失效，尝试撤销旧令牌后重新授权...")
                            // v7.5.4: 用旧token撤销全部授权（即使已失效也无害），尽量清空token名额
                            if (wahooApi.deauthorize(savedToken)) appendLog("✅ 旧Wahoo令牌已撤销")
                            else appendLog("ℹ️ 旧令牌撤销失败（可能已失效），继续重新授权")
                        }
                    }
                    if (reused != null) {
                        prefs.saveWahooToken(reused.first)
                        prefs.saveWahooRefresh(reused.second)
                        appendLog("✅ Wahoo登录成功（含上传权限）")
                        fetchUsernameAfterLogin(DataSource.WAHOO)
                    } else {
                        appendLog("🔐 开始Wahoo OAuth2重新授权...")
                        val result = WahooOAuth2Service.login(email, password)
                        if (result != null) {
                            prefs.saveWahooToken(result.first)
                            prefs.saveWahooRefresh(result.second)
                            appendLog("✅ Wahoo登录成功（含上传权限）")
                            fetchUsernameAfterLogin(DataSource.WAHOO)
                        } else {
                            // v7.5.4: 错误原因已在WahooOAuth2Service日志中详细展示
                            appendLog("❌ Wahoo登录失败，详见上方日志")
                            // v7.5.4: token数量超限时弹出针对性引导
                            if (WahooOAuth2Service.lastError == WahooOAuth2Service.ERROR_TOKEN_LIMIT) {
                                runOnUiThread {
                                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                                        .setTitle("Wahoo令牌数量超限")
                                        .setMessage("该Wahoo账号下\"鸡翅幸哲迈进OB\"的未撤销令牌已达10枚上限，无法继续登录。\n\n解决方法：\n1. 打开手机上的Wahoo官方App\n2. 进入 设置(Settings) → 已授权应用(Authorized Apps)\n3. 找到\"鸡翅幸哲迈进OB\"，点击 撤销授权(Deauthorize)\n4. 回到本应用重新登录即可")
                                        .setPositiveButton("我知道了", null)
                                        .show()
                                }
                            }
                        }
                    }
                    runOnUiThread { loginFragment.updateStatus() }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** v8.2.0: MyWhoosh 直接登录——账号密码原生表单直调 MyWhooshApi（纯API，仅下载源） */
    internal fun openMywhooshLogin() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val accountInput = android.widget.EditText(this).apply {
            hint = "MyWhoosh 账号（邮箱）"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setText(prefs.getMywhooshAccount() ?: "")
        }
        val passwordInput = android.widget.EditText(this).apply {
            hint = "MyWhoosh 密码"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        }
        layout.addView(accountInput)
        layout.addView(passwordInput)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("登录 MyWhoosh")
            .setMessage("账号密码直接登录，MyWhoosh 作为数据源（仅下载，不支持上传）")
            .setView(layout)
            .setPositiveButton("登录") { _, _ ->
                val account = accountInput.text.toString().trim()
                val password = passwordInput.text.toString()
                if (account.isEmpty() || password.isEmpty()) {
                    appendLog("⚠️ 请输入 MyWhoosh 账号和密码")
                    return@setPositiveButton
                }
                prefs.saveMywhooshAccount(account)
                appendLog("🔐 MyWhoosh直接登录中...")
                lifecycleScope.launch(Dispatchers.IO) {
                    val result = mywhooshApi.login(account, password)
                    runOnUiThread {
                        if (result != null) {
                            prefs.saveMywhooshToken(result.token)
                            prefs.saveMywhooshWhooshId(result.whooshId)
                            prefs.saveMywhooshRefreshToken(result.refreshToken)
                            appendLog("✅ MyWhoosh登录成功")
                            fetchUsernameAfterLogin(DataSource.MYWHOOSH)
                        } else {
                            appendLog("❌ MyWhoosh登录失败：账号或密码错误，请重新输入")
                        }
                        loginFragment.updateStatus()
                        try { settingsFragment?.refreshLoginState() } catch (_: Exception) {}
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** v8.2.0: Zwift 直接登录——账号密码原生表单直调 ZwiftApi（纯API，仅下载源） */
    internal fun openZwiftLogin() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val accountInput = android.widget.EditText(this).apply {
            hint = "Zwift 账号（邮箱）"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setText(prefs.getZwiftAccount() ?: "")
        }
        val passwordInput = android.widget.EditText(this).apply {
            hint = "Zwift 密码"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        }
        layout.addView(accountInput)
        layout.addView(passwordInput)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("登录 Zwift")
            .setMessage("账号密码直接登录，Zwift 作为数据源（仅下载，不支持上传）")
            .setView(layout)
            .setPositiveButton("登录") { _, _ ->
                val account = accountInput.text.toString().trim()
                val password = passwordInput.text.toString()
                if (account.isEmpty() || password.isEmpty()) {
                    appendLog("⚠️ 请输入 Zwift 账号和密码")
                    return@setPositiveButton
                }
                prefs.saveZwiftAccount(account)
                appendLog("🔐 Zwift直接登录中...")
                lifecycleScope.launch(Dispatchers.IO) {
                    val result = zwiftApi.login(account, password)
                    runOnUiThread {
                        if (result != null) {
                            prefs.saveZwiftToken(result.token)
                            prefs.saveZwiftRefreshToken(result.refreshToken)
                            // 解析 playerId 供活动列表使用
                            lifecycleScope.launch(Dispatchers.IO) {
                                val pid = zwiftApi.getProfileId(result.token)
                                runOnUiThread {
                                    if (!pid.isNullOrBlank()) prefs.saveZwiftPlayerId(pid)
                                    appendLog("✅ Zwift登录成功")
                                    fetchUsernameAfterLogin(DataSource.ZWIFT)
                                    loginFragment.updateStatus()
                                    try { settingsFragment?.refreshLoginState() } catch (_: Exception) {}
                                }
                            }
                        } else {
                            appendLog("❌ Zwift登录失败：账号或密码错误，请重新输入")
                            loginFragment.updateStatus()
                            try { settingsFragment?.refreshLoginState() } catch (_: Exception) {}
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** v8.2.1: Keep 直接登录——手机号密码原生表单直调 KeepApi（纯API，仅下载源） */
    internal fun openKeepLogin() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val accountInput = android.widget.EditText(this).apply {
            hint = "Keep 手机号"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setText(prefs.getKeepAccount() ?: "")
        }
        val passwordInput = android.widget.EditText(this).apply {
            hint = "Keep 密码"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        }
        layout.addView(accountInput)
        layout.addView(passwordInput)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("登录 Keep")
            .setMessage("手机号密码直接登录，Keep 作为数据源（下载运动记录，仅下载）")
            .setView(layout)
            .setPositiveButton("登录") { _, _ ->
                val account = accountInput.text.toString().trim()
                val password = passwordInput.text.toString()
                if (account.isEmpty() || password.isEmpty()) {
                    appendLog("⚠️ 请输入 Keep 手机号和密码")
                    return@setPositiveButton
                }
                prefs.saveKeepAccount(account)
                appendLog("🔐 Keep直接登录中...")
                lifecycleScope.launch(Dispatchers.IO) {
                    val result = keepApi.login(account, password)
                    runOnUiThread {
                        if (result != null) {
                            prefs.saveKeepToken(result.token)
                            appendLog("✅ Keep登录成功")
                            fetchUsernameAfterLogin(DataSource.KEEP)
                        } else {
                            appendLog("❌ Keep登录失败：账号或密码错误，请重新输入")
                        }
                        loginFragment.updateStatus()
                        try { settingsFragment?.refreshLoginState() } catch (_: Exception) {}
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** v8.3.8: 咕咚直接登录——手机号+密码（纯API，仅下载源） */
    internal fun openCodoonLogin() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val accountInput = android.widget.EditText(this).apply {
            hint = "咕咚 手机号"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setText(prefs.getCodoonAccount() ?: "")
        }
        val passwordInput = android.widget.EditText(this).apply {
            hint = "咕咚 密码"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        }
        layout.addView(accountInput)
        layout.addView(passwordInput)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("登录 咕咚")
            .setMessage("手机号密码直接登录，咕咚作为数据源（下载运动记录）；咕咚官方无开放上传API，正式版仅支持上传到Outbase")
            .setView(layout)
            .setPositiveButton("登录") { _, _ ->
                val account = accountInput.text.toString().trim()
                val password = passwordInput.text.toString()
                if (account.isEmpty() || password.isEmpty()) {
                    appendLog("⚠️ 请输入咕咚手机号和密码")
                    return@setPositiveButton
                }
                prefs.saveCodoonAccount(account)
                prefs.saveCodoonPassword(password)
                appendLog("🔐 咕咚直接登录中...")
                lifecycleScope.launch(Dispatchers.IO) {
                    val result = codoonApi.login(account, password)
                    runOnUiThread {
                        if (result != null) {
                            prefs.saveCodoonToken(result.token)
                            prefs.saveCodoonUserId(result.userId)
                            appendLog("✅ 咕咚登录成功")
                            fetchUsernameAfterLogin(DataSource.CODOON)
                        } else {
                            appendLog("❌ 咕咚登录失败：账号或密码错误，请重新输入")
                        }
                        loginFragment.updateStatus()
                        try { settingsFragment?.refreshLoginState() } catch (_: Exception) {}
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** v8.3.8: Zepp（华米）直接登录——邮箱/手机号+密码（纯API，仅下载源） */
    internal fun openZeppLogin() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val accountInput = android.widget.EditText(this).apply {
            hint = "Zepp 邮箱或手机号"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setText(prefs.getZeppAccount() ?: "")
        }
        val passwordInput = android.widget.EditText(this).apply {
            hint = "Zepp 密码"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        }
        layout.addView(accountInput)
        layout.addView(passwordInput)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("登录 Zepp")
            .setMessage("使用 Zepp/小米运动 App 账号邮箱或手机号密码直接登录，Zepp 作为数据源（下载运动记录）；华米无官方开放上传API，正式版仅支持上传到Outbase")
            .setView(layout)
            .setPositiveButton("登录") { _, _ ->
                val account = accountInput.text.toString().trim()
                val password = passwordInput.text.toString()
                if (account.isEmpty() || password.isEmpty()) {
                    appendLog("⚠️ 请输入 Zepp 邮箱/手机号和密码")
                    return@setPositiveButton
                }
                prefs.saveZeppAccount(account)
                // v8.3.8: 登录节流——华米对连续登录风控严格(429)，10 秒内禁止重复提交
                val now = System.currentTimeMillis()
                if (now - lastZeppLoginAttempt < 10_000) {
                    appendLog("⚠️ Zepp 登录过于频繁，请稍等 10 秒再试（避免触发华米风控）")
                    return@setPositiveButton
                }
                lastZeppLoginAttempt = now
                appendLog("🔐 Zepp直接登录中...")
                lifecycleScope.launch(Dispatchers.IO) {
                    val result = zeppApi.login(account, password)
                    runOnUiThread {
                        if (result != null && result.appToken.isNotBlank()) {
                            prefs.saveZeppToken(result.appToken)
                            prefs.saveZeppUserId(result.userId)
                            appendLog("✅ Zepp登录成功")
                            fetchUsernameAfterLogin(DataSource.ZEPP)
                        } else {
                            appendLog("❌ Zepp登录失败：${result?.error ?: "账号或密码错误，请重新输入"}")
                        }
                        loginFragment.updateStatus()
                        try { settingsFragment?.refreshLoginState() } catch (_: Exception) {}
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** v8.3.8: Komoot 直接登录——邮箱+密码（纯API Basic认证，仅下载源） */
    internal fun openKomootLogin() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val emailInput = android.widget.EditText(this).apply {
            hint = "Komoot 邮箱"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(prefs.getKomootAccount() ?: "")
        }
        val passwordInput = android.widget.EditText(this).apply {
            hint = "Komoot 密码"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        }
        layout.addView(emailInput)
        layout.addView(passwordInput)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("登录 Komoot")
            .setMessage("使用 Komoot 官网注册账号的邮箱密码直接登录，Komoot 作为数据源（下载运动记录）；正式版仅支持上传到Outbase")
            .setView(layout)
            .setPositiveButton("登录") { _, _ ->
                val email = emailInput.text.toString().trim()
                val password = passwordInput.text.toString()
                if (email.isEmpty() || password.isEmpty()) {
                    appendLog("⚠️ 请输入 Komoot 邮箱和密码")
                    return@setPositiveButton
                }
                prefs.saveKomootAccount(email)
                appendLog("🔐 Komoot直接登录中...")
                lifecycleScope.launch(Dispatchers.IO) {
                    val result = komootApi.login(email, password)
                    runOnUiThread {
                        if (result != null) {
                            prefs.saveKomootToken(result.token)
                            prefs.saveUsername(DataSource.KOMOT, result.username)
                            appendLog("✅ Komoot登录成功")
                            fetchUsernameAfterLogin(DataSource.KOMOT)
                        } else {
                            appendLog("❌ Komoot登录失败：邮箱或密码错误，请重新输入")
                        }
                        loginFragment.updateStatus()
                        try { settingsFragment?.refreshLoginState() } catch (_: Exception) {}
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * v8.3.8: Suunto（松拓）登录——OAuth2 授权。
     * 需要 apizone.suunto.com 注册开发者应用的 client_id/client_secret/subscription_key 三凭证。
     * ① 若凭证已内置/已保存 → 直接打开 WebView 授权页；
     * ② 否则先弹对话框填三凭证（仅首次）。
     */
    internal fun openSuuntoLogin() {
        val hasCreds = com.jichi.ob.api.SuuntoApi.isBuiltinConfigured() ||
            (!prefs.getSuuntoClientId().isNullOrEmpty() && !prefs.getSuuntoClientSecret().isNullOrEmpty() && !prefs.getSuuntoSubscriptionKey().isNullOrEmpty())
        if (!hasCreds) {
            // 首次：填三凭证
            val layout = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(48, 24, 48, 24)
            }
            val clientIdInput = android.widget.EditText(this).apply {
                hint = "Client ID"
                inputType = android.text.InputType.TYPE_CLASS_TEXT
                setText(prefs.getSuuntoClientId() ?: "")
            }
            val clientSecretInput = android.widget.EditText(this).apply {
                hint = "Client Secret"
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                setText(prefs.getSuuntoClientSecret() ?: "")
            }
            val subKeyInput = android.widget.EditText(this).apply {
                hint = "Subscription Key"
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                setText(prefs.getSuuntoSubscriptionKey() ?: "")
            }
            layout.addView(clientIdInput)
            layout.addView(clientSecretInput)
            layout.addView(subKeyInput)

            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("松拓开发者凭证")
                .setMessage("在 apizone.suunto.com 免费注册开发者应用（个人申请约3-4周审批），到应用详情页复制 Client ID / Client Secret / Subscription Key 三凭证（仅首次填写，仅存本机）")
                .setView(layout)
                .setPositiveButton("下一步") { _, _ ->
                    val cid = clientIdInput.text.toString().trim()
                    val cs = clientSecretInput.text.toString().trim()
                    val sk = subKeyInput.text.toString().trim()
                    if (cid.isEmpty() || cs.isEmpty() || sk.isEmpty()) {
                        appendLog("⚠️ 请完整填写松拓三个凭证")
                        return@setPositiveButton
                    }
                    prefs.saveSuuntoClientId(cid)
                    prefs.saveSuuntoClientSecret(cs)
                    prefs.saveSuuntoSubscriptionKey(sk)
                    launchSuuntoWebAuth(cid)
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            val cid = if (com.jichi.ob.api.SuuntoApi.isBuiltinConfigured())
                com.jichi.ob.api.SuuntoApi.BUILTIN_CLIENT_ID else prefs.getSuuntoClientId()!!
            launchSuuntoWebAuth(cid)
        }
    }

    private fun launchSuuntoWebAuth(clientId: String) {
        appendLog("🔐 打开松拓授权页...")
        openLogin(LoginWebActivity.TYPE_SUUNTO, suuntoApi.authorizeUrl(clientId))
    }

    /**
     * v7.5.1: 佳明中国直接登录（模拟garth库mobile SSO流程，不需要WebView）
     * 用邮箱密码直接获取OAuth2 Bearer token，调用connectapi.garmin.cn
     */
    internal fun openGarminCnLogin() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val emailInput = android.widget.EditText(this).apply {
            hint = "佳明中国邮箱"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val passwordInput = android.widget.EditText(this).apply {
            hint = "佳明中国密码"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        }
        layout.addView(emailInput)
        layout.addView(passwordInput)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("佳明中国直接登录")
            .setMessage("后台自动完成mobile SSO+OAuth2授权（参考garth库，Bearer token调connectapi）")
            .setView(layout)
            .setPositiveButton("登录") { _, _ ->
                val email = emailInput.text.toString().trim()
                val password = passwordInput.text.toString()
                if (email.isEmpty() || password.isEmpty()) {
                    appendLog("⚠️ 请输入邮箱和密码")
                    return@setPositiveButton
                }
                // v8.2.0: 佳明中国补风控检查——按账号维度（中国区走OAuth1→OAuth2，主通道GCM_ANDROID_DARK）
                if (com.jichi.ob.api.GarminApi.isCooldown(DataSource.GARMIN_CN, email, "GCM_ANDROID_DARK")) {
                    val remain = com.jichi.ob.api.GarminApi.cooldownRemainMinutes(DataSource.GARMIN_CN, email, "GCM_ANDROID_DARK")
                    appendLog("❌ 该账号处于佳明中国风控冷却中，请约${remain}分钟后重试（冷却仅针对该账号，可切换其他账号登录）")
                    return@setPositiveButton
                }
                appendLog("🔐 佳明中国直接登录中...")
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val oauth2 = GarminOAuthHelper.loginWithCredentialsCn(email, password)
                        // 保存为diToken格式（GarminApi已支持佳明中国DI token+connectapi）
                        val cred = JSONObject().apply {
                            put("di_token", oauth2.accessToken)
                            put("di_refresh_token", oauth2.refreshToken)
                            put("di_client_id", "GCM_ANDROID_DARK")
                            put("jwt_web", "")
                            put("session", "")
                            put("csrf", "")
                            // v8.2.0: 记录账号email，便于refresh按账号维度冷却
                            put("email", email)
                        }.toString()
                        runOnUiThread {
                            prefs.saveGarminCnToken(cred)
                            prefs.saveGarminCnCookie("")
                            appendLog("✅ 佳明中国登录成功(mobile SSO+DI Token)")
                            com.jichi.ob.util.GarminLoginHint.show(this@MainActivity, "佳明中国")
                            fetchUsernameAfterLogin(DataSource.GARMIN_CN)
                            loginFragment.updateStatus()
                        }
                    } catch (e: Exception) {
                        // v8.2.0: 佳明中国429（风控）单独提示 + 写冷却
                        val msg = e.message ?: ""
                        if (msg.contains("429") || msg.contains("rate limit") || msg.contains("限流")) {
                            com.jichi.ob.api.GarminApi.writeCooldownFor(DataSource.GARMIN_CN, email, "GCM_ANDROID_DARK")
                            val remain = com.jichi.ob.api.GarminApi.cooldownRemainMinutes(DataSource.GARMIN_CN, email, "GCM_ANDROID_DARK")
                            runOnUiThread {
                                appendLog("❌ 佳明中国触发风控限流(429)，已写入冷却。该账号请约${remain}分钟后重试（冷却仅针对该账号）")
                                loginFragment.updateStatus()
                            }
                        } else {
                            runOnUiThread {
                                appendLog("❌ 佳明中国登录失败: $msg")
                                loginFragment.updateStatus()
                            }
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** v7.2.0: Wahoo配置对话框（保留，用于用户自配置凭证） */
    private fun openWahooConfigDialog() {
        val savedId = prefs.getWahooClientId() ?: ""
        val savedSecret = prefs.getWahooClientSecret() ?: ""

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        val etId = android.widget.EditText(this).apply {
            hint = "Client ID"
            setText(savedId)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val etSecret = android.widget.EditText(this).apply {
            hint = "Client Secret"
            setText(savedSecret)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val tvTutorial = android.widget.TextView(this).apply {
            text = "申请沙箱教程：\n1. 打开 developers.wahooligan.com/applications\n2. 注册/登录后点击 New Application\n3. 填写名称，Redirect URI 填 http://localhost:8080\n4. Scopes 勾选 user_read、workouts_read、offline_data\n5. 提交后复制 Client ID 和 Client Secret 填入上方\n（沙箱免费，审核通过后即可使用）"
            textSize = 11f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 24, 0, 0)
        }

        layout.addView(etId)
        layout.addView(etSecret)
        layout.addView(tvTutorial)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Wahoo 开发者凭证配置")
            .setView(layout)
            .setPositiveButton("保存并登录") { _, _ ->
                val id = etId.text.toString().trim()
                val secret = etSecret.text.toString().trim()
                if (id.isEmpty() || secret.isEmpty()) {
                    android.widget.Toast.makeText(this, "请填写 Client ID 和 Client Secret", android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                prefs.saveWahooClientId(id)
                prefs.saveWahooClientSecret(secret)
                openLogin(LoginWebActivity.TYPE_WAHOO, wahooApi.authorizeUrl(id))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ============ v8.4.0 数据合并：全屏覆盖页（裁剪开发版贴纸，仅保留合并+上传Outbase） ============
    fun openMerge() {
        val bottomNav = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNav)
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        supportFragmentManager.beginTransaction().show(mergeFragment).commit()
        bottomNav?.visibility = android.view.View.GONE
        toolbar?.visibility = android.view.View.GONE
        appendLog("🗂 进入数据合并")
    }

    fun closeMerge() {
        val bottomNav = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNav)
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        supportFragmentManager.beginTransaction().hide(mergeFragment).commit()
        bottomNav?.visibility = android.view.View.VISIBLE
        toolbar?.visibility = android.view.View.VISIBLE
        try { showFragment(syncFragment) } catch (_: Exception) {}
    }

    /** v8.4.0: 合并页记录分页结果 */
    data class MergeFetchPage(val records: List<ActivityRecord>, val hasMore: Boolean)

    /**
     * v8.4.0: 合并页拉取记录（对齐开发版 v8.1.2 分页化）：
     * - 无日期筛选：只拉最近 1 批（30 条），秒开；
     * - 指定日期范围：从 skip 开始翻页拉取并过滤，单次最多拉 500 条防卡，hasMore=可能还有更早记录。
     */
    suspend fun fetchMergeActivities(source: DataSource, fromDate: String? = null, toDate: String? = null, skipStart: Int = 0): MergeFetchPage = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, ActivityRecord>()
        var skip = skipStart
        val page = 30
        val max = 500
        var fetched = 0
        var lastBatchFull = false
        try {
            while (fetched < max) {
                val batch = try {
                    fetchActivities(source, skip, page)
                } catch (e: Exception) {
                    break
                }
                if (batch.isEmpty()) { lastBatchFull = false; break }
                lastBatchFull = batch.size >= page
                skip += batch.size; fetched += batch.size
                if (fromDate == null && toDate == null) {
                    for (r in batch) out[r.id] = r
                    break
                }
                for (r in batch) {
                    val d = recDay(r.startTime)
                    if ((fromDate == null || d >= fromDate) && (toDate == null || d <= toDate)) out[r.id] = r
                }
                if (batch.size < page) break
            }
        } catch (_: Exception) {}
        val list = out.values.toList()
        if (fromDate == null && toDate == null) return@withContext MergeFetchPage(list.take(page), lastBatchFull)
        MergeFetchPage(list, lastBatchFull && fetched >= max)
    }

    private fun recDay(startTime: String): String = startTime.take(10)

    /** v8.4.0: 合并页下载单条轨迹原始数据（FIT/GPX） */
    suspend fun downloadForMerge(source: DataSource, record: ActivityRecord): ByteArray? = withContext(Dispatchers.IO) {
        try {
            downloadActivity(source, DataSource.OUTBASE, record)
        } catch (e: Exception) {
            null
        }
    }

    /** v8.4.0: 失效平台集合（登录页红标显示，checkAllLogins 填充） */
    internal val lastInvalidPlatforms = mutableSetOf<DataSource>()

    internal fun checkAllLogins(onDone: ((valid: Int, refreshed: Int, invalid: Int) -> Unit)? = null) {
        appendLog("🔍 启动登录检测中...")
        lastInvalidPlatforms.clear()
        lifecycleScope.launch(Dispatchers.IO) {
            var valid = 0
            var refreshed = 0
            var invalid = 0
            val platforms = listOf(
                DataSource.IGPSPORT, DataSource.XINGZHE, DataSource.MAGENE, DataSource.BLACKBIRD,
                DataSource.BRYTON, DataSource.OUTBASE, DataSource.GARMIN_COM, DataSource.GARMIN_CN,
                DataSource.COROS_CN, DataSource.COROS_INT, DataSource.WAHOO, DataSource.MYWHOOSH, DataSource.ZWIFT, DataSource.KEEP,
                DataSource.CODOON, DataSource.ZEPP, DataSource.KOMOT, DataSource.SUUNTO
            )
            for (ds in platforms) {
                if (!prefs.isLoggedIn(ds)) continue  // 未登录过的跳过，不发无用请求
                kotlinx.coroutines.delay(300)  // 间隔避免并发触发风控
                val cred = prefs.getCredential(ds) ?: continue
                val username = try {
                    when (ds) {
                        DataSource.IGPSPORT -> igpsportApi.getUsername(cred)
                        DataSource.XINGZHE -> xingzheApi.getUsername(cred)
                        DataSource.MAGENE -> mageneApi.getUsername(cred)
                        DataSource.BLACKBIRD -> blackbirdApi.getUsername(cred)
                        DataSource.BRYTON -> brytonApi.getUsername(cred)
                        DataSource.OUTBASE -> outbaseApi.getUsername(cred)
                        DataSource.GARMIN_COM -> garminApi.getUsername(ds, cred)
                        DataSource.GARMIN_CN -> garminApi.getUsername(ds, cred)
                        DataSource.COROS_CN -> corosApi.getUsername(cred)
                        DataSource.COROS_INT -> corosApi.getUsername(cred)
                        DataSource.WAHOO -> wahooApi.getUsername(cred)
                        DataSource.MYWHOOSH -> mywhooshApi.getUsername(cred)
                        DataSource.ZWIFT -> zwiftApi.getUsername(cred)
                        DataSource.KEEP -> keepApi.getUsername(cred)
                        DataSource.CODOON -> codoonApi.getUsername(cred, prefs.getCodoonUserId())
                        DataSource.ZEPP -> zeppApi.getUsername(cred)
                        DataSource.KOMOT -> {
                            val email = prefs.getKomootAccount()
                            if (email.isNullOrEmpty()) null else komootApi.getUsername(email, cred)
                        }
                        DataSource.SUUNTO -> {
                            val sk = suuntoSubscriptionKey()
                            if (sk.isNullOrEmpty()) null else suuntoApi.getUsername(cred, sk)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "启动登录检测 ${ds.displayName} 异常: ${e.message}")
                    null
                }
                if (!username.isNullOrBlank()) {
                    valid++
                    prefs.saveUsername(ds, username)
                    // v7.6.9: 迈金JWT若快过期(<1小时)，后台主动刷新，避免同步时才401
                    if (ds == DataSource.MAGENE) {
                        try {
                            val expLeft = mageneApi.getJwtExpRemainingSec(cred)
                            if (expLeft != null && expLeft < 3600) {
                                val refresh = prefs.getMageneRefreshToken()
                                if (!refresh.isNullOrEmpty()) {
                                    val newTok = mageneApi.refreshToken(refresh)
                                    if (newTok != null && newTok != cred) {
                                        prefs.saveCredential(ds, newTok)
                                        prefs.saveMageneRefreshToken(refresh)
                                        runOnUiThread { appendLog("🔄 ${ds.displayName} token将过期，已后台提前刷新") }
                                    }
                                }
                            }
                        } catch (e: Exception) { Log.w(TAG, "迈金提前刷新异常: ${e.message}") }
                    }
                    runOnUiThread { appendLog("✅ 登录有效: ${ds.displayName} ($username)") }
                    continue
                }
                // 登录态失效 → 尝试刷新
                val newCred = refreshCredentialOnStart(ds, cred)
                if (newCred != null && newCred != cred) {
                    refreshed++
                    prefs.saveCredential(ds, newCred)
                    // v7.6.1: 刷新后校验新token是否真实可用，输出明确成功/失败日志
                    // v7.6.8: 迈金刷新成功即保存新token；校验失败不再提示"同步失败请重新登录"（新token已保存，
                    //         同步时若真失效会有明确的401报错兜底，避免刷新成功却误报需重登）
                    val verifyName = try {
                        when (ds) {
                            DataSource.MAGENE -> mageneApi.getUsername(newCred)
                            DataSource.WAHOO -> wahooApi.getUsername(newCred)
                            else -> null
                        }
                    } catch (e: Exception) { null }
                    val suffix = if (verifyName.isNullOrBlank())
                        "（已自动刷新，同步时将自动校验）"
                    else " ✅ 登录有效 ($verifyName)"
                    runOnUiThread { appendLog("🔄 ${ds.displayName} 登录态失效，已自动刷新$suffix") }
                } else {
                    invalid++
                    lastInvalidPlatforms.add(ds)
                    if (ds == DataSource.WAHOO || ds == DataSource.COROS_CN || ds == DataSource.COROS_INT) {
                        // v7.6.8: Wahoo/高驰失效时【绝不】清除凭证！
                        // Wahoo: token/refresh_token有复用价值，清除会导致重登走完整OAuth新建token触发10枚上限
                        // 高驰: 凭证含regionId/cookie，保留后重登时WebView可复用cookie自动登录，无需重新选区域输账号
                        runOnUiThread { appendLog("❌ ${ds.displayName} 登录失效，请重新登录（已保留令牌，重登时自动复用/刷新，不会新建令牌）") }
                    } else {
                        prefs.clearCredential(ds)
                        runOnUiThread { appendLog("❌ ${ds.displayName} 登录失效，请重新登录") }
                    }
                }
            }
            runOnUiThread {
                loginFragment.updateStatus()
                appendLog("📊 登录检测完成: ${valid}有效 / ${refreshed}刷新成功 / ${invalid}失效")
                onDone?.invoke(valid, refreshed, invalid)
            }
        }
    }

    /**
     * v7.5.9: 启动检测时尝试刷新失效平台的登录态
     * 仅支持有刷新机制的平台；返回 null 表示无法刷新（需重新登录）
     */
    private suspend fun refreshCredentialOnStart(ds: DataSource, cred: String): String? = when (ds) {
        DataSource.MAGENE -> {
            val refresh = prefs.getMageneRefreshToken()
            if (refresh.isNullOrEmpty()) null else mageneApi.refreshToken(refresh)
        }
        DataSource.WAHOO -> {
            val refresh = prefs.getWahooRefresh()
            val clientId = if (com.jichi.ob.api.WahooApi.isBuiltinConfigured())
                com.jichi.ob.api.WahooApi.BUILTIN_CLIENT_ID else prefs.getWahooClientId()
            val clientSecret = if (com.jichi.ob.api.WahooApi.isBuiltinConfigured())
                com.jichi.ob.api.WahooApi.BUILTIN_CLIENT_SECRET else prefs.getWahooClientSecret()
            if (refresh.isNullOrEmpty() || clientId.isNullOrEmpty() || clientSecret.isNullOrEmpty()) null
            else {
                // v7.6.0: 用getUsableTokenOrNull（先测token→失效则refresh→刷新后用新token验证撤销旧token）
                // 刷新成功必须同时保存新access_token和新refresh_token（refresh_token是轮换令牌，旧的作废）
                val usable = wahooApi.getUsableTokenOrNull(cred, refresh, clientId, clientSecret)
                if (usable != null) {
                    prefs.saveWahooToken(usable.first)
                    prefs.saveWahooRefresh(usable.second)
                    usable.first
                } else null
            }
        }
        // v8.2.0: 佳明DI token静默刷新（用refresh_token，不重新SSO登录，避免撞429风控）
        // ensureValidToken内部判断：未过期→原样返回；过期且有refresh_token→刷新返回新凭证
        DataSource.GARMIN_COM, DataSource.GARMIN_CN -> {
            try { garminApi.ensureValidToken(ds, cred) } catch (e: Exception) { null }
        }
        DataSource.CODOON -> null  // 咕咚无 refresh 端点，需重新登录
        DataSource.ZEPP -> null  // Zepp 无 refresh 端点，需重新登录
        DataSource.KOMOT -> {
            // v8.3.8: Komoot token 为长期令牌，失效时尝试自动重登
            try {
                val fresh = komootApi.reLoginIfNeeded()
                fresh?.token
            } catch (e: Exception) { null }
        }
        DataSource.SUUNTO -> {
            // v8.3.8: Suunto 用 refresh_token 刷新（轮换令牌，成功后需同时保存新access+refresh）
            val refresh = prefs.getSuuntoRefresh()
            val clientId = if (com.jichi.ob.api.SuuntoApi.isBuiltinConfigured()) com.jichi.ob.api.SuuntoApi.BUILTIN_CLIENT_ID else prefs.getSuuntoClientId()
            val clientSecret = if (com.jichi.ob.api.SuuntoApi.isBuiltinConfigured()) com.jichi.ob.api.SuuntoApi.BUILTIN_CLIENT_SECRET else prefs.getSuuntoClientSecret()
            val subKey = suuntoSubscriptionKey()
            if (refresh.isNullOrEmpty() || clientId.isNullOrEmpty() || clientSecret.isNullOrEmpty() || subKey.isNullOrEmpty()) null
            else {
                val fresh = suuntoApi.refreshToken(refresh, clientId, clientSecret, subKey)
                if (fresh != null) {
                    prefs.saveSuuntoToken(fresh.accessToken)
                    prefs.saveSuuntoRefresh(fresh.refreshToken)
                    fresh.accessToken
                } else null
            }
        }
        else -> null
    }

    /** v8.3.8: Suunto Subscription Key（内置优先，其次用户配置） */
    private fun suuntoSubscriptionKey(): String? =
        if (com.jichi.ob.api.SuuntoApi.isBuiltinConfigured())
            com.jichi.ob.api.SuuntoApi.BUILTIN_SUBSCRIPTION_KEY
        else prefs.getSuuntoSubscriptionKey()

    private fun fetchUsernameAfterLogin(ds: DataSource) {
        lifecycleScope.launch(Dispatchers.IO) {
            val cred = prefs.getCredential(ds) ?: return@launch
            val name = when (ds) {
                DataSource.IGPSPORT -> igpsportApi.getUsername(cred)
                DataSource.XINGZHE -> xingzheApi.getUsername(cred)
                DataSource.MAGENE -> mageneApi.getUsername(cred)
                DataSource.BLACKBIRD -> blackbirdApi.getUsername(cred)
                DataSource.BRYTON -> brytonApi.getUsername(cred)
                DataSource.OUTBASE -> outbaseApi.getUsername(cred)
                // v6.7.2: 佳明displayName是UUID(用户ID)，不获取不显示，直接已登录
                DataSource.GARMIN_COM -> null
                DataSource.GARMIN_CN -> null
                DataSource.COROS_CN -> corosApi.getUsername(cred)
                DataSource.COROS_INT -> corosApi.getUsername(cred)
                DataSource.WAHOO -> wahooApi.getUsername(cred)
                DataSource.MYWHOOSH -> mywhooshApi.getUsername(cred)
                DataSource.ZWIFT -> zwiftApi.getUsername(cred)
                DataSource.KEEP -> keepApi.getUsername(cred)
                DataSource.CODOON -> codoonApi.getUsername(cred, prefs.getCodoonUserId())
                DataSource.ZEPP -> zeppApi.getUsername(cred)
                DataSource.KOMOT -> {
                    val email = prefs.getKomootAccount()
                    if (email.isNullOrEmpty()) null else komootApi.getUsername(email, cred)
                }
                DataSource.SUUNTO -> {
                    val sk = suuntoSubscriptionKey()
                    if (sk.isNullOrEmpty()) null else suuntoApi.getUsername(cred, sk)
                }
            }
            if (name != null) {
                prefs.saveUsername(ds, name)
                appendLog("👤 ${ds.displayName}用户: $name")
                runOnUiThread { loginFragment.updateStatus() }
            }
            // v8.4.2: 登录成功后询问是否预拉取运动记录（对齐开发版；写入 ActivityCache 后登录页显示条数）
            runOnUiThread {
                try {
                    if (!isFinishing && !isDestroyed) {
                        androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle("拉取最近运动记录？")
                            .setMessage("已登录 ${ds.displayName}。是否立即拉取运动记录到本地缓存？\n（后台执行不影响使用；也可在批量同步时自动写入缓存，登录页将显示平台条数）")
                            .setPositiveButton("立即拉取") { _, _ -> preloadRecent(ds) }
                            .setNegativeButton("暂不", null)
                            .show()
                    }
                } catch (_: Exception) {}
            }
        }
    }

    // ============ v8.4.2: 预拉取运动记录入库（对齐开发版，登录页条数徽标数据源） ============
    /** 登录后询问「立即拉取」入口 */
    fun preloadRecent(ds: DataSource) {
        lifecycleScope.launch(Dispatchers.IO) {
            val n = preloadAllOf(ds)
            runOnUiThread {
                if (n == 0) Toast.makeText(this@MainActivity, "${ds.displayName} 暂无运动记录", Toast.LENGTH_SHORT).show()
                else if (n > 0) Toast.makeText(this@MainActivity, "已拉取 $n 条 ${ds.displayName} 记录", Toast.LENGTH_LONG).show()
                try { loginFragment.updateStatus() } catch (_: Exception) {}
            }
        }
    }

    /** 单平台全量拉取核心：返回入库条数（0=无记录，-1=失败） */
    private suspend fun preloadAllOf(ds: DataSource): Int {
        try {
            appendLog("📥 ${ds.displayName} 预拉取运动记录...")
            val all = mutableListOf<ActivityRecord>()
            val seen = HashSet<String>()
            var skip = 0
            val BATCH = 200
            val MAX = 2000 // 与缓存库单平台上限一致
            var batches = 0
            while (all.size < MAX && batches < 60) {
                val batch = try { fetchActivities(ds, skip, BATCH) } catch (e: Exception) {
                    appendLog("⚠️ ${ds.displayName} 第 ${skip + 1} 条起拉取中断: ${e.message}")
                    break
                }
                if (batch.isEmpty()) break
                val before = seen.size
                batch.forEach { seen.add(it.id) }
                if (seen.size == before) break // 游标未前进，收口
                all.addAll(batch)
                batches++
                appendLog("📥 ${ds.displayName} 已拉取 ${all.size} 条...")
                skip += batch.size
            }
            if (all.isEmpty()) { appendLog("⏳ ${ds.displayName} 暂无运动记录"); return 0 }
            cacheUpsertActivities(ds, all)
            try {
                com.jichi.ob.util.ActivityCache.get(this).addPlatformLog(ds.shortName, "导入", "预拉取 ${all.size} 条记录入库")
            } catch (_: Exception) {}
            appendLog("💾 ${ds.displayName} 已缓存 ${all.size} 条记录")
            return all.size
        } catch (e: Exception) {
            appendLog("⚠️ ${ds.displayName} 预拉取失败: ${e.message}")
            return -1
        }
    }

    /** v8.4.2: iGPSPORT 批量修复缺失时间（1970 记录 → 下载 FIT 解析回填；对齐开发版） */
    internal fun repairIgpTimes() {
        val token = prefs.getIgpsportToken()
        if (token.isNullOrEmpty()) {
            Toast.makeText(this, "iGPSPORT 未登录，无法修复时间", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "开始修复 iGPSPORT 缺失时间（后台进行，查看平台日志）", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val cache = com.jichi.ob.util.ActivityCache.get(this@MainActivity)
                val bad = cache.queryBadTime(DataSource.IGPSPORT.shortName, 100)
                if (bad.isEmpty()) {
                    appendLog("✅ iGPSPORT 无缺失时间记录")
                    return@launch
                }
                appendLog("🔧 修复 iGPSPORT 缺失时间: ${bad.size} 条（下载 FIT 解析，串行）...")
                var fixed = 0
                for ((i, rec) in bad.withIndex()) {
                    if (!isFinishing) {
                        try {
                            val fit = igpsportApi.downloadFitFile(token, rec.id, rec.extra)
                            val ms = igpsportApi.parseFitStartTimeMs(fit)
                            if (ms > 0) {
                                cache.setStartTime(DataSource.IGPSPORT.shortName, rec.id, ms)
                                fixed++
                                if (fixed % 10 == 0) appendLog("✅ 已修复 $fixed/${bad.size} 条")
                            }
                        } catch (e: Exception) { Log.w(TAG, "igp 时间修复单条失败: ${e.message}") }
                    }
                    kotlinx.coroutines.delay(300) // 串行节流，避免风控
                }
                appendLog("✅ iGPSPORT 时间修复完成: 成功 $fixed / ${bad.size} 条")
                withContext(Dispatchers.Main) {
                    try { loginFragment.updateStatus() } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.e(TAG, "igp 时间批量修复失败", e)
                appendLog("❌ iGPSPORT 时间修复失败: ${e.message}")
            }
        }
    }

    // v7.6.2: 日志/进度/同步态统一转发给SyncFragment
    internal fun appendLog(message: String) {
        Log.i(TAG, message)
        // v7.6.9: 同步日志持久化，App重开/后台自动同步日志仍可见
        prefs.appendPersistLog(message)
        runOnUiThread { syncFragment.appendLog(message) }
    }

    // v6.7.5: 输出GarminApi调试日志到界面（转发SyncFragment）
    private fun flushGarminDebugLogs() {
        try {
            val logs = GarminApi.debugLogs
            synchronized(logs) {
                if (logs.isNotEmpty()) {
                    for (line in logs) { syncFragment.appendLog(line) }
                    logs.clear()
                }
            }
        } catch (_: Exception) {}
    }

    private fun setSyncing(syncing: Boolean) {
        AutoSyncWorker.syncing = syncing
        runOnUiThread { syncFragment.setSyncing(syncing) }
    }

    // ============ v8.4.2: ActivityCache 接入（对齐开发版：登录页条数/统计依赖此库） ============
    /** 拉取列表后写入缓存库（IO 线程调用） */
    private fun cacheUpsertActivities(source: DataSource, acts: List<ActivityRecord>) {
        if (acts.isEmpty()) return
        try {
            val cache = com.jichi.ob.util.ActivityCache.get(this)
            cache.upsertBatch(source.shortName, acts.map {
                com.jichi.ob.util.ActivityCache.Entry(
                    id = it.id, platform = source.shortName,
                    startTime = if (it.startTimeMs > 0) it.startTimeMs else com.jichi.ob.util.ActivityCache.parseStartTimeMs(it.startTime),
                    type = it.title, title = it.title, distanceKm = it.distance, durationSec = it.duration,
                    filename = "", extra = it.extra ?: ""
                )
            })
        } catch (_: Exception) {}
    }

    /** 下载保存成功后回填文件名 */
    private fun cacheSetFilename(source: DataSource, id: String, name: String) {
        try { com.jichi.ob.util.ActivityCache.get(this).setFilename(source.shortName, id, name) } catch (_: Exception) {}
    }

    /** 平台统计累计 + 平台日志（同步收尾调用） */
    private fun cacheAddStat(source: DataSource, ok: Int, skip: Int, fail: Int, note: String) {
        try {
            val cache = com.jichi.ob.util.ActivityCache.get(this)
            cache.addPlatformStat(source.shortName, ok, skip, fail, System.currentTimeMillis())
            if (note.isNotBlank()) cache.addPlatformLog(source.shortName, "导出", note)
        } catch (_: Exception) {}
    }

    internal fun startSync() {
        // v8.0.0 正式版: 多对一 Outbase 上传 —— 多选数据来源 → 统一上传 Outbase
        val sources = settingsFragment.getSelectedSources()
        if (sources.isEmpty()) { Toast.makeText(this, "请选择至少一个数据来源", Toast.LENGTH_SHORT).show(); return }
        val target = DataSource.OUTBASE
        val count = settingsFragment.getCount()
        val skip = settingsFragment.getSkip()
        // 过滤不可下载的源（百锐腾不支持下载）
        val unavailable = sources.filter { !DownloadSupport.fromDataSource(it).available }
        if (unavailable.isNotEmpty()) {
            Toast.makeText(this, "${unavailable.joinToString { it.displayName }}下载功能不可用，已移除", Toast.LENGTH_SHORT).show()
        }
        val validSources = sources.filter { DownloadSupport.fromDataSource(it).available }
        if (validSources.isEmpty()) return
        if (!prefs.isLoggedIn(target)) { Toast.makeText(this, "请先登录${target.displayName}", Toast.LENGTH_SHORT).show(); return }
        val notLoggedIn = validSources.filter { !prefs.isLoggedIn(it) }
        if (notLoggedIn.isNotEmpty()) {
            Toast.makeText(this, "请先登录${notLoggedIn.joinToString { it.displayName }}", Toast.LENGTH_SHORT).show(); return
        }
        prefs.setLastSources(validSources.map { it.shortName })
        val sourceNames = validSources.joinToString("、") { it.displayName }
        syncFragment.resetStats()
        appendLog("━━━━━━━━━━━━━━━━━━━━━━")
        appendLog("🚀 开始同步: ${sourceNames} → ${target.displayName} (跳过$skip, 同步$count)")
        setSyncing(true)
        flushGarminDebugLogs()
        syncJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Outbase会话预校验
                val obSid = prefs.getOutbaseSessionId()
                if (obSid == null) { appendLog("⚠️ Outbase会话缺失，请先登录Outbase"); setSyncing(false); return@launch }
                appendLog("🔍 校验Outbase会话...")
                if (outbaseApi.warmUp(obSid)) appendLog("✅ Outbase会话有效") else appendLog("⚠️ Outbase会话校验未通过")
                // v8.0.0: 先收集所有来源的活动列表（按源顺序）
                data class ActWrap(val source: DataSource, val act: ActivityRecord)
                val all = mutableListOf<ActWrap>()
                for (source in validSources) {
                    appendLog("📥 [${source.displayName}] 获取活动列表...")
                    try {
                        val acts = fetchActivities(source, skip, count)
                        appendLog("📋 [${source.displayName}] 获取到 ${acts.size} 条活动")
                        cacheUpsertActivities(source, acts)
                        for (a in acts) all.add(ActWrap(source, a))
                    } catch (e: Exception) {
                        appendLog("⚠️ [${source.displayName}] 获取活动失败: ${e.message}")
                    }
                }
                flushGarminDebugLogs()
                if (all.isEmpty()) { appendLog("❌ 未获取到任何活动"); setSyncing(false); return@launch }
                withContext(Dispatchers.Main) { syncFragment.setProgressIndeterminate(false); syncFragment.setProgressMax(all.size); syncFragment.setProgress(0) }
                var success = 0; var skipped = 0; var failed = 0
                val statMap = HashMap<String, IntArray>() // shortName -> [ok, skip, fail]
                for ((idx, wrap) in all.withIndex()) {
                    if (!isActive) break
                    val source = wrap.source
                    val act = wrap.act
                    val syncKey = "${source.shortName}_${act.id}_to_${target.shortName}"
                    val forceRetransmit = prefs.isForceRetransmit()
                    if (!forceRetransmit && prefs.isSynced(syncKey)) {
                        skipped++; appendLog("⏭️ [${idx+1}/${all.size}] 已同步跳过: ${act.title.take(20)}")
                        withContext(Dispatchers.Main) { syncFragment.setProgress(idx + 1) }; continue
                    }
                    // v8.1.0: 日期截止过滤 —— 开启后只同步指定日期之前的数据（不写入同步记忆，关闭后可重新同步）
                    if (prefs.isDateCutoffEnabled()) {
                        val cutoff = prefs.getCutoffDate()
                        if (cutoff.isNotBlank() && !isBeforeCutoffDate(act.startTime, cutoff)) {
                            skipped++; appendLog("⏭️ [${idx+1}/${all.size}] 日期过滤跳过(≥$cutoff): ${act.title.take(20)}")
                            withContext(Dispatchers.Main) { syncFragment.setProgress(idx + 1) }; continue
                        }
                    }
                    appendLog("⬇️ [${idx+1}/${all.size}] 下载: ${act.title.take(20)} id=${act.id} (${"%.1f".format(act.distance)}km) [${source.displayName}]")
                    val fileData = try { downloadActivity(source, target, act) } catch (e: Exception) {
                        appendLog("❌ 下载失败: ${e.message}"); failed++
                        withContext(Dispatchers.Main) { syncFragment.setProgress(idx + 1) }; continue
                    }
                    if (fileData == null || fileData.size < 100) {
                        appendLog("❌ 文件数据无效"); failed++
                        withContext(Dispatchers.Main) { syncFragment.setProgress(idx + 1) }; continue
                    }
                    val ext = if (isFit(fileData)) "fit" else "gpx"
                    val localName = FileNameGenerator.generate(source, act, ext)
                    try {
                        FileOutputStream(File(cacheDir, localName)).use { it.write(fileData) }
                        val savedPath = com.jichi.ob.util.FileSaver.saveToDownloads(this@MainActivity, localName, fileData)
                        appendLog("💾 已存: $savedPath (${fileData.size}字节)")
                        cacheSetFilename(source, act.id, localName)
                    } catch (_: Exception) {}
                    // 上传到 Outbase（固定目标）
                    val t0 = System.currentTimeMillis()
                    appendLog("📤 上传到 ${target.displayName} (${fileData.size}字节)...")
                    val result = uploadEngine.upload(target, obSid, fileData, act, emptyMap())
                    val tCost = System.currentTimeMillis() - t0
                    if (result.success) { success++; prefs.addSyncedId(syncKey); statMap.getOrPut(source.shortName){IntArray(3)}[0]++; appendLog("✅ 上传成功(${tCost}ms): ${result.message}") }
                    else if (result.skipped) { skipped++; prefs.addSyncedId(syncKey); statMap.getOrPut(source.shortName){IntArray(3)}[1]++; appendLog("⏭️ 已存在跳过: ${result.message}") }
                    else { failed++; statMap.getOrPut(source.shortName){IntArray(3)}[2]++; appendLog("❌ 上传失败(${tCost}ms): ${result.message}") }
                    withContext(Dispatchers.Main) { syncFragment.setProgress(idx + 1); settingsFragment.setSyncedCount(prefs.getSyncedCount()) }
                    delay(150) // v6.2.4: 缩短条间间隔，减少多活动同步累计等待
                }
                appendLog("━━━━━━━━━━━━━━━━━━━━━━")
                appendLog("📊 同步完成: 成功$success / 跳过$skipped / 失败$failed")
                for ((p, arr) in statMap) {
                    DataSource.fromShortName(p)?.let { ds ->
                        cacheAddStat(ds, arr[0], arr[1], arr[2], "手动同步→Outbase 成功${arr[0]} 跳过${arr[1]} 失败${arr[2]}")
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "sync error", e); appendLog("❌ 同步异常: ${e.message}") }
            finally { setSyncing(false) }
        }
    }

    internal fun stopSync() { syncJob?.cancel(); appendLog("⏹ 正在停止同步...") }

    // ============ v8.4.0 自动化同步任务（目标固定 Outbase，多来源可多选） ============
    fun openCreateTask() {
        val bottomNav = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNav)
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        supportFragmentManager.beginTransaction().show(createTaskFragment).commit()
        bottomNav?.visibility = android.view.View.GONE
        toolbar?.visibility = android.view.View.GONE
    }

    fun closeCreateTask() {
        val bottomNav = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNav)
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        supportFragmentManager.beginTransaction().hide(createTaskFragment).commit()
        bottomNav?.visibility = android.view.View.VISIBLE
        toolbar?.visibility = android.view.View.VISIBLE
        when (bottomNav?.selectedItemId) {
            R.id.nav_login -> showFragment(loginFragment)
            R.id.nav_settings -> showFragment(settingsFragment)
            R.id.nav_about -> showFragment(aboutFragment)
            else -> showFragment(syncFragment)
        }
        try { syncFragment.refreshTaskState() } catch (_: Exception) {}
    }

    internal fun refreshTaskUi() {
        runOnUiThread {
            try { syncFragment.refreshTaskState() } catch (_: Exception) {}
        }
    }

    /** v8.4.0: 运行同步任务（精简正式版：多来源 → Outbase，增量用同步记忆过滤，无缓存库） */
    internal fun runTask(task: com.jichi.ob.model.SyncTask) {
        if (syncJob?.isActive == true) {
            Toast.makeText(this, "批量同步运行中，请先停止", Toast.LENGTH_SHORT).show(); return
        }
        if (taskActive) return
        val sources = task.sources.mapNotNull { DataSource.fromShortName(it) }.distinct()
        val targets = task.targets.mapNotNull { DataSource.fromShortName(it) }.distinct()
        if (sources.isEmpty() || targets.isEmpty()) {
            Toast.makeText(this, "任务未配置来源或目标", Toast.LENGTH_SHORT).show(); return
        }
        val validSources = sources.filter { DownloadSupport.fromDataSource(it).available }
        if (validSources.isEmpty()) {
            Toast.makeText(this, "所选来源下载功能不可用", Toast.LENGTH_SHORT).show(); return
        }
        val notLoggedSrc = validSources.filter { !prefs.isLoggedIn(it) }
        if (notLoggedSrc.isNotEmpty()) {
            Toast.makeText(this, "请先登录${notLoggedSrc.joinToString { it.displayName }}", Toast.LENGTH_SHORT).show(); return
        }
        val notLoggedTgt = targets.filter { !prefs.isLoggedIn(it) }
        if (notLoggedTgt.isNotEmpty()) {
            Toast.makeText(this, "请先登录${notLoggedTgt.joinToString { it.displayName }}", Toast.LENGTH_SHORT).show(); return
        }
        appendLog("━━━━━━━━━━━━━━━━━━━━━━")
        appendLog("📦 任务开始: ${task.name}（${validSources.size}来源 → ${targets.joinToString { it.displayName }}）")
        taskActive = true
        refreshTaskUi()
        taskJob = lifecycleScope.launch(Dispatchers.IO) {
            var ok = 0; var skipped = 0; var failed = 0
            val statMap = HashMap<String, IntArray>()
            try {
                for (source in validSources) {
                    if (!taskActive) break
                    appendLog("📥 [${source.displayName}] 获取活动列表...")
                    val activities = try { fetchActivities(source, task.skip, task.count) } catch (e: Exception) {
                        appendLog("❌ ${source.displayName} 获取列表失败: ${e.message}")
                        failed++; continue
                    }
                    appendLog("📋 获取到 ${activities.size} 条活动")
                    cacheUpsertActivities(source, activities)
                    flushGarminDebugLogs()
                    var list = activities
                    // 增量：跳过已同步记忆
                    if (task.incremental) {
                        val before = list.size
                        list = list.filter { act -> targets.none { prefs.isSynced("${source.shortName}_${act.id}_to_${it.shortName}") } }
                        if (list.size < before) appendLog("⏭️ 增量模式: 跳过已同步 ${before - list.size} 条，本次同步 ${list.size} 条")
                    }
                    for ((i, act) in list.withIndex()) {
                        if (!taskActive) break
                        val syncKey = "${source.shortName}_${act.id}_to_${targets.first().shortName}"
                        if (!task.force && prefs.isSynced(syncKey)) {
                            skipped++; appendLog("⏭️ [${i+1}/${list.size}] 已同步跳过: ${act.title.take(20)}")
                            continue
                        }
                        appendLog("⬇️ [${i+1}/${list.size}] 下载: ${act.title.take(20)} id=${act.id} (${"%.1f".format(act.distance)}km)")
                        val fileData = try { downloadActivity(source, targets.first(), act) } catch (e: Exception) {
                            appendLog("❌ 下载失败: ${e.message}"); failed++; continue
                        }
                        if (fileData == null || fileData.size < 100) {
                            appendLog("❌ 文件数据无效"); failed++; continue
                        }
                        val ext = if (isFit(fileData)) "fit" else "gpx"
                        val localName = FileNameGenerator.generate(source, act, ext)
                        try {
                            FileOutputStream(File(cacheDir, localName)).use { it.write(fileData) }
                            val savedPath = com.jichi.ob.util.FileSaver.saveToDownloads(this@MainActivity, localName, fileData)
                            appendLog("💾 已存: $savedPath (${fileData.size}字节)")
                            cacheSetFilename(source, act.id, localName)
                        } catch (_: Exception) {}
                        for (target in targets) {
                            if (!taskActive) break
                            val t0 = System.currentTimeMillis()
                            appendLog("📤 上传到 ${target.displayName} (${fileData.size}字节)...")
                            val cred = prefs.getCredential(target)
                            val result = if (cred != null) uploadEngine.upload(target, cred, fileData, act, emptyMap())
                            else com.jichi.ob.api.UploadEngine.UploadResult(success = false, skipped = false, message = "登录态无效")
                            val tCost = System.currentTimeMillis() - t0
                            val k = "${source.shortName}_${act.id}_to_${target.shortName}"
                            if (result.success) { ok++; prefs.addSyncedId(k); statMap.getOrPut(source.shortName){IntArray(3)}[0]++; appendLog("✅ 上传成功(${tCost}ms): ${result.message}") }
                            else if (result.skipped) { skipped++; prefs.addSyncedId(k); statMap.getOrPut(source.shortName){IntArray(3)}[1]++; appendLog("⏭️ 已存在跳过: ${result.message}") }
                            else { failed++; statMap.getOrPut(source.shortName){IntArray(3)}[2]++; appendLog("❌ 上传失败(${tCost}ms): ${result.message}") }
                        }
                        delay(150)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "task sync error", e)
                appendLog("❌ 任务异常: ${e.message}")
            } finally {
                taskActive = false
                prefs.upsertTask(task.copyRun(ok, skipped, failed))
                appendLog("━━━━━━━━━━━━━━━━━━━━━━")
                appendLog("📦 任务完成: 成功$ok / 跳过$skipped / 失败$failed")
                for ((p, arr) in statMap) {
                    DataSource.fromShortName(p)?.let { ds ->
                        cacheAddStat(ds, arr[0], arr[1], arr[2], "任务「${task.name}」成功${arr[0]} 跳过${arr[1]} 失败${arr[2]}")
                    }
                }
                runOnUiThread {
                    refreshTaskUi()
                }
            }
        }
    }

    internal fun stopTask() {
        taskJob?.cancel()
        taskActive = false
        appendLog("⏹ 正在停止任务...")
        refreshTaskUi()
    }

    /**
     * v6.2.4: 百锐腾上传 —— WebView 真实文件选择通道
     *
     * 逆向结论：百锐腾(Bryton Active) 是 Meteor(DDP) 应用，无公开 REST 上传接口；
     * 网页 /activities 页右上角"+"→ 上传弹窗 input[type=file] → 真实文件选择可成功落库
     * （已在浏览器实测：上传后 userActivities collection 新增记录）。故用 WebView +
     * onShowFileChooser 把本地FIT/GPX喂给页面，等价用户手动上传。
     */
    private suspend fun uploadToBrytonViaWebView(fitPath: String): com.jichi.ob.api.UploadEngine.UploadResult =
        suspendCancellableCoroutine { cont ->
            val token = prefs.getBrytonToken() ?: ""
            val userId = prefs.getBrytonUserId() ?: ""
            val uploader = BrytonWebUploader(this, token, userId)
            uploader.upload(fitPath) { ok, msg ->
                uploader.destroy()
                if (ok) {
                    cont.resume(com.jichi.ob.api.UploadEngine.UploadResult(true, message = msg))
                } else {
                    cont.resume(com.jichi.ob.api.UploadEngine.UploadResult(false, message = msg))
                }
            }
            cont.invokeOnCancellation { uploader.destroy() }
        }

    /** v8.1.0: 各平台 startTime 格式不一，统一提取 yyyy-MM-dd 判断是否早于截止日期 */
    private fun isBeforeCutoffDate(startTime: String, cutoff: String): Boolean {
        val d = extractDatePart(startTime) ?: return false
        return d < cutoff
    }

    private fun extractDatePart(raw: String): String? {
        if (raw.isBlank()) return null
        var v = raw.trim()
        // 纯数字时间戳（毫秒13位/秒10位）
        if (v.all { it.isDigit() }) {
            return try {
                val ms = if (v.length >= 13) v.toLong() else v.toLong() * 1000L
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(ms))
            } catch (_: Exception) { null }
        }
        // yyyy-MM-dd / yyyy/MM/dd / yyyy-MM-ddTHH:mm:ss / ISO8601
        v = v.replace("/", "-").replace("T", " ")
        val d = v.take(10)
        return d.takeIf { it.length == 10 && it[4] == '-' && it[7] == '-' }
    }

    /**
     * 清除上传记忆：删除全部已同步记录ID，下次同步将重新全量上传（便于频繁测试）
     */
    internal fun clearSyncMemory() {
        try {
            val before = prefs.getSyncedCount()
            prefs.clearSyncedIds()
            settingsFragment.setSyncedCount(0)
            appendLog("🗑 已清除上传记忆($before 条) → 下次同步将重新全量上传")
            Toast.makeText(this, "上传记忆已清除($before 条)", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "clearSyncMemory error", e)
            Toast.makeText(this, "清除失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // v7.5.5: 后台自动同步改用WorkManager（系统调度，跨开机，最低15分钟）
    internal fun startAutoSync() {
        val intervalSec = prefs.getAutoInterval().coerceAtLeast(15 * 60)
        // v7.5.7: WorkManager调用包try-catch，防止任何异常导致闪退
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<AutoSyncWorker>(intervalSec.toLong(), TimeUnit.SECONDS)
                .setConstraints(constraints)
                .addTag(AutoSyncWorker.WORK_TAG)
                .build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                AutoSyncWorker.WORK_TAG,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            appendLog("⏰ 后台自动同步已开启，间隔 ${intervalSec / 60}分钟（WorkManager调度，跨开机）")
            // v7.6.9: 检测电池优化白名单，未加入则提示（后台可能被系统限制，导致自动同步不执行）
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    appendLog("⚠️ 未加入电池优化白名单，后台可能被系统限制，建议在同步页点击「后台常驻指引」申请")
                }
            } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.e(TAG, "startAutoSync failed", e)
            appendLog("❌ 自动同步开启失败: ${e.message?.take(50)}")
        }
    }

    internal fun stopAutoSync() {
        WorkManager.getInstance(this).cancelAllWorkByTag(AutoSyncWorker.WORK_TAG)
        // v7.5.6: 关闭自动同步时立即取消状态栏通知（前台+摘要）
        AutoSyncWorker.cancelAllNotifications(this)
        appendLog("⏰ 后台自动同步已关闭")
    }

    private suspend fun fetchActivities(source: DataSource, skip: Int, limit: Int): List<ActivityRecord> {
        var cred = prefs.getCredential(source) ?: return emptyList()
        // v6.5.3: 佳明token过期自动刷新
        if (source == DataSource.GARMIN_COM || source == DataSource.GARMIN_CN) {
            val newCred = garminApi.ensureValidToken(source, cred)
            if (newCred != cred) {
                cred = newCred
                if (source == DataSource.GARMIN_COM) prefs.saveGarminComToken(cred)
                else prefs.saveGarminCnToken(cred)
            }
        }
        // v7.6.9: 迈金源token过期(401)自动刷新后重试一次
        if (source == DataSource.MAGENE) {
            try {
                return mageneApi.getActivities(cred, skip, limit)
            } catch (e: Exception) {
                if (e.message?.contains("过期") == true || e.message?.contains("401") == true) {
                    val refresh = prefs.getMageneRefreshToken()
                    if (!refresh.isNullOrEmpty()) {
                        appendLog("🔄 迈金登录已过期，自动刷新token后重试...")
                        val newTok = mageneApi.refreshToken(refresh)
                        if (newTok != null) {
                            prefs.saveMageneToken(newTok)
                            return mageneApi.getActivities(newTok, skip, limit)
                        }
                    }
                }
                throw e
            }
        }
        return when (source) {
            DataSource.IGPSPORT -> igpsportApi.getActivities(cred, skip, limit)
            DataSource.XINGZHE -> xingzheApi.getActivities(cred, skip, limit)
            DataSource.MAGENE -> mageneApi.getActivities(cred, skip, limit)
            DataSource.BLACKBIRD -> blackbirdApi.getActivities(cred, skip, limit)
            DataSource.BRYTON -> {
                // v6.2.4: 百锐腾无REST列表，走WebView读 Meteor userActivities collection
                val tok = prefs.getBrytonToken() ?: return emptyList()
                val uid = prefs.getBrytonUserId() ?: return emptyList()
                BrytonWebApi(this, tok, uid).getActivities(skip, limit)
            }
            DataSource.GARMIN_COM -> garminApi.getActivities(source, cred, skip, limit)
            DataSource.GARMIN_CN -> garminApi.getActivities(source, cred, skip, limit)
            DataSource.COROS_CN -> corosApi.getActivities(cred, skip, limit)
            DataSource.COROS_INT -> corosApi.getActivities(cred, skip, limit)
            DataSource.WAHOO -> {
                // v7.1.3: Wahoo优先用内置生产凭证刷新token，其次用用户配置的凭证
                var token = cred
                val refresh = prefs.getWahooRefresh()
                val clientId = if (com.jichi.ob.api.WahooApi.isBuiltinConfigured()) com.jichi.ob.api.WahooApi.BUILTIN_CLIENT_ID else prefs.getWahooClientId()
                val clientSecret = if (com.jichi.ob.api.WahooApi.isBuiltinConfigured()) com.jichi.ob.api.WahooApi.BUILTIN_CLIENT_SECRET else prefs.getWahooClientSecret()
                if (refresh != null && !clientId.isNullOrEmpty() && !clientSecret.isNullOrEmpty()) {
                    val fresh = wahooApi.refreshToken(refresh, clientId, clientSecret)
                    if (fresh != null) {
                        prefs.saveWahooToken(fresh.first); prefs.saveWahooRefresh(fresh.second)
                        token = fresh.first
                    }
                }
                wahooApi.getActivities(token, skip, limit)
            }
            DataSource.MYWHOOSH -> {
                // v8.2.0: MyWhoosh 无 refresh 端点，401 时抛异常提示重新登录
                val whooshId = prefs.getMywhooshWhooshId() ?: ""
                mywhooshApi.getActivities(cred, whooshId, skip, limit)
            }
            DataSource.ZWIFT -> {
                // v8.2.0: Zwift 401 时用 refresh_token 刷新后重试
                getZwiftActivitiesWithRefresh(cred, prefs.getZwiftPlayerId(), prefs.getZwiftRefreshToken(), skip, limit)
            }
            DataSource.KEEP -> keepApi.getActivities(cred, skip, limit)
            DataSource.CODOON -> codoonApi.getActivities(cred, prefs.getCodoonUserId() ?: "", skip, limit)
            DataSource.ZEPP -> zeppApi.getActivities(cred, prefs.getZeppUserId() ?: "", skip, limit)
            DataSource.KOMOT -> {
                val email = prefs.getKomootAccount()
                if (email.isNullOrEmpty()) emptyList() else komootApi.getActivities(email, cred, skip, limit)
            }
            DataSource.SUUNTO -> suuntoApi.getActivities(cred, suuntoSubscriptionKey() ?: "", skip, limit)
            else -> emptyList()
        }
    }

    /** v8.2.0: Zwift 源列表——401/过期时用 refresh_token 刷新后重试一次 */
    private suspend fun getZwiftActivitiesWithRefresh(
        token: String, playerId: String?, refresh: String?, skip: Int, limit: Int
    ): List<ActivityRecord> {
        try {
            return zwiftApi.getActivities(token, playerId, skip, limit)
        } catch (e: Exception) {
            if (e.message?.contains("401") == true && !refresh.isNullOrEmpty()) {
                val fresh = zwiftApi.refreshToken(refresh)
                if (fresh != null) {
                    prefs.saveZwiftToken(fresh.first)
                    prefs.saveZwiftRefreshToken(fresh.second)
                    appendLog("🔄 Zwift token已自动刷新")
                    return zwiftApi.getActivities(fresh.first, prefs.getZwiftPlayerId(), skip, limit)
                }
            }
            throw e
        }
    }

    private suspend fun downloadActivity(source: DataSource, target: DataSource = DataSource.OUTBASE, record: ActivityRecord): ByteArray? {
        val cred = prefs.getCredential(source) ?: return null
        var data = when (source) {
            DataSource.IGPSPORT -> igpsportApi.downloadFitFile(cred, record.id, record.extra)
            DataSource.XINGZHE -> {
                // v7.5.2: 行者→iGPSPORT时GPX优先，可能解决8小时时差
                val preferGpx = (target == DataSource.IGPSPORT)
                val (bytes, kind) = xingzheApi.downloadGpxOrFit(cred, record.id, preferGpx)
                if (preferGpx) appendLog("📄 行者下载格式: ${kind.displayName}")
                bytes
            }
            DataSource.MAGENE -> {
                try {
                    val result = mageneApi.downloadFit(cred, record.id)
                    // 迈金坐标转换: 仅对fit_content接口下载的GCJ-02坐标FIT执行转换
                    // 七牛云直链(durl)下载的已是WGS84，不转换
                    if (prefs.isGcj02Convert() && result.fromFitContent && isFit(result.data)) {
                        appendLog("🔄 迈金fit_content来源(GCJ-02)，执行WGS84转换...")
                        convertFitCoordinates(result.data)
                    } else {
                        if (prefs.isGcj02Convert() && !result.fromFitContent) {
                            appendLog("ℹ️ 迈金七牛云直链(WGS84)，无需转换")
                        }
                        result.data
                    }
                } catch (e: MageneApi.NoFileException) { null }
            }
            DataSource.BLACKBIRD -> {
                val bbData = blackbirdApi.downloadActivity(cred, record.id)  // v6.3.16: 黑鸟固定GCJ-02→WGS84，内部默认convertCoord=true
                // v6.3.15调试：输出黑鸟原始track字段（带位置索引），确定真实字段顺序，排查字段错位
                val rawSample = com.jichi.ob.api.BlackbirdApi.lastRawTrackSample
                if (rawSample.isNotEmpty()) {
                    appendLog("🔬 黑鸟原始字段(startTime=${com.jichi.ob.api.BlackbirdApi.lastStartTime}):")
                    rawSample.split("\n").forEach { appendLog(it.take(400)) }
                }
                // 黑鸟坐标已是WGS84，不转换
                if (false && bbData.size >= 14 && bbData[8] == '.'.code.toByte() && bbData[9] == 'F'.code.toByte()) {
                    appendLog("🔄 黑鸟FIT坐标(GCJ-02)，执行WGS84转换...")
                    val fixed = convertFitCoordinates(bbData)
                    if (fixed != null && fixed.isNotEmpty()) fixed else bbData
                } else bbData
            }
            DataSource.BRYTON -> {
                // v6.2.4: 百锐腾官方未开放FIT/GPX下载接口（网页仅展示summary，CDP实测全部下载路径返回SPA HTML），
                // 从百锐腾下载原始轨迹不可行；仅支持将其他平台数据上传到百锐腾
                null
            }
            DataSource.GARMIN_COM -> garminApi.downloadFit(source, cred, record.id)
            DataSource.GARMIN_CN -> garminApi.downloadFit(source, cred, record.id)
            DataSource.COROS_CN -> corosApi.downloadFit(cred, record.id, record.extra)
            DataSource.COROS_INT -> corosApi.downloadFit(cred, record.id, record.extra)
            DataSource.WAHOO -> {
                // v7.1.3: Wahoo下载优先用内置生产凭证刷新token，其次用用户配置的凭证
                var token = cred
                val refresh = prefs.getWahooRefresh()
                val clientId = if (com.jichi.ob.api.WahooApi.isBuiltinConfigured()) com.jichi.ob.api.WahooApi.BUILTIN_CLIENT_ID else prefs.getWahooClientId()
                val clientSecret = if (com.jichi.ob.api.WahooApi.isBuiltinConfigured()) com.jichi.ob.api.WahooApi.BUILTIN_CLIENT_SECRET else prefs.getWahooClientSecret()
                if (refresh != null && !clientId.isNullOrEmpty() && !clientSecret.isNullOrEmpty()) {
                    val fresh = wahooApi.refreshToken(refresh, clientId, clientSecret)
                    if (fresh != null) {
                        prefs.saveWahooToken(fresh.first); prefs.saveWahooRefresh(fresh.second)
                        token = fresh.first
                    }
                }
                wahooApi.downloadFit(token, record.id)
            }
            DataSource.MYWHOOSH -> {
                // v8.2.0: MyWhoosh 下载（extra=activityFileId）
                val whooshId = prefs.getMywhooshWhooshId() ?: ""
                mywhooshApi.downloadFit(cred, whooshId, record.extra ?: "")
            }
            DataSource.ZWIFT -> {
                // v8.2.0: Zwift S3 直链下载（extra=bucket|key），S3 无需 token
                zwiftApi.downloadFit(record.extra ?: "")
            }
            DataSource.KEEP -> {
                // v8.2.1: Keep 下载轨迹→GPX（extra=run_id），上传引擎自动转 FIT
                keepApi.downloadGpx(cred, record.extra ?: record.id)
            }
            DataSource.CODOON -> {
                // v8.3.8: 咕咚下载轨迹→GPX（extra=route_id）
                codoonApi.downloadGpx(cred, record.extra ?: record.id)
            }
            DataSource.ZEPP -> {
                // v8.3.8: Zepp 下载轨迹→GPX（id=trackid，extra=source）
                zeppApi.downloadGpx(cred, record.id, record.extra ?: "")
            }
            DataSource.KOMOT -> {
                // v8.3.8: Komoot 下载轨迹→GPX（extra=tour id），国际平台 WGS-84 无需坐标转换
                val email = prefs.getKomootAccount()
                if (email.isNullOrEmpty()) null else komootApi.downloadGpx(email, cred, record.extra ?: record.id)
            }
            DataSource.SUUNTO -> {
                // v8.3.8: Suunto 下载轨迹→FIT（国际平台 WGS-84，无需坐标转换）
                suuntoApi.download(cred, suuntoSubscriptionKey() ?: "", record.extra ?: record.id, gpx = false)
            }
            else -> null
        }
        return data
    }

    private fun isFit(bytes: ByteArray): Boolean = bytes.size >= 14 && bytes[8] == '.'.code.toByte() && bytes[9] == 'F'.code.toByte()

    /** v8.1.0: 迁移旧存储目录「鸡翅幸哲迈进OB」→「迈向Ob」，不丢失已下载文件 */
    private fun migrateLegacyStorageDir() {
        try {
            val legacy = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "鸡翅幸哲迈进OB")
            if (!legacy.exists()) return
            if (!SAVE_DIR.exists()) SAVE_DIR.mkdirs()
            val files = legacy.listFiles() ?: return
            var moved = 0
            for (f in files) {
                if (!f.isFile) continue
                val dest = File(SAVE_DIR, f.name)
                try {
                    if (f.renameTo(dest)) {
                        moved++
                    } else if (!dest.exists()) {
                        f.copyTo(dest, overwrite = false)
                        f.delete()
                        moved++
                    }
                } catch (_: Exception) {}
            }
            if (moved > 0) {
                appendLog("📂 已迁移 $moved 个文件到新存储目录")
            }
            // 旧目录清空后尝试删除空壳
            try { if (legacy.listFiles()?.isEmpty() == true) legacy.delete() } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    /** 申请通知权限（Android 13+）*/
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /** 初始化坐标转换WebView (加载magene_fix.js) */
    @SuppressLint("SetJavaScriptEnabled")
    // v7.6.2: 四页面Fragment初始化 + 底部导航切换
    private fun initFragments() {
        loginFragment = com.jichi.ob.ui.LoginFragment()
        settingsFragment = com.jichi.ob.ui.SyncSettingsFragment()
        syncFragment = com.jichi.ob.ui.SyncFragment()
        aboutFragment = com.jichi.ob.ui.AboutFragment()
        mergeFragment = com.jichi.ob.ui.MergeFragment()
        createTaskFragment = com.jichi.ob.ui.CreateTaskFragment()
        supportFragmentManager.beginTransaction()
            .add(R.id.fragmentContainer, loginFragment, "login")
            .add(R.id.fragmentContainer, settingsFragment, "settings").hide(settingsFragment)
            .add(R.id.fragmentContainer, syncFragment, "sync").hide(syncFragment)
            .add(R.id.fragmentContainer, aboutFragment, "about").hide(aboutFragment)
            .add(R.id.fragmentContainer, mergeFragment, "merge").hide(mergeFragment)
            .add(R.id.fragmentContainer, createTaskFragment, "createtask").hide(createTaskFragment)
            .commit()
        val bottomNav = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNav)
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_login -> showFragment(loginFragment)
                R.id.nav_settings -> showFragment(settingsFragment)
                R.id.nav_sync -> showFragment(syncFragment)
                R.id.nav_about -> showFragment(aboutFragment)
            }
            true
        }
        bottomNav.selectedItemId = R.id.nav_login
    }

    private fun showFragment(target: androidx.fragment.app.Fragment) {
        val others = listOf(loginFragment, settingsFragment, syncFragment, aboutFragment, mergeFragment, createTaskFragment).filter { it !== target }
        val tr = supportFragmentManager.beginTransaction()
        for (o in others) tr.hide(o)
        tr.show(target).commit()
        // 注：登录/注销状态刷新已由各Fragment的onResume自行处理（v7.6.7）
    }

    private fun initFixWebView() {
        fixWebView = android.webkit.WebView(this)
        fixWebView.settings.javaScriptEnabled = true
        fixWebView.settings.allowFileAccess = true
        fixWebView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                fixJsReady = true
                appendLog("✅ 迈金坐标转换引擎已就绪")
            }
        }
        fixWebView.loadUrl("file:///android_asset/magene_fix.html")
    }

    /** 迈金FIT坐标转换 GCJ-02→WGS-84 (通过WebView执行JS) */
    private suspend fun convertFitCoordinates(fitData: ByteArray): ByteArray? = withContext(Dispatchers.Main) {
        if (!fixJsReady) {
            appendLog("⚠️ 坐标转换引擎未就绪，跳过转换")
            return@withContext fitData
        }
        try {
            val base64 = android.util.Base64.encodeToString(fitData, android.util.Base64.NO_WRAP)
            val deferred = CompletableDeferred<String?>()
            fixWebView.evaluateJavascript("__fixFit('$base64')") { result ->
                deferred.complete(result)
            }
            val result = deferred.await()
            if (result == null || result == "null" || result.isBlank()) {
                appendLog("⚠️ 坐标转换返回空结果")
                return@withContext fitData
            }
            // evaluateJavascript 返回的是JSON编码字符串，需要解码一层
            // 若JS返回对象 => result 直接是 {"ok":...}
            // 若JS返回JSON字符串 => result 是 "{\"ok\":...}"（带转义），需先解析字符串再解析JSON
            val json = try {
                val first = org.json.JSONTokener(result).nextValue()
                when (first) {
                    is org.json.JSONObject -> first
                    is String -> org.json.JSONObject(first)
                    else -> { appendLog("⚠️ 坐标转换返回格式异常"); return@withContext fitData }
                }
            } catch (e: Exception) {
                appendLog("❌ 坐标转换响应解析失败: ${e.message}")
                return@withContext fitData
            }
            if (json.optBoolean("ok")) {
                val fixedBase64 = json.optString("base64")
                val summary = json.optJSONObject("summary")
                val changed = summary?.optInt("changedRecords", 0) ?: 0
                val avgShift = summary?.optDouble("averageShiftM", 0.0) ?: 0.0
                appendLog("🔄 坐标转换完成: $changed 个坐标点修正，平均偏移 ${avgShift}m")
                if (fixedBase64.isEmpty()) { appendLog("⚠️ 坐标转换结果为空"); return@withContext fitData }
                return@withContext android.util.Base64.decode(fixedBase64, android.util.Base64.NO_WRAP)
            } else {
                appendLog("❌ 坐标转换失败: ${json.optString("error")}")
                return@withContext fitData
            }
        } catch (e: Exception) {
            appendLog("❌ 坐标转换异常: ${e.message}")
            return@withContext fitData
        }
    }

    /** 测试下载: 下载1条记录保存到本地，验证下载功能 */
    internal fun testDownload() {
        // v8.0.0 正式版: 多对一 —— 测试所有勾选的来源各下载1条
        val sources = settingsFragment.getSelectedSources()
        if (sources.isEmpty()) {
            Toast.makeText(this, "请先在设置页勾选数据来源", Toast.LENGTH_SHORT).show()
            return
        }
        val notLoggedIn = sources.filter { !prefs.isLoggedIn(it) }
        if (notLoggedIn.isNotEmpty()) {
            Toast.makeText(this, "请先登录${notLoggedIn.joinToString { it.displayName }}", Toast.LENGTH_SHORT).show()
            return
        }
        appendLog("🧪 测试下载: ${sources.joinToString("、") { it.displayName }} 各获取1条记录...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                for (source in sources) {
                    try {
                        val activities = fetchActivities(source, 0, 1)
                        if (activities.isEmpty()) { appendLog("❌ [${source.displayName}] 未获取到活动"); continue }
                        val act = activities[0]
                        appendLog("⬇️ [${source.displayName}] 下载: ${act.title.take(20)} (${"%.1f".format(act.distance)}km)")
                        val data = downloadActivity(source, record = act)
                        if (data == null || data.size < 100) { appendLog("❌ [${source.displayName}] 下载失败: 数据无效"); continue }
                        val ext = if (isFit(data)) "fit" else "gpx"
                        val tName = "test_" + FileNameGenerator.generate(source, act, ext)
                        val tPath = com.jichi.ob.util.FileSaver.saveToDownloads(this@MainActivity, tName, data)
                        appendLog("✅ [${source.displayName}] 测试下载成功! 文件: $tName (${data.size} bytes)")
                        appendLog("📂 保存路径: $tPath（系统「文件」App→下载→迈向Ob）")
                    } catch (e: Exception) {
                        appendLog("❌ [${source.displayName}] 测试下载异常: ${e.message}")
                    }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "测试下载完成，详见日志", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                appendLog("❌ 测试下载异常: ${e.message}")
            }
        }
    }

    /** 电源保护指引: 显示各品牌后台常驻和电池优化设置 */
    internal fun showPowerGuide() {
        val guide = """
            🔋 后台常驻 & 电池保护设置指引

            【通用设置】
            1. 多任务界面锁定本APP(下拉锁定)
            2. 设置 → 电池 → 关闭电池优化/加入白名单
            3. 设置 → 应用 → 本APP → 允许后台运行/自启动

            【华为】
            手机管家 → 应用启动管理 → 找到本APP → 关闭自动管理 → 允许自启动/后台活动
            设置 → 电池 → 更多电池设置 → 休眠时始终保持网络连接

            【小米/红米】
            设置 → 应用设置 → 授权管理 → 自启动管理 → 允许本APP
            设置 → 电池 → 应用智能省电 → 本APP → 无限制
            多任务页 → 长按本APP → 锁定

            【OPPO/一加/realme】
            设置 → 电池 → 应用耗电管理 → 本APP → 允许后台活动/自启动
            设置 → 应用管理 → 本APP → 电池 → 不优化

            【vivo/iQOO】
            i管家 → 应用管理 → 自启动 → 允许本APP
            设置 → 电池 → 后台耗电管理 → 本APP → 允许后台高耗电
            多任务页 → 下拉锁定

            【三星】
            设置 → 应用程序 → 本APP → 电池 → 不受限制
            智能管理器 → 自动运行应用程序 → 开启本APP

            完成以上设置后，后台自动同步才能稳定运行。
        """.trimIndent()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🔋 后台常驻指引")
            .setMessage(guide)
            .setPositiveButton("我知道了", null)
            .setNeutralButton("申请电池优化白名单") { _, _ ->
                try {
                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(this, "请手动在设置中关闭电池优化", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    /** 显示后台同步通知 */
    private fun showAutoSyncNotification(message: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val channelId = "jichi_ob_autosync"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(channelId, "自动同步", android.app.NotificationManager.IMPORTANCE_LOW)
                nm.createNotificationChannel(channel)
            }
            val notif = androidx.core.app.NotificationCompat.Builder(this, channelId)
                .setContentTitle("迈向Ob 自动同步")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_menu_rotate)
                .setOngoing(true)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
                .build()
            nm.notify(1001, notif)
        } catch (e: Exception) {
            Log.e("MainActivity", "Notification error", e)
        }
    }

    private fun cancelAutoSyncNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(1001)
        } catch (_: Exception) {}
    }

    override fun onDestroy() { syncJob?.cancel(); autoSyncJob?.cancel(); cancelAutoSyncNotification(); super.onDestroy() }
}
