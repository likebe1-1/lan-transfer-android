# 依赖现状与升级策略（Dependency Audit）

- 文档编号：DEP-001
- 适用工程：LanLink（Android 局域网传输 App，Windows / Gradle 单模块）
- 核对基准：`gradle/libs.versions.toml` @ commit `1098ab4`（2026-11-22，工作区干净）
- 版本事实来源：以 `gradle/libs.versions.toml` 为唯一准绳（Gradle Version Catalog）；本文档只记录现状与策略，**不修改任何版本号**
- 配套配置：`.github/dependabot.yml`（自动依赖 PR）

> 维护约定：升级依赖后，同步更新本文档对应表格行与「变更记录」，保证文档中的版本号与
> `gradle/libs.versions.toml` 一致。

## 1. 构建工具链与版本

| 组件 | 坐标 / 位置 | 当前版本 | 版本别名 | 说明 |
| --- | --- | --- | --- | --- |
| Kotlin（Android + Compose 编译器插件） | `org.jetbrains.kotlin.android` / `org.jetbrains.kotlin.plugin.compose` | **2.0.21** | `kotlin` | Kotlin 2.x 起 Compose 编译器随 Kotlin 发布，`kotlin-compose` 插件版本必须与 Kotlin 一致 |
| Android Gradle Plugin | `com.android.application` | **8.7.3** | `agp` | compileSdk 35 / minSdk 29 / targetSdk 35 |
| Gradle Wrapper | `gradle/wrapper/gradle-wrapper.properties` | **9.6.0**（bin） | — | 版本组合不在 AGP 官方矩阵内（AGP 8.7 官方测试基线为 Gradle 8.9），但本机构建日志 `build-test.log` 显示当前可成功构建；升级 AGP 时必须整体回归 |
| JDK（构建） | `gradle.properties` / `scripts/gw.ps1` | **JDK 21**（`D:/SDK/JDK21`） | — | source/targetCompatibility = 11 |

## 2. 运行时关键依赖

| 依赖 | 坐标 | 当前版本 | 版本别名 | 用途 / 备注 |
| --- | --- | --- | --- | --- |
| Compose BOM | `androidx.compose:compose-bom` | **2024.10.01** | `composeBom` | 统一 Compose UI / Material3 / tooling 版本；`ui`、`material3` 等不单独写版本 |
| AndroidX Core KTX | `androidx.core:core-ktx` | **1.13.1** | `coreKtx` | Kotlin 扩展与兼容工具 |
| AndroidX Lifecycle | `androidx.lifecycle:lifecycle-runtime-ktx` / `lifecycle-runtime-compose` / `lifecycle-viewmodel-compose` | **2.8.7** | `lifecycleRuntimeKtx` | 三个坐标共用同一版本 |
| AndroidX Activity Compose | `androidx.activity:activity-compose` | **1.9.3** | `activityCompose` | 单 Activity + Compose Navigation |
| AndroidX Navigation Compose | `androidx.navigation:navigation-compose` | **2.8.4** | `navigationCompose` | 页面路由 |
| AndroidX DocumentFile | `androidx.documentfile:documentfile` | **1.0.1** | `documentFile` | SAF 目录树遍历（文件夹传输） |
| Kotlinx Coroutines | `org.jetbrains.kotlinx:kotlinx-coroutines-android` / `kotlinx-coroutines-test` | **1.9.0** | `coroutines` | 传输队列、并发调度；test 坐标同版本 |
| Apache Commons Net | `commons-net:commons-net` | **3.11.1** | `commonsNet` | FTP / FTPS 客户端（`FTPClient` / `FTPSClient`） |
| JSch（mwiede 维护 fork） | `com.github.mwiede:jsch` | **0.2.26** | `jsch` | SFTP / SCP 客户端；**原 `com.jcraft:jsch` 已停止维护（0.1.55，2018）**，必须继续用 mwiede fork |
| Apache MINA SSHD | `org.apache.sshd:sshd-core` / `sshd-sftp` / `sshd-scp` | **2.14.0** | `sshd` | 本机服务器（手机作为服务端）与 JVM 测试内嵌 SSH 服务端；三个坐标共用同一版本 |
| BouncyCastle | `org.bouncycastle:bcprov-jdk18on` / `bcpkix-jdk18on` | **1.78.1** | `bouncycastle` | FTPS 自签证书生成 / 证书处理；两坐标共用同一版本 |
| SLF4J NOP | `org.slf4j:slf4j-nop` | **2.0.16** | `slf4j` | SSHD 依赖 slf4j-api，Android 上用 no-op 绑定避免日志刷屏与重复绑定告警；版本需与 SSHD 传递的 slf4j-api 同线（2.0.x） |
| ZXing Core | `com.google.zxing:core` | **3.5.3** | `zxingCore` | 二维码生成（快速传文件接收码） |
| ZXing Android Embedded | `com.journeyapps:zxing-android-embedded` | **4.3.0** | `zxingEmbedded` | 相机扫码界面；其传递依赖的 zxing core 版本较旧，由显式声明的 3.5.3 覆盖 |

