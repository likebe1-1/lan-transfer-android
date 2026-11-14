package com.manst.lantransfer.core

import android.content.Context

/** 应用级本地偏好设置（首次启动提示等）。 */
class AppPreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** 是否已经展示过「请在你信任的局域网下使用」的首次启动提示。 */
    var trustedLanNoticeShown: Boolean
        get() = prefs.getBoolean(KEY_TRUSTED_LAN_NOTICE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_TRUSTED_LAN_NOTICE, value).apply()
        }

    /** 测试用：重置所有本地偏好。 */
    fun resetAll() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val FILE_NAME = "app_preferences"
        const val KEY_TRUSTED_LAN_NOTICE = "trusted_lan_notice_shown"
    }
}
