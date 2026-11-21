# 架构决策记录：局域网文件传输 App

- 文档编号：ADR-001
- 产品名：LanLink（局域网传输）
- 状态：已采纳
- 日期：2026-11-13

## 1. 需求

在**同一局域网**内，用手机连接局域网中的服务器（NAS、PC、路由器外挂盘等），通过
**FTP / FTPS / SFTP / SCP** 四种协议浏览远端目录并上传、下载文件。

典型场景：

1. 在家里 Wi-Fi 下连 NAS，把手机里的照片上传到 NAS；
2. 从公司局域网的 Linux 服务器用 SFTP/SCP 拉取日志或安装包；
3. 连接老旧设备仅提供 FTP / FTPS 时的兼容传输。

非目标（本版不做）：局域网设备自动发现、云同步、断点续传、文件夹递归传输。

> 版本演进：v1.1 已新增「本机服务器」——手机也可以作为 FTP/FTPS/SFTP/SCP 服务端，详见第 10 节。

## 2. 平台与形态选型

| 方案 | 优点 | 缺点 | 结论 |
| --- | --- | --- | --- |
| **原生 Android（Kotlin + Jetpack Compose）** | 协议库全是 JVM 原生库，零桥接；前台服务、通知、MediaStore/SAF 全部原生可控；本机工具链齐全，可直接产出签名 APK 并在模拟器验证 | 仅 Android | **采用** |
| Flutter | 跨平台 | 本机未装 Flutter SDK；SFTP/SCP 仍需插件或 FFI；后台传输需额外适配 | 否 |
| React Native | 跨平台、热更新 | 协议库需原生桥接；后台传输、文件系统访问插件质量参差 | 否 |
| 桌面端（Tauri/Electron） | 大屏体验好、网络库成熟 | 与「手机随手传文件」的核心场景不符；用户交付物为 APK | 否 |
| 纯 JVM 桌面（Swing/JavaFX） | 复用 Commons Net/JSch | 移动端不可用 | 否 |

**结论：单模块原生 Android 应用**，Kotlin 2.0 + Jetpack Compose（Material 3），minSdk 29 / targetSdk 35。

## 3. 应用内分层架构

```
┌──────────────────────────────────────────────┐
│ UI 层（Compose）                              │
│  服务器列表 / 服务器编辑 / 远端浏览 / 传输队列   │
└───────────────┬──────────────────────────────┘
                │ StateFlow + 事件回调
┌───────────────▼──────────────────────────────┐
│ ViewModel 层（每屏一个，单向数据流）            │
└───────────────┬──────────────────────────────┘
                │ 领域接口
┌───────────────▼──────────────────────────────┐
│ 领域层                                        │
│  ServerRepository（配置持久化）                │
│  TransferManager（有限并发队列 + 进度 + 保活）   │
└───────────────┬──────────────────────────────┘
                │ RemoteFileSystem 抽象
┌───────────────▼──────────────────────────────┐
│ 协议适配层（纯 JVM，可单测）                     │
│  FtpRemoteFileSystem（FTP/FTPS，Commons Net）  │
│  SshRemoteFileSystem（SFTP/SCP，JSch）         │
└──────────────────────────────────────────────┘
```

关键设计约束：

1. **单 Activity + Compose Navigation**，无 Fragment、无 XML 布局。
2. **手动依赖注入**：`AppContainer` 在 Application 中创建，按需 lazy 初始化；不引入 Hilt/KSP，避免注解处理器拖慢构建。
3. **协议层纯 JVM**：不 import 任何 Android API，因此可以用 JVM 单元测试 + 内嵌服务器跑真实报文测试。
4. **一任务一连接**：不做连接池。每次浏览会话、每个传输任务各自建立 `RemoteFileSystem`，结束即关闭；取消任务 = 关闭连接。FTP 控制连接并发限制、SSH 会话状态隔离问题都被绕开。
5. **有限并发传输**：默认 3 路并发（`TransferScheduler.DEFAULT_MAX_CONCURRENT_TASKS`），每个任务独立连接；
   小文件多时显著减少「连接/认证/断开」固定开销。极端场景（服务端限制并发数）可调为 1 退回串行。
6. **前台服务保活**：有活动任务时启动 `TransferService`（`dataSync` 类型）显示通知；队列清空即停止。
7. **下载落盘**：Android 10+ 用 `MediaStore.Downloads` 直接写入公共目录 `Download/` 根目录（不新建子文件夹），无需存储权限；上传用系统文件选择器（SAF）返回的 content Uri 流式读取。

## 4. 协议层设计

统一抽象 `RemoteFileSystem`：

```kotlin
interface RemoteFileSystem : Closeable {
    val profile: ServerProfile
    suspend fun connect()
    suspend fun list(path: String): List<RemoteEntry>
    suspend fun download(remotePath: String, sink: OutputStream, onProgress: (Long) -> Unit = {})
    suspend fun upload(source: InputStream, remotePath: String, sizeHint: Long = -1L, onProgress: (Long) -> Unit = {})
    suspend fun mkdir(path: String)
    suspend fun delete(path: String, isDirectory: Boolean)
    suspend fun rename(fromPath: String, toPath: String)
    override fun close()
}
```

| 协议 | 库 | 实现要点 |
| --- | --- | --- |
| FTP | Apache Commons Net `FTPClient` | 被动模式默认（局域网 NAT 少但防火墙友好），主动模式可选；二进制传输；`LIST` 用 `MLSD` 优先、失败回退 `LIST` 解析 |
| FTPS | Apache Commons Net `FTPSClient` | 显式（AUTH TLS，默认）与隐式（990 端口）两种模式；可配置「信任自签名证书」（局域网自签是常态） |
| SFTP | JSch（mwiede 维护分支）`ChannelSftp` | 密码认证；`ls`/`stat` 带 `LsEntry` 属性；下载 `get(path, stream)`，上传 `put(stream, path, monitor, OVERWRITE)`，通过 `SftpProgressMonitor` 汇报进度 |
| SCP | JSch `ChannelExec` | 实现 SCP 线协议：下载执行 `scp -f <path>`，读 `C` 记录后回 `\0` 并流式读取；上传执行 `scp -t <dir>`，发 `C0644 <size> <name>\n` 后写数据并读取对端 ACK |