## 3. 测试依赖（不进入 APK）

| 依赖 | 坐标 | 当前版本 | 版本别名 | 用途 / 备注 |
| --- | --- | --- | --- | --- |
| JUnit 4 | `junit:junit` | **4.13.2** | `junit` | JVM 单元测试 |
| AndroidX Test JUnit | `androidx.test.ext:junit` | **1.2.1** | `junitVersion` | 仪器测试入口 |
| Espresso | `androidx.test.espresso:espresso-core` | **3.6.1** | `espressoCore` | UI 仪器测试 |
| MockFtpServer | `org.mockftpserver:MockFtpServer` | **3.1.0** | `mockftpserver` | FTP / FTPS 适配器 JVM 测试 |
| org.json | `org.json:json` | **20240303** | `jsonOrg` | JVM 单测中的 JSON 实现（Android 运行时自带 org.json） |

## 4. 风险与维护状态

### 4.1 安全敏感且需要人工把关的依赖

| 依赖 | 现状评估 | 风险与建议 |
| --- | --- | --- |
| **JSch = `com.github.mwiede:jsch` 0.2.26** | ✅ **选型正确**：原 `com.jcraft:jsch` 自 2018 年 0.1.55 后停更，mwiede fork 持续跟进 CVE 与算法更新，且是行业事实标准替代品 | 0.x 版本号语义上仍是 minor 位；升级可能改变默认算法/密钥交换策略。升级后必须跑 `SshRemoteFileSystemSftpTest`、`SshRemoteFileSystemScpTest`、`SshHostKeyPinningTest`、`SshServerDeviceTest`。R8 规则已保留 `com.jcraft.jsch` 类，升级后仍需验证 release 包不被裁剪 |
| **BouncyCastle 1.78.1**（`bcprov-jdk18on` / `bcpkix-jdk18on`） | ⚠️ 安全敏感：1.78.1 是 2024 年修复多个 CVE 的补丁版本（此前 1.78 亦修复多项安全问题），当前仍属较新的 1.78 维护线 | **Android 平台内置了一份裁剪版 BC（同包名 `org.bouncycastle`）**，必须保证运行时优先使用打包进来的 jar（当前通过 `packaging.resources.excludes` 与 R8 处理多版本元数据）。升级后重点验证 FTPS 自签证书生成、证书指纹固定与 `FtpsCertificatePinningTest`；关注 1.78.x 后续安全公告，patch 应尽快合入 |
| **Apache MINA SSHD 2.14.0**（core/sftp/scp） | ✅ Apache 官方维护，2.x 活跃；2.12.1+ 已处理 Terrapin（CVE-2023-48795）类 SSH 前缀截断攻击，2.14.0 在安全修复线之后 | 本项目**同时把 SSHD 当服务端用**（本机 FTP/SFTP/SCP 服务器），安全暴露面比纯客户端更大。升级 minor/patch 也需回归服务端认证、封禁（`ServerAuthGuard`）与设备测试；出现安全公告时优先于其他依赖升级 |
| **slf4j-nop 2.0.16** | ✅ 正确选型：Android 上无需日志后端，no-op 绑定避免 "Failed to load class org.slf4j.impl.StaticLoggerBinder" 与日志噪声 | 版本必须与 SSHD 传递的 `slf4j-api` 大版本一致（当前 2.0.x）；若 SSHD 升级带动 slf4j-api 升到 2.1/3.x，需同步升 nop，否则运行期报 `NoSuchMethodError` |
| **Compose BOM 2024.10.01 + Kotlin 2.0.21** | ✅ 当前组合官方兼容（Compose 编译器随 Kotlin 2.0.21 发布） | BOM 的 minor/patch 通常安全，但新 BOM 可能要求更高 compileSdk 或 AGP；major 升级（如跨年 BOM）必须人工评估，且 Compose 编译器由 Kotlin 版本决定，不能只升 BOM |
| **AGP 8.7.3 + Gradle 9.6.0** | ⚠️ 非官方兼容矩阵组合（AGP 8.7 官方基线 Gradle 8.9）；当前本机构建成功（`build-test.log`：`BUILD SUCCESSFUL`），并出现 "incompatible with Gradle 10" 的弃用提示 | 升级 AGP 属 major 级工程变更，需同步核对 Gradle、Kotlin、Compose 与 `compileSdk`；在升级 Gradle 10 前必须先升 AGP，否则构建会失败 |

