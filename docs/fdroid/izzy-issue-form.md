# IzzyOnDroid App Inclusion Request · 填写稿（英中双语）

模板：**App Inclusion Request**　标题：`[App Inclusion] LanLink`

> 建议：英文在前、中文在后（审核以英文为主）。每段可整段粘贴。

---

## Summary

```
LanLink is a LAN-only file transfer app for Android: two devices on the same Wi-Fi or hotspot move files directly over FTP/FTPS/SFTP/SCP — as a client, or by turning the phone into a server. No cloud, no account.

（中文）
LanLink 是纯局域网的 Android 文件传输工具：同一 Wi-Fi 或热点下两台设备直传文件，支持 FTP/FTPS/SFTP/SCP，既可作为客户端也可让手机自建服务器，不经过云端、无需账号。
```

---

## Description

```
LanLink is an Android file transfer tool that works entirely on your local network — no cloud, no account. It is designed for moving files directly between devices: phone to phone, phone to NAS/server, or phone to desktop.

Features
• Four protocols — FTP, FTPS, SFTP and SCP. Connect to a NAS or any server, or turn the phone itself into a server (built-in server mode).
• Quick transfer — pick files and LanLink starts a temporary server and shows an 8-digit code; the other device types the code or scans a QR code. Supports folders, resume, and auto-save into the system Downloads folder.
• Transfer queue — 3 concurrent tasks, live speed/progress/ETA, automatic retry, history, and opening finished files from the notification or history list.
• Network tools — LAN device discovery (with AP-isolation hints), network diagnostics, and port reachability tests.
• One-click hotspot — scan the hotspot QR code to join the sender's hotspot, which works even when the router enforces AP isolation.

Security
• Quick transfer pins the FTPS certificate and SSH host key by SHA-256 fingerprint (delivered through the QR code / LAN beacon), so a man-in-the-middle cannot impersonate the peer.
• Automatic protocol fallback only uses encrypted transports (SFTP → SCP → FTPS); plain FTP must be selected explicitly.
• Server-side authentication throttling: per-source escalating lockout plus a global failure-rate cap, applied to FTP/FTPS/SFTP/SCP alike.
• Saved server passwords are encrypted with Android Keystore (AES-256-GCM, non-exportable key); allowBackup is disabled.
• The update manifest is fetched over HTTPS only and verified with an Ed25519 signature; downloaded APKs are additionally checked by size, SHA-256, signing certificate and versionCode.

Privacy
No data collection, no ads, no analytics, no crash reporting. Files never leave the local network. The only outbound request is the optional update check (GitHub/Gitee release metadata), and it is disabled in the store flavor.

Open source
Released under GPL-3.0-or-later — audit it, build it, fork it.

──────────────── 中文 ────────────────

LanLink 是一款不依赖云端的 Android 局域网文件传输工具，面向「两台设备之间直接搬文件」的场景（手机↔手机、手机↔NAS/服务器、手机↔电脑）。

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
不收集任何数据，无广告、无统计、无崩溃上报；文件只在局域网内传输。唯一的对外网络请求是「检查更新」读取 GitHub/Gitee 的版本清单，且商店分发版（fdroid flavor）已禁用应用内更新。

开源
源码以 GPL-3.0-or-later 发布，欢迎审计与自建。
```

---

## Build instructions

```
Prerequisites: JDK 17+ (tested with JDK 21), Android SDK platform 35 and build-tools 34+.
The Gradle wrapper (8.9) is committed, so no local Gradle installation is required.

    git clone https://github.com/likebe1-1/lan-transfer-android.git
    cd lan-transfer-android
    git checkout v2.3.1

Build the flavor used for store distribution (in-app updater disabled, REQUEST_INSTALL_PACKAGES removed, single universal APK):

    export JAVA_HOME=/path/to/jdk-21
    ./gradlew :app:assembleFdroidRelease

Output: app/build/outputs/apk/fdroid/release/app-fdroid-universal-release.apk

Notes
- Release builds are signed from ~/.lanlink/keystore.properties (see scripts/migrate_keystore_to_userdir.ps1). Without signing material Gradle emits *-unsigned.apk only. F-Droid's buildserver signs with its own key.
- Dependencies come from Maven Central / Google Maven only; no Google Play Services, no ads or analytics SDKs.
- CI (GitHub Actions) runs: ./gradlew testGithubDebugUnitTest lintGithubDebug assembleGithubDebug assembleFdroidRelease
```

---

## AI Tools Usage

**Assistance Level**: `Significant`

**"AI" Tool(s)**:
```
DeepSeek Harness (AI coding agent, model: DeepSeek)
```

**What did the tools help with, and how?**
```
- Architecture and module split: FTP/FTPS/SFTP/SCP adapters, transfer queue, built-in server, quick-transfer engine.
- Boilerplate: Compose screens, JSON persistence, foreground services, notifications and permission flows.
- Security hardening: a pure-Kotlin Ed25519 verifier for update manifests, FTPS certificate / SSH host-key
  fingerprint pinning, server-side authentication throttling, Android Keystore credential encryption —
  all requested and reviewed by the developer.
- Debugging: concurrency issues (transfer queue, server session snapshots) and JSch/SSHD compatibility on Android.
- Documentation: README, architecture notes, release notes and store listing.

All AI output was reviewed, unit-tested (350+ tests) and validated on emulator and real devices before merging.

（中文）架构与模块划分、样板代码、安全加固、并发与 Android 兼容性调试、文档撰写；
所有 AI 产出均经人工评审、350+ 单元测试与真机/模拟器集成测试验证后合入。
```

---

## Further Notices

```
I am the developer of this app (GitHub account likebe1-1). It is released under GPL-3.0-or-later; the full
license text is in the repository root.

Two notes about distribution:
1) The store flavor ("fdroid") disables the in-app updater and removes REQUEST_INSTALL_PACKAGES, so updates are
   handled by the repository client. The GitHub/website flavor ("github") keeps the built-in updater.
2) Both flavors share the same applicationId (com.manst.lantransfer) and version code, and are signed with the
   same key, so they can replace each other on-device (a re-signed F-Droid build could not).

Store-flavor test APK:
<PASTE THE URL OF LanLink-v2.3.1-fdroid-universal.apk FROM THE v2.3.1 RELEASE HERE>
SHA-256: 7a54f2d6cc9332a62c8f2362e96b1eeca16a3cc23a20e4e6794c3d02a2c9583c

Permissions: INTERNET / network state (LAN transfer), CAMERA (QR scanning only), NEARBY_WIFI_DEVICES
(Android 13+, joining a hotspot), ACCESS_FINE/COARSE_LOCATION (maxSdkVersion 32, hotspot detection on older
systems), foreground services (dataSync for transfers, specialUse for the long-running local server),
POST_NOTIFICATIONS. No storage permissions (SAF / MediaStore only).

（中文）我是本应用开发者，应用以 GPL-3.0-or-later 开源，仓库根目录含完整 LICENSE。
商店分发使用 fdroid flavor（禁用应用内更新、移除安装权限）；官网/GitHub 分发保留应用内更新。
两个 flavor 包名、版本号一致且同签名，可互相覆盖安装。权限清单同上，无存储权限。
```

---

## 提交前清单

- [ ] 已把 `LanLink-v2.3.1-fdroid-universal.apk` 追加到 v2.3.1 Release（否则 Further Notices 里的链接无内容）
- [ ] 标题带应用名：`[App Inclusion] LanLink`
- [ ] Categories 选 `File Transfer`
- [ ] License 填 `GPL-3.0-or-later`
- [ ] AI Tools Usage 如实填写（Assistance Level: Significant）