安全取舍：

- FTPS 默认严格校验系统 CA；快速传文件等自签名场景改用**证书指纹固定**（`ServerProfile.pinnedCertificateSha256`）。`allowInsecureTls` 仅作兼容旧配置的兜底，启用时打印安全警告；
- 主机密钥（SFTP/SCP）：快速传文件的发送方用 `SshHostKeyProbe` 取回主机密钥指纹并随二维码/广播（`hk` 字段）下发，接收方通过 `ServerProfile.pinnedSshHostKeySha256` 固定校验；无指纹的普通服务器退回首次信任；
- 服务器密码使用 Android Keystore 的 AES-256-GCM 加密后写入 `filesDir/servers.json`（`AndroidKeystoreSecretCipher`，密钥不可导出）；Keystore 异常时降级明文并记录日志；
- 本机服务器（FTP/FTPS/SFTP/SCP）在服务端统计认证失败次数并临时封禁来源（`ServerAuthGuard`）。

## 5. 数据与状态

- `ServerRepository`：`servers.json`（org.json 手写序列化 + 原子写临时文件后 rename），`StateFlow<List<ServerProfile>>` 驱动 UI；单元测试传入临时文件，可在 JVM 直接测。
- `TransferManager`：`StateFlow<List<TransferTask>>`；任务按 `TransferScheduler` 有限并发执行（默认 3 路，同一 taskId 不会重复启动），进度按 100ms 节流推送到 UI，并计算瞬时速度；`cancel` 关闭连接，`retry` 重新入队。
- 不引入 Room：数据量是「几十条服务器配置 + 内存态队列」，Room 只增加构建复杂度与 APK 体积。

## 6. 工程与构建

- Gradle 9.6 wrapper + AGP 8.7.3 + Kotlin 2.0.21 + Compose BOM 2024.10.01；
- 签名密钥 `lantransfer-release.jks` 与 `keystore.properties` 位于项目根（已被 .gitignore 排除），release 构建自动签名并开启 R8（混淆规则已保留 JSch/Commons Net 反射类）；
- 构建统一走 `scripts/gw.ps1`（强制 JDK 21，避免本机默认 JDK 版本差异）；
- debug 包 `applicationIdSuffix=.debug`，可与线上包共存。

## 7. 测试策略

| 层级 | 手段 | 覆盖内容 |
| --- | --- | --- |
| JVM 单元测试 | JUnit4 + 内嵌服务器 | `RemotePath` 归一化；FTP/FTPS 适配器对接 MockFtpServer；SFTP/SCP 适配器对接 Apache MINA SSHD；上传下载字节级一致性、目录列表、增删改 |
| 仪器测试 | Compose UI Test + 模拟器 | 启动、空状态、添加服务器、持久化、传输队列界面 |
| 手工冒烟 | 模拟器 + 宿主机服务 | 安装 release APK,连接宿主机 FTP/SFTP 服务端完成一次真实传输、截图 |

## 8. 已知限制 / 后续演进

1. SCP 线协议本身不支持 REST，续传依赖各协议自身能力（FTP `REST`、SFTP `RESUME`）；
2. 仅快速传文件路径做证书/主机密钥指纹固定；手动配置的普通服务器仍为首次信任；
3. 监听地址固定 `0.0.0.0`，依赖服务端认证限流（`ServerAuthGuard`）兜底；多网卡/VPN/公网暴露场景需谨慎；
4. SFTP 数据包尺寸受 JSch 硬编码 32 KiB（`LOCAL_MAXIMUM_PACKET_SIZE`）限制，应用侧无法放大；
5. Keystore 加密异常时降级为明文保存（优先保证可用性，日志可查）。

## 9. 验证记录（2026-11-13）

