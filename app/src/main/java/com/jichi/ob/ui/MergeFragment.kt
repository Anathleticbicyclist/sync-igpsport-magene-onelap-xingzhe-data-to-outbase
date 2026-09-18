package com.jichi.ob.ui

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.jichi.ob.GpxToFitConverter
import com.jichi.ob.MainActivity
import com.jichi.ob.R
import com.jichi.ob.merge.FitParser
import com.jichi.ob.merge.TrackMerger
import com.jichi.ob.model.ActivityRecord
import com.jichi.ob.model.DataSource
import com.jichi.ob.util.PrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v8.4.0: 数据合并页（正式版精简版，裁剪开发版贴纸/透明贴纸/3D 功能）
 * 流程：1. 选来源平台 → 2. 多选记录 → 3. 合并预览（统计+导出） → 4. 存本地 + 上传 Outbase
 * 铁律：正式版仅保留「多来源 → Outbase」单向上传，合并结果固定上传 Outbase。
 */
class MergeFragment : Fragment() {

    private lateinit var prefs: PrefsManager
    private var source: DataSource? = null
    private var localMode = false

    // 步骤容器
    private lateinit var stepSource: View
    private lateinit var stepSelect: View
    private lateinit var stepPreview: View
    private lateinit var stepResult: View
    private lateinit var mergeTitle: TextView
    private lateinit var mergeStepHint: TextView

    // Step1
    private lateinit var gridMergeSource: GridLayout
    private lateinit var btnMergeNext: MaterialButton

    // Step2
    private lateinit var rvMergeList: RecyclerView
    private lateinit var tvMergeHint: TextView
    private lateinit var tvMergeSelected: TextView
    private lateinit var btnDoMerge: MaterialButton
    private var mergeAdapter: MergeAdapter? = null
    private val selectedIndexes = linkedSetOf<Int>()
    private var rangeMode = false
    private var rangeAnchor = -1

    // Step3（统计+导出+保存）
    private lateinit var gridMergeStats: GridLayout
    private lateinit var btnSaveMerge: MaterialButton
    private lateinit var btnExportGpx: TextView
    private lateinit var btnExportFit: TextView

    // Step4（结果+上传 Outbase）
    private lateinit var tvMergeResultTitle: TextView
    private lateinit var tvMergeResultFile: TextView
    private lateinit var tvMergeResultPath: TextView
    private lateinit var tvMergeOutbaseStatus: TextView
    private lateinit var btnUploadMerge: MaterialButton
    private lateinit var btnMergeDone: TextView

    // 合并数据
    private var mergedResult: TrackMerger.MergedResult? = null
    private var savedFile: File? = null

    // Step2 全量记录 + 日期范围筛选 + 分页
    private var allRecords: List<ActivityRecord> = emptyList()
    private var fromDate: String? = null
    private var toDate: String? = null
    private var lastErr = ""
    private var pageSkip = 0
    private var listHasMore = false
    private var pageLoading = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_merge, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = PrefsManager(requireContext())

        stepSource = view.findViewById(R.id.stepSource)
        stepSelect = view.findViewById(R.id.stepSelect)
        stepPreview = view.findViewById(R.id.stepPreview)
        stepResult = view.findViewById(R.id.stepResult)
        mergeTitle = view.findViewById(R.id.mergeTitle)
        mergeStepHint = view.findViewById(R.id.mergeStepHint)

        gridMergeSource = view.findViewById(R.id.gridMergeSource)
        btnMergeNext = view.findViewById(R.id.btnMergeNext)

        rvMergeList = view.findViewById(R.id.rvMergeList)
        tvMergeHint = view.findViewById(R.id.tvMergeHint)
        tvMergeSelected = view.findViewById(R.id.tvMergeSelected)
        btnDoMerge = view.findViewById(R.id.btnDoMerge)
        rvMergeList.layoutManager = LinearLayoutManager(requireContext())

        // v8.1.2: 分页加载更多
        view.findViewById<TextView>(R.id.btnLoadMore).apply {
            setOnClickListener {
                if (!pageLoading && listHasMore) loadActivities(append = true)
            }
        }

        gridMergeStats = view.findViewById(R.id.gridMergeStats)
        btnSaveMerge = view.findViewById(R.id.btnSaveMerge)
        btnExportGpx = view.findViewById(R.id.btnExportGpx)
        btnExportFit = view.findViewById(R.id.btnExportFit)

