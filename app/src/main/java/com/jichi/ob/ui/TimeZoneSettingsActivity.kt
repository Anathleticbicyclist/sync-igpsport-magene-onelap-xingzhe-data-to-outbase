package com.jichi.ob.ui

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.jichi.ob.R

/**
 * 时区修正设置页（v9.2.0 移植自开发版 v9.2.2 TimeZoneSettingsActivity）
 *
 * 针对上传到 Outbase 的各平台运动数据进行时区调整：Outbase 按 UTC 解析，
 * 部分平台（如 iGPSPORT 源）历史数据可能整体偏 8 小时。
 * 每个平台可单独设置「当天 / 历史」的调整方向：+8 小时 / -8 小时 / 不调整。
 *
 * 存储：SharedPreferences("jichi_ob_prefs")，key = 平台枚举名 + ("_same_day" / "_history")，
 * 值 1=+8 小时、2=-8 小时、0=不调整（默认）。
 */
class TimeZoneSettingsActivity : AppCompatActivity(), View.OnClickListener,
    RadioGroup.OnCheckedChangeListener {

    private lateinit var prefs: SharedPreferences

    /** 正式版数据来源（9 个，均为 UploadEngine 可能的上传来源） */
    private val platformNames = arrayOf(
        "迈金", "佳明国际", "佳明中国", "Keep", "行者",
        "黑鸟", "高驰中国", "高驰国际", "Wahoo"
    )
    private val platformKeys = arrayOf(
        "MAGENE", "GARMIN_COM", "GARMIN_CN", "KEEP", "XINGZHE",
        "BLACKBIRD", "COROS_CN", "COROS_INT", "WAHOO"
    )

    override fun onClick(v: View?) {
        finish()
    }

    override fun onCheckedChanged(group: RadioGroup, checkedId: Int) {
        val tag = group.tag as? String ?: return
        val checked = group.findViewById<View>(checkedId) ?: return
        val value = checked.tag as? Int ?: return
        prefs.edit().putInt(tag, value).apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_timezone_settings)

        prefs = getSharedPreferences("jichi_ob_prefs", 0)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener(this)

        val container = findViewById<LinearLayout>(R.id.containerTimezone)

        // 说明文字
        val desc = TextView(this).apply {
            text = "针对上传到 Outbase 的各平台运动数据进行时区调整。Outbase 服务器按 UTC 解析，" +
                    "部分平台历史数据可能偏8小时。每个平台可单独设置当天/历史的调整方向。"
            textSize = 13f
            setTextColor(0xFF666666.toInt())
        }
        container.addView(desc, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 24 })

        // 11→9 平台循环
        for (i in platformKeys.indices) {
            val name = platformNames[i]
            val key = platformKeys[i]

            // 平台标题
            val title = TextView(this).apply {
                text = name
                textSize = 15f
                setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                setTextColor(0xFF333333.toInt())
            }
            container.addView(title, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 })

            // 当天 RadioGroup
            val sameDayLabel = TextView(this).apply {
                text = "当天："
                textSize = 12f
            }
            container.addView(sameDayLabel, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 })

            val sameDayGroup = RadioGroup(this).apply {
                orientation = RadioGroup.HORIZONTAL
                tag = key + "_same_day"
            }
            addRadioButtons(sameDayGroup)
            val sameDaySetting = prefs.getInt(key + "_same_day", 0)
            selectRadio(sameDayGroup, sameDaySetting)
            sameDayGroup.setOnCheckedChangeListener(this)
            container.addView(sameDayGroup, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))

            // 历史 RadioGroup
            val historyLabel = TextView(this).apply {
                text = "历史："
                textSize = 12f
            }
            container.addView(historyLabel, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 4 })

            val historyGroup = RadioGroup(this).apply {
                orientation = RadioGroup.HORIZONTAL
                tag = key + "_history"
            }
            addRadioButtons(historyGroup)
            val historySetting = prefs.getInt(key + "_history", 0)
            selectRadio(historyGroup, historySetting)
            historyGroup.setOnCheckedChangeListener(this)
            container.addView(historyGroup, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))

            // 分隔线
            val divider = View(this).apply {
                setBackgroundColor(0xFFEEEEEE.toInt())
            }
            container.addView(divider, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { topMargin = 16; bottomMargin = 8 })
        }
    }

    private fun addRadioButtons(group: RadioGroup) {
        val plus = RadioButton(this).apply {
            text = "+8小时"
            textSize = 12f
            tag = 1
        }
        group.addView(plus)
        val minus = RadioButton(this).apply {
            text = "-8小时"
            textSize = 12f
            tag = 2
        }
        group.addView(minus)
        val none = RadioButton(this).apply {
            text = "不调整"
            textSize = 12f
            tag = 0
        }
        group.addView(none)
    }

    /** 按设置值选中对应 RadioButton（1=+8, 2=-8, 0=不调整） */
    private fun selectRadio(group: RadioGroup, setting: Int) {
        for (i in 0 until group.childCount) {
            val rb = group.getChildAt(i)
            if (rb.tag == setting) {
                (rb as RadioButton).isChecked = true
                return
            }
        }
    }
}