| 验证项 | 环境 | 结果 |
| --- | --- | --- |
| JVM 单元测试 | JDK 21 / Gradle 9.6 | 146 个用例全部通过（客户端协议 + 服务端协议 + 数据/传输层，内嵌真实服务端走线） |
| 设备四协议端到端 | Android 10（API 29）Pixel_4_XL 模拟器 + 宿主机真实 FTP/FTPS/SFTP/SCP 服务端 | 4/4 通过（列表、下载、上传回读字节一致、删除、错误映射、MediaStore 落盘） |
| UI 冒烟 + 截图巡游 | 同上 | 通过，截图见 docs/screenshots |
| 本机服务器设备测试 | API 29 模拟器（ServerModeDeviceTest / SshServerDeviceTest） | 四种协议启动成功并回环收发；客户端上传文件落到共享目录 |
| 跨设备（宿主作为另一台设备） | ServerModeHostTestBridge + adb forward + Python ftplib/paramiko | FTP/FTPS/SFTP/SCP 4/4 通过，含 SCP 元数据命令；设备端断言上传文件全部出现 |
| 设备测试总览 | API 29 模拟器 | 16 个用例全部通过（服务端/客户端/UI/共享目录） |
| Release（R8）运行时 | API 29 模拟器安装签名 release APK | 客户端 SFTP 下载正常；本机服务器四协议由宿主机实测上传下载回读 4/4 通过；mapping.txt 确认 JSch/Commons Net/SSHD/BouncyCastle 反射类完整保留 |
| 快速传文件设备测试 | API 29 模拟器 QuickTransferEngineTest | 发送会话四协议分别连接/自动接收/MediaStore 校验/回传互传/会话清理全部通过；UDP 广播→扫描回环通过 |
| 快速传文件跨设备 E2E | QuickHostE2EBridge + adb forward + 宿主机 Python 客户端 | 宿主机读取会话后以 FTP/FTPS/SFTP/SCP 下载并 SHA-256 校验全部文件、各上传一个文件；设备端断言 4 个上传文件全部出现在「收到的文件」 |
| 传输中速度/进度/剩余时间 | API 29 模拟器 QuickProgressTest | 真实 64MB 文件传输期间界面显示「41% · 4.9 MB/s」「剩余约 8 秒」，并截图归档；JVM 单测覆盖时长/剩余时间格式化与估算 |
| 设备测试总览（v1.3.0） | API 29 模拟器 | 31 个用例全部通过（传输指标 + 首次启动提示 + 快速传文件 + 本机服务器 + 共享目录 + UI 冒烟） |
| **两台真机互传（真实 Wi-Fi）** | Xiaomi 24129PN74C + Xiaomi 25080RABDC / Android 16 / 同一 Wi-Fi | 自动化桥接：A 启动真实「快速传文件」会话；B 通过 UDP 广播发现 A 后，分别用 FTP/FTPS/SFTP/SCP 下载 2MB 文件（大小校验一致）并各回传一个文件；A 断言收到 4 个回传文件，4/4 协议通过 |
| **两台真机功能测试套件（v1.3.1）** | 同上两台 / Android 16 | 每台 25 个非 UI 设备用例全部通过（协议客户端/服务端、共享目录、下载根目录、快速传文件引擎）；UI 仪器测试受 MIUI 后台启动 Activity 限制无法在真机执行，由 API 29 模拟器覆盖 |
| **真机功能测试** | Xiaomi 25080RABDC / Android 16 / arm64-v8a（无线 ADB） | 13 个非 UI 用例全部通过：本机服务器四协议回环收发与共享目录落盘、SFTP/SCP 服务端、共享目录仓库 |
| **真机跨设备 E2E** | 真机（服务器）+ 宿主机（另一台设备，无线 adb forward） | FTP/FTPS/SFTP/SCP 4/4 通过；设备端断言宿主机上传文件全部出现在共享目录 |
| 真机 release 运行时 | 真机安装签名 R8 APK | 安装/启动成功，无崩溃、无类缺失；release 版保留在设备上 |

验证过程中发现：本机 QEMU 模拟器在高速大流量 TCP 下会静默丢字节（设备端 `nc` 亦可复现，与应用代码无关），因此端到端测试服务端对 FTP/FTPS 数据通道做了限速，真机不受此影响。

## 10. 本机服务器（v1.1 新增）

### 10.1 需求与定位

同一 App 既是客户端也是服务端：手机 A 一键启动服务端，手机 B / 电脑用标准 FTP / FTPS / SFTP / SCP 客户端连接 A，浏览并下载 A 的共享文件，也可以上传文件到 A（A 即「局域网文件站」）。

### 10.2 分层

```
Compose UI（ServerScreen / ServerViewModel）
        │ StateFlow / 事件
LocalServerManager（DefaultLocalServerManager：统一启停、端点状态、错误聚合）
        ├── FtpProtocolServer（FTP / FTPS 共用实现，自研）
        ├── SshProtocolServer（Apache MINA SSHD：SFTP 子系统 + SCP 命令工厂 + 元数据 shell 命令）
        └── ShareRepository（共享目录：导入 / 列表 / 删除 / 导出）
LocalServerService（dataSync 前台服务，保活 + 运行中通知）
```

### 10.3 协议服务端实现取舍

| 协议 | 方案 | 理由 |
| --- | --- | --- |
| FTP / FTPS | **自研**（监听 + 会话状态机 + TLS 升级） | 现成 Java FTP 服务端库（Apache FtpServer）依赖 MINA + 配置体系，Android 兼容性风险高；FTP 协议服务端命令集有限，自研可控且能精确兼容本项目客户端与 Python ftplib |
| SFTP / SCP | **Apache MINA SSHD 2.14**（sshd-core/sftp/scp） | JVM 生态最成熟的 SSH 服务端实现，直接提供 SFTP 子系统、SCP 命令工厂与虚拟文件系统；自研 SSH2 服务端成本过高 |

- FTP/FTPS：0.0.0.0 监听、每连接一线程；PASV/EPSV 被动模式；Unix 长格式 LIST/NLST/MLSD；UTF-8；路径归一化 + 越界拒绝；RETR 流式；STOR 临时文件 + 原子替换；FTPS 用 BouncyCastle 运行时生成 RSA2048 自签证书（SAN 覆盖本机 IPv4），显式 AUTH TLS 升级控制连接，PROT P 对数据通道做 TLS。
- SFTP/SCP：密码认证；VirtualFileSystemFactory 将共享目录映射为虚拟根；SCP 额外实现本 App 客户端使用的 `ls -la / mkdir -p / rmdir / rm -f / mv` 元数据命令（POSIX 引号解析 + 越界校验）。

### 10.4 Android 兼容性（实测踩坑，务必保留）

1. **无 user.home**：MINA SSHD 静态初始化会读 user home，Android 上抛 `ExceptionInInitializerError`；必须在创建 SshServer 前调用 `PathUtils.setUserHomeFolderResolver { Paths.get(System.getProperty("java.io.tmpdir")) }`。
2. **BouncyCastle provider 冲突**：App 为 FTPS 证书引入了 bcprov，SSHD 检测到 BC 类后会优先使用名为 "BC" 的 provider，而 Android 系统内置的 BC 已被裁剪（无 ECDH KeyAgreement），握手报 `no such algorithm: ECDH for provider BC`。解决：SSHD 启动前 `SecurityUtils.setAPrioriDisabledProvider("BC", true)`（以及 EdDSA），让其使用平台默认 JCE。
3. **默认 KEX 列表为空**：Android 上 SSHD 的算法支持性探测会把内置 key exchange 全部判为 unsupported，`setUpDefaultServer()` 后 kex 列表为 0，start 报 “KeyExchangeFactories not set”。解决：显式安装保底 KEX 集合（ECDH nistp256/384/521 + DH group14/16/18），并仅把 JCE 探测为可用的算法追加进去。
4. **主机密钥**：默认算法为 EC，Android 上生成/探测不稳定；显式使用 RSA 2048 + `SimpleGeneratorHostKeyProvider` 持久化到应用 cache，重启复用。
5. **IO 工厂**：显式 `Nio2ServiceFactoryFactory` 绕开 ServiceLoader。
6. **R8**：ProGuard 保留 `org.apache.sshd.**`、`org.bouncycastle.**`、`org.slf4j.**`，release 构建已实测四种服务端协议可用。

