package com.jichi.ob.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.Spannable
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.jichi.ob.R
import com.jichi.ob.model.DataSource
import com.jichi.ob.model.UploadSupport
import com.jichi.ob.util.PrefsManager

/**
 * v8.0.0 正式版：页面2 数据同步设置页（多对一 Outbase 上传）
 * 数据来源【可多选】→ 统一上传到固定目标 Outbase
 */
class SyncSettingsFragment : Fragment() {

    private lateinit var prefs: PrefsManager
    private lateinit var gridSource: GridLayout
    private lateinit var gridTarget: GridLayout
    // v8.0.0: 多对一 —— 数据来源多选
    private val selectedSourceTags = LinkedHashSet<String>()
    // v8.0.0: 同步目标固定为 Outbase（不可取消）
    private val targetTag = "ob"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_sync_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = PrefsManager(requireContext())
        gridSource = view.findViewById(R.id.gridSource)
        gridTarget = view.findViewById(R.id.gridTarget)
        // fragment可见时刷新登录状态（登录/注销后切回本页自动刷新）
        lifecycle.addObserver(object : androidx.lifecycle.LifecycleEventObserver {
            override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: androidx.lifecycle.Lifecycle.Event) {
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refreshLoginState()
            }
        })

        val sliderCount = view.findViewById<Slider>(R.id.sliderCount)
        val tvCount = view.findViewById<TextView>(R.id.tvCount)
        val sliderSkip = view.findViewById<Slider>(R.id.sliderSkip)
        val tvSkip = view.findViewById<TextView>(R.id.tvSkip)
        val switchGcj02 = view.findViewById<SwitchMaterial>(R.id.switchGcj02)
        val switchForce = view.findViewById<SwitchMaterial>(R.id.switchForceRetransmit)

        sliderCount.addOnChangeListener { _, v, _ -> tvCount.text = v.toInt().toString() }
        sliderSkip.addOnChangeListener { _, v, _ -> tvSkip.text = v.toInt().toString() }
        tvCount.setOnClickListener { showInputDialog("同步数量", sliderCount, tvCount, 1, 1000) }
        tvSkip.setOnClickListener { showInputDialog("跳过前N条", sliderSkip, tvSkip, 0, 10000) }
        switchGcj02.setOnCheckedChangeListener { _, checked -> prefs.setGcj02Convert(checked) }
        switchForce.setOnCheckedChangeListener { _, checked ->
            prefs.setForceRetransmit(checked)
            updateForceRetransmitState()
        }

        setupSourceButtons()
        setupTargetButtons()
        updateSourceChips()
        restoreSettings(view)
        updateForceRetransmitState()

        // v8.1.2: 点击"打开目录"跳转到存储目录，查看已保存的FIT/GPX文件
        view.findViewById<TextView>(R.id.btnOpenSaveDir)?.setOnClickListener { openSaveDir() }
    }

    /**
     * v8.1.2: 打开存储目录（多级兼容，适配vivo等国产ROM，源自开发版 v7.8.2）
     * ① content:// 目录URI + 目录MIME（分区存储标准方案）
     * ② file:// 目录 + 目录MIME（部分ROM/旧系统文件管理器）
     * ③ 系统文档选择器 DocumentsUI（所有设备兜底，尽量定位到目标目录）
     */
    private fun openSaveDir() {
        val dir = com.jichi.ob.MainActivity.SAVE_DIR
        try { if (!dir.exists()) dir.mkdirs() } catch (_: Exception) {}
        val mimeDir = DocumentsContract.Document.MIME_TYPE_DIR // vnd.android.document/directory
        val rel = dir.absolutePath.removePrefix("/storage/emulated/0/").trimStart('/')

        // ① 首选：content:// 目录URI + 目录MIME
        try {
            val uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$rel")
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = uri
                type = mimeDir
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            }
            startActivity(intent); return
        } catch (_: Exception) {}

        // ② 回退：file:// 目录 + 目录MIME
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.fromFile(dir), mimeDir)
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            startActivity(intent); return
        } catch (_: Exception) {}

        // ③ 兜底：系统文档选择器 DocumentsUI（所有设备可用），尽量定位到目标目录
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                intent.putExtra("android.intent.extra.INITIAL_URI", DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents", "primary:$rel"))
            }
            startActivity(intent); return
        } catch (_: Exception) {}

        Toast.makeText(requireContext(), "无法直接打开目录，请到文件管理器查看：\n${dir.absolutePath}", Toast.LENGTH_LONG).show()
    }

    // ===== 源/目标选择 =====
    private fun platformColor(tag: String): Int = when (tag) {
        "igp" -> requireContext().getColor(R.color.igp_green)
        "xz" -> requireContext().getColor(R.color.xingzhe_blue)
        "mg" -> requireContext().getColor(R.color.magene_blue)
        "bb" -> requireContext().getColor(R.color.blackbird_dark)
        "br" -> requireContext().getColor(R.color.bryton_red)
        "gm", "gcn" -> requireContext().getColor(R.color.garmin_blue)
        "cscn", "cs" -> requireContext().getColor(R.color.coros_red)
        "wo" -> requireContext().getColor(R.color.wahoo_red)
        "ob" -> requireContext().getColor(R.color.outbase_orange)
        else -> requireContext().getColor(R.color.primary)
    }

    private fun setButtonSelected(btn: MaterialButton, selected: Boolean, tag: String) {
        // v8.1: 目标 Outbase 固定使用 target-lock 样式（浅蓝锁定块，保持功能）
        if (tag == "ob") {
            btn.setBackgroundColor(requireContext().getColor(R.color.primary_soft))
            btn.setTextColor(requireContext().getColor(R.color.primary_dark))
            btn.alpha = 1.0f
            return
        }
        if (!btn.isEnabled) {
            btn.setBackgroundColor(0xFFE8E8E8.toInt())
            btn.setTextColor(0xFFB0B0B0.toInt())
            btn.alpha = 0.6f
            return
        }
        btn.alpha = 1.0f
        if (selected) {
            btn.setBackgroundColor(platformColor(tag))
            btn.setTextColor(requireContext().getColor(R.color.white))
        } else {
            btn.setBackgroundColor(requireContext().getColor(R.color.grey_light))
            btn.setTextColor(requireContext().getColor(R.color.text_primary))
        }
    }

    /** v8.0.0: 数据来源多选 toggle */
    private fun setupSourceButtons() {
        for (i in 0 until gridSource.childCount) {
            val btn = gridSource.getChildAt(i) as? MaterialButton ?: continue
            val tag = btn.tag as? String ?: continue
            btn.setOnClickListener {
                if (!btn.isEnabled) return@setOnClickListener
                val ds = DataSource.fromShortName(tag)
                if (ds == null || !prefs.isLoggedIn(ds)) {
                    Toast.makeText(requireContext(), "请先登录${ds?.displayName ?: "该平台"}", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                // 多选 toggle
                if (selectedSourceTags.contains(tag)) selectedSourceTags.remove(tag)
                else selectedSourceTags.add(tag)
                refreshSourceButtons()
            }
        }
    }

    private fun refreshSourceButtons() {
        for (j in 0 until gridSource.childCount) {
            val b = gridSource.getChildAt(j) as? MaterialButton ?: continue
            setButtonSelected(b, selectedSourceTags.contains(b.tag as? String ?: ""), b.tag as? String ?: "")
        }
        updateSourceCountLabel()
        updateForceRetransmitState()
    }

    private fun updateSourceCountLabel() {
        view?.findViewById<TextView>(R.id.tvSourceHint)?.text =
            if (selectedSourceTags.isEmpty()) "数据来源 (可多选，点击切换):"
            else "数据来源 (可多选): 已选 ${selectedSourceTags.size} 个"
    }

    /** v8.0.0: 同步目标固定为 Outbase，保持选中态 */
    private fun setupTargetButtons() {
        for (i in 0 until gridTarget.childCount) {
            val btn = gridTarget.getChildAt(i) as? MaterialButton ?: continue
            val tag = btn.tag as? String ?: continue
            // 仅 Outbase 目标，固定选中（点击提示）
            btn.setOnClickListener {
                Toast.makeText(requireContext(), "正式版仅支持上传到 Outbase", Toast.LENGTH_SHORT).show()
            }
            if (tag == targetTag) {
                btn.isEnabled = true
                setButtonSelected(btn, true, tag)
            }
        }
        view?.findViewById<TextView>(R.id.tvTargetHint)?.text = "同步目标"
    }

    /** v8.0.0: 忽略记忆强制重传开关状态（用户自选） */
    private fun updateForceRetransmitState() {
        val sw = view?.findViewById<SwitchMaterial>(R.id.switchForceRetransmit) ?: return
        val hint = view?.findViewById<TextView>(R.id.tvForceRetransmitHint) ?: return
        sw.isEnabled = true
        sw.isChecked = prefs.isForceRetransmit()
        hint.text = if (sw.isChecked) "已开启：忽略同步记忆，本次同步将重新上传"
        else "已关闭：已在同步记忆中的记录将自动跳过"
    }

    private fun restoreSettings(view: View) {
        // v8.0.0: 恢复多选来源（过滤未登录平台）
        selectedSourceTags.clear()
        selectedSourceTags.addAll(prefs.getLastSources().filter { t ->
            DataSource.fromShortName(t)?.let { prefs.isLoggedIn(it) } ?: false
        })
        for (i in 0 until gridSource.childCount) {
            val btn = gridSource.getChildAt(i) as? MaterialButton ?: continue
            val tag = btn.tag as? String ?: continue
            setButtonSelected(btn, selectedSourceTags.contains(tag), tag)
        }
        updateSourceCountLabel()
        updateSourceChips()
        view.findViewById<SwitchMaterial>(R.id.switchGcj02).isChecked = prefs.isGcj02Convert()
        val saveDir = view.findViewById<TextView>(R.id.tvSaveDir)
        try {
            saveDir.text = com.jichi.ob.MainActivity.SAVE_DIR.absolutePath
        } catch (e: Exception) {
            saveDir.text = "下载/迈向Ob"
        }
        view.findViewById<TextView>(R.id.tvSyncedCount).text = "已同步: ${prefs.getSyncedCount()} 条"
        updateForceRetransmitState()
        initDateCutoff(view)
    }

    /** v8.1.0: 日期截止过滤 —— 开启后只同步指定日期之前的数据 */
    private fun initDateCutoff(view: View) {
        val sw = view.findViewById<SwitchMaterial>(R.id.switchDateCutoff) ?: return
        val tv = view.findViewById<TextView>(R.id.tvCutoffDate) ?: return
        val hint = view.findViewById<TextView>(R.id.tvCutoffHint) ?: return
        val defaultDate = "2020-01-01"
        fun refreshCutoff() {
            val enabled = prefs.isDateCutoffEnabled()
            val date = prefs.getCutoffDate().ifBlank { defaultDate }
            sw.isChecked = enabled
            tv.text = if (enabled) "截止 $date" else "点击选择日期"
            hint.text = if (enabled) "已开启：仅同步 $date 之前的数据，之后的数据将被跳过"
            else "关闭：同步全部数据（不按日期过滤）"
        }
        refreshCutoff()
        sw.setOnCheckedChangeListener { _, b ->
            prefs.setDateCutoffEnabled(b)
            refreshCutoff()
        }
        tv.setOnClickListener {
            val parts = prefs.getCutoffDate().ifBlank { defaultDate }.split("-")
            val y = parts.getOrNull(0)?.toIntOrNull() ?: 2020
            val m = parts.getOrNull(1)?.toIntOrNull()?.minus(1) ?: 0
            val d = parts.getOrNull(2)?.toIntOrNull() ?: 1
            val picker = android.app.DatePickerDialog(requireContext(), { _, yy, mm, dd ->
                prefs.setCutoffDate(String.format("%04d-%02d-%02d", yy, mm + 1, dd))
                prefs.setDateCutoffEnabled(true)
                refreshCutoff()
            }, y, m, d)
            picker.show()
        }
    }

    private fun updateTargetChips() {
        for (i in 0 until gridTarget.childCount) {
            val btn = gridTarget.getChildAt(i) as? MaterialButton ?: continue
            val ds = DataSource.fromShortName(btn.tag as? String ?: "") ?: continue
            if (btn.tag as? String == targetTag) {
                btn.isEnabled = true
                setButtonSelected(btn, true, targetTag)
            }
        }
    }

    /** v8.0.0: 来源网格未登录平台置灰不可点 */
    private fun updateSourceChips() {
        for (i in 0 until gridSource.childCount) {
            val btn = gridSource.getChildAt(i) as? MaterialButton ?: continue
            val ds = DataSource.fromShortName(btn.tag as? String ?: "") ?: continue
            if (!prefs.isLoggedIn(ds)) {
                btn.isEnabled = false
                btn.setBackgroundColor(0xFFE8E8E8.toInt())
                btn.setTextColor(0xFFB0B0B0.toInt())
                btn.alpha = 0.7f
            } else {
                btn.isEnabled = true
                btn.alpha = 1.0f
                setButtonSelected(btn, selectedSourceTags.contains(btn.tag as? String ?: ""), btn.tag as? String ?: "")
            }
        }
    }

    /** v8.0.0: 登录状态变化后刷新设置页 */
    fun refreshLoginState() {
        try {
            updateSourceChips()
            // 清理来源中未登录的平台
            selectedSourceTags.removeAll { t ->
                val ds = DataSource.fromShortName(t)
                ds == null || !prefs.isLoggedIn(ds)
            }
            updateSourceCountLabel()
            updateTargetChips()
            updateForceRetransmitState()
        } catch (e: Exception) {
            // 忽略刷新异常
        }
    }

    // v7.6.2: 手动输入数值（滑块+输入框联动）
    private fun showInputDialog(title: String, slider: Slider, tv: TextView, min: Int, max: Int) {
        val input = android.widget.EditText(requireContext())
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        input.setText(slider.value.toInt().toString())
        input.selectAll()
        val pad = (16 * resources.displayMetrics.density).toInt()
        input.setPadding(pad, pad, pad, pad)
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val v = input.text.toString().toIntOrNull()
                if (v != null) {
                    val clamped = v.coerceIn(min, max)
                    slider.value = clamped.toFloat()
                    tv.text = clamped.toString()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===== MainActivity调用 =====
    /** v8.0.0: 多对一 - 返回所有勾选的数据来源 */
    fun getSelectedSources(): List<DataSource> =
        selectedSourceTags.mapNotNull { DataSource.fromShortName(it) }
    /** v8.0.0: 固定目标 Outbase */
    fun getTarget(): DataSource = DataSource.OUTBASE
    fun getCount(): Int = requireView().findViewById<Slider>(R.id.sliderCount).value.toInt()
    fun getSkip(): Int = requireView().findViewById<Slider>(R.id.sliderSkip).value.toInt()
    fun setSyncedCount(n: Int) {
        view?.findViewById<TextView>(R.id.tvSyncedCount)?.text = "已同步: $n 条"
    }
    fun setSaveDir(path: String) {
        view?.findViewById<TextView>(R.id.tvSaveDir)?.text = path
    }
}
