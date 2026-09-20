package com.jichi.ob.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.jichi.ob.MainActivity
import com.jichi.ob.R
import com.jichi.ob.model.DataSource
import com.jichi.ob.util.PrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * v8.4.0: 登录页对齐开发版 8.3.3 UI
 * - 顶部品牌横幅 + Outbase 固定卡（始终显示、不可收起）+ 一键登录检测
 * - 已登录平台：状态卡片（每行2个，点击 → 详情弹窗）
 * - 未登录平台：折叠区（默认收起）
 * - 全部卡片程序化构建；正式版裁剪统计/日志/记录中心依赖（不引入 ActivityCache）
 */
class LoginFragment : Fragment() {

    /** 登录页展示平台（Outbase 单独固定卡，不进容器） */
    private val LOGIN_PLATFORMS = listOf(
        DataSource.IGPSPORT, DataSource.XINGZHE, DataSource.MAGENE, DataSource.BLACKBIRD,
        DataSource.GIANT, DataSource.BRYTON, DataSource.GARMIN_COM, DataSource.GARMIN_CN,
        DataSource.COROS_CN, DataSource.COROS_INT, DataSource.WAHOO, DataSource.MYWHOOSH,
        DataSource.ZWIFT, DataSource.KEEP, DataSource.CODOON, DataSource.ZEPP,
        DataSource.KOMOT, DataSource.SUUNTO, DataSource.TWO_BULU, DataSource.JOYRUN
    )

    private lateinit var prefs: PrefsManager
    private lateinit var containerLogged: LinearLayout
    private lateinit var containerLoggedOut: LinearLayout
    private lateinit var tvGroupLogged: TextView
    private lateinit var tvFoldToggle: TextView
    private lateinit var tvFoldArrow: TextView
    private lateinit var llFoldToggle: LinearLayout
    /** v8.4.2: 平台统计行 + 右上角记录条数徽标（对齐开发版，数据来自 ActivityCache） */
    private val statViews = mutableMapOf<DataSource, TextView>()
    private val countViews = mutableMapOf<DataSource, TextView>()
    private var loggedOutExpanded = false
    private var checking = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_login, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = PrefsManager(requireContext())
        containerLogged = view.findViewById(R.id.containerLogged)
        containerLoggedOut = view.findViewById(R.id.containerLoggedOut)
        tvGroupLogged = view.findViewById(R.id.tvGroupLogged)
        tvFoldToggle = view.findViewById(R.id.tvFoldToggle)
        tvFoldArrow = view.findViewById(R.id.tvFoldArrow)
        llFoldToggle = view.findViewById(R.id.llFoldToggle)
        // v8.4.1: 修复未登录平台折叠区无法展开 —— 点击切换显示/隐藏
        llFoldToggle.setOnClickListener {
            val show = containerLoggedOut.visibility != View.VISIBLE
            containerLoggedOut.visibility = if (show) View.VISIBLE else View.GONE
            tvFoldArrow.text = if (show) "▴ 点击收起" else "▾ 点击展开"
        }

        // Outbase 固定卡（始终显示，不收起）
        val cardOutbase = view.findViewById<MaterialCardView>(R.id.cardOutbaseFixed)
        val tvOutbaseStatus = view.findViewById<TextView>(R.id.tvOutbaseStatus)
        val btnOutbaseLogin = view.findViewById<TextView>(R.id.btnOutbaseLogin)
        cardOutbase.setOnClickListener { openPlatformLogin(DataSource.OUTBASE) }
        btnOutbaseLogin.setOnClickListener { openPlatformLogin(DataSource.OUTBASE) }
        fun refreshOutbase() {
            val logged = prefs.isLoggedIn(DataSource.OUTBASE)
            val username = prefs.getUsername(DataSource.OUTBASE)
            tvOutbaseStatus.text = when {
                logged && !username.isNullOrBlank() -> "✅ 已登录 · $username"
                logged -> "✅ 已登录"
                else -> "未登录"
            }
            tvOutbaseStatus.setTextColor(requireContext().getColor(if (logged) R.color.green else R.color.text_secondary))
            btnOutbaseLogin.text = if (logged) "重新登录" else "登录Outbase"
        }
        refreshOutbase()