### 10.5 共享目录与生命周期

- 共享目录：`getExternalFilesDir("share")`（回退 `filesDir/share`），无需存储权限；导入（SAF 复制）、列表、删除、导出到公共下载目录均由 `ShareRepository` 负责，协议服务端直接读写该目录。
- 生命周期：服务器运行期间由 `LocalServerService`（dataSync 前台服务）保活并显示协议/端口/地址通知；手动停止或全部协议启动失败时停止服务；界面销毁不影响服务器。
- 授权模型：单用户（配置的用户名/密码），仅局域网内明文/加密协议访问，无公钥认证与多用户。

### 10.6 验证策略

- JVM：FTP/FTPS 服务端与 SSHD 服务端均由真实客户端（本工程客户端 + 裸 Socket 断言）回环验证，共 38 个服务端用例；
- 设备：`ServerModeDeviceTest`/`SshServerDeviceTest` 在模拟器上启动服务器并回环验证四协议；
- 跨设备：`ServerModeHostTestBridge` 在设备上启动服务器，宿主机经 `adb forward` 以「另一台设备」身份用 Python 标准客户端完成四协议收发，设备端断言上传文件落到共享目录；
- Release：签名 R8 APK 上真实启动服务器并由宿主机完成四协议上传下载回读。

## 11. 快速传文件（v1.2 新增）

### 11.1 目标

把「两台设备互传文件」的步骤降到最少：A 选文件后什么都不用配置，B 在附近设备列表里点一下即可收文件，并且 B 也能把文件发回 A。底层仍然使用 FTP / FTPS / SFTP / SCP 四种协议（不引入私有传输协议），做到「用户简单、协议标准」。

### 11.2 会话模型

```
A：选文件 → 生成 6 位接收码 → SessionFileStore 复制到会话目录
   → LocalServerManager.start(四协议 + 派生帐号 + shareRootOverride=会话目录 + 自动选空闲端口)
   → UdpBeaconBroadcaster 每秒广播 {code, deviceName, addresses, ftp/ftps/ssh ports, protocols}
B：UdpBeaconScanner 监听 47823 → 附近设备列表 → 点击/输入 IP+码
   → QuickSessionCode.deriveCredentials(code) 得到帐号 → 按 SFTP→SCP→FTPS→FTP 回退连接
   → 列出文件 → 自动逐个下载（MediaStore.Downloads，直接写入 Download/ 根目录）
   → 互传：同一个连接把 B 的文件上传回 A（A 端「收到的文件」+ 导出到下载）
```

关键设计：

- **帐号无需传递**：发送端与接收端用同一算法从接收码派生临时用户名/密码（SHA-256 派生），用户只需分享 6 位数字；
- **不新增私有协议**：会话本质仍是四种标准协议的服务器；接收端自动回退到可用协议，也可手动指定，四种协议产物一致；
- **临时目录隔离**：通过 `LocalServerConfig.shareRootOverride` 让快捷会话使用 `quick-send/<code>` 临时目录，不复用/污染「本机服务器」的共享目录；结束会话即删除；
- **无需手动配置端口**：发送端优先使用 2121/2122/2222，被占用时自动 +1 选空闲端口，广播携带真实端口；
- **发现失败可兜底**：广播不可用时接收端可手动输入 IP + 接收码（默认端口场景）。

### 11.3 组件

| 组件 | 职责 |
| --- | --- |
| `QuickSendManager` | 接收码/凭据、会话目录、四协议服务端启停、UDP 广播、对方上传文件识别与导出 |
| `QuickReceiveManager` | 局域网发现、手动连接、协议回退、自动下载、反向上传（互传）、断开清理 |
| `SessionFileStore` | 会话目录管理：SAF 导入（清洗/去重/原子落盘）、列表、导出到下载、清理 |
| `QuickDownloadSink` | 接收文件写入 MediaStore.Downloads（IS_PENDING + commit/abort 语义） |
| `UdpLanBeacon // QuickBeaconCodec` | 局域网广播报文编解码（版本化 JSON）与 UDP 发送/扫描（SO_REUSEADDR、MulticastLock） |
| UI（ui/quick） | 快速传文件入口卡、发送页（接收码大字/全屏/端口/文件/收到文件）、接收页（附近设备/手动输入/协议选择/进度/互传） |

### 11.4 安全与隐私

- 接收码仅为临时会话凭证，会话结束后服务端关闭、目录删除；派生帐号只在本会话有效；
- 广播报文包含接收码与设备名，同网段可见（定位为可信局域网内的临时互传，与 AirDrop「对所有人」同类）；
- 文件传输仍走各协议自身的 TLS/SSH 加密（SFTP/SCP/FTPS）；FTP 为明文，自动回退顺序把明文 FTP 放在最后；
- 不引入云服务，所有流量仅局域网内。

### 11.5 实现中修复的 Android 兼容问题

`UdpBeaconScanner` 绑定端口时误用了 `DatagramSocket.apply { }` 内部的 `port`（未绑定 socket 的端口为 -1），导致「port out of range:-1」而发现功能静默失效；已在设备测试中发现并修复（构造参数提前捕获为 `bindPort`）。这类问题只有真实设备走线测试才能暴露，JVM 测试与编译期都无法发现。