### 4.2 维护状态一般 / 需要留意的依赖

| 依赖 | 现状评估 | 风险与建议 |
| --- | --- | --- |
| **zxing-android-embedded 4.3.0** | ⚠️ 维护活跃度低（journeyapps 的 4.3.0 发布于 2022 年，此后基本无新版本），但其功能是本地相机扫码，无服务端暴露面 | 不要期待 major 更新；其传递依赖的 `zxing:core` 较旧，项目已显式声明 3.5.3，Gradle 会取最高版本。升级/替换前用 `dependencyInsight` 确认实际解析版本；若未来需要新相机 API，评估迁移到 CameraX + ML Kit 或维护状态更好的扫码库 |
| **commons-net 3.11.1** | ✅ Apache 维护，3.11.x 为较新维护线 | FTPS 相关 API 在 3.x 内有弃用（`TrustManager` 相关），升级时注意编译告警；升级后跑 `FtpRemoteFileSystemTest`、`FtpsRemoteFileSystemTest`、`FtpsProtocolServerTest`、`FtpsCertificatePinningTest` |
| **AndroidX 组件（core/lifecycle/activity/navigation/documentfile）** | ✅ 官方维护，稳定 | minor/patch 可自动升级；注意新版 AndroidX 可能要求更高 compileSdk（当前 35），出现 "compileSdk too low" 时人工评估 targetSdk/compileSdk 联动 |
| **kotlinx-coroutines 1.9.0** | ✅ 官方维护 | 与 Kotlin 版本有兼容区间，升级 coroutines 时确认 Kotlin 版本在其支持范围 |
| **测试依赖（JUnit4 / Espresso / MockFtpServer / org.json）** | ✅ 仅测试 classpath，不进 APK | MockFtpServer 3.x 更新缓慢但仅测试使用；升级不影响发布包，可跟随自动 PR，合并前确认 `testDebugUnitTest` 通过 |

### 4.3 风险等级汇总

| 等级 | 项 | 处理方式 |
| --- | --- | --- |
| 高 | SSHD / BouncyCastle / JSch（协议与加密核心） | 任何升级都按小版本对待：先看 changelog / CVE，再跑协议与真机测试；出现安全公告立即升级 |
| 高 | AGP / Gradle / Kotlin / Compose BOM 联动 | 禁止自动 major；人工分支升级并完整回归 |
| 中 | zxing-android-embedded 维护停滞、slf4j 版本对齐、Android 平台内置 BC 冲突 | 依赖解析检查 + 打包后真机验证 |
| 低 | 测试依赖、AndroidX 常规组件 | 自动 PR + 单元测试/仪器测试通过即可 |

## 5. 升级策略

### 5.1 自动（Dependabot，见 `.github/dependabot.yml`）

- **范围**：仅 Gradle 生态（`gradle/libs.versions.toml`），目标分支 `master`，每周一 06:00（Asia/Shanghai）检查，最多 5 个并行 PR。
- **允许自动开 PR**：minor / patch 升级；按 `kotlin`、`compose`、`androidx`、`sshd-bc-jsch`、`ftp-test`、`zxing`、`minor-patch` 分组，减少 PR 数量。
- **禁止自动**：所有 major 升级（`version-update:semver-major` 全部 ignore）。
- **自动 PR 不等于自动合并**：merge 前必须通过 CI / `testDebugUnitTest`；涉及 SSHD、BouncyCastle、JSch、Compose BOM 的 PR 需按第 5.3 节跑对应测试，必要时真机验证。

