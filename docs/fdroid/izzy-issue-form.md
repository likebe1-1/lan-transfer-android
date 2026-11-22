# IzzyOnDroid App Inclusion Request · 填写稿（英中双语）

模板：**App Inclusion Request**　标题：`[App Inclusion] LanLink`

> 建议：英文在前、中文在后（审核以英文为主）。每段可整段粘贴。

---

## Summary（Markdown）

```markdown
**LanLink** is a LAN-only file transfer app for Android: two devices on the same Wi-Fi or hotspot move files
directly over **FTP / FTPS / SFTP / SCP** — as a client, or by turning the phone into a server.
No cloud, no account, no data collection.

- **Quick transfer:** pick files → an 8-digit code / QR code → the other device receives them
- **Built-in server:** share one folder over FTP/FTPS/SFTP/SCP
- **Transfer queue:** 3 concurrent tasks, resume, retry, history
- **Network tools:** LAN discovery, diagnostics, port tests, one-click hotspot join
- **Hardened by design:** FTPS certificate & SSH host-key pinning, encrypted-transport-only auto fallback,
  server-side auth throttling, Keystore-encrypted credentials, Ed25519-signed update manifest

**中文**：**LanLink** 是纯局域网的 Android 文件传输工具 —— 同一 Wi-Fi 或热点下两台设备通过
**FTP / FTPS / SFTP / SCP** 直传文件，既可作客户端，也可让手机自建服务器；不经过云端、无需账号、不收集数据。

- **快速传文件**：选文件 → 8 位接收码 / 二维码 → 对方直接接收
- **本机服务器**：一个共享目录即可对外提供 FTP/FTPS/SFTP/SCP
- **传输队列**：3 路并发、断点续传、失败重试、历史记录
- **网络工具**：局域网发现、诊断、端口测试、一键加入热点
- **默认安全**：FTPS 证书与 SSH 主机密钥指纹固定、自动回退仅用加密协议、
  服务端认证限流、Keystore 加密凭据、Ed25519 签名更新清单
```

---

## Description（Markdown）

```markdown
### What it is

LanLink is an Android file transfer tool that works **entirely on your local network** — no cloud, no account,
no data collection. It is built for moving files directly between devices: phone → phone, phone → NAS/server,
phone → desktop.

### Features

- **Four protocols** — FTP, FTPS, SFTP and SCP. Use it as a client to reach a NAS or any server, or turn the
  phone itself into a server (built-in server mode, one shared folder).
- **Quick transfer** — pick files and LanLink starts a temporary server showing an **8-digit code**; the other
  device enters the code or scans a QR code. Folders, resume and auto-save into the system *Downloads* folder
  are supported.
- **Transfer queue** — 3 concurrent tasks, live speed / progress / ETA, automatic retry, history, and opening
  finished files from the notification or history list.
- **Network tools** — LAN device discovery (with AP-isolation hints), network diagnostics, port reachability
  tests.
- **One-click hotspot** — scan the hotspot QR code to join the sender's hotspot; works even when the router
  enforces AP isolation.
- **Languages** — Chinese and English UI.

### Security

- **Peer authentication, not just encryption** — quick transfer pins the FTPS certificate and the SSH host key
  by SHA-256 fingerprint, delivered out-of-band via the QR code / LAN beacon. A man-in-the-middle on the same
  network cannot impersonate the peer, because it cannot present a matching fingerprint.
- **Encrypted transports by default** — automatic fallback only tries SFTP → SCP → FTPS; plain-text FTP must be
  selected explicitly by the user.
- **Server-side brute-force protection** — per-source escalating lockout plus a global failure-rate cap, applied
  to FTP/FTPS/SFTP/SCP alike (the client-side limiter alone would be bypassable by any FTP/SSH client).
- **Credentials encrypted at rest** — saved server passwords are encrypted with Android Keystore
  (AES-256-GCM, non-exportable key); `allowBackup` is disabled.
- **Signed updates** — the update manifest is fetched over HTTPS only and verified with an Ed25519 signature;
  downloaded APKs are additionally checked by size, SHA-256, signing certificate and versionCode.

### Privacy

No data collection, no ads, no analytics, no crash reporting. Files never leave the local network. The only
outbound request is the optional update check (GitHub/Gitee release metadata) — and it is **disabled in the
store flavor**.

### Distribution note

Two build flavors share the same `applicationId`, version code and signing key:
`fdroid` (in-app updater disabled, `REQUEST_INSTALL_PACKAGES` removed — used for this repository) and
`github` (keeps the built-in updater for direct downloads).

### License

GPL-3.0-or-later. Source: https://github.com/likebe1-1/lan-transfer-android

---

### 应用简介（中文）

LanLink 是一款**不依赖云端**的 Android 局域网文件传输工具，面向「两台设备之间直接搬文件」的场景：
手机↔手机、手机↔NAS/服务器、手机↔电脑。无需账号、不收集任何数据。

### 主要功能

- **四种协议**：FTP、FTPS、SFTP、SCP。既能连接 NAS 或其他服务器，也能把手机变成服务器（本机服务器模式）。
- **快速传文件**：选择文件后自动开启临时服务器并显示 **8 位接收码**；对方输入接收码或扫码即可接收。
  支持文件夹传输、断点续传、接收后自动保存到系统下载目录。
- **传输队列**：3 路并发、实时速度/进度/剩余时间、失败自动重试、历史记录，
  支持从通知或历史记录打开已下载文件。
- **网络工具**：局域网设备扫描（含 AP 隔离提示）、网络诊断、端口连通性测试。
- **一键组网**：扫描热点二维码自动加入对方热点，解决路由器 AP 隔离导致无法互传的问题。
- **界面语言**：中文与英文。

### 安全设计

- **不只是加密，还要认证对端**：快速传文件对 FTPS 证书与 SSH 主机密钥做 SHA-256 指纹固定，
  指纹通过二维码/局域网广播带外下发；同网段的中间人无法出示相同指纹，因此无法冒充。
- **默认只用加密协议**：自动回退顺序为 SFTP → SCP → FTPS，明文 FTP 需用户手动选择。
- **服务端防爆破**：单来源逐级锁定 + 全局失败速率上限，FTP/FTPS/SFTP/SCP 统一生效
  （仅靠客户端限流可被任意 FTP/SSH 客户端绕过）。
- **凭据加密存储**：服务器密码使用 Android Keystore（AES-256-GCM，密钥不可导出）加密落盘；
  `allowBackup` 关闭。
- **更新可验证**：清单仅接受 HTTPS + Ed25519 签名；下载的 APK 再校验大小、SHA-256、签名证书与 versionCode。

### 隐私

不收集任何数据，无广告、无统计、无崩溃上报；文件只在局域网内传输。
唯一的对外网络请求是可选的「检查更新」（读取 GitHub/Gitee 版本清单），且在本仓库分发的版本中已禁用。

### 许可证

GPL-3.0-or-later，源码：https://github.com/likebe1-1/lan-transfer-android
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
https://github.com/likebe1-1/lan-transfer-android/releases/download/v2.3.1/LanLink-v2.3.1-fdroid-universal.apk
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

- [x] 已把 `LanLink-v2.3.1-fdroid-universal.apk` 追加到 v2.3.1 Release（已验证下载哈希一致）
- [ ] 标题带应用名：`[App Inclusion] LanLink`
- [ ] Categories 选 `File Transfer`
- [ ] License 填 `GPL-3.0-or-later`
- [ ] AI Tools Usage 如实填写（Assistance Level: Significant）