### 11.6 验证策略

- JVM：接收码/凭据（收发双端算法一致）、广播编解码容错、会话文件名逻辑；
- 设备：`QuickTransferEngineTest` 设备内启动真实会话，四种协议分别连接/自动接收/回传互传，UDP 广播回环；
- 跨设备：`QuickHostE2EBridge` + `scripts/quick_mode_hosttest.py`，宿主机以「另一台设备」身份用四协议下载校验并上传，设备端断言收到的文件；
- UI：`QuickScreenSmokeTest` + 截图巡游 `QuickScreenshotTest`。
### 11.7 传输中速度 / 进度 / 剩余时间

- **速度**：`TransferSpeedMeter` 维护 2 秒滑动窗口（记录时间戳与累计字节），速度 = 窗口内字节增量 / 实际时间差；客户端传输队列早已用该实现，快速传文件在 v1.3 引入同样的 `TransferProgressTracker`（100ms / 128KB 节流）计算每文件瞬时速度，随 `QuickTransferProgress.bytesPerSecond` 推送到 UI；
- **剩余时间**：`Format.remainingSeconds` 用剩余字节 / 当前速度向上取整，`Format.remainingTime` 输出「剩余约 X 分 Y 秒 / X 秒」「即将完成」或「剩余时间未知」（速度为 0 或大小未知时）；`Format.duration` 统一时长文案（秒/分/小时）；
- **进度**：进度条 + 已传/总大小 + 百分比（客户端队列页与快速传文件接收页都展示）；快速传文件同时显示「第 N/M 个文件」；
- **通知**：前台服务进度通知在原有任务名/百分比之后追加速度与剩余时间；
- **验证**：JVM 单测覆盖格式化与估算边界（174 个用例）；设备端 `QuickProgressTest` 用 64MB 真实文件在传输中截图并断言界面出现速度/剩余时间文字，截图见 `docs/screenshots/13_quick_transfer_progress.png`。
## 12. v2.0 实用功能（P0）设计

### 12.1 断点续传与自动重试

- 协议层新增 `supportsResume` / `downloadPartial` / `uploadPartial` / `size` / `mkdirs`（`RemoteFileSystem`）：
  - FTP/FTPS 下载使用 `REST` offset 后重新取流，上传校验远端 `SIZE` 后用 `APPE` 追加剩余内容；
  - SFTP 使用 JSch 的 offset 读写（`get(skip)` / `put(RESUME)`）；
  - SCP 协议本身不支持续传（`supportsResume=false`），任务自动从 0 重传，UI 标注不支持；
- 传输引擎下载先写应用私有暂存文件 `cacheDir/transfer/<taskId>.part`，中断保留；恢复/重试时从暂存文件长度继续，完成后复制到 MediaStore 并删除暂存；
- 上传续传按远端已有字节数与本地已传字节数校验后追加；不一致则整文件覆盖；
- 自动重试：`TransferRetryPolicy` 对连接/IO 类错误最多重试 3 次（2s/4s/8s 退避），认证与路径错误不重试；`attempts` 持久化并在 UI 展示「重试中 (n/3)」。

### 12.2 队列持久化与历史

- `JsonTransferStore` 以原子写（临时文件 + rename）持久化任务快照（含服务器 `profileSnapshot`、已传字节、`localUri`、重试次数），历史最多保留 200 条；
- 进程启动时恢复：未完成任务重置为 QUEUED 并自动继续（上传任务若源 Uri 已不可读则标记失败并提示重新选择），已完成/失败/取消进入历史；
- 任何状态变化按 500ms 节流落盘，损坏文件回退为空队列并保留 `.bak`。

### 12.3 文件夹递归传输

- 下载：`enqueueDownloadFolder` 用单连接 BFS 递归列远端目录（上限 1000 个文件），逐文件生成下载任务，`localRelativeDir` 保留目录结构，落盘到 `Download/<目录名>/<子目录>/file`；
- 上传：`enqueueUploadFolder` 用 `DocumentFile` 递归 SAF 目录树，逐文件生成上传任务，远端路径保留层级，执行前调用 `mkdirs` 自动建目录；
- 所有相对路径逐段清洗（去除 `.`/`..` 与非法字符）防目录穿越；SAF Uri 调用 `takePersistableUriPermission` 尽量保证重启后续传。

### 12.4 系统分享直达

- `MainActivity` 注册 `ACTION_SEND` / `ACTION_SEND_MULTIPLE`（`*/*`），`singleTop`；解析 `ClipData` / `EXTRA_STREAM` 后写入 `AppContainer.shareImports`；
- `LanTransferApp` 观察该事件自动跳转「快速传文件」发送页，`QuickSendViewModel` 用分享的文件自动 `startSession` 并清空事件。

### 12.5 扫码快速传输（Base64 载荷）

- 发送页显示二维码（ZXing core 生成）；接收页用 `zxing-android-embedded` 相机扫码；
- 二维码内容 = **Base64(UTF-8 JSON)**：JSON 字段 `v/code/name/addrs/ftp/ftps/ssh/protocols`，由 `QuickQrCodec` 编解码；不携带临时密码，接收端用接收码派生凭据；
- Base64 解码同时兼容 URL-safe 与标准 Base64（便于复制粘贴），非法载荷返回 null 并提示「二维码无法识别」；
- 相机权限拒绝/取消时保留手动输入与局域网发现兜底。

### 12.6 通知打开文件

- 下载完成后 `TransferTask.localUri` 记录 MediaStore Uri；结果通知的 contentIntent 与「打开」action 使用 `ACTION_VIEW` + `FLAG_GRANT_READ_URI_PERMISSION`，点击直接打开文件；无 Uri 时回退打开 App。

### 12.7 验证与踩坑

