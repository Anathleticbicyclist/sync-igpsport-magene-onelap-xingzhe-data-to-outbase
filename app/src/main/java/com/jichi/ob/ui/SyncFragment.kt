package com.jichi.ob.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.jichi.ob.MainActivity
import com.jichi.ob.R
import com.jichi.ob.util.PrefsManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v7.6.2: 四页面布局 - 页面3 同步页
 * 自动同步 + 开始/停止/测试/清记忆 + 进度 + 运行日志
 */
class SyncFragment : Fragment() {

    private lateinit var prefs: PrefsManager
    private var tvLog: TextView? = null
    private var logScrollView: ScrollView? = null
    private var btnSync: MaterialButton? = null
    private var btnStop: MaterialButton? = null
    private var btnSyncProgress: LinearProgressIndicator? = null
    private var tvLogReady = false
    private val pendingLogs = mutableListOf<String>()
    // v8.1: 统计摘要
    private var statOk: TextView? = null
    private var statSkip: TextView? = null
    private var statFail: TextView? = null
    private var statBar: View? = null
    private var cntOk = 0
    private var cntSkip = 0
    private var cntFail = 0
    // v8.2: 按钮跑马灯呼吸动画 + 暂停滚动
    private var breathAnim: android.animation.ValueAnimator? = null
    private var logPaused = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_sync, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = PrefsManager(requireContext())
        tvLog = view.findViewById(R.id.tvLog)
        logScrollView = view.findViewById(R.id.svLog)
        btnSyncProgress = view.findViewById(R.id.btnSyncProgress)
        btnSync = view.findViewById(R.id.btnSync)
        btnStop = view.findViewById(R.id.btnStop)
        // v8.1: 统计摘要
        statOk = view.findViewById(R.id.statOk)
        statSkip = view.findViewById(R.id.statSkip)
        statFail = view.findViewById(R.id.statFail)
        statBar = view.findViewById(R.id.logStats)

        // v8.2: 日志区 - 暂停滚动 / 清空
        view.findViewById<View>(R.id.logPause)?.setOnClickListener {
            logPaused = !logPaused
            (view.findViewById<TextView>(R.id.logPause))?.text = if (logPaused) "继续滚动" else "暂停滚动"
        }
        view.findViewById<View>(R.id.logClear)?.setOnClickListener {
            tvLog?.text = "等待操作..."
            cntOk = 0; cntSkip = 0; cntFail = 0
            statBar?.visibility = View.GONE
        }

        // 操作按钮
        btnSync?.setOnClickListener { (activity as? MainActivity)?.startSync() }
        btnStop?.setOnClickListener { (activity as? MainActivity)?.stopSync() }
        view.findViewById<MaterialButton>(R.id.btnTestDownload)?.setOnClickListener { (activity as? MainActivity)?.testDownload() }
        view.findViewById<MaterialButton>(R.id.btnPowerGuide)?.setOnClickListener { (activity as? MainActivity)?.showPowerGuide() }
        view.findViewById<MaterialButton>(R.id.btnClearSync)?.setOnClickListener { (activity as? MainActivity)?.clearSyncMemory() }
        view.findViewById<MaterialButton>(R.id.btnCopyLog)?.setOnClickListener { copyLog() }

        // 自动同步
        val switchAutoSync = view.findViewById<SwitchMaterial>(R.id.switchAutoSync)
        val sliderAutoInterval = view.findViewById<Slider>(R.id.sliderAutoInterval)
        val tvAutoInterval = view.findViewById<TextView>(R.id.tvAutoInterval)
        switchAutoSync.isChecked = prefs.isAutoSync()
        val interval = prefs.getAutoInterval().coerceAtLeast(15 * 60)
        sliderAutoInterval.value = interval.toFloat()
        tvAutoInterval.text = "${interval / 60}分钟"
        switchAutoSync.setOnCheckedChangeListener { _, checked ->
            prefs.setAutoSync(checked)
            val act = activity as? MainActivity
            if (checked) act?.startAutoSync() else act?.stopAutoSync()
        }
        sliderAutoInterval.addOnChangeListener { _, v, _ ->
            val sec = v.toInt().coerceAtLeast(15 * 60)
            prefs.setAutoInterval(sec)
            tvAutoInterval.text = "${sec / 60}分钟"
        }

