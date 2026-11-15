package com.manst.lantransfer.update

import kotlinx.coroutines.flow.StateFlow

/**
 * 应用内更新：检查 update.json（GitHub/镜像多源）+ 下载 APK + 拉起系统安装。
 * 下载失败或国内网络不可用时，界面提供蓝奏云浏览器下载兜底。
 */
interface UpdateManager {
    val state: StateFlow<UpdateUiState>

    /** 检查更新；auto=true 时静默失败（不打扰用户），manual 时失败展示错误。 */
    fun check(auto: Boolean = false)

    /** 下载当前更新并拉起安装（已有校验通过的缓存 APK 时直接安装）。 */
    fun downloadAndInstall()

    /** 浏览器打开蓝奏云备用下载页（页面需要密码，界面已展示密码）。 */
    fun openLanzou()

    /** 浏览器打开 GitHub Releases 页面。 */
    fun openReleasePage()

    /** 关闭更新对话框（本次进程内不再自动弹出）。 */
    fun dismiss()

    fun clearError()
}