- JVM 单测 236 个全绿（新增协议续传、传输持久化/重试/路径、二维码/文件夹路径等）；
- 设备仪器测试 47 个全绿（新增传输恢复/自动重试、文件夹子目录落盘/通知打开、快捷传文件递归接收、二维码/文件夹入口 UI）；
- 发现的真实问题：恢复未完成任务时若在 `AppContainer` 懒初始化路径中同步启动前台服务，`TransferService.onCreate` 会再次访问同一个 lazy 导致主线程等待，触发 `ForegroundServiceDidNotStartInTimeException`；已改为初始化完成后异步启动；
- MediaStore 对 `IS_PENDING=1` 的下载记录在部分设备/版本查询不可见，测试需先 commit 再查询 RELATIVE_PATH。
## 13. 发布体积优化（v2.1.1）

- ABI 拆分：Gradle `splits.abi` 生成 `armeabi-v7a / arm64-v8a / x86 / x86_64` 独立签名 APK，并保留 universal APK；`bundle {}` 同时启用语言/密度/ABI 拆分，便于后续发布 AAB；
- 资源精简：`resourceConfigurations = ["zh", "en"]`；`packaging.resources.excludes` 移除 BouncyCastle 未使用的 PQC 参数资源（`org/bouncycastle/pqc/**`）与多语言错误文案；
- 结果：v2.1.0 universal 11.77 MiB → v2.1.1 arm64 10.44 MiB（约 -11.3%），universal 10.49 MiB；
- 注意：App 原生库仅约 40 KB（androidx graphics path），ABI 拆分实际收益约 50 KB；主要收益来自资源精简。dex（SSHD/BouncyCastle/JSch/Compose）仍是主要体积来源，后续可发布 AAB 或改用内置静态证书移除 BouncyCastle；
- 验证：236 JVM 单测全绿；设备端 `ServerModeDeviceTest`（含 FTPS 自签证书 + 四协议回环）与 `DownloadRootPathTest` 通过；x86_64 拆分 APK 在模拟器安装启动正常。
## 14. 下个版本改进（未发布，2026-11）

### 14.1 快速传文件广播安全加固

- **广播不再携带接收码**：`QuickBeaconCodec.VERSION = 2`，`encode` 只输出 `v/name/addrs/ftp/ftps/ssh/protocols/ts`；`decode` 只接受 v2，code 恒为空（即使伪造报文带 code 也不采信），v1 旧报文忽略；
- **接收码来源**：发送方屏幕显示 + 发送页二维码（`QuickQrCodec` 仍是 Base64(JSON) 且含 code），或用户手动输入；
- **附近设备交互**：接收页附近设备卡片只显示设备名/IP/端口/协议，点击后弹窗要求输入 6 位接收码再连接；
- **错误次数限制**：新增 `QuickJoinAttemptLimiter`（纯 JVM，key = host+三端口）：连续 5 次认证失败后锁定 10 分钟，成功连接清除记录；`DefaultQuickReceiveManager.join` 前置检查并在认证失败时按一次失败计数（协议自动回退不重复计数），锁定期间直接返回中文提示；
- **测试**：JVM 单测新增广播 v2 编码不含 code/v1 忽略、限流器 5 次/10 分钟/成功清除/目标隔离等；设备端 beacon 回环测试改为断言 code 为空且 `hasCode=false`。

### 14.2 UI 文案资源化（strings.xml）

- 所有 Compose 界面用户可见中文文案抽取到 `res/values/strings.xml`（277 条，按模块前缀命名，带参数的用 `%1$s/%1$d` 占位），Compose 使用 `stringResource(...)`；
- 原有文案逐字保留（UI 测试断言不受影响）；ViewModel/Snackbar 动态文案暂未抽取，后续可再做一次注入改造。

### 14.3 前台服务启动顺序修复

- `TransferService.onCreate` 先立即 `startForeground`（空任务通知），再初始化 `TransferManager` 并刷新通知；
- 传输引擎仅在**存在前台 Activity** 时启动传输前台服务（`LanTransferApplication` 通过 Activity 生命周期计数），避免无界面/仪器测试场景 startForegroundService 超时导致 `ForegroundServiceDidNotStartInTimeException`；真实场景中服务一旦在界面前台时启动，离开界面后仍持续运行；
- 该问题在设备测试中由 `TransferPersistenceDeviceTest` 的恢复+自动重试用例暴露并修复。
## 15. 网络工具（局域网雷达/诊断）

- 页面：首页顶栏「网络工具」→ `ui/network/NetworkToolsScreen`，数据来自 `net/LanScanner`；
- 当前网络：`ConnectivityManager` + `LinkProperties` 读取活动网络的 IPv4、前缀、默认网关、接口与 Wi-Fi/有线类型（不需要定位权限）；
- 设备扫描：对 `LanSubnet.hostAddresses()` 生成的同网段地址（大网段只取本机 /24）× 常见端口（21/22/80/139/443/445/8080/2121/2222）做并发 TCP 连接探测（48 并发、250ms 超时），结合 `/proc/net/arp`（可读时）补充 MAC 与无开放端口但曾在线的设备；同时监听快速传文件 UDP 广播识别正在等待接收的 LanLink 设备；对在线设备做限量反向 DNS 获取主机名；
- 诊断：`LanSubnet.buildChecks()` 生成「已连接局域网 / 网关可达 / 发现其他设备 / LanLink 设备 / 疑似 AP 隔离」项（网关可达但无设备时提示 AP 隔离）；
- 端口测试：任意 IP:端口 TCP 连接测试（2s 超时），用于排查对方服务是否启动；
- 纯逻辑（网段计算、设备类型判定、诊断项）在 `net/LanModels.kt` 并用 JVM 单测覆盖；扫描/UI 在模拟器冒烟测试覆盖。
## 16. 一键组网（热点二维码 / 应用级 Wi-Fi 加入）

