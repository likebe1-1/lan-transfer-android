package com.manst.lantransfer.update

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.manst.lantransfer.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 应用内更新默认实现：
 * - 从 [UpdateSources.manifestUrls] 多源拉取 update.json（jsDelivr / GitHub raw / ghproxy 镜像）；
 * - 下载 APK 时对 GitHub 地址自动拼接镜像前缀轮询；
 * - 下载后校验大小、SHA-256、APK 签名（必须与已安装应用一致），再拉起系统安装器；
 * - 失败时保留错误状态，界面可引导使用蓝奏云浏览器下载。
 */
class DefaultUpdateManager(
    context: Context,
    /**
     * 是否允许应用内更新：调试版（debug）安装包用 debug 证书签名，
     * 与 Release APK 的签名不一致，系统/校验都会拒绝覆盖安装。
     * 因此 debug 版不提供更新能力，只提示用户安装正式版。
     */
    /**
     * 是否允许应用内更新：
     * - debug 包永远关闭（debug 证书与 release 不同，无法覆盖安装）；
     * - fdroid 渠道关闭（更新由 F-Droid/IzzyOnDroid 客户端负责，政策要求）；
     * - github 渠道保留（官网/GitHub 分发，应用内更新是核心体验）。
     */
    private val allowUpdates: Boolean = !BuildConfig.DEBUG && BuildConfig.IN_APP_UPDATES
) : UpdateManager {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(UpdateUiState())

    init {
        // 让更新清单拉取过程的关键拒绝原因进入 logcat，便于排查（非 HTTPS / 签名无效 / 未签名）
        UpdateManifestFetcher.logger = { message -> Log.w(TAG, message) }
    }

    override val state: StateFlow<UpdateUiState> = mutableState.asStateFlow()

    private val packageManager get() = appContext.packageManager

    private val supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList()

    /** 自动检查进行中用户又点了「检查更新」：等本次结束后按手动方式展示错误。 */
    @Volatile
    private var manualCheckRequested = false

    override fun check(auto: Boolean) {
        if (!allowUpdates) {
            if (!auto) {
                mutableState.update {
                    it.copy(
                        checking = false,
                        info = null,
                        dismissed = false,
                        error = if (BuildConfig.DEBUG) {
                            "当前是调试版，不支持应用内更新。请安装正式版（com.manst.lantransfer），或在设置中关闭调试包后重试。"
                        } else {
                            "当前版本由应用商店分发，请在 F-Droid / IzzyOnDroid 客户端中更新；也可从官网手动下载。"
                        }
                    )
                }
            }
            Log.i(TAG, "调试版跳过应用内更新检查")
            return
        }
        if (mutableState.value.downloading) return
        if (mutableState.value.checking) {
            if (!auto) manualCheckRequested = true
            return
        }
        if (!auto) {
            manualCheckRequested = false
            mutableState.update { it.copy(dismissed = false) }
        }
        mutableState.update { it.copy(checking = true, error = null) }
        scope.launch {
            val diagnostics = java.util.Collections.synchronizedList(mutableListOf<String>())
            val found = fetchManifestInParallel(diagnostics)
            Log.i(TAG, "更新清单检查结果：" + if (found == null) "全部来源失败" else found.manifest.versionName)
            val currentCode = installedVersionCode()
            val newer = found != null && UpdateManifestParser.isNewer(found.manifest.versionCode, currentCode)
            val manualPending = manualCheckRequested
            manualCheckRequested = false
            val shouldShowError = found == null && (!auto || manualPending)
            mutableState.update {
                it.copy(
                    checking = false,
                    info = if (newer) found else null,
                    error = if (shouldShowError) "检查更新失败，请稍后重试，或使用蓝奏云下载" else null
                )
            }
        }
    }

    override fun downloadAndInstall() {
        if (!allowUpdates) {
            mutableState.update {
                it.copy(error = if (BuildConfig.DEBUG) "调试版不支持应用内更新，请安装正式版" else "当前版本由应用商店分发，请在商店客户端中更新")
            }
            return
        }
        val info = mutableState.value.info ?: return
        if (mutableState.value.downloading) return
        mutableState.update {
            it.copy(downloading = true, progress = -1f, downloadedBytes = 0L, error = null, installing = false)
        }
        scope.launch {
            val apk = info.apk
            val target = File(targetDir(), "LanTransfer-" + info.manifest.versionName + "-" + apk.abi + ".apk")
            val cached = target.isFile && target.length() == apk.size && sha256(target) == apk.sha256
            if (!cached) {
                target.delete()
                var success = false
                for (candidate in candidateUrls(apk)) {
                    Log.i(TAG, "尝试下载更新包：" + candidate)
                    if (downloadTo(candidate, target, apk.size)) {
                        success = true
                        break
                    }
                    target.delete()
                }
                if (!success) {
                    mutableState.update {
                        it.copy(
                            downloading = false,
                            progress = -1f,
                            error = "下载失败，请检查网络后重试，或使用蓝奏云下载"
                        )
                    }
                    return@launch
                }
            }
            val hashOk = sha256(target) == apk.sha256
            if (!hashOk) {
                target.delete()
                mutableState.update { it.copy(downloading = false, error = "安装包校验失败（SHA-256 不一致），已取消安装") }
                return@launch
            }
            if (!signatureMatches(target)) {
                target.delete()
                mutableState.update { it.copy(downloading = false, error = "安装包签名校验失败，已取消安装") }
                return@launch
            }
            mutableState.update { it.copy(downloading = false, progress = 1f, installing = true) }
            withContext(Dispatchers.Main) { startInstaller(target, info) }
        }
    }

    override fun openLanzou() {
        val info = mutableState.value.info
        val url = info?.manifest?.lanzouUrl ?: UpdateSources.LANZOU_URL
        val password = info?.manifest?.lanzouPassword ?: UpdateSources.LANZOU_PASSWORD
        runCatching {
            (appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(
                ClipData.newPlainText("蓝奏云密码", password)
            )
        }
        openBrowser(url)
    }

    override fun openReleasePage() {
        openBrowser(mutableState.value.info?.manifest?.releasePageUrl ?: UpdateSources.RELEASE_PAGE_URL)
    }

    override fun dismiss() {
        mutableState.update { it.copy(info = null, dismissed = true, error = null, progress = -1f) }
    }

    override fun clearError() {
        mutableState.update { it.copy(error = null) }
    }

    // ------------------------------------------------------------ 网络与校验

    /**
     * 多源并发拉取清单：任一来源成功即可，全部失败最多等约一个超时周期。
     * 串行拉取在离线/被墙时可能累积 4 个来源 × 超时时间，导致「正在检查更新」停留过久。
     */
    private fun fetchManifestInParallel(diagnostics: MutableList<String>): UpdateInfo? {
        val urls = UpdateSources.manifestUrls
        val result = java.util.concurrent.atomic.AtomicReference<UpdateInfo?>()
        val latch = java.util.concurrent.CountDownLatch(urls.size)
        urls.forEach { url ->
            Thread {
                try {
                    val info = UpdateManifestFetcher.fetchFirst(
                        urls = listOf(url),
                        supportedAbis = supportedAbis,
                        connectTimeoutMillis = 4_000,
                        readTimeoutMillis = 4_000,
                        onDiagnostic = { reason -> diagnostics.add(reason) }
                    )
                    if (info != null) result.compareAndSet(null, info)
                } catch (e: Exception) {
                    Log.w(TAG, "并发获取更新清单失败：" + url, e)
                } finally {
                    latch.countDown()
                }
            }.apply {
                isDaemon = true
                name = "update-manifest-fetch"
                start()
            }
        }
        latch.await()
        return result.get()
    }

    private fun candidateUrls(apk: UpdateApk): List<String> {
        val result = LinkedHashSet<String>()
        for (url in apk.urls) {
            for (prefix in UpdateSources.mirrorPrefixes) {
                val candidate = if (prefix.isEmpty()) url else prefix + url
                if (candidate.startsWith("https://") || candidate.startsWith("http://")) result.add(candidate)
            }
        }
        return result.toList()
    }

    private fun downloadTo(url: String, target: File, expectedSize: Long): Boolean {
        val temp = File(target.parentFile, target.name + ".part")
        var connection: HttpURLConnection? = null
        return try {
            target.parentFile?.mkdirs()
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "LanLink-Updater/1.0")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return false
            val total = expectedSize.takeIf { it > 0 } ?: connection.contentLengthLong
            temp.delete()
            connection.inputStream.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var lastReported = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (downloaded - lastReported >= 256 * 1024 || downloaded == total) {
                            lastReported = downloaded
                            reportProgress(downloaded, total)
                        }
                    }
                    output.flush()
                }
            }
            if (temp.length() != expectedSize) {
                temp.delete()
                return false
            }
            target.delete()
            temp.renameTo(target)
        } catch (e: Exception) {
            Log.w(TAG, "下载更新包失败：" + url, e)
            temp.delete()
            false
        } finally {
            connection?.disconnect()
        }
    }

    private fun reportProgress(downloaded: Long, total: Long) {
        val progress = if (total > 0L) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else -1f
        mutableState.update { it.copy(downloadedBytes = downloaded, progress = progress) }
    }

    private fun sha256(file: File): String = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        ""
    }

    private fun signatureMatches(apkFile: File): Boolean {
        return try {
            val archiveInfo = packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
            )
            val archiveSigners = archiveInfo?.signingInfo?.apkContentsSigners ?: return false
            val installedSigners = packageManager.getPackageInfo(
                appContext.packageName,
                android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
            ).signingInfo?.apkContentsSigners ?: return false
            val archiveHashes = archiveSigners.map { signerSha256(it.toByteArray()) }.toSet()
            val installedHashes = installedSigners.map { signerSha256(it.toByteArray()) }.toSet()
            archiveHashes == installedHashes && archiveHashes.isNotEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "校验安装包签名失败", e)
            false
        }
    }

    private fun signerSha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun installedVersionCode(): Int = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageManager.getPackageInfo(appContext.packageName, 0).longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(appContext.packageName, 0).versionCode
        }
    } catch (e: Exception) {
        0
    }

    private fun targetDir(): File = File(appContext.filesDir, "updates").apply { mkdirs() }

    // ------------------------------------------------------------ 安装与跳转

    private fun startInstaller(apkFile: File, info: UpdateInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            requestInstallPermission()
            mutableState.update {
                it.copy(
                    installing = false,
                    error = "请在系统设置中允许「安装未知应用」后重试"
                )
            }
            return
        }
        try {
            val authority = appContext.packageName + ".fileprovider"
            val uri: Uri = FileProvider.getUriForFile(appContext, authority, apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            appContext.startActivity(intent)
            mutableState.update { it.copy(installing = false) }
        } catch (e: Exception) {
            Log.w(TAG, "拉起系统安装器失败", e)
            mutableState.update { it.copy(installing = false, error = "无法打开系统安装器：" + (e.message ?: "未知错误")) }
        }
    }

    private fun requestInstallPermission() {
        runCatching {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:" + appContext.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
        }
    }

    private fun openBrowser(url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
        }
    }

    private companion object {
        const val TAG = "DefaultUpdateManager"
    }
}