        tvMergeResultTitle = view.findViewById(R.id.tvMergeResultTitle)
        tvMergeResultFile = view.findViewById(R.id.tvMergeResultFile)
        tvMergeResultPath = view.findViewById(R.id.tvMergeResultPath)
        tvMergeOutbaseStatus = view.findViewById(R.id.tvMergeOutbaseStatus)
        btnUploadMerge = view.findViewById(R.id.btnUploadMerge)
        btnMergeDone = view.findViewById(R.id.btnMergeDone)

        view.findViewById<TextView>(R.id.btnMergeBack).setOnClickListener { onBack() }
        btnMergeNext.setOnClickListener { loadActivities() }
        view.findViewById<TextView>(R.id.btnPickDate).setOnClickListener { pickDateFilter() }
        view.findViewById<TextView>(R.id.btnClearDate).setOnClickListener {
            fromDate = null; toDate = null
            pageSkip = 0; listHasMore = false
            refreshDateFilterViews()
            loadActivities()
            toast("已恢复显示最近记录")
        }
        view.findViewById<TextView>(R.id.btnMergeAllToday).setOnClickListener { selectToday() }
        view.findViewById<TextView>(R.id.btnMergeRange).setOnClickListener { toggleRangeMode() }
        view.findViewById<TextView>(R.id.btnMergeClear).setOnClickListener { clearSelection() }
        btnDoMerge.setOnClickListener { doMerge() }
        btnSaveMerge.setOnClickListener { saveMerge() }
        btnExportGpx.setOnClickListener { exportMerge(exportFit = false) }
        btnExportFit.setOnClickListener { exportMerge(exportFit = true) }
        btnUploadMerge.setOnClickListener { uploadMerge() }
        btnMergeDone.setOnClickListener { (activity as? MainActivity)?.closeMerge() }