### 5.2 人工（major 或跨模块联动升级）

1. 新建 issue，写明目标版本、上游 changelog / CVE 链接与影响面（客户端 / 本机服务端 / 测试基建）；
2. 独立分支修改 `gradle/libs.versions.toml`（major 升级可临时调整 Dependabot 的 `ignore` 或改用 `allow`，不要直接合并自动 PR）；
3. 按第 5.3 节执行测试矩阵，记录结果到 PR；
4. 合并后同步更新本文档第 1~3 节版本表与「变更记录」。

### 5.3 升级后必须执行的测试

```powershell
# 1) JVM 单元测试（协议适配器、证书固定、协议服务端等，全部本地可跑）
.\scripts\gw.ps1 testDebugUnitTest

# 2) 关键设备（仪器）测试：SSH 服务端真实设备验证
.\scripts\gw.ps1 connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.manst.lantransfer.server.ssh.SshServerDeviceTest"

# 3) 四协议端到端（模拟器/真机，覆盖 FTP/FTPS/SFTP/SCP）
.\scripts\gw.ps1 connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.manst.lantransfer.e2e.FourProtocolE2ETest"

# 4) release 构建（R8 混淆 + 资源压缩，验证 keep 规则未被破坏）
.\scripts\gw.ps1 assembleRelease
```

- `testDebugUnitTest` 已覆盖题目要求的两个关键测试类：`FtpsCertificatePinningTest`（FTPS 自签证书指纹固定）与 `FtpsProtocolServerTest`（FTPS 服务端协议行为），二者位于 `app/src/test`；
- `SshServerDeviceTest` 位于 `app/src/androidTest`（`com.manst.lantransfer.server.ssh`），需要模拟器/真机与 `connectedDebugAndroidTest`；
- 升级 SSHD / JSch / BC / Commons Net 时，必须额外人工跑一遍四种协议的真实连接（FTP / FTPS / SFTP / SCP 各一次上传 + 下载），并验证自签证书场景与主机密钥固定提示。

### 5.4 查看实际解析版本（冲突排查）

```powershell
.\scripts\gw.ps1 :app:dependencies --configuration debugRuntimeClasspath
.\scripts\gw.ps1 :app:dependencyInsight --dependency zxing-core --configuration debugRuntimeClasspath
.\scripts\gw.ps1 :app:dependencyInsight --dependency slf4j-api --configuration debugRuntimeClasspath
```

## 6. 升级检查清单

- [ ] PR 只改动 `gradle/libs.versions.toml`（或 Gradle Wrapper），没有夹带业务代码 / 构建脚本变更
- [ ] 已阅读上游 release notes / CVE，确认无 breaking API 或默认安全策略变化
- [ ] `.\scripts\gw.ps1 testDebugUnitTest` 全绿（含 `FtpsCertificatePinningTest`、`FtpsProtocolServerTest` 及全部协议适配器测试）
- [ ] SSH / FTPS / BC 相关升级：跑 `SshServerDeviceTest`、`FourProtocolE2ETest`，并人工做四协议真机连接
- [ ] `assembleRelease` 通过，安装 release APK 后确认 JSch / Commons Net / BC 未被 R8 误裁剪
- [ ] 用 `dependencyInsight` 确认同一依赖没有被传递版本回退（尤其 `zxing-core`、`slf4j-api`、`bouncycastle`）
- [ ] 升级 SSHD 时确认 `slf4j-nop` 与传递的 `slf4j-api` 版本线一致
- [ ] 版本表中对应行与「变更记录」已同步更新
- [ ] major 升级额外要求：至少一台真机 + 一个模拟器验证，升级说明写入 PR 描述

## 7. 变更记录

| 日期 | 变更 | 说明 |
| --- | --- | --- |
| 2026-11-22 | 初版 | 基于 commit `1098ab4` 的 `gradle/libs.versions.toml` 建立依赖现状与升级策略；新增 `.github/dependabot.yml`。本次不改动任何版本号或业务代码 |
