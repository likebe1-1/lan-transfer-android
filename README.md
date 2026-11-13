# 局域网传输 LanTransfer

一个 **Android 局域网文件传输** 应用：用手机连接同一局域网内的服务器，通过 **FTP / FTPS / SFTP / SCP** 四种协议浏览远端目录，并上传、下载文件。

> 架构选型与设计取舍见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)（ADR-001）。

| 服务器列表 | 远程浏览 | 传输队列 | 新增服务器 |
| --- | --- | --- | --- |
| ![服务器列表](docs/screenshots/01_server_list.png) | ![远程浏览](docs/screenshots/02_remote_browser.png) | ![传输队列](docs/screenshots/04_transfer_queue.png) | ![新增服务器](docs/screenshots/05_server_editor.png) |

| 本机服务器运行中 | 共享文件管理 |
| --- | --- |
| ![本机服务器](docs/screenshots/06_server_running.png) | ![共享文件](docs/screenshots/07_server_share_files.png) |

截图由仪器测试 [ScreenshotTourTest.kt](app/src/androidTest/java/com/manst/lantransfer/e2e/ScreenshotTourTest.kt) 在模拟器上用真实服务端数据生成。

## 功能

- **服务器管理**：新增 / 编辑 / 删除服务器配置，协议彩色标识，最近使用排序，一键「测试连接」。
- **远程浏览**：目录面包屑与上级导航、目录优先排序、文件大小/修改时间、刷新、错误重试。
- **文件操作**：下载到公共「下载/LanTransfer」目录（无需存储权限）、通过系统文件选择器多选上传、新建文件夹、重命名、删除（二次确认）。
- **传输队列**：串行执行、实时进度与速度、取消、重试、移除、清空已完成；有任务时以 `dataSync` 前台服务保活并显示进度通知。
- **四协议统一体验**：同一套浏览 / 传输界面，协议差异被适配层屏蔽。
- **本机服务器（收发一体）**：App 内一键启动 FTP / FTPS / SFTP / SCP 服务端，另一台手机或电脑用任意一种协议连接本机，即可下载共享文件、上传文件（手机变成局域网文件站）；支持前台服务保活、访问地址展示、共享文件导入/导出/删除。

## 协议支持

| 协议 | 实现库 | 默认端口 | 认证 | 说明 |
| --- | --- | --- | --- | --- |
| FTP | Apache Commons Net 3.11.1 | 21 | 用户名/密码 | 被动模式默认，可切换主动模式；MLSD 优先、LIST 回退 |
| FTPS | Apache Commons Net 3.11.1 | 21（隐式 990） | 用户名/密码 | 显式 `AUTH TLS`（默认）/ 隐式 TLS；可信任局域网自签名证书 |
| SFTP | JSch 0.2.26（mwiede 维护版） | 22 | 用户名/密码 | 基于 SSH2 子系统，流式传输 + 进度回调 |
| SCP | JSch 0.2.26（mwiede 维护版） | 22 | 用户名/密码 | 手写 SCP 线协议（`scp -f / -t`），元数据操作走远端 POSIX shell |

## 本机服务器（服务端模式）

App 同时是客户端和服务端：

```
手机 A（发送方）                              手机 B / 电脑（接收方）
┌─────────────────────────┐   局域网     ┌──────────────────────────┐
│ 本机服务器（一键启动）    │  FTP/FTPS/  │ 任意标准客户端：           │
│  FTP :2121               │  SFTP/SCP   │  - 本 App 的客户端模式     │
│  FTPS :2122（显式 TLS）  │ ──────────> │  - FileZilla / WinSCP      │
│  SFTP/SCP :2222          │  浏览/下载  │  - 命令行 ftp/sftp/scp     │
│  共享目录（导入文件）     │ <────────── │  上传文件（对方收到）      │
└─────────────────────────┘   上传文件   └──────────────────────────┘
```

使用步骤：