        setupSourceGrid()
        showStep(1)
    }

    private fun showStep(step: Int) {
        val root = view ?: return
        stepSource.visibility = if (step == 1) View.VISIBLE else View.GONE
        stepSelect.visibility = if (step == 2) View.VISIBLE else View.GONE
        stepPreview.visibility = if (step == 3) View.VISIBLE else View.GONE
        stepResult.visibility = if (step == 4) View.VISIBLE else View.GONE
        mergeTitle.text = when (step) {
            1 -> "数据合并"
            2 -> "选择记录"
            3 -> "合并预览"
            else -> "合并完成"
        }
        mergeStepHint.text = "$step/4"
    }

    private fun onBack() {
        when {
            stepResult.visibility == View.VISIBLE -> showStep(3)
            stepPreview.visibility == View.VISIBLE -> showStep(2)
            stepSelect.visibility == View.VISIBLE -> { showStep(1); refreshSourceSelection() }
            else -> (activity as? MainActivity)?.closeMerge()
        }
    }

    private fun color(id: Int) = requireContext().getColor(id)
    private val dp: Float get() = resources.displayMetrics.density
    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    // ================= Step1 来源（所有可下载平台 + 本地文件） =================
    private fun setupSourceGrid() {
        gridMergeSource.removeAllViews()
        val downloadable = DataSource.sourcePlatforms().toSet()
        // 百锐腾下载接口未开放，不作为合并数据源
        val list = DataSource.entries.filter { it in downloadable && it != DataSource.BRYTON }
        if (list.isEmpty()) {
            gridMergeSource.addView(TextView(requireContext()).apply {
                text = "暂无可用来源平台"; textSize = 13f
                setTextColor(color(R.color.text_secondary))
            }, GridLayout.LayoutParams().apply { columnSpec = GridLayout.spec(0, 4) })
            return
        }
        for (ds in list) {
            val loggedIn = prefs.isLoggedIn(ds)
            val btn = PlatformButton(requireContext()).apply {
                tag = ds.shortName
                bind(platformColor(ds.shortName))
                buttonText = if (loggedIn) ds.displayName else "${ds.displayName}\n未登录"
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0; height = (50 * dp).toInt()
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins((2 * dp).toInt(), (2 * dp).toInt(), (2 * dp).toInt(), (2 * dp).toInt())
                }
                setOnClickListener {
                    if (!prefs.isLoggedIn(ds)) {
                        toast("请先到登录页登录 ${ds.displayName}，登录后即可作为合并数据源")
                        return@setOnClickListener
                    }
                    source = ds
                    localMode = false
                    refreshSourceSelection()
                }
            }
            if (!loggedIn) btn.setPlatformState(false, false)
            gridMergeSource.addView(btn)
        }
        // 本地文件数据源（永远可用，不依赖登录）
        val localBtn = PlatformButton(requireContext()).apply {
            tag = "local"
            bind(color(R.color.primary))
            buttonText = "📁 本地文件"
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0; height = (44 * dp).toInt()
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins((2 * dp).toInt(), (2 * dp).toInt(), (2 * dp).toInt(), (2 * dp).toInt())
            }
            setOnClickListener { showLocalSourceChoices() }
        }
        gridMergeSource.addView(localBtn)
        // 默认选第一个已登录平台
        source = list.firstOrNull { prefs.isLoggedIn(it) }
        localMode = false
        refreshSourceSelection()
    }

    private fun showLocalSourceChoices() {
        val box = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * dp).toInt(), (8 * dp).toInt(), (18 * dp).toInt(), (2 * dp).toInt())
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("📁 本地文件数据源\n（FIT/GPX · 多选=合并）")
            .setView(box)
            .setNegativeButton("取消", null)
            .create()
        fun addBtn(text: String, onClick: () -> Unit) {
            val b = MaterialButton(requireContext()).apply {
                this.text = text
                isAllCaps = false
                cornerRadius = (12 * dp).toInt()
                insetTop = 0; insetBottom = 0
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (52 * dp).toInt()
                ).apply { topMargin = (8 * dp).toInt() }
            }
            b.setOnClickListener { dialog.dismiss(); onClick() }
            box.addView(b)
        }
        addBtn("📁 从已下载目录选择") { showDownloadedFilesDialog() }
        addBtn("🗂 浏览手机文件") { launchFilePicker() }
        dialog.show()
    }

    private fun showDownloadedFilesDialog() {
        val files = (MainActivity.SAVE_DIR.listFiles() ?: emptyArray())
            .filter { it.isFile && (it.name.endsWith(".fit", true) || it.name.endsWith(".gpx", true)) }
            .sortedByDescending { it.lastModified() }
        if (files.isEmpty()) {
            toast("已下载目录没有 FIT/GPX 文件（可先去同步页下载，或用浏览手机文件选择）")
            return
        }
        val names = files.map { it.name }.toTypedArray()
        val checked = BooleanArray(files.size)
        AlertDialog.Builder(requireContext())
            .setTitle("已下载目录（${files.size} 个 FIT/GPX，多选=合并）")
            .setMultiChoiceItems(names, checked) { _, i, b -> checked[i] = b }
            .setPositiveButton("确定") { _, _ ->
                val sel = files.filterIndexed { i, _ -> checked[i] }
                if (sel.isEmpty()) toast("未选择文件")
                else processLocalFiles(sel.map { it.name }, sel.map { runCatching { it.readBytes() }.getOrNull() })
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 系统文件选择器（SAF，多选任意位置 FIT/GPX） */
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        val names = ArrayList<String>()
        val bytes = ArrayList<ByteArray?>()
        lifecycleScope.launch {
            for (uri in uris) {
                // v8.4.1: 用 DISPLAY_NAME 取真实文件名（lastPathSegment 常为 content:// id 导致扩展名判断失败 → “未选择”）
                var name = "file"
                try {
                    requireContext().contentResolver.query(
                        uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
                    )?.use { c ->
                        if (c.moveToFirst()) {
                            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0 && !c.isNull(idx)) name = c.getString(idx)
                        }
                    }
                } catch (_: Exception) {}
                val b = withContext(Dispatchers.IO) {
                    try { requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (_: Exception) { null }
                }
                names.add(name); bytes.add(b)
            }
            // v8.4.1: 不再按扩展名过滤——交由 parseBytes 按内容识别（FIT 魔数/GPX 内容），避免误报“未选择”
            if (names.isEmpty()) { toast("未选择到 FIT/GPX 文件"); return@launch }
            processLocalFiles(names, bytes)
        }
    }

    private fun launchFilePicker() {
        try {
            filePicker.launch(arrayOf("*/*"))
        } catch (e: Exception) {
            toast("无法打开文件选择器：${e.message}")
        }
    }

    /** 处理本地文件：多选=合并 */
    private fun processLocalFiles(names: List<String>, bytesList: List<ByteArray?>) {
        if (names.isEmpty()) { toast("未选择文件"); return }
        tvMergeHint.text = "正在解析 ${names.size} 个本地文件..."
        btnSaveMerge.isEnabled = false
        lifecycleScope.launch {
            val groups = ArrayList<List<GpxToFitConverter.TrackPoint>>()
            var parsedSport = -1
            var failCount = 0
            var lastErrLocal = ""
            for (i in names.indices) {
                val bytes = bytesList.getOrNull(i) ?: run { failCount++; lastErrLocal = "文件读取失败"; continue }
                val pts = withContext(Dispatchers.Default) { parseBytes(bytes) }
                if (pts.isNullOrEmpty()) { failCount++; lastErrLocal = "「${names[i]}」$lastErrLocal"; continue }
                groups.add(pts)
                if (parsedSport < 0) parsedSport = TrackMerger.detectSportFromName(names[i])
            }
            btnSaveMerge.isEnabled = true
            if (groups.isEmpty()) {
                toast("所选文件均无法解析（$failCount 个失败）$lastErrLocal")
                tvMergeHint.text = "处理失败：$failCount 个文件均无法解析\n$lastErrLocal"
                return@launch
            }
            val isMerge = groups.size >= 2
            if (failCount > 0) tvMergeHint.text = "部分文件解析失败（${groups.size}/$names.size），继续处理剩余..."
            val result = try {
                withContext(Dispatchers.Default) {
                    TrackMerger.mergePointGroups(groups, forcedSport = if (!isMerge) parsedSport else -1)
                }
            } catch (e: Exception) {
                toast("处理失败：${e.message}"); return@launch
            }
            localMode = true
            mergedResult = result
            showPreview(result, groups.size)
        }
    }

    /** 解析 FIT/GPX；失败时返回 null，并附带可读原因 */
    private fun parseBytes(bytes: ByteArray): List<GpxToFitConverter.TrackPoint>? {
        return try {
            if (GpxToFitConverter.isFit(bytes)) {
                if (bytes.size >= 12) {
                    val declared = (bytes[4].toLong() and 0xFF) or ((bytes[5].toLong() and 0xFF) shl 8) or
                        ((bytes[6].toLong() and 0xFF) shl 16) or ((bytes[7].toLong() and 0xFF) shl 24)
                    val actual = (bytes.size - 14 - 2).coerceAtLeast(0)
                    if (declared > actual) {
                        lastErr = "FIT 文件不完整（头部声明 $declared 字节，实际只有 $actual 字节）——下载中断或文件损坏，请重新下载"
                        return null
                    }
                }
                val fr = FitParser.parse(bytes)
                if (fr == null) { lastErr = "FIT 文件无法解析（${bytes.size} 字节，可能不是标准 FIT）"; null }
                else if (fr.points.isEmpty()) { lastErr = "FIT 无有效轨迹点（${bytes.size} 字节）"; null }
                else fr.points
            } else {
                val pts = GpxToFitConverter.parseGpxBytes(bytes)
                if (pts.isEmpty()) { lastErr = "GPX 无有效轨迹点（${bytes.size} 字节）"; null } else pts
            }
        } catch (e: Exception) {
            lastErr = "解析异常：${e.message}"
            null
        }
    }

    private fun refreshSourceSelection() {
        for (i in 0 until gridMergeSource.childCount) {
            val b = gridMergeSource.getChildAt(i) as? PlatformButton ?: continue
            val tag = b.tag as? String ?: continue
            val ds = DataSource.fromShortName(tag)
            if (ds != null && !prefs.isLoggedIn(ds)) { b.setPlatformState(false, false); continue }
            if (tag == "local") b.setPlatformState(localMode, true)
            else b.setPlatformState((tag == source?.shortName) && !localMode, true)
        }
    }

    private fun platformColor(tag: String): Int = when (tag) {
        "igp" -> color(R.color.igp_green)
        "xz" -> color(R.color.xingzhe_blue)
        "mg" -> color(R.color.magene_blue)
        "bb" -> color(R.color.blackbird_green)
        "br" -> color(R.color.bryton_red)
        "gm", "gcn" -> color(R.color.garmin_blue)
        "cscn", "cs" -> color(R.color.coros_red)
        "wo" -> color(R.color.wahoo_red)
        "ob" -> color(R.color.outbase_orange)
        "mw" -> color(R.color.mywhoosh_orange)
        "zf" -> color(R.color.zwift_purple)
        "kp" -> color(R.color.keep_yellow)
        "cd" -> color(R.color.codoon_green)
        "zp" -> color(R.color.zepp_blue)
        "kt" -> color(R.color.komoot_red)
        "su" -> color(R.color.suunto_blue)
        else -> color(R.color.primary)
    }

    // ================= Step2 多选列表 =================
    private fun loadActivities(append: Boolean = false) {
        val src = source ?: run { toast("请先选择来源平台"); return }
        if (pageLoading) return
        pageLoading = true
        if (!append) {
            btnMergeNext.isEnabled = false
            btnMergeNext.text = "加载中..."
        }
        tvMergeHint.text = if (append) "正在加载更早记录..." else "正在获取 ${src.displayName} 活动列表..."
        val btnMore = view?.findViewById<TextView>(R.id.btnLoadMore)
        btnMore?.isEnabled = false
        lifecycleScope.launch {
            val res = withTimeoutOrNull(25_000) {
                withContext(Dispatchers.IO) {
                    (activity as? MainActivity)?.fetchMergeActivities(src, fromDate, toDate, pageSkip)
                }
            }
            pageLoading = false
            btnMergeNext.isEnabled = true
            btnMergeNext.text = "下一步：选择记录"
            btnMore?.isEnabled = true
            if (res == null) {
                tvMergeHint.text = "加载超时：网络不佳或平台响应慢"
                toast("加载超时（网络慢或平台响应慢），可重试；也可改用本地文件合并")
                return@launch
            }
            val records = res.records
            if (records.isEmpty()) {
                listHasMore = false
                btnMore?.visibility = View.GONE
                tvMergeHint.text = if (fromDate != null) "所选日期范围内没有记录" else "未获取到 ${src.displayName} 活动记录"
                if (!append) toast("未获取到活动记录：登录态可能失效或该平台接口异常；也可改用本地文件合并")
                applyDateFilter()
                return@launch
            }
            allRecords = if (append) (allRecords + records) else records
            pageSkip += records.size
            listHasMore = res.hasMore
            btnMore?.visibility = if (listHasMore) View.VISIBLE else View.GONE
            selectedIndexes.clear(); rangeMode = false; rangeAnchor = -1
            applyDateFilter()
            refreshDateFilterViews()
            if (!append) showStep(2)
        }
    }

    private fun pickDateFilter() {
        val cal = java.util.Calendar.getInstance()
        android.app.DatePickerDialog(requireContext(), { _, y, m, d ->
            val start = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)
            android.app.DatePickerDialog(requireContext(), { _, y2, m2, d2 ->
                val end = String.format(Locale.US, "%04d-%02d-%02d", y2, m2 + 1, d2)
                if (end < start) toast("结束日期不能早于开始日期")
                else {
                    fromDate = start; toDate = end
                    pageSkip = 0; listHasMore = false
                    refreshDateFilterViews()
                    loadActivities()
                }
            }, y, m, d).show()
        }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH)).show()
    }

    private fun refreshDateFilterViews() {
        val pick = view?.findViewById<TextView>(R.id.btnPickDate)
        val clear = view?.findViewById<TextView>(R.id.btnClearDate)
        if (fromDate == null) {
            pick?.text = "📅 日期范围"
            clear?.text = "最近记录"
            clear?.setTextColor(color(R.color.text_secondary))
        } else {
            pick?.text = "📅 ${fromDate!!.substring(5)} ~ ${toDate!!.substring(5)}"
            clear?.text = "✕ 清除"
            clear?.setTextColor(color(R.color.primary))
        }
    }

    private fun dayOf(rec: ActivityRecord): String = rec.startTime.take(10)

    private fun applyDateFilter() {
        val src = source ?: return
        val all = allRecords
        val filtered = if (fromDate == null) all else all.filter { val d = dayOf(it); d >= fromDate!! && d <= toDate!! }
        tvMergeHint.text = if (fromDate == null)
            "已加载最近 ${all.size} 条 · 点「📅 日期范围」可检索更早记录"
        else
            "${fromDate!!.substring(5)} ~ ${toDate!!.substring(5)} 共 ${filtered.size} 条"
        if (filtered.isEmpty()) {
            tvMergeSelected.text = "已选 0 条"
            mergeAdapter = MergeAdapter(filtered)
            rvMergeList.adapter = mergeAdapter
            btnDoMerge.isEnabled = false
            btnDoMerge.text = "合并"
            return
        }
        selectedIndexes.clear(); rangeMode = false; rangeAnchor = -1
        mergeAdapter = MergeAdapter(filtered)
        rvMergeList.adapter = mergeAdapter
        refreshSelectionHint()
    }

    private fun onItemClick(pos: Int) {
        if (rangeMode) {
            if (rangeAnchor < 0) {
                rangeAnchor = pos
                toast("起点已标记，请再点选终点")
            } else {
                val a = minOf(rangeAnchor, pos); val b = maxOf(rangeAnchor, pos)
                selectedIndexes.addAll(a..b)
                rangeMode = false; rangeAnchor = -1
                toast("已选择 ${b - a + 1} 条记录")
                updateRangeBtn()
            }
            mergeAdapter?.notifyDataSetChanged()
            refreshSelectionHint()
            return
        }
        if (!selectedIndexes.add(pos)) selectedIndexes.remove(pos)
        refreshSelectionHint()
    }

    private fun updateRangeBtn() {
        val btn = view?.findViewById<TextView>(R.id.btnMergeRange) ?: return
        btn.background = if (rangeMode) requireContext().getDrawable(R.drawable.merge_chip_white_on)
        else requireContext().getDrawable(R.drawable.merge_chip_white)
        btn.setTextColor(if (rangeMode) color(R.color.primary) else color(R.color.text_primary))
    }

    private fun selectToday() {
        val adapter = mergeAdapter ?: return
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        var added = 0
        for (i in adapter.records.indices) {
            val rec = adapter.records[i]
            if (rec.startTime.startsWith(today)) { if (selectedIndexes.add(i)) added++ }
        }
        if (added == 0) toast("今天没有记录，已选其他记录试试")
        refreshSelectionHint()
    }

    private fun toggleRangeMode() {
        rangeMode = !rangeMode
        rangeAnchor = -1
        toast(if (rangeMode) "连续多选模式：先点起点记录，再点终点记录" else "已退出连续多选")
        updateRangeBtn()
        mergeAdapter?.notifyDataSetChanged()
    }

    private fun clearSelection() { selectedIndexes.clear(); rangeMode = false; rangeAnchor = -1; refreshSelectionHint() }

    private fun refreshSelectionHint() {
        mergeAdapter?.notifyDataSetChanged()
        val n = selectedIndexes.size
        tvMergeHint.text = "共 ${mergeAdapter?.records?.size ?: 0} 条记录 · 点按勾选（多选合并）"
        tvMergeSelected.text = "已选 $n 条"
        btnDoMerge.text = when {
            n >= 2 -> "合并（$n 条）"
            n == 1 -> "生成单条（1 条）"
            else -> "合并"
        }
        btnDoMerge.isEnabled = n >= 1
    }

    // ================= Step3 合并执行 =================
    private fun doMerge() {
        val src = source ?: return
        val adapter = mergeAdapter ?: return
        val chosen = adapter.records.filterIndexed { i, _ -> i in selectedIndexes }
        if (chosen.isEmpty()) { toast("请至少选择 1 条记录"); return }
        val isMerge = chosen.size >= 2
        btnSaveMerge.isEnabled = false
        tvMergeHint.text = if (isMerge) "正在合并 ${chosen.size} 条记录..." else "正在处理 1 条记录..."
        lifecycleScope.launch {
            val groups = ArrayList<List<GpxToFitConverter.TrackPoint>>()
            val main = activity as? MainActivity ?: return@launch
            var parsedSport = -1
            var fitFail = 0
            var downloadFail = 0
            var lastErrMsg = ""
            for (rec in chosen) {
                val bytes = withContext(Dispatchers.IO) { main.downloadForMerge(src, rec) }
                if (bytes == null || bytes.isEmpty()) {
                    downloadFail++
                    lastErrMsg = "下载返回为空（登录态可能失效）"
                    continue
                }
                if (GpxToFitConverter.isFit(bytes)) {
                    val fr = FitParser.parse(bytes)
                    if (fr != null && fr.points.isNotEmpty()) {
                        groups.add(fr.points)
                        if (parsedSport < 0 && fr.sport > 0) parsedSport = fr.sport
                    } else {
                        fitFail++
                        lastErrMsg = "FIT 解析失败（${bytes.size} 字节，文件可能不完整或格式特殊）"
                    }
                } else {
                    try {
                        val pts = GpxToFitConverter.parseGpxBytes(bytes)
                        if (pts.isNotEmpty()) groups.add(pts)
                        else { fitFail++; lastErrMsg = "GPX 无有效轨迹点（${bytes.size} 字节，室内运动如跑步机无GPS轨迹，请选室外记录）" }
                    } catch (e: Exception) {
                        fitFail++
                        lastErrMsg = "GPX 解析异常：${e.message}"
                    }
                }
            }
            btnSaveMerge.isEnabled = true
            if (groups.isEmpty()) {
                val why = if (lastErrMsg.isNotEmpty()) "\n原因：$lastErrMsg" else ""
                tvMergeHint.text = "处理失败：下载失败 $downloadFail / 解析失败 $fitFail 条$why"
                toast("处理失败：无可解析的轨迹（下载失败 $downloadFail / 解析失败 $fitFail 条）$why")
                return@launch
            }
            if (groups.size < chosen.size && isMerge) {
                tvMergeHint.text = "部分记录无法解析（${groups.size}/${chosen.size}），继续处理剩余..."
            }
            val result = try {
                withContext(Dispatchers.Default) {
                    TrackMerger.mergePointGroups(groups, forcedSport = if (!isMerge) parsedSport else -1)
                }
            } catch (e: Exception) {
                toast("处理失败：${e.message}"); return@launch
            }
            mergedResult = result
            showPreview(result, chosen.size)
        }
    }

    private fun showPreview(result: TrackMerger.MergedResult, mergedCount: Int) {
        buildStats(result, mergedCount)
        mergeStepHint.text = if (mergedCount >= 2) "3/4 · $mergedCount 条已合并 → 1 条（可导出 GPX/FIT 或保存上传）" else "3/4 · 单条预览（可导出 GPX/FIT 或保存上传）"
        showStep(3)
    }

    private fun buildStats(result: TrackMerger.MergedResult, mergedCount: Int) {
        gridMergeStats.removeAllViews()
        fun addStat(label: String, value: String) {
            val box = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
            }
            box.addView(TextView(requireContext()).apply {
                text = label; textSize = 11f; setTextColor(color(R.color.text_secondary))
            })
            box.addView(TextView(requireContext()).apply {
                text = value; textSize = 15f; setTextColor(color(R.color.text_primary)); setTypeface(null, android.graphics.Typeface.BOLD)
            })
            gridMergeStats.addView(box, GridLayout.LayoutParams().apply { columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f) })
        }
        addStat("运动类型", TrackMerger.sportName(result.sport).let { when (it) { "running" -> "跑步"; "hiking" -> "徒步"; else -> "骑行" } })
        addStat(if (mergedCount >= 2) "合并段数" else "记录数", if (mergedCount >= 2) "$mergedCount 条 → 1 条" else "1 条（未合并）")
        addStat("总距离", String.format(Locale.US, "%.2f km", result.totalDistanceKm))
        addStat("总时长", formatDuration(result.totalDurationSec))
        addStat("累计爬升", String.format(Locale.US, "%.0f m", result.ascentM))
        addStat("平均心率", if (result.avgHr > 0) "${result.avgHr} bpm" else "—")
        addStat("平均踏频", if (result.avgCad > 0) "${result.avgCad} rpm" else "—")
        addStat("平均功率", if (result.avgPower > 0) "${result.avgPower} W" else "—")
    }

    private fun formatDuration(sec: Long): String {
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    private fun saveMerge() {
        val result = mergedResult ?: return
        val name = mergeFileName()
        val dir = File(MainActivity.SAVE_DIR, "合并")
        if (!dir.exists()) dir.mkdirs()
        val fit = try { GpxToFitConverter.convertPoints(result.points, result.sport) } catch (e: Exception) { toast("FIT 生成失败：${e.message}"); return }
        val file = File(dir, "$name.fit")
        try {
            file.writeBytes(fit)
            savedFile = file
            tvMergeResultTitle.text = "✅ 合并成功"
            tvMergeResultFile.text = "$name.fit"
            tvMergeResultPath.text = file.absolutePath
            refreshOutbaseCard()
            showStep(4)
            toast("已保存：${file.absolutePath}")
        } catch (e: Exception) {
            toast("保存失败：${e.message}")
        }
    }

    private fun exportMerge(exportFit: Boolean) {
        val result = mergedResult ?: return
        val dir = File(MainActivity.SAVE_DIR, "合并")
        if (!dir.exists()) dir.mkdirs()
        val name = mergeFileName()
        try {
            val data = if (exportFit) {
                GpxToFitConverter.convertPoints(result.points, result.sport)
            } else {
                TrackMerger.buildGpx(result, "merged-${TrackMerger.sportName(result.sport)}")
            }
            val ext = if (exportFit) "fit" else "gpx"
            val file = File(dir, "$name.$ext")
            file.writeBytes(data)
            toast("已导出：${file.absolutePath}")
        } catch (e: Exception) {
            toast("导出失败：${e.message}")
        }
    }

    private fun mergeFileName(): String {
        val src = if (localMode) null else source
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "${src?.shortName ?: "local"}_merged_$ts"
    }

    // ================= Step4 上传 Outbase =================
    private fun refreshOutbaseCard() {
        val logged = prefs.isLoggedIn(DataSource.OUTBASE)
        val username = prefs.getUsername(DataSource.OUTBASE)
        tvMergeOutbaseStatus.text = when {
            logged && !username.isNullOrBlank() -> "Outbase · ✅ 已登录 · $username"
            logged -> "Outbase · ✅ 已登录"
            else -> "Outbase · 未登录"
        }
        tvMergeOutbaseStatus.setTextColor(color(if (logged) R.color.green else R.color.outbase_orange))
        btnUploadMerge.text = if (logged) "上传到 Outbase" else "请先登录 Outbase"
    }

    private fun uploadMerge() {
        val file = savedFile ?: run { toast("尚未保存合并文件"); return }
        if (!prefs.isLoggedIn(DataSource.OUTBASE)) {
            toast("请先到登录页登录 Outbase")
            refreshOutbaseCard()
            return
        }
        val engine = com.jichi.ob.api.UploadEngine(requireContext())
        val result = mergedResult ?: return
        btnUploadMerge.isEnabled = false
        btnUploadMerge.text = "上传中..."
        lifecycleScope.launch {
            val cred = prefs.getCredential(DataSource.OUTBASE)
            val record = ActivityRecord(
                id = "merged",
                title = file.nameWithoutExtension,
                startTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(result.startTime * 1000)),
                distance = result.totalDistanceKm,
                duration = result.totalDurationSec.toInt(),
                source = source ?: DataSource.IGPSPORT
            )
            val r = if (cred != null) withContext(Dispatchers.IO) { engine.upload(DataSource.OUTBASE, cred, file.readBytes(), record) }
            else null
            btnUploadMerge.isEnabled = true
            btnUploadMerge.text = "上传到 Outbase"
            if (r != null && r.success) {
                tvMergeResultTitle.text = "✅ 合并 + 上传 Outbase 完成"
                toast("已上传到 Outbase")
            } else {
                tvMergeResultTitle.text = "✅ 合并成功（上传失败，可稍后重试）"
                toast("上传失败：${r?.message ?: "登录态无效"}")
            }
        }
    }

    // ================= Adapter =================
    inner class MergeAdapter(val records: List<ActivityRecord>) : RecyclerView.Adapter<MergeAdapter.VH>() {
        private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)
        private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

        private fun parseTs(s: String): Long {
            return try {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(s)?.time
                    ?: isoFmt.parse(s)?.time
                    ?: 0L
            } catch (_: Exception) {
                try { isoFmt.parse(s)?.time ?: 0L } catch (_: Exception) { 0L }
            }
        }

        inner class VH(
            val root: LinearLayout,
            val dot: View,
            val tvTitle: TextView,
            val tvMeta: TextView,
            val tvCheck: TextView
        ) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val root = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt())
                background = itemBg(false)
                layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
            }
            val dot = View(parent.context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(platformColor(source?.shortName ?: "")) }
                layoutParams = LinearLayout.LayoutParams((10 * dp).toInt(), (10 * dp).toInt()).apply { marginEnd = (10 * dp).toInt() }
            }
            val textBox = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val title = TextView(parent.context).apply {
                textSize = 14f; maxLines = 1
                setTextColor(color(R.color.text_primary)); setTypeface(null, android.graphics.Typeface.BOLD)
            }
            val meta = TextView(parent.context).apply {
                textSize = 11f; setTextColor(color(R.color.text_secondary))
            }
            textBox.addView(title); textBox.addView(meta)
            val check = TextView(parent.context).apply {
                textSize = 20f
                setTextColor(color(R.color.primary))
            }
            root.addView(dot); root.addView(textBox); root.addView(check)
            return VH(root, dot, title, meta, check)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val rec = records[position]
            val ts = parseTs(rec.startTime)
            holder.tvTitle.text = if (rec.title.isBlank()) "未命名运动" else rec.title
            val day = if (ts > 0) dayFmt.format(Date(ts)) else rec.startTime.take(10)
            val t = if (ts > 0) fmt.format(Date(ts)) else "--:--"
            val dur = if (rec.duration > 0) formatDuration(rec.duration.toLong()) else "--:--"
            val dist = if (rec.distance > 0) String.format(Locale.US, "%.2f km", rec.distance) else "--"
            holder.tvMeta.text = "$day $t · $dist · $dur"
            val selected = position in selectedIndexes
            val isAnchor = rangeMode && position == rangeAnchor
            holder.tvCheck.text = when {
                isAnchor -> "①"
                selected -> "☑"
                else -> "○"
            }
            holder.tvCheck.setTextColor(if (selected || isAnchor) color(R.color.primary) else color(R.color.text_secondary))
            holder.root.background = itemBg(selected, isAnchor)
            holder.root.setOnClickListener { onItemClick(position) }
        }

        override fun getItemCount() = records.size

        private fun itemBg(selected: Boolean, isAnchor: Boolean = false): GradientDrawable {
            val g = GradientDrawable().apply {
                cornerRadius = 14 * dp
                setColor(if (selected || isAnchor) 0xFFEAF4FF.toInt() else Color.WHITE)
            }
            when {
                isAnchor -> g.setStroke((2 * dp).toInt(), 0xFFFC4C02.toInt())
                selected -> g.setStroke((2 * dp).toInt(), platformColor(source?.shortName ?: ""))
                else -> g.setStroke((1 * dp).toInt(), 0xFFE8EEF4.toInt())
            }
            return g
        }
    }
}