        // 一键检测
        val btnCheckAll = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCheckAll)
        val tvCheckStatus = view.findViewById<TextView>(R.id.tvCheckStatus)
        val tvCheckSummary = view.findViewById<TextView>(R.id.tvCheckSummary)
        btnCheckAll.setOnClickListener {
            if (checking) return@setOnClickListener
            val act = activity as? MainActivity ?: return@setOnClickListener
            checking = true
            btnCheckAll.isEnabled = false
            tvCheckStatus.text = "正在检测所有已登录平台..."
            tvCheckSummary.visibility = View.GONE
            act.checkAllLogins { valid, refreshed, invalid ->
                checking = false
                btnCheckAll.isEnabled = true
                tvCheckStatus.text = "检测完成"
                tvCheckSummary.text = "✅ ${valid} 有效 · 🔄 ${refreshed} 刷新 · ❌ ${invalid} 失效"
                tvCheckSummary.setTextColor(requireContext().getColor(
                    if (invalid > 0) R.color.log_error else R.color.green
                ))
                tvCheckSummary.visibility = View.VISIBLE
                updateStatus()
                refreshOutbase()
            }
        }
        val switchAutoCheck = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchAutoCheck)
        switchAutoCheck.isChecked = prefs.isAutoCheckLogin()
        switchAutoCheck.setOnCheckedChangeListener { _, checked -> prefs.setAutoCheckLogin(checked) }

        lifecycle.addObserver(object : androidx.lifecycle.LifecycleEventObserver {
            override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: androidx.lifecycle.Lifecycle.Event) {
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    updateStatus()
                    refreshOutbase()
                }
            }
        })
        updateStatus()
    }

    /** v8.4.0: 刷新已登录/未登录两组卡片（MainActivity 登录返回后调用） */
    fun updateStatus() {
        if (!::prefs.isInitialized) return
        try {
            statViews.clear()
            countViews.clear()
            buildLogged()
            buildLoggedOut()
            updateFoldToggle()
            refreshStats()
        } catch (_: Exception) {}
    }

    private fun updateFoldToggle() {
        val loggedOut = LOGIN_PLATFORMS.filter { !prefs.isLoggedIn(it) }
        tvFoldToggle.text = "未登录平台（${loggedOut.size}）"
        llFoldToggle.visibility = if (loggedOut.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun buildLogged() {
        containerLogged.removeAllViews()
        val logged = LOGIN_PLATFORMS.filter { prefs.isLoggedIn(it) }
        tvGroupLogged.text = "已登录平台（${logged.size}）"
        if (logged.isEmpty()) {
            containerLogged.addView(emptyHint("暂无已登录平台，展开下方「未登录平台」登录"))
            return
        }
        for (i in logged.indices step 2) {
            val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(makeCard(logged[i], true), rowChildLp(weight = 1f, marginEnd = if (i + 1 < logged.size) 4 else 0))
            if (i + 1 < logged.size) row.addView(makeCard(logged[i + 1], true), rowChildLp(weight = 1f, marginEnd = 0))
            containerLogged.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun buildLoggedOut() {
        containerLoggedOut.removeAllViews()
        val loggedOut = LOGIN_PLATFORMS.filter { !prefs.isLoggedIn(it) }
        if (loggedOut.isEmpty()) return
        for (i in loggedOut.indices step 2) {
            val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(makeCard(loggedOut[i], false), rowChildLp(weight = 1f, marginEnd = if (i + 1 < loggedOut.size) 4 else 0))
            if (i + 1 < loggedOut.size) row.addView(makeCard(loggedOut[i + 1], false), rowChildLp(weight = 1f, marginEnd = 0))
            containerLoggedOut.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun rowChildLp(weight: Float, marginEnd: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply {
            this.marginEnd = dp(marginEnd.toFloat()).toInt()
            topMargin = dp(2f).toInt(); bottomMargin = dp(2f).toInt()
        }

    private fun emptyHint(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        textSize = 12f
        setTextColor(requireContext().getColor(R.color.text_secondary))
        setPadding(dp(8f).toInt(), dp(10f).toInt(), dp(8f).toInt(), dp(10f).toInt())
    }

    /** v8.4.2: 异步刷新已登录平台的累计统计 + 缓存条数（IO 查询，主线程更新） */
    private fun refreshStats() {
        val act = activity as? MainActivity ?: return
        val logged = LOGIN_PLATFORMS.filter { prefs.isLoggedIn(it) }
        if (logged.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val cache = try { com.jichi.ob.util.ActivityCache.get(requireContext()) } catch (_: Exception) { null }
            val stats = logged.associateWith { ds ->
                try { cache?.getPlatformStat(ds.shortName) } catch (_: Exception) { null }
            }
            val counts = logged.associateWith { ds ->
                try { cache?.count(ds.shortName) ?: -1 } catch (_: Exception) { -1 }
            }
            act.runOnUiThread {
                stats.forEach { (ds, st) ->
                    if (st != null) statViews[ds]?.text = "↑${st.ok} ↓${st.skip} ✗${st.fail}"
                }
                counts.forEach { (ds, c) ->
                    if (c >= 0) countViews[ds]?.apply {
                        text = "$c 条"
                        visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    /** 平台卡片（登录/未登录通用；点击 → 详情弹窗） */
    private fun makeCard(ds: DataSource, logged: Boolean): MaterialCardView {
        val ctx = requireContext()
        val isInvalid = logged && invalidPlatforms().contains(ds)
        val card = MaterialCardView(ctx).apply {
            radius = dp(14f)
            elevation = 0f
            strokeWidth = dp(1f).toInt()
            setStrokeColor(if (isInvalid) android.graphics.Color.parseColor("#F5C2C4") else android.graphics.Color.parseColor("#E3EEFA"))
            setCardBackgroundColor(if (isInvalid) android.graphics.Color.parseColor("#FFF8F8") else android.graphics.Color.WHITE)
            setOnClickListener { showDetail(ds) }
        }
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8f).toInt(), dp(7f).toInt(), dp(8f).toInt(), dp(7f).toInt())
        }
        // 第一行：状态点 + 平台名
        val row1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        val dot = View(ctx).apply {
            setBackgroundResource(dotRes(ds))
            layoutParams = LinearLayout.LayoutParams(dp(8f).toInt(), dp(8f).toInt())
        }
        row1.addView(dot)
        row1.addView(TextView(ctx).apply {
            text = ds.displayName
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ctx.getColor(R.color.text_primary))
            maxLines = 1
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6f).toInt() })
        // v8.4.2: 右上角记录条数徽标（已登录且缓存>0 时显示；异步刷新）
        if (logged) {
            val tvCount = TextView(ctx).apply {
                textSize = 9.5f
                setTextColor(android.graphics.Color.WHITE)
                setPadding(dp(6f).toInt(), dp(1f).toInt(), dp(6f).toInt(), dp(1f).toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(8f)
                    setColor(android.graphics.Color.parseColor("#2B8CFF"))
                }
                visibility = View.GONE
            }
            countViews[ds] = tvCount
            row1.addView(tvCount, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4f).toInt() })
        }
        inner.addView(row1)
        // 第二行：状态/账号（失效红标「已失效·点击重登」）
        inner.addView(TextView(ctx).apply {
            textSize = 10f
            maxLines = 1
            when {
                isInvalid -> {
                    text = "已失效 · 点击重登"
                    setTextColor(ctx.getColor(R.color.log_error))
                }
                logged -> {
                    val username = prefs.getUsername(ds)
                    val hide = ds == DataSource.GARMIN_CN || ds == DataSource.GARMIN_COM
                    text = if (username != null && !hide) "✅ $username" else "✅ 已登录"
                    setTextColor(ctx.getColor(R.color.green))
                }
                else -> {
                    text = "未登录"
                    setTextColor(ctx.getColor(R.color.text_secondary))
                }
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2f).toInt() })
        // v8.4.2: 平台统计行（↑下载 ↓跳过 ✗失败；异步刷新）
        val tvStat = TextView(ctx).apply {
            textSize = 9.5f
            maxLines = 1
            text = if (logged) "↑- ↓- ✗-" else ""
            setTextColor(ctx.getColor(R.color.text_secondary))
        }
        if (logged) statViews[ds] = tvStat
        inner.addView(tvStat, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(1f).toInt() })
        // 第三行：主按钮（点击进详情/登录）
        inner.addView(TextView(ctx).apply {
            text = when {
                isInvalid -> "重新登录"
                logged -> "重新登录"
                else -> "登录${ds.displayName}"
            }
            textSize = 10f
            gravity = android.view.Gravity.CENTER
            setTextColor(ctx.getColor(
                when {
                    isInvalid -> R.color.log_error
                    logged -> R.color.green
                    else -> R.color.primary
                }
            ))
            setBackgroundResource(R.drawable.login_btn_bg)
            setPadding(0, dp(6f).toInt(), 0, dp(6f).toInt())
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5f).toInt() })
        card.addView(inner)
        return card
    }

    /** v8.4.0: 当前会话检测到的失效平台（来自 MainActivity.checkAllLogins 结果） */
    private fun invalidPlatforms(): Set<DataSource> =
        (activity as? MainActivity)?.lastInvalidPlatforms?.toSet() ?: emptySet()

    /** v8.4.0: 简化详情弹窗（状态/账号/操作按钮；正式版无统计/日志/记录中心） */
    private fun showDetail(ds: DataSource) {
        val ctx = requireContext()
        val act = activity as? MainActivity
        val logged = prefs.isLoggedIn(ds)
        val username = prefs.getUsername(ds)
        val isGarmin = ds == DataSource.GARMIN_COM || ds == DataSource.GARMIN_CN
        var dlg: androidx.appcompat.app.AlertDialog? = null

        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f).toInt(), dp(8f).toInt(), dp(20f).toInt(), dp(4f).toInt())
        }
        fun infoRow(label: String, value: String, color: Int) {
            layout.addView(TextView(ctx).apply {
                text = "$label $value"
                textSize = 13f
                setTextColor(ctx.getColor(color))
                setPadding(0, dp(6f).toInt(), 0, 0)
            })
        }
        val hideUsername = isGarmin
        infoRow("状态：", if (logged) "已登录" else "未登录", if (logged) R.color.green else R.color.text_secondary)
        if (logged && username != null && !hideUsername) infoRow("账号：", username, R.color.text_primary)
        // v8.4.2: 平台统计（累计 上传/下载/失败 + 最后同步时间；异步查询后填充）
        val tvStat = TextView(ctx).apply {
            textSize = 12f
            setTextColor(ctx.getColor(R.color.text_primary))
            setPadding(0, dp(6f).toInt(), 0, 0)
        }
        layout.addView(tvStat)
        // v8.4.2: 最近运动记录（缓存库最近5条；标题行可点开独立弹窗）
        val tvRecentTitle = TextView(ctx).apply {
            text = "最近运动记录（缓存） ▸"
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ctx.getColor(R.color.primary))
            setPadding(0, dp(10f).toInt(), 0, 0)
            setOnClickListener { if (logged) showRecentDialog(ds) }
        }
        val tvRecents = TextView(ctx).apply {
            textSize = 11f
            setTextColor(ctx.getColor(R.color.text_secondary))
            setPadding(0, dp(4f).toInt(), 0, 0)
        }
        val btnAllRecords = TextView(ctx).apply {
            text = "查看全部"
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setTextColor(ctx.getColor(R.color.primary))
            setBackgroundResource(R.drawable.bg_check_update)
            setPadding(0, dp(8f).toInt(), 0, dp(8f).toInt())
        }
        if (logged) {
            layout.addView(tvRecentTitle)
            layout.addView(tvRecents)
            val fetchRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(8f).toInt(), 0, 0)
            }
            fetchRow.addView(TextView(ctx).apply {
                text = "拉取全部记录"
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundResource(R.drawable.primary_btn_bg)
                setPadding(0, dp(8f).toInt(), 0, dp(8f).toInt())
                setOnClickListener {
                    try { dlg?.dismiss() } catch (_: Exception) {}
                    act?.preloadRecent(ds)
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6f).toInt() })
            fetchRow.addView(btnAllRecords, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6f).toInt() })
            layout.addView(fetchRow)
            // iGPSPORT 专属：批量修复缺失时间（列表接口无时间，FIT 文件自带时间）
            if (ds == DataSource.IGPSPORT) {
                layout.addView(TextView(ctx).apply {
                    text = "修复缺失时间（1970 记录）"
                    textSize = 12f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(ctx.getColor(R.color.primary))
                    setBackgroundResource(R.drawable.logout_bg)
                    setPadding(0, dp(8f).toInt(), 0, dp(8f).toInt())
                    setOnClickListener {
                        try { dlg?.dismiss() } catch (_: Exception) {}
                        (activity as? MainActivity)?.repairIgpTimes()
                    }
                }, LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, dp(8f).toInt(), 0, 0)
                })
            }
        }
        // v8.4.2: 平台日志（最近 10 条；标题行可点开独立完整日志弹窗）
        val tvLogTitle = TextView(ctx).apply {
            text = "平台同步日志 ▸"
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ctx.getColor(R.color.primary))
            setPadding(0, dp(10f).toInt(), 0, 0)
            setOnClickListener { if (logged) showLogDialog(ds) }
        }
        val tvLogs = TextView(ctx).apply {
            textSize = 11f
            setTextColor(ctx.getColor(R.color.text_secondary))
            setPadding(0, dp(4f).toInt(), 0, 0)
        }
        if (logged) {
            layout.addView(tvLogTitle)
            layout.addView(tvLogs)
        }
        if (isGarmin) {
            infoRow("提示：", "若登录遇「冷却中」拦截，可先清空风控后再试", R.color.text_secondary)
        }
        // 操作按钮行
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(14f).toInt(), 0, 0)
        }
        fun actionBtn(text: String, textColor: Int, bg: Int, onClick: () -> Unit): TextView =
            TextView(ctx).apply {
                this.text = text
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                setTextColor(ctx.getColor(textColor))
                setBackgroundResource(bg)
                setPadding(0, dp(8f).toInt(), 0, dp(8f).toInt())
                setOnClickListener { onClick() }
            }
        btnRow.addView(
            actionBtn(if (logged) "重新登录" else "登录", R.color.white, R.drawable.primary_btn_bg) {
                try { dlg?.dismiss() } catch (_: Exception) {}
                act?.openPlatformLogin(ds)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6f).toInt() }
        )
        if (logged) {
            btnRow.addView(
                actionBtn("注销", R.color.text_secondary, R.drawable.logout_bg) { logout(ds) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6f).toInt() }
            )
        } else if (isGarmin) {
            btnRow.addView(
                actionBtn("清空风控", R.color.log_error, R.drawable.logout_bg) { clearCooldown(ds) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6f).toInt() }
            )
        }
        layout.addView(btnRow)
        if (logged && isGarmin) {
            layout.addView(actionBtn("清空风控冷却", R.color.log_error, R.drawable.logout_bg) { clearCooldown(ds) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f).toInt() })
        }

        dlg = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(ds.displayName)
            .setView(layout)
            .setNegativeButton("关闭", null)
            .show()

        // 「查看全部」→ 该平台最近记录列表弹窗（正式版无记录中心入口）
        btnAllRecords.setOnClickListener {
            try { dlg?.dismiss() } catch (_: Exception) {}
            showRecentDialog(ds)
        }

        // 异步加载统计 + 最近记录 + 平台日志（IO 查询，主线程填充）
        if (logged) {
            lifecycleScope.launch(Dispatchers.IO) {
                val cache = try { com.jichi.ob.util.ActivityCache.get(requireContext()) } catch (_: Exception) { null }
                val stat = try { cache?.getPlatformStat(ds.shortName) } catch (_: Exception) { null }
                val logs = try { cache?.getPlatformLogs(ds.shortName, 10) ?: emptyList() } catch (_: Exception) { emptyList() }
                val recents = try { cache?.queryByPlatform(ds.shortName)?.take(5) ?: emptyList() } catch (_: Exception) { emptyList() }
                act?.runOnUiThread {
                    if (stat != null) {
                        val last = if (stat.lastSync > 0)
                            android.text.format.DateFormat.getDateFormat(requireContext())
                                .format(java.util.Date(stat.lastSync)) else "从未"
                        tvStat.text = "累计：↑${stat.ok} 下载 · ↓${stat.skip} 上传 · ✗${stat.fail} 失败　最后同步 ${last}"
                    } else {
                        tvStat.text = "累计：暂无同步记录"
                    }
                    tvRecents.text = if (recents.isEmpty()) "（暂无缓存记录，同步后自动写入）"
                    else recents.joinToString("\n") { r ->
                        val t = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(r.startTime))
                        val d = if (r.distanceKm > 0) "%.1fkm".format(r.distanceKm) else ""
                        "[$t] ${r.title.ifBlank { "未命名活动" }}${if (d.isNotEmpty()) " · $d" else ""}"
                    }
                    tvLogs.text = if (logs.isEmpty()) "（暂无日志）"
                    else logs.joinToString("\n") { l ->
                        val t = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(l.time))
                        val tag = when (l.type) {
                            "ok" -> "✅ 上传成功"; "dl" -> "⬇ 下载"; "skip" -> "⏭ 跳过"; "err" -> "❌ 失败"; else -> ""
                        }
                        "[" + t + "] " + tag + (if (l.msg.isBlank()) "" else " " + l.msg)
                    }
                }
            }
        }
    }

    /** v8.4.2: 最近运动记录独立弹窗——该平台缓存记录列表（时间+标题+距离，最多50条） */
    private fun showRecentDialog(ds: DataSource) {
        val ctx = requireContext()
        val act = activity as? MainActivity ?: return
        val body = TextView(ctx).apply {
            text = "加载中..."
            textSize = 12f
            setTextColor(ctx.getColor(R.color.text_secondary))
            setPadding(dp(24f).toInt(), dp(12f).toInt(), dp(24f).toInt(), dp(12f).toInt())
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("${ds.displayName} · 最近运动记录")
            .setView(body)
            .setNegativeButton("关闭", null)
            .show()
        lifecycleScope.launch(Dispatchers.IO) {
            val cache = try { com.jichi.ob.util.ActivityCache.get(requireContext()) } catch (_: Exception) { null }
            val recents = try { cache?.queryByPlatform(ds.shortName)?.take(50) ?: emptyList() } catch (_: Exception) { emptyList() }
            act.runOnUiThread {
                body.text = if (recents.isEmpty()) "（暂无缓存记录，同步后自动写入）"
                else recents.joinToString("\n") { r ->
                    val t = try {
                        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(r.startTime))
                    } catch (_: Exception) { "时间未知" }
                    val d = if (r.distanceKm > 0) "%.1fkm".format(r.distanceKm) else ""
                    "▪ $t  ${r.title.ifBlank { "未命名活动" }}${if (d.isNotEmpty()) " · $d" else ""}"
                }
            }
        }
    }

    /** v8.4.2: 平台同步日志独立弹窗——最近 50 条完整日志 */
    private fun showLogDialog(ds: DataSource) {
        val ctx = requireContext()
        val act = activity as? MainActivity ?: return
        val body = TextView(ctx).apply {
            text = "加载中..."
            textSize = 12f
            setTextColor(ctx.getColor(R.color.text_secondary))
            setPadding(dp(24f).toInt(), dp(12f).toInt(), dp(24f).toInt(), dp(12f).toInt())
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("${ds.displayName} · 平台同步日志")
            .setView(body)
            .setNegativeButton("关闭", null)
            .show()
        lifecycleScope.launch(Dispatchers.IO) {
            val cache = try { com.jichi.ob.util.ActivityCache.get(requireContext()) } catch (_: Exception) { null }
            val logs = try { cache?.getPlatformLogs(ds.shortName, 50) ?: emptyList() } catch (_: Exception) { emptyList() }
            act.runOnUiThread {
                body.text = if (logs.isEmpty()) "（暂无日志）"
                else logs.joinToString("\n") { l ->
                    val t = try {
                        java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(l.time))
                    } catch (_: Exception) { "" }
                    val tag = when (l.type) {
                        "ok" -> "✅ 上传成功"; "dl" -> "⬇ 下载"; "skip" -> "⏭ 跳过"; "err" -> "❌ 失败"; else -> ""
                    }
                    "[$t] $tag" + (if (l.msg.isBlank()) "" else " " + l.msg)
                }
            }
        }
    }

    private fun openPlatformLogin(ds: DataSource) {
        (activity as? MainActivity)?.openPlatformLogin(ds)
    }

    private fun logout(ds: DataSource) {
        val act = activity as? MainActivity ?: return
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("注销 ${ds.displayName}")
            .setMessage("确认注销该平台登录？")
            .setPositiveButton("注销") { _, _ ->
                prefs.clearCredential(ds)
                act.appendLog("👋 ${ds.displayName} 已注销")
                updateStatus()
                if (ds == DataSource.OUTBASE) {
                    try {
                        val tv = view?.findViewById<TextView>(R.id.tvOutbaseStatus)
                        tv?.text = "未登录"
                        tv?.setTextColor(requireContext().getColor(R.color.text_secondary))
                        view?.findViewById<TextView>(R.id.btnOutbaseLogin)?.text = "登录Outbase"
                    } catch (_: Exception) {}
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun clearCooldown(ds: DataSource) {
        val act = activity as? MainActivity ?: return
        act.clearGarminCooldown(ds)
    }

    private fun dotRes(ds: DataSource): Int = when (ds) {
        DataSource.IGPSPORT -> R.drawable.bg_dot_igp
        DataSource.XINGZHE -> R.drawable.bg_dot_xingzhe
        DataSource.MAGENE -> R.drawable.bg_dot_magene
        DataSource.BLACKBIRD -> R.drawable.bg_dot_blackbird
        DataSource.GIANT -> R.drawable.bg_dot_giant
        DataSource.BRYTON -> R.drawable.bg_dot_bryton
        DataSource.GARMIN_COM, DataSource.GARMIN_CN -> R.drawable.bg_dot_garmin
        DataSource.COROS_CN, DataSource.COROS_INT -> R.drawable.bg_dot_coros
        DataSource.WAHOO -> R.drawable.bg_dot_wahoo
        DataSource.MYWHOOSH -> R.drawable.bg_dot_mywhoosh
        DataSource.ZWIFT -> R.drawable.bg_dot_zwift
        DataSource.KEEP -> R.drawable.bg_dot_keep
        DataSource.CODOON -> R.drawable.bg_dot_codoon
        DataSource.ZEPP -> R.drawable.bg_dot_zepp
        DataSource.KOMOT -> R.drawable.bg_dot_komoot
        DataSource.SUUNTO -> R.drawable.bg_dot_suunto
        DataSource.TWO_BULU -> R.drawable.bg_dot_keep
        DataSource.JOYRUN -> R.drawable.bg_dot_keep
        DataSource.OUTBASE -> R.drawable.bg_dot_outbase
        else -> R.drawable.bg_dot_keep
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