1. App 首页右上角「本机服务器」→ 设置用户名/密码（默认端口 2121/2122/2222，可修改）→「开始服务器」；
2. 页面会显示本机局域网 IPv4 地址与四种协议的连接示例（可一键复制），把它发给另一台设备；
3. 另一台设备用任意 FTP/FTPS/SFTP/SCP 客户端按地址与帐号连接，浏览「共享文件」并下载，或上传文件到本机；
4. 本机在「共享文件」区点「刷新」即可看到对方上传的文件，也可「导入文件」把自己要发的文件放进共享目录、或「导出到下载」；
5. 服务器运行期间由前台服务保活（通知栏显示「本机服务器运行中」），不需要保持界面在前台。

## 架构一览

```
客户端：Compose UI ──StateFlow──> ViewModel ──> ServerRepository / TransferManager
                                                     │
                                                     └──> RemoteFileSystem 抽象
                                                            ├── FtpRemoteFileSystem（FTP/FTPS）
                                                            └── SshRemoteFileSystem（SFTP/SCP）

服务端：Compose UI ──> ServerViewModel ──> LocalServerManager
                                              ├── FtpProtocolServer（自实现 FTP/FTPS，含 TLS）
                                              ├── SshProtocolServer（Apache MINA SSHD：SFTP + SCP）
                                              └── ShareRepository（共享目录）+ LocalServerService（前台保活）
```

- 单 Activity + Compose Navigation，MVVM + 单向数据流，手动依赖注入（`AppContainer`）。
- 协议适配层是**纯 JVM 代码**（不依赖 Android API），可直接用 JVM 单元测试对接内嵌 FTP/SSH 服务器做真实报文测试。
- 每任务独立连接、任务结束即关闭；取消传输 = 关闭连接。

## 构建

前置：JDK 21、Android SDK（platform 35、build-tools）、Gradle 由 wrapper 提供。

```powershell
# 统一入口（固定 JDK 21，Windows）
cmd /c "scripts\gw.cmd" :app:assembleDebug
cmd /c "scripts\gw.cmd" :app:assembleRelease

# 产物
#   app/build/outputs/apk/debug/app-debug.apk
#   app/build/outputs/apk/release/app-release.apk（R8 + 资源压缩，已用 lantransfer-release.jks 签名）
```

Release 签名配置位于 `keystore.properties`（含密钥库路径与口令，已在 `.gitignore` 中排除，请勿提交）。

安装：

```powershell
adb install -r app\build\outputs\apk\release\app-release.apk
```

## 测试与验证结果

### 1. JVM 单元测试（无需设备）

```powershell
cmd /c "scripts\gw.cmd" :app:testDebugUnitTest --console=plain
```

**结果：146 个用例全部通过（0 失败）。** 覆盖：

- `core`：远端路径归一化 / 拼接 / 父目录，字节与速度格式化；
- `data`：服务器配置 JSON 持久化（往返、损坏降级、并发写、删除/更新）；
- `transfer`：队列状态机、文件名清洗与重名后缀、速度与进度节流、错误映射；
- `protocol`：用 **MockFtpServer** 与 **Apache MINA SSHD** 在测试内启动真实 FTP/FTPS/SFTP/SCP 服务端，校验列目录、上传下载字节一致性（含 0 字节与 1 MiB 文件）、mkdir/rename/delete、错误密码与未监听端口；
- `server/ftp`（19 个）：自实现 FTP/FTPS 服务端 + 真实客户端走线（LIST/NLST、PASV/EPSV、显式/隐式 TLS、PROT P 数据通道、上传临时文件原子替换、路径穿越 550 等）；
- `server/ssh`（19 个）：MINA SSHD 服务端 SFTP/SCP 回环传输、SCP 元数据 shell 命令（ls -la/mkdir/mv/rm/rmdir）与 POSIX 引号解析、越界拒绝、主机密钥持久化；
- `server` + `data`：共享目录导入/导出/删除、文件名清洗、本机服务器配置持久化。

### 2. 设备集成测试（真实四协议端到端）

宿主机启动四种协议的测试服务端（Python 3，依赖 `pyftpdlib`、`pyopenssl`、`paramiko`、`cryptography`）：