        tvLogReady = true
        flushPendingLogs()
        // v7.6.9: 加载持久化日志（自动同步/历史同步记录），App重开仍可见，避免"假同步"无日志
        try {
            val logs = prefs.getPersistLogs().takeLast(80)
            if (logs.isNotEmpty()) {
                val tv = tvLog
                if (tv != null) {
                    tv.text = buildColoredLog("━━━ 最近同步记录 ━━━\n" + logs.joinToString("\n"))
                    logScrollView?.post { try { logScrollView?.fullScroll(ScrollView.FOCUS_DOWN) } catch (_: Exception) {} }
                }
            }
        } catch (_: Exception) {}
    }

    private fun flushPendingLogs() {
        if (!tvLogReady) return
        for (msg in pendingLogs) appendLog(msg)
        pendingLogs.clear()
    }

    /** MainActivity调用：追加日志（v8.1: 按状态着色，状态时间线式展示） */
    fun appendLog(message: String) {
        val ts = try { SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()) } catch (_: Exception) { "??:??:??" }
        val tv = tvLog
        if (tv == null) {
            pendingLogs.add("[$ts] $message")
            return
        }
        val cur = tv.text?.toString() ?: ""
        val newText = if (cur.isBlank() || cur == "等待操作...") "[$ts] $message" else "$cur\n[$ts] $message"
        tv.text = buildColoredLog(newText)
        // v8.1: 统计摘要（成功/跳过/失败）
        countAndUpdateStats(message)
        if (!logPaused) {
            logScrollView?.post { try { logScrollView?.fullScroll(ScrollView.FOCUS_DOWN) } catch (_: Exception) {} }
        }
    }

    /** v8.1: 按状态累加并刷新统计摘要条 */
    private fun countAndUpdateStats(message: String) {
        when {
            message.contains("✅") -> cntOk++
            message.contains("❌") -> cntFail++
            message.contains("⏭") || message.contains("跳过") -> cntSkip++
            else -> return
        }
        updateStats()
    }

    private fun updateStats() {
        val bar = statBar ?: return
        bar.visibility = View.VISIBLE
        statOk?.text = "成功 $cntOk"
        statSkip?.text = "跳过 $cntSkip"
        statFail?.text = "失败 $cntFail"
    }

    /** v8.1: 开始新一次同步时重置统计 */
    fun resetStats() {
        cntOk = 0; cntSkip = 0; cntFail = 0
        statBar?.visibility = View.GONE
    }

    /** v8.1: 日志状态时间线——按行前缀 emoji 着色（成功绿/失败红/跳过橙/信息蓝/其余中性） */
    private fun buildColoredLog(text: String): SpannableString {
        val s = SpannableString(text)
        val ctx = requireContext()
        val colorOk = ctx.getColor(R.color.ok_green)
        val colorErr = ctx.getColor(R.color.err_red)
        val colorWarn = ctx.getColor(R.color.warn_orange)
        val colorInfo = ctx.getColor(R.color.primary)
        val colorDefault = ctx.getColor(R.color.text_primary)
        val colorTime = ctx.getColor(R.color.text_tertiary)
        val lines = text.split("\n")
        var offset = 0
        for (line in lines) {
            val len = line.length
            if (len > 0) {
                var msgStart = 0
                // v8.1: 时间戳前缀 [xxx] 灰色，消息按状态着色（状态时间线效果）
                val close = line.indexOf(']')
                if (close > 0 && line.startsWith("[")) {
                    msgStart = close + 1
                    s.setSpan(ForegroundColorSpan(colorTime), offset, offset + msgStart, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                val msg = if (msgStart < len) line.substring(msgStart) else ""
                val c = when {
                    msg.contains("✅") -> colorOk
                    msg.contains("❌") -> colorErr
                    msg.contains("⏭") || msg.contains("跳过") -> colorWarn
                    msg.contains("🚀") || msg.contains("📥") || msg.contains("📤") ||
                        msg.contains("💾") || msg.contains("📋") || msg.contains("⏰") ||
                        msg.contains("📊") || msg.contains("💡") || msg.contains("🔋") ||
                        msg.contains("🧪") || msg.contains("🗑") || msg.contains("⏹") ||
                        msg.contains("🔐") || msg.contains("📄") -> colorInfo
                    else -> colorDefault
                }
                s.setSpan(ForegroundColorSpan(c), offset + msgStart, offset + len, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            offset += len + 1
        }
        return s
    }

    /** MainActivity调用：设置同步中状态（v8.2: 按钮进度条 + 呼吸动画，随同步缓慢推进至填满） */
    fun setSyncing(syncing: Boolean) {
        com.jichi.ob.AutoSyncWorker.syncing = syncing
        val btnS = btnSync ?: return
        val btnT = btnStop ?: return
        val bp = btnSyncProgress
        btnS.isEnabled = !syncing
        btnT.isEnabled = syncing
        btnS.text = if (syncing) "同步中..." else "开始同步"
        if (syncing) {
            // 启动按钮跑马灯：进度条呼吸动画（缓慢推进由 setProgress 控制）
            bp?.visibility = View.VISIBLE
            bp?.isIndeterminate = false
            breathAnim?.cancel()
            breathAnim = android.animation.ValueAnimator.ofFloat(0.55f, 1f, 0.55f).apply {
                duration = 2200L
                repeatCount = android.animation.ValueAnimator.INFINITE
                addUpdateListener { bp?.alpha = it.animatedValue as Float }
                start()
            }
        } else {
            // 同步结束：进度条填满后淡出，停止呼吸
            breathAnim?.cancel()
            breathAnim = null
            val p = bp
            if (p != null) {
                p.alpha = 1f
                p.progress = p.max
                p.postDelayed({
                    try { p.visibility = View.GONE; p.progress = 0 } catch (_: Exception) {}
                }, 600L)
            }
        }
    }

    fun setProgressIndeterminate(v: Boolean) { btnSyncProgress?.isIndeterminate = v }
    fun setProgressMax(max: Int) { btnSyncProgress?.max = max }
    fun setProgress(cur: Int) {
        btnSyncProgress?.progress = cur
    }

    private fun copyLog() {
        val log = tvLog?.text?.toString() ?: ""
        if (log.isNotBlank()) {
            (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("运行日志", log))
            Toast.makeText(requireContext(), "日志已复制", Toast.LENGTH_SHORT).show()
        }
    }
}
