# 上架 F-Droid / IzzyOnDroid

## 渠道与更新方式

| 渠道 | flavor | 应用内更新 | 签名 | 覆盖安装 |
| --- | --- | --- | --- | --- |
| 官网 / GitHub Release / 蓝奏云 | `github` | 保留 | 自己的 keystore | 基准 |
| IzzyOnDroid | `fdroid` | 关闭（商店负责） | **自己的 keystore** | 可与官网版互相覆盖 |
| F-Droid 主库 | `fdroid` | 关闭（商店负责） | F-Droid 密钥 | **不能**与官网版互相覆盖 |

已实测的兼容性事实：

- 两个 flavor 的 `applicationId`（`com.manst.lantransfer`）、`versionCode`、`versionName` **完全一致**；
- 两 flavor 的 release 包均由同一 keystore 签名（证书 SHA-256 `928480fb…`），因此 github 版与 fdroid 版可互相覆盖安装；
- `fdroid` flavor 移除了 `REQUEST_INSTALL_PACKAGES`，并设置 `BuildConfig.IN_APP_UPDATES=false`。

## 构建

```bash
./gradlew :app:assembleGithubRelease   # 官网 / GitHub Release，保留应用内更新
./gradlew :app:assembleFdroidRelease   # 商店分发，关闭自更新、无安装权限
```

产物：`app/build/outputs/apk/<flavor>/release/`。发布脚本 `scripts/release.py` 已指向 github flavor。

## IzzyOnDroid 提交材料（v2.3.1，已构建）

| 项 | 值 |
| --- | --- |
| 提交入口 | https://codeberg.org/IzzyOnDroid/repodata/issues （New issue，标题 `[App Inclusion] LanLink`） |
| 商店分发 APK | `build/izzy-assets/LanLink-v2.3.1-fdroid-universal.apk`（fdroid flavor，universal） |
| 大小 | 11,169,966 字节（约 10.7 MiB，< izzy 的 30 MB 上限） |
| SHA-256 | `7a54f2d6cc9332a62c8f2362e96b1eeca16a3cc23a20e4e6794c3d02a2c9583c` |
| package / 版本 | `com.manst.lantransfer` / versionCode 12 / 2.3.1 |
| 签名 | `CN=LanTransfer`，证书 SHA-256 `928480fb…d56564`（与官网版同一密钥 → 可互相覆盖安装） |
| 权限 | **不含** `REQUEST_INSTALL_PACKAGES`（已移除），无自更新 |

> 该 APK 需要上传到某个公开位置供 izzy 审核附加/下载（izzy 通常要求开发者把 APK 挂在 Release 上）。
> 方案：在 v2.3.1 Release 里**追加**一个资产 `LanLink-v2.3.1-fdroid-universal.apk`（不影响现有 github 版资产与 update.json）。

## 提交 IzzyOnDroid（推荐先做）

**提交入口**：https://codeberg.org/IzzyOnDroid/repodata/issues （新建 issue，标题建议 `[App Inclusion] LanLink`）

**已满足的前置条件（izzy 审核会逐条核对）**

| 检查项 | 状态 |
| --- | --- |
| FOSS 许可证 | ✅ GPL-3.0-or-later（仓库根 `LICENSE`） |
| Fastlane 已配置 | ✅ `fastlane/metadata/android/{en-US,zh-CN}`（title / short_description ≤80 字符 / full_description / changelogs / icon / phoneScreenshots） |
| 有 tag 且与 versionName 对应 | ✅ `v2.3.1` = versionName 2.3.1 |
| Release 附带 APK 且 < 30 MB | ✅ v2.3.1 三个资产各约 11.2 MB |
| 无 proprietary / ads / tracking | ✅ 无 Play Services、无统计 SDK |
| `usesCleartextTraffic` | ✅ 未设置（默认禁止明文） |
| 权限说明 | ✅ 见下方 issue 正文模板 |

**issue 正文模板（可直接粘贴）**

```markdown
### App
- Name: LanLink
- Package: com.manst.lantransfer
- Source: https://github.com/likebe1-1/lan-transfer-android
- License: GPL-3.0-or-later
- Releases: https://github.com/likebe1-1/lan-transfer-android/releases (latest v2.3.1, APKs ~11.2 MB)
- Fastlane metadata: present (en-US, zh-CN)

### What it does
LAN-only file transfer for Android. Two devices on the same Wi-Fi/hotspot can move files and folders
directly (no cloud, no account) using FTP/FTPS/SFTP/SCP — either as client, or by turning the phone
into a server. Includes quick transfer with a short code/QR, transfer queue with resume, LAN tools.

### Notes for reviewers
- Distribution flavor used for stores: fdroid (in-app updater disabled, REQUEST_INSTALL_PACKAGES removed).
- Permissions: INTERNET/network state (local transfer), CAMERA (QR scanning only),
  NEARBY_WIFI_DEVICES (Android 13+, hotspot join), ACCESS_FINE/COARSE_LOCATION (maxSdkVersion 32, older
  hotspot detection), Foreground service types dataSync + specialUse (transfer & long-running local server),
  POST_NOTIFICATIONS. No storage permissions (SAF/MediaStore).
- No ads, no analytics, no crash reporting; only outbound requests are GitHub/Gitee release metadata for
  the optional updater (disabled in the store flavor).
```

**流程**：开 issue → izzy 机器人做 APK 扫描/人工测试（可能要求 you attach APK，我们的 Release 已有）→ 审核通过后自动纳入仓库，并跟随 GitHub Release 自动更新。

## 提交 F-Droid 主库（可选）

1. 把 `docs/fdroid/com.manst.lantransfer.yml` 内容作为 `metadata/com.manst.lantransfer.yml` 提交到 fdroiddata（MR）；
2. buildserver 用 fdroid flavor 从源码构建（`gradle: - fdroid`）；
3. 默认 F-Droid 密钥重签：与官网版签名不同，切换渠道需卸载重装；
4. 想保留自己签名需走 Reproducible Builds，建议 izzy 稳定后再做。

## 前置检查

- [x] `LICENSE` GPL-3.0-or-later
- [x] `gradle.properties` 无本机绝对路径
- [x] Gradle 8.9 + AGP 8.7.3（官方兼容基线）
- [x] 无广告/统计/Play Services
- [x] Fastlane 元数据（en-US / zh-CN）
- [x] fdroid flavor 关闭自更新并移除安装权限
- [ ] 提交 IzzyOnDroid（需人工填表）
- [ ] 提交 fdroiddata MR（可选）
