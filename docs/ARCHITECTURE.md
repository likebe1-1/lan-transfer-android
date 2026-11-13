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

非目标（本版不做）：手机当服务端（FTP 服务器模式）、局域网设备自动发现、云同步、断点续传、文件夹递归传输。

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
| JVM 单元测试 | JDK 21 / Gradle 9.6 | 90 个用例全部通过（内嵌 MockFtpServer 与 Apache MINA SSHD 真实服务端） |
| 设备四协议端到端 | Android 10（API 29）Pixel_4_XL 模拟器 + 宿主机真实 FTP/FTPS/SFTP/SCP 服务端 | 4/4 通过（列表、下载、上传回读字节一致、删除、错误映射、MediaStore 落盘） |
| UI 冒烟 + 截图巡游 | 同上 | 通过，截图见 docs/screenshots |
| Release（R8）运行时 | API 29 模拟器安装签名 release APK | SFTP 浏览与下载正常，下载文件 MD5 与源文件一致；mapping.txt 确认 JSch/Commons Net 反射类完整保留 |

验证过程中发现：本机 QEMU 模拟器在高速大流量 TCP 下会静默丢字节（设备端 `nc` 亦可复现，与应用代码无关），因此端到端测试服务端对 FTP/FTPS 数据通道做了限速，真机不受此影响。
