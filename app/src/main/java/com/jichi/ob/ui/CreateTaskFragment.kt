package com.jichi.ob.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.jichi.ob.MainActivity
import com.jichi.ob.R
import com.jichi.ob.model.DataSource
import com.jichi.ob.model.SyncTask
import com.jichi.ob.util.PrefsManager
import java.util.UUID

/**
 * v8.4.0: 新建同步任务——两步向导（来源 → 参数）
 * 正式版精简版：目标固定 Outbase（不可取消），多来源可多选（多对一）；
 * 保存后写入 PrefsManager 任务列表，同步页任务区即时显示。
 */
class CreateTaskFragment : Fragment() {

    private lateinit var prefs: PrefsManager
    private lateinit var containerStep: LinearLayout
    private lateinit var tvTitle: TextView
    private lateinit var tvStep1: TextView
    private lateinit var tvStep2: TextView
    private lateinit var tvStep3: TextView
    private lateinit var btnPrev: MaterialButton
    private lateinit var btnNext: MaterialButton

    private var step = 0
    private val selectedSources = LinkedHashSet<DataSource>()
    private var taskName = ""
    private var incremental = true
    private var count = 200
    private var skip = 0
    private var force = false
    private var coordinateConvert = true
    private var autoSync = false
    private var autoIntervalSec = 900

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_create_task, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = PrefsManager(requireContext())
        containerStep = view.findViewById(R.id.containerStep)
        tvTitle = view.findViewById(R.id.tvTitle)
        tvStep1 = view.findViewById(R.id.tvStep1)
        tvStep2 = view.findViewById(R.id.tvStep2)
        tvStep3 = view.findViewById(R.id.tvStep3)
        btnPrev = view.findViewById(R.id.btnPrev)
        btnNext = view.findViewById(R.id.btnNext)

        view.findViewById<TextView>(R.id.tvBack).setOnClickListener {
            (activity as? MainActivity)?.closeCreateTask()
        }
        btnPrev.setOnClickListener { if (step > 0) { step--; render() } }
        btnNext.setOnClickListener { onNext() }

