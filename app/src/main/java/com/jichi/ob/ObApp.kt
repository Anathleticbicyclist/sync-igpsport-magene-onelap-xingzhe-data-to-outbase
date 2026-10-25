package com.jichi.ob

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.jichi.ob.util.PrefsManager

/**
 * v9.4.0: 应用入口，启动时应用深色模式设置（0 跟随系统 / 1 浅色 / 2 深色）。
 * 对齐开发版 v9.2.9 深色模式三态开关。
 */
class ObApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val mode = PrefsManager(this).getDarkMode()
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                1 -> AppCompatDelegate.MODE_NIGHT_NO
                2 -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }
}
