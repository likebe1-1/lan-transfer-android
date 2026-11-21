package com.manst.lantransfer.update

/** 更新清单里的一个 APK 产物（按 ABI 选择，universal 作为兜底）。 */
data class UpdateApk(
    /** arm64-v8a / armeabi-v7a / x86_64 / x86 / universal */
    val abi: String,
    val size: Long,
    val sha256: String,
    /** 候选下载地址（GitHub 直链在前，镜像在代码中自动补充） */
    val urls: List<String>
)

/** 从远端 update.json 解析出的更新信息。 */
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val changelog: String,
    val apks: List<UpdateApk>,
    val releasePageUrl: String? = null,
    val lanzouUrl: String? = null,
    val lanzouPassword: String? = null
)

/** 当前设备可安装的更新（已按 ABI 选出 APK）。 */
data class UpdateInfo(
    val manifest: UpdateManifest,
    val apk: UpdateApk
)

/** 更新检查/下载界面状态。 */
data class UpdateUiState(
    val checking: Boolean = false,
    val info: UpdateInfo? = null,
    val downloading: Boolean = false,
    /** 0f ~ 1f，总大小未知时为 -1f */
    val progress: Float = -1f,
    val downloadedBytes: Long = 0L,
    val error: String? = null,
    /** 下载/校验完成，正在拉起系统安装器 */
    val installing: Boolean = false,
    /** 用户点了「稍后」，本次进程内不再自动弹出 */
    val dismissed: Boolean = false
) {
    val hasUpdate: Boolean get() = info != null
}

/** 更新清单来源（多镜像依次尝试，国内网络下 GitHub 直连可能失败）。 */
object UpdateSources {

    /**
     * Gitee 的 CDN 对部分 UA（如 Dart/OkHttp）会把 raw 请求重定向到广告页；
     * App 侧请求 Gitee 源时使用原生域名，避免被广告中间页污染。
     */
    const val GITEE_HOST = "https://gitee.com"
    /**
     * update.json 的多个来源（全部 HTTPS）。
     * 清单必须带 Ed25519 签名（见 [UpdateManifestVerifier]），因此即使经过第三方镜像也能保证完整性。
     */
    val manifestUrls: List<String> = listOf(
        "https://gitee.com/likebe1-1/lanlink-android/raw/master/update.json",
        "https://cdn.jsdelivr.net/gh/likebe1-1/lan-transfer-android@master/docs/update.json",
        "https://raw.githubusercontent.com/likebe1-1/lan-transfer-android/master/docs/update.json",
        "https://ghproxy.net/https://raw.githubusercontent.com/likebe1-1/lan-transfer-android/master/docs/update.json"
    )

    /** GitHub 下载地址的镜像前缀（按顺序尝试；空串表示直连）。 */
    val mirrorPrefixes: List<String> = listOf(
        "",
        "https://ghproxy.net/",
        "https://gh-proxy.com/"
    )

    const val RELEASE_PAGE_URL = "https://github.com/likebe1-1/lan-transfer-android/releases"

    /** 内置兜底蓝奏云链接（清单里也可覆盖）。 */
    const val LANZOU_URL = "https://wwbds.lanzoum.com/b0139vopuf"
    const val LANZOU_PASSWORD = "1234"
}
