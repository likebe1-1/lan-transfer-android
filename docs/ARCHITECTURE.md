# 架构决策记录：局域网文件传输 App

- 文档编号：ADR-001
- 产品名：局域网传输（LanTransfer）
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
│  TransferManager（串行传输队列 + 进度 + 保活）   │
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
5. **传输串行**：局域网带宽下多任务并发只会互相抢带宽并增加失败面，队列串行执行、展示逐条进度。
6. **前台服务保活**：有活动任务时启动 `TransferService`（`dataSync` 类型）显示通知；队列清空即停止。
7. **下载落盘**：Android 10+ 用 `MediaStore.Downloads` 写入公共目录 `Download/LanTransfer/`，无需存储权限；上传用系统文件选择器（SAF）返回的 content Uri 流式读取。

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

- 默认信任自签名证书（局域网设备常见），可在服务器配置中关闭，改为严格校验系统 CA；
- 主机密钥（SFTP/SCP）本版不做 known_hosts 固定校验，文档标注为已知限制；
- 密码以明文存于应用私有目录的 JSON（`filesDir/servers.json`，其他应用不可读），本版不做加密，README 中说明。

## 5. 数据与状态

- `ServerRepository`：`servers.json`（org.json 手写序列化 + 原子写临时文件后 rename），`StateFlow<List<ServerProfile>>` 驱动 UI；单元测试传入临时文件，可在 JVM 直接测。
- `TransferManager`：`StateFlow<List<TransferTask>>`；任务串行执行，进度按 100ms 节流推送到 UI，并计算瞬时速度；`cancel` 关闭连接，`retry` 重新入队。
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

1. 不支持断点续传（SCP 协议本身不支持 REST；FTP/SFTP 可在 v2 增加）；
2. 不做 SFTP 主机密钥指纹固定；
3. 无文件夹递归上传/下载；
4. 无局域网服务发现（可后续加 mDNS/端口扫描）；
5. 密码加密可升级为 Android Keystore 派生密钥的 AES-GCM 存储。

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