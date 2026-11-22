# IzzyOnDroid 上架：issue 填写稿（LanLink v2.3.1）

模板：**App Inclusion Request**　标题：`[App Inclusion] LanLink`

> 说明：本文件是逐字段的填写稿，直接对照表单粘贴即可。表单字段名与显示语言可能略有差异。

---

## App meta-data

**Link to the source code**
```
https://github.com/likebe1-1/lan-transfer-android
```

**Link to app in another app store**
```
（留空——尚未上架 Google Play / F-Droid；若只想填一个可填）
https://github.com/likebe1-1/lan-transfer-android/releases
```

**License used**
```
GPL-3.0-or-later
```

**Categories**（最多 5 个，建议只选一个最贴切的）
```
File Transfer
```
（备选可加：Networking / Utilities）

**Summary**（1-2 句）
```
LanLink 是纯局域网的 Android 文件传输工具：同一 Wi-Fi 或热点下两台设备直传文件，支持 FTP/FTPS/SFTP/SCP，既可作为客户端也可让手机自建服务器，不经过云端、无需账号。
```

**Description**
```
LanLink 是一款不依赖云端的 Android 局域网文件传输工具，面向"两台设备之间直接搬文件"的场景（手机↔手机、手机↔NAS/服务器、手机↔电脑）。

主要功能
• 四种协议：FTP、FTPS、SFTP、SCP。既能连接 NAS 或其他服务器，也能把手机变成服务器（本机服务器模式）。
• 快速传文件：选择文件后自动开启临时服务器并显示 8 位接收码，对方输入接收码或扫描二维码即可接收；支持文件夹传输、断点续传、接收后自动保存到系统下载目录。
• 传输队列：多任务并发（3 路）、实时速度/进度/剩余时间、失败自动重试、历史记录，支持从通知或历史记录打开已下载文件。
• 网络工具：局域网设备扫描（含 AP 隔离提示）、网络诊断、端口连通性测试。
• 一键组网：扫描热点二维码自动加入对方热点，解决路由器 AP 隔离导致无法互传的问题。

安全设计
• 快速传文件对 FTPS 证书与 SSH 主机密钥做 SHA-256 指纹固定（指纹通过二维码/广播下发），中间人无法冒充。
• 自动连接只使用加密协议（SFTP → SCP → FTPS），明文 FTP 需用户手动选择。
• 服务端认证失败限流：单来源逐级锁定 + 全局失败速率上限，FTP/FTPS/SFTP/SCP 统一生效。
• 服务器密码使用 Android Keystore AES-256-GCM 加密落盘（密钥不可导出）；allowBackup=false。
• 更新清单仅接受 HTTPS + Ed25519 签名，下载的 APK 再校验 size/SHA-256/签名/versionCode。

隐私
不收集任何数据，无广告、无统计、无崩溃上报；文件只在局域网内传输。唯一的对外网络请求是"检查更新"读取 GitHub/Gitee 的版本清单，且商店分发版（fdroid flavor）已禁用应用内更新。

开源
源码以 GPL-3.0-or-later 发布，欢迎审计与自建。
```

---

## Technical instructions

**Build instructions**（命令行）
```
Prerequisites: JDK 17+ (tested with JDK 21), Android SDK platform 35 + build-tools 34+.
The Gradle wrapper (8.9) is committed; no local Gradle install needed.

    git clone https://github.com/likebe1-1/lan-transfer-android.git
    cd lan-transfer-android
    git checkout v2.3.1

Build the flavor used for store distribution (in-app updater disabled,
REQUEST_INSTALL_PACKAGES removed, single universal APK):

    export JAVA_HOME=/path/to/jdk-21
    ./gradlew :app:assembleFdroidRelease

Output: app/build/outputs/apk/fdroid/release/app-fdroid-universal-release.apk

Notes:
- Release builds are signed from ~/.lanlink/keystore.properties (see
  scripts/migrate_keystore_to_userdir.ps1); without signing material Gradle
  produces *-unsigned.apk only. F-Droid's buildserver signs with its own key.
- No Google Play Services, no ads/analytics SDKs. Dependencies come from
  Maven Central / Google Maven only.
- CI (GitHub Actions) runs ./gradlew testGithubDebugUnitTest lintGithubDebug
  assembleGithubDebug assembleFdroidRelease.
```

---

## AI Tools Usage

**Assistance Level**
```
Significant — 大部分实现代码由 AI 编码代理在开发者主导下编写与重构（架构、协议层、UI、测试），
开发者负责需求、评审、真机验证与最终取舍。
```
（如果下拉选项为 None / Minor / Moderate / Significant / Extensive，选 **Significant**；若实际以手写为主，可下调为 Moderate——**请按真实情况选择**。）

**"AI" Tool(s)**
```
DeepSeek Harness (AI coding agent, model: DeepSeek), GitHub Copilot 辅助补全
```
（请按实际使用情况增删，例如 ChatGPT / Claude / Cursor / 通义灵码 等。）

**What did the tools help with, and how?**
```
- 架构与模块划分：把 FTP/FTPS/SFTP/SCP 适配层、传输队列、本机服务器、快速传文件拆成独立模块并生成接口草稿；
- 样板代码：Compose 页面、JSON 持久化、前台服务、通知与权限流程；
- 安全加固：Ed25519 清单验签的纯 Kotlin 实现、FTPS 证书与 SSH 主机密钥指纹固定、服务端认证限流、
  Android Keystore 凭据加密（均由开发者提出需求并逐项评审）；
- 调试：并发问题（传输队列、服务端会话快照）、JSch/SSHD 在 Android 上的兼容性问题；
- 文档：README、架构文档、发布说明、上架文档。
所有 AI 产出均经过代码审查、单元测试（350+）与真机/模拟器集成测试验证后合入。
```

**AI Accountability**（勾选确认项）
```
☑ 我（开发者）对提交的代码负全部责任，理解并能维护其中每一部分；
☑ AI 生成的代码已通过人工评审与自动化测试验证；
☑ 代码中不含来路不明的第三方代码，所有依赖的许可证均已核对（GPL-3.0 兼容）；
☑ 无隐藏遥测/跟踪，AI 工具未引入任何第三方服务调用。
```

---

## Further Notices

```
我是本应用的开发者（账号 likebe1-1）。应用以 GPL-3.0-or-later 开源，仓库根目录含完整 LICENSE。

关于分发的两点说明：
1) 商店分发使用 "fdroid" flavor：已禁用应用内更新并移除 REQUEST_INSTALL_PACKAGES 权限，
   由仓库客户端负责更新；GitHub/官网分发使用 "github" flavor，保留应用内更新。
2) 两个 flavor 的 applicationId（com.manst.lantransfer）与版本号一致，且由同一密钥签名，
   因此与官网版可互相覆盖安装（F-Droid 主库若重签则不可互换）。

想测试商店版可以下载：<请在这里填 v2.3.1 Release 里 LanLink-v2.3.1-fdroid-universal.apk 的链接>
SHA-256: 7a54f2d6cc9332a62c8f2362e96b1eeca16a3cc23a20e4e6794c3d02a2c9583c

安全相关：权限仅 INTERNET/网络状态（局域网传输）、CAMERA（仅扫码）、NEARBY_WIFI_DEVICES（Android 13+
加入热点）、ACCESS_FINE/COARSE_LOCATION（maxSdkVersion 32，旧系统热点检测）、前台服务（dataSync 传输 +
specialUse 长驻本机服务器）、POST_NOTIFICATIONS；无存储权限（使用 SAF/MediaStore）。
```