        render()
    }

    private fun onNext() {
        when (step) {
            0 -> {
                if (selectedSources.isEmpty()) {
                    Toast.makeText(requireContext(), "请至少选择一个来源平台", Toast.LENGTH_SHORT).show()
                    return
                }
                step = 1; render()
            }
            1 -> saveTask()
        }
    }

    private fun saveTask() {
        if (selectedSources.isEmpty()) return
        val name = if (taskName.isBlank()) {
            "${selectedSources.first().displayName} → Outbase"
        } else taskName.trim()
        val task = SyncTask(
            id = UUID.randomUUID().toString(),
            name = name,
            sources = selectedSources.map { it.shortName },
            targets = listOf(DataSource.OUTBASE.shortName),
            count = count,
            skip = skip,
            incremental = incremental,
            force = force,
            coordinateConvert = coordinateConvert,
            autoSync = autoSync,
            autoIntervalSec = autoIntervalSec,
            enabled = true
        )
        prefs.upsertTask(task)
        Toast.makeText(requireContext(), "任务「$name」已创建", Toast.LENGTH_SHORT).show()
        (activity as? MainActivity)?.let {
            it.closeCreateTask()
            it.refreshTaskUi()
        }
    }

    private fun render() {
        containerStep.removeAllViews()
        when (step) {
            0 -> renderStep1()
            1 -> renderStep2()
        }
        tvStep1.setTextColor(requireContext().getColor(if (step == 0) R.color.primary else R.color.green))
        tvStep2.setTextColor(requireContext().getColor(if (step == 1) R.color.primary else R.color.text_secondary))
        tvStep3.visibility = View.GONE
        btnPrev.isEnabled = step > 0
        btnPrev.alpha = if (step > 0) 1f else 0.4f
        btnNext.text = if (step == 1) "保存任务" else "下一步"
        tvTitle.text = if (step == 0) "选择同步来源" else "任务参数"
    }

    // ===== 平台选择网格（每行4个）=====
    private fun renderPlatformGrid(
        platforms: List<DataSource>,
        selected: LinkedHashSet<DataSource>,
        canSelect: (DataSource) -> Boolean,
        onToggle: (DataSource) -> Unit
    ) {
        for (i in platforms.indices step 4) {
            val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
            val chunk = platforms.subList(i, minOf(i + 4, platforms.size))
            for (ds in chunk) {
                val card = platformChip(ds, selected.contains(ds), canSelect(ds), onToggle)
                row.addView(card, LinearLayout.LayoutParams(0, dpInt(52f), 1f).apply {
                    marginStart = dpInt(2f); marginEnd = dpInt(2f); topMargin = dpInt(2f); bottomMargin = dpInt(2f)
                })
            }
            containerStep.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun platformChip(
        ds: DataSource, isSelected: Boolean, selectable: Boolean,
        onToggle: (DataSource) -> Unit
    ): MaterialCardView {
        val card = MaterialCardView(requireContext()).apply {
            radius = dpInt(10f).toFloat()
            elevation = 0f
            strokeWidth = dpInt(1f)
            setStrokeColor(requireContext().getColor(
                when {
                    !selectable -> R.color.text_secondary
                    isSelected -> R.color.primary
                    else -> R.color.text_secondary
                }
            ))
            setCardBackgroundColor(
                when {
                    !selectable -> requireContext().getColor(R.color.bg_light)
                    isSelected -> requireContext().getColor(R.color.primary_light)
                    else -> android.graphics.Color.WHITE
                }
            )
            isClickable = selectable
            setOnClickListener { if (selectable) onToggle(ds) }
        }
        val tv = TextView(requireContext()).apply {
            text = ds.displayName + if (!selectable) " ·未登录" else ""
            textSize = 11f
            gravity = android.view.Gravity.CENTER
            setTextColor(requireContext().getColor(if (selectable && isSelected) R.color.primary else R.color.text_secondary))
            maxLines = 1
        }
        card.addView(tv)
        return card
    }

    private fun sectionTitle(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        textSize = 14f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(requireContext().getColor(R.color.text_primary))
        setPadding(0, dpInt(10f), 0, dpInt(2f))
    }

    private fun sectionHint(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        textSize = 12f
        setTextColor(requireContext().getColor(R.color.text_secondary))
        setPadding(0, dpInt(8f), 0, dpInt(8f))
    }

    // ===== 第一页：选择同步来源（多选） =====
    private fun renderStep1() {
        containerStep.addView(sectionTitle("同步来源（可多选，合并上传到 Outbase）"))
        val srcPlatforms = DataSource.sourcePlatforms().filter { prefs.isLoggedIn(it) }
        renderPlatformGrid(srcPlatforms, selectedSources,
            canSelect = { true },
            onToggle = { ds ->
                if (!selectedSources.add(ds)) selectedSources.remove(ds)
                rerenderKeepScroll()
            }
        )
        // 固定目标卡：Outbase
        containerStep.addView(sectionTitle("同步目标（固定）"))
        val obRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        val obCard = platformChip(DataSource.OUTBASE, true, false) { }
        obRow.addView(obCard, LinearLayout.LayoutParams(0, dpInt(52f), 1f).apply {
            marginStart = dpInt(2f); marginEnd = dpInt(2f); topMargin = dpInt(2f); bottomMargin = dpInt(2f)
        })
        containerStep.addView(obRow)
        containerStep.addView(sectionHint("正式版固定同步目标：Outbase（不可取消）"))
        // 底部固定一行摘要
        val srcNames = if (selectedSources.isEmpty()) "未选择" else selectedSources.map { it.displayName }.joinToString("、")
        containerStep.addView(sectionHint("已选：$srcNames  →  Outbase"))
        if (selectedSources.contains(DataSource.MAGENE)) {
            containerStep.addView(sectionHint("⚙️ 迈金 FIT 为 GCJ-02 坐标，下一步可开启「坐标转换」"))
        }
    }

    // ===== 第二页：任务参数 =====
    private fun renderStep2() {
        containerStep.addView(sectionTitle("任务参数（来源：${selectedSources.map { it.displayName }.joinToString("、")} → Outbase）"))
        // 任务名
        val nameBox = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dpInt(6f), 0, dpInt(2f))
        }
        nameBox.addView(TextView(requireContext()).apply {
            text = "任务名"
            textSize = 13f
            setTextColor(requireContext().getColor(R.color.text_primary))
        }, LinearLayout.LayoutParams(dpInt(64f), LinearLayout.LayoutParams.WRAP_CONTENT))
        val nameInput = android.widget.EditText(requireContext()).apply {
            hint = "如：晨骑同步到Outbase"
            setText(taskName)
            textSize = 13f
            setBackgroundResource(R.drawable.input_box_bg)
            setPadding(dpInt(8f), dpInt(6f), dpInt(8f), dpInt(6f))
        }
        nameBox.addView(nameInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        containerStep.addView(nameBox)
        nameInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { taskName = s?.toString() ?: "" }
        })

        containerStep.addView(SwitchMaterial(requireContext()).apply {
            text = "仅同步最新数据（缓存库中不存在的记录）"
            textSize = 13f
            isChecked = incremental
            setOnCheckedChangeListener { _, checked -> incremental = checked }
        })
        if (selectedSources.contains(DataSource.MAGENE)) {
            containerStep.addView(SwitchMaterial(requireContext()).apply {
                text = "迈金坐标转换（GCJ-02 → WGS84）"
                textSize = 13f
                isChecked = coordinateConvert
                setOnCheckedChangeListener { _, checked -> coordinateConvert = checked }
            })
            containerStep.addView(sectionHint("迈金 FIT 坐标为 GCJ-02（高德系），建议开启转为 WGS-84 后上传"))
        }
        containerStep.addView(SwitchMaterial(requireContext()).apply {
            text = "自动同步（后台定时运行，需开启全局自动同步）"
            textSize = 13f
            isChecked = autoSync
            setOnCheckedChangeListener { _, checked -> autoSync = checked }
        })
        containerStep.addView(paramSliderRow("同步数量", count, 1, 1000) { count = it })
        containerStep.addView(paramSliderRow("跳过前N条", skip, 0, 10000) { skip = it })
        containerStep.addView(SwitchMaterial(requireContext()).apply {
            text = "忽略上传记忆，强制重传"
            textSize = 13f
            isChecked = force
            setOnCheckedChangeListener { _, checked -> force = checked }
        })
        containerStep.addView(sectionHint("💡 增量模式：依据记录缓存，自动跳过已同步的旧记录，只同步最新数据"))
    }

    private fun paramSliderRow(label: String, initValue: Int, min: Int, max: Int, onChange: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dpInt(4f), 0, dpInt(2f))
        }
        row.addView(TextView(requireContext()).apply {
            text = label
            textSize = 13f
            setTextColor(requireContext().getColor(R.color.text_primary))
        }, LinearLayout.LayoutParams(dpInt(90f), LinearLayout.LayoutParams.WRAP_CONTENT))
        val slider = com.google.android.material.slider.Slider(requireContext()).apply {
            valueFrom = min.toFloat(); valueTo = max.toFloat(); stepSize = 1f; value = initValue.toFloat()
        }
        val tv = TextView(requireContext()).apply {
            text = initValue.toString()
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            setTextColor(requireContext().getColor(R.color.primary))
        }
        slider.addOnChangeListener { _, v, _ -> tv.text = v.toInt().toString(); onChange(v.toInt()) }
        row.addView(slider, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(tv, LinearLayout.LayoutParams(dpInt(44f), LinearLayout.LayoutParams.WRAP_CONTENT))
        return row
    }

    /** 点选后重绘整页并恢复滚动位置 */
    private fun rerenderKeepScroll() {
        val sv = view?.findViewById<android.widget.ScrollView>(R.id.scrollStep)
        val sy = sv?.scrollY ?: 0
        render()
        sv?.post { sv.scrollTo(0, sy) }
    }

    private fun dpInt(v: Float): Int = (v * resources.displayMetrics.density).toInt()
}