- 入口：网络工具页顶部「一键组网（热点）」卡片 → `ui/setup/NetworkSetupScreen`；
- A（发送方）：`Intent("android.settings.WIFI_TETHER_SETTINGS")` 打开系统热点设置（逐级回退 WIFI_SETTINGS/SETTINGS）；`HotspotInfoProvider` 在 Android 11+ 用反射读取 `getSoftApConfiguration()` 预填 SSID/密码（失败时手动输入）；`WifiSetupQrCodec` 生成 `LANLINK-WIFI:` + Base64(JSON{ssid,password,security}) 二维码（二维码用现有 `QrCodeRenderer`）；
- B（接收方）：扫码相机（zxing）→ `WifiSetupQrCodec.decode` → 请求 `NEARBY_WIFI_DEVICES`（API 33+）/`ACCESS_FINE_LOCATION`（≤32）与相机权限 → `WifiNetworkSpecifier.Builder().setSsid().setWpa2Passphrase()` + `ConnectivityManager.requestNetwork()`（30s 超时）；`onAvailable` 后 `bindProcessToNetwork(network)` 让后续快速传文件/网络工具走热点，断开/页面销毁时 `bindProcessToNetwork(null)`；
- 连接为应用级 Wi-Fi：不修改系统 Wi-Fi 设置，其他 App 不受影响；页面提供「打开快速传文件（接收）」跳转；
- 测试：`WifiSetupQrCodecTest` 覆盖编解码/非法载荷/开放网络/密码与 SSID 校验；`NetworkSetupSmokeTest` 覆盖入口与页面区块；实际加入热点需要两台真机验证。

## 17. 安全加固（P0）

### 17.1 凭据与令牌
- `.gitee_token` 等访问令牌**禁止落盘**进仓库：`.gitignore` 已覆盖 `.gitee_token`、`*_token`、`*token.txt`、`.env*`；脚本统一从环境变量（`GITEE_TOKEN`）读取；
- 误提交的令牌已通过 `git filter-branch` 从全部历史中移除并强推，令牌本身需在服务端吊销。

### 17.2 凭据落盘加密
- 服务器密码不再明文写盘：`SecretCipher` 抽象 + `AndroidKeystoreSecretCipher`（Keystore AES-256-GCM，密钥不可导出）；
- 影响文件：`servers.json`（`JsonServerRepository`）与传输队列 `transfer_tasks.json`（`JsonTransferStore`）；
- 密文格式：`enc:v1:` + Base64(IV‖ciphertext‖tag)，每条随机 IV；
- 历史明文自动兼容：读取时若没有前缀则原样使用，下次保存自动转为密文；
- 解密失败（密钥失效/恢复备份）返回空密码要求重新输入，不崩溃；加密失败极端情况下退回明文并记录日志；
- 测试：JVM 用假 cipher 验证落盘无明文 + 往返；设备测试验证真实 Keystore 往返与仓库读写。

### 17.2.1 签名材料隔离
- 明文口令与 keystore 移出工作区：`scripts/migrate_keystore_to_userdir.ps1` 复制到 `%USERPROFILE%\.lanlink\` 并收紧 ACL；
- `app/build.gradle.kts` 解析顺序：环境变量（`LANT_KEYSTORE_FILE` / `LANT_KEYSTORE_PASSWORD` / `LANT_KEY_ALIAS` / `LANT_KEY_PASSWORD`）→ 用户目录 `.lanlink/keystore.properties` → 项目内 `keystore.properties`（兼容回退）；
- 注意：`java.util.Properties` 会跳过首行，配置文件首行必须是注释。

### 17.3 FTPS 证书校验与固定
- `ServerProfile.allowInsecureTls` 默认 `false`（严格校验 + 主机名校验），历史配置未显式开启时按严格处理；
  `ServerRepository` 与 `JsonTransferStore` 的反序列化默认值现在完全一致（均为 `false`）；
- 仅用户显式勾选「信任任意证书」时才使用信任任意证书的 TrustManager（运行时会打印安全警告）；
- **快速传文件改用证书固定（pinning）**：发送方把 FTPS 自签名证书的 SHA-256 指纹随**二维码/局域网广播**下发，
  接收方 `CertificatePinning` 只信任该指纹的证书（`ServerProfile.pinnedCertificateSha256`），
  中间人无法出示同指纹证书，因此不会再出现「只加密不认证」；
- 仅当对方是旧版本（未提供指纹）时才退回信任任意证书，且界面连接成功消息会明确提示「本次未校验证书」；
- **SFTP/SCP 主机密钥固定（P0-1 新增）**：发送方用 `SshHostKeyProbe` 对本机 SSH 端口做一次真实密钥交换，
  取到 JSch 视角的主机密钥线格式 blob 并计算 SHA-256 指纹，随二维码/广播（`hk` 字段）下发；
  接收方 `ServerProfile.pinnedSshHostKeySha256` + `SshHostKeyPinning.PinnedRepository` 只接受该指纹，
  `StrictHostKeyChecking=yes`；中间人无法出示同指纹主机密钥（除非持有发送方私钥）。
  旧版本对端无指纹时退回首次信任，并在连接成功消息中明确提示「本次未校验证书/主机密钥」。

### 17.4 接收码与服务端限流
- 接收码由 6 位升级为 **8 位**（1e8 空间），仍兼容旧版 6 位输入；
- 新增 `server/ServerAuthGuard`：按**来源 IP** 统计认证失败（5 次 / 10 分钟窗口 → 锁定 10 分钟），FTP（USER/PASS 阶段）与 SSHD（PasswordAuthenticator）均已接入；
- **SSHD 侧限流**：`SshProtocolServer.passwordAuthenticator` 读取 `session.clientAddress`（InetSocketAddress）作为来源 IP，锁定期间直接返回认证失败；SFTP 与 SCP 共用该入口；
- **Guard 线程安全**：`isLocked` / `remainingLockMillis` 全程在单个 `synchronized(records)` 内完成；`recordFailure` 内部按 60 秒节流自动清理过期记录，即使调用方不 prune 也不会无限增长；
- **定期清理**：`DefaultLocalServerManager` 启动守护 Timer（5 分钟）调用各协议服务的 `pruneAuthGuards()`；
- 锁定期间即使密码正确也拒绝，攻击者更换用户名无法绕过；客户端 `QuickJoinAttemptLimiter` 保留用于即时 UI 反馈。

## 18. P1 加固

### 18.1 前台服务类型（Android 15）
- 传输服务保持 `dataSync`（短时同步任务）；
- 本机服务器 `LocalServerService` 改为 `specialUse`，并声明 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`，
  避免 Android 15 对 `dataSync` 的 6 小时限制掐断长驻 FTP/SFTP 服务。