```powershell
python -m pip install pyftpdlib pyopenssl paramiko cryptography
python scripts\e2e_servers.py --masquerade 10.0.2.2
# 输出：FTP :2121  FTPS :2122  SFTP/SCP :2222  用户 test / test
```

启动模拟器后运行：

```powershell
cmd /c "scripts\gw.cmd" :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.manst.lantransfer.e2e.FourProtocolE2ETest --console=plain
```

**结果：在 Android 10（API 29）模拟器上 4 个用例全部通过**（客户端模式）。该测试在 Android 运行时下逐协议真实执行：连接 → 列目录 → 下载 hello.txt → 上传 64KB → 回读字节比对 → 删除，并额外验证错误密码映射 `AuthenticationException`、未监听端口映射 `ConnectionException`、以及传输队列下载落盘 MediaStore。

> 模拟器网络说明：本机 QEMU 模拟器在高速大流量下可能静默丢字节（用设备端 `nc` 复现，非应用问题）。因此 `scripts/e2e_servers.py` 对 FTP/FTPS 数据通道做了限速；脚本内有注释说明，真机不受影响。

### 3. 本机服务器端到端测试（另一台设备连接手机）

两段式验证：

1. **设备内回环**（`ServerModeDeviceTest`、`SshServerDeviceTest`）：在 API 29 模拟器上启动本机服务器，用 App 自身客户端连 127.0.0.1 验证四种协议 list/下载/上传回读/删除；并验证客户端上传的文件出现在共享目录。
2. **宿主作为“另一台设备”**（`ServerModeHostTestBridge` + `scripts/server_mode_hosttest.py`）：模拟器启动服务器后，通过 `adb forward` 把端口映射到宿主机，宿主机用 Python（ftplib / FTP_TLS / paramiko）以普通客户端身份完成：
   - FTP：列目录、下载校验、上传回读；
   - FTPS（显式 TLS）：同上；
   - SFTP：列目录、下载校验、上传 64KB 回读；
   - SCP：`LC_ALL=C ls -la`、`scp -f/-t` 上传下载、`mkdir/mv/rm/rmdir` 元数据操作；
   设备端测试随后断言宿主上传的四个文件全部出现在共享目录。

**结果：宿主机 4/4 协议全部通过；设备端断言通过（OK 16 tests）。**

### 4. UI 冒烟测试

```powershell
cmd /c "scripts\gw.cmd" :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.manst.lantransfer.ui.AppSmokeTest --console=plain
```

**结果：通过**（含 `AppSmokeTest` 首屏/新增服务器、`ServerScreenSmokeTest` 本机服务器页、`ShareRepositoryTest` 共享目录、`ScreenshotTourTest` / `ServerScreenshotTest` 截图巡游）。

### 5. Release（R8 混淆）运行时验证

- `:app:assembleRelease` 产物用 `apksigner` 校验签名通过（CN=LanTransfer）；
- `mapping.txt` 确认 JSch / Commons Net 反射类被完整保留（ProGuard keep 规则生效）；
- 在 API 29 模拟器安装**签名 release APK**，真实添加 SFTP 服务器并浏览远程目录、下载文件到 `Download/LanTransfer/`，下载文件 MD5 与宿主端源文件一致。证明 R8 混淆后 JSch 反射与传输链路正常；
- 同一 release APK 上真实启动本机服务器，宿主机用 FTP/FTPS/SFTP/SCP 四协议完成上传下载回读（4/4 通过），证明 R8 混淆下 MINA SSHD、BouncyCastle 自签证书与自实现 FTP 服务端运行正常。

### 6. 真机验证（Xiaomi 25080RABDC · Android 16 · arm64-v8a）

在真实手机上（无线 ADB 连接）完成了以下验证：

