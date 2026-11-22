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

## 提交 IzzyOnDroid（推荐先做）

1. 用 `assembleFdroidRelease` 产出的 universal APK 作为 izzy 的发布资产；
2. 在 https://izzyondroid.org/contributing/NewAppInclusions/ 填表提交（仓库地址 + 包名 + 说明）；
3. izzy 会跟随 GitHub Release 自动更新，你照常发版即可；
4. 因为用你自己的签名，用户可在官网版与 izzy 版之间覆盖安装。

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