### 18.2 更新通道完整性
- 清单只接受 **HTTPS** 来源（`UpdateManifestFetcher.isHttps`），HTTP 一律跳过；
- 清单支持 **Ed25519 签名**（`UpdateManifestVerifier`）：递归去 `sig`、键排序、紧凑 JSON 作为规范化载荷；
  签名端 `scripts/canonical_manifest.py` 使用同一规则；
- 签名无效 → 拒绝；未签名清单默认拒绝（`allowUnsigned=false`），仅测试可放开；
- 公钥内置在 App（`PUBLIC_KEY_BASE64`），私钥保存在项目外 `.update-signing/`（已 gitignore）；
- Ed25519 验签为**纯 Kotlin 实现**（`update/Ed25519.kt`）：Android 系统 Provider 不提供 Ed25519，改用自带实现；
- 签名后的清单同时发布到 GitHub `docs/update.json` 与 Gitee `update.json`（Gitee raw 用原生域名，避免 CDN 广告跳转）；
- 下载 APK 仍校验大小 + SHA-256 + 签名，形成「清单签名 → 文件 hash → APK 签名」三层校验；
- 发布流程：`python scripts/sign_update_manifest.py --key .update-signing/update_signing_private.pem`。

### 18.3 待验证 / 已知问题
- 模拟器环境 DNS 污染：`gitee.com` 在模拟器内被解析到广告域名，导致 App 内 Gitee 清单源请求失败；
  真机环境下用原生 gitee.com 域名可正常访问（已验证 curl 200 且签名校验通过）。
- 更新清单的签名链路已用三条独立路径验证：
  1. Python `canonical_manifest` + OpenSSL 验签；
  2. Kotlin 纯实现 `Ed25519.verify` + 真实发布公钥（单测 `UpdateManifestSignatureTest`）；
  3. Gitee 远端 raw 清单（curl 拉取 200、`sig` 字段存在）。

## 19. P2（兼容性 / 可观测性 / 代码质量）

### 19.1 广播兼容（#8）
- `QuickBeaconCodec.decode` 同时接受 v2 与旧版 v1 广播，便于新客户端发现老版本设备；
- 两种版本的 `code` 字段**一律丢弃**，接收方仍需手动输入对方屏幕接收码（安全不降级）；
- v3 及未知版本继续忽略。

### 19.2 失败计数语义（#10）
- 新增 `QuickJoinFailurePolicy.shouldCountAsCodeFailure`：只有**全部失败都是认证错误**时才计为接收码错误；
- 若混入连接被拒/端口占用/超时等非认证错误，更可能是端口冲突或目标不是 LanLink，不计失败、不锁定。

### 19.3 更新失败可观测性（#11）
- `UpdateManifestFetcher` 新增 `onDiagnostic` 回调与 `describeFailure`：
  区分 DNS 解析失败 / 连接超时 / TLS 握手失败 / 连接被拒 / HTTP 状态码 / 签名问题；
- `UpdateUiState.diagnostic` 保存最多 3 条原因，更新对话框在失败时展示，logcat 同步输出。

### 19.4 代码质量（#12~#16）
- 删除 `QuickQrCodec` 中重复的 `QUICK_QR_PAYLOAD_VERSION` / `quickQrPayloadVersion()` 死代码；
- 移除未使用的 `slf4j-simple` catalog 条目；
- Kotlin Android 插件统一由根工程通过 version catalog 声明（`libs.plugins.kotlin.android`），子模块使用无版本 id；
- 新增 `scripts/check_release_consistency.py`：校验 Gradle 版本号 ↔ `docs/update.json` ↔ 本地 Release APK 的 size/SHA-256
  （默认 APK 差异仅警告，发布前加 `--strict-apk` 视为错误），同时校验清单一律 HTTPS 且带签名。

## 20. 已知可接受风险与清理记录

### 20.1 监听 0.0.0.0（已接受风险）
- FTP/FTPS/SFTP/SCP 服务端绑定 `0.0.0.0`，设计目标是「同一局域网任意设备可连」；
- 风险已由以下措施大幅降低：8/6 位接收码 + 服务端认证失败锁定（5 次/10 分钟，按 IP）、
  加密协议自动优先、FTPS/SFTP 证书/主机密钥固定；
- 仍有残余：同网段任意主机可发起连接尝试（受限于限流）、服务暴露面不区分网段；
- 后续可选项：仅接受私有网段来源 IP、每服务连接数上限、可选「仅可信 Wi-Fi」开关。

### 20.2 构建清理（P2）
- `app/build.gradle.kts` 的 signingConfigs/buildTypes 花括号排版已修正；
- `proguard-rules.pro` 由整包 `-keep` 改为 `-keep,allowoptimization`：保留反射所需的类名/签名，
  同时允许 R8 继续优化；已实测 release 构建通过、模拟器安装启动无崩溃；
- 本机（Windows + JDK 21）`:app:lintDebug` 的 lint worker 会崩，故 `checkReleaseBuilds` 仍为 false，
  静态检查移交 CI（Linux/JDK17）执行；注释已写明原因与命令；
- 接收码展示分组注释与实现一致：8 位「1234 5678」、旧版 6 位「123 456」，并有单元测试锁定。