- **设备内功能测试：13 个用例全部通过**（`ServerModeDeviceTest` 本机服务器四协议回环收发 + 共享目录上传落盘；`SshServerDeviceTest` SFTP/SCP 服务端；`ShareRepositoryTest` 共享目录导入/导出/删除）；
- **跨设备端到端：宿主机作为「另一台设备」通过无线 adb forward 连接手机服务器，FTP/FTPS/SFTP/SCP 4/4 协议通过**（列目录、下载校验、上传回读、SCP 元数据命令），设备端测试断言宿主机上传的 4 个文件全部出现在共享目录；
- **签名 release APK（v1.1.0, R8）在真机安装并启动成功**，无崩溃/类缺失；
- 说明：真机当时处于锁屏状态，需要解锁屏幕的 3 个 UI 仪器用例无法自动执行（已在模拟器上全部通过）；手机仍保持 release 版安装，解锁后可直接体验。

## 使用

### 客户端模式（连接其他服务器）

1. 手机与服务器连同一局域网 Wi-Fi；
2. 首页右下角「+」新增服务器：选择协议、填主机/端口/用户名/密码（FTPS 按需选择显式或隐式、信任自签名证书）；
3. 点「测试连接」确认可达后保存；
4. 点击服务器进入远端浏览：点目录进入、点文件下载；右上角上传按钮从系统文件选择器选文件；长按条目可重命名 / 删除；工具栏可新建文件夹；
5. 顶栏队列图标查看进度，支持取消与重试。

### 服务端模式（手机之间互传）

见上文「本机服务器（服务端模式）」。两台手机都装本 App 时，A 开启本机服务器并导入文件，B 在客户端模式新增服务器、协议选 FTP/FTPS/SFTP/SCP 任意一种，地址填 A 页面显示的 IP:端口、帐号填 A 设置的用户名密码，即可浏览并下载 A 的文件；B 上传的文件会出现在 A 的共享文件里。

## 目录结构

```
app/src/main/java/com/manst/lantransfer/
├── core/        模型、异常、远端路径与格式化工具
├── protocol/    客户端 RemoteFileSystem 抽象 + FTP/FTPS/SFTP/SCP 适配器
├── server/      本机服务端：LocalServerManager、FTP/FTPS 自实现服务端、MINA SSHD（SFTP/SCP）、前台服务
├── data/        服务器配置仓库、共享目录仓库（JSON 持久化）
├── transfer/    传输队列、MediaStore 下载、通知与前台服务
├── ui/          Compose 界面（列表 / 编辑 / 浏览 / 队列 / 本机服务器）与 ViewModel
├── AppContainer.kt / LanTransferApplication.kt / MainActivity.kt
app/src/test/          JVM 单元测试（含内嵌 FTP/SSH 服务器）
app/src/androidTest/   设备集成测试（四协议端到端 / 截图巡游 / UI 冒烟）
docs/ARCHITECTURE.md   架构决策记录
docs/screenshots/      应用截图
scripts/               构建入口 gw.cmd / gw.ps1、客户端 E2E 服务端 e2e_servers.py、服务端 E2E 宿主客户端 server_mode_hosttest.py
```

## 已知限制

- 不支持断点续传与文件夹递归上传/下载（单文件传输 + 失败重试）；SCP 协议本身不提供 REST 续传；
- SFTP/SCP 未做主机密钥指纹固定校验（首次连接直接信任）；FTPS 默认允许信任自签名证书，可在配置中关闭；
- 服务器密码以明文保存在应用私有目录（`filesDir/servers.json`，其他应用无法读取），未做加密存储；
- 应用进程被系统杀死后，内存中的传输队列不恢复；
- SCP 的列目录/建目录/重命名/删除依赖远端 POSIX shell（仅支持 SCP 传输、无 shell 的服务器只能下载/上传文件）；
- 本机服务器仅密码认证、单个共享目录、FTP 服务端只支持被动模式（PASV/EPSV）；FTPS 使用进程内自签证书，App 重启后证书会重新生成；
- 共享目录位于应用外部私有目录（Android/data/…/files/share），卸载 App 会随之删除；其他设备上传后需在页面「刷新」查看；
- 未实现局域网服务发现（需手动填写地址）。

## 安全说明

- 所有流量仅在局域网内传输，应用不收集、不上传任何数据；
- FTPS/SFTP/SCP 走标准 TLS/SSH 加密；FTP 为明文协议，仅在可信局域网使用；
- `keystore.properties` 与签名密钥不入库。