# 真机测试报告（Xiaomi 25080RABDC · Android 16 · arm64-v8a）

- 日期：2026-11-22 21:23–21:40
- 设备：25080RABDC（lapis），Android 16，arm64-v8a
- 版本：**v2.3.1（versionCode 12）**，来自 GitHub Release 的 `app-github-arm64-v8a-release.apk`
- 连接方式：无线 adb（serial `tox8wgw8cascvotc`）
- PC：Windows，IP 192.168.3.x（与手机同网段），Python 3.12 / paramiko 5.1.0 / OpenSSH 9.5

## 1. 安装与启动

| 项 | 结果 |
| --- | --- |
| adb install（覆盖 debug 包共存） | ✅ Success |
| 安装后版本 | `versionCode=12 versionName=2.3.1` |
| 冷启动 | ✅ 正常进入首页，无 FATAL / 无 ClassNotFound |
| 首次启动安全提示 | ✅ 「请在你信任的局域网下使用」弹窗正常，点「我已知晓」关闭 |
| 首页 | ✅ 快速传文件卡片、服务器列表空态、底部「添加服务器」均正常渲染 |

## 2. 应用内更新

| 项 | 结果 |
| --- | --- |
| 启动时自动检查 | ✅ 拉到 `2.3.1`（日志：`更新清单检查结果：2.3.1`） |
| 多源回落 | ✅ jsDelivr 请求失败、ghproxy 超时后，仍从剩余源成功获取（诊断日志清晰） |
| 已是最新 | ✅ 不弹更新窗（版本相同） |

## 3. 本机服务器（手机当服务器）

配置：用户名 `android`、密码 `lantest123`、FTP 2121 / FTPS 2122 / SSH 2222，地址 `192.168.3.32`。

| 协议 | 结果 | 细节 |
| --- | --- | --- |
| **FTP** | ✅ PASS | banner `220 LanTransfer FTP Server ready`；登录、PASV、STOR、RETR 回读一致、DELE 全部正常 |
| **FTPS（显式 TLS）** | ✅ PASS | AUTH TLS + PROT P，8 KiB 随机数据上传/回读一致 |
| **SFTP** | ✅ PASS | 列目录 + 上传/下载往返一致 |
| **SCP** | ✅ PASS | `ls -la` + 上传/下载往返一致 |

> 说明：仓库里的 `server_mode_hosttest.py` 默认走 adb forward（为模拟器设计），对真机同网段直连会误判 FTP/FTPS 失败；
> 本次 FTP/FTPS 用 Python `ftplib` / `FTP_TLS` 直连真机 IP 验证通过（见上方结果）。

## 4. 服务端认证限流（真实攻击面验证）

| 协议 | 测试 | 结果 |
| --- | --- | --- |
| **FTP** | 连续 4 次错误密码后第 5 次返回 `421 Too many failed login attempts` | ✅ 锁定生效 |
| **FTP** | 锁定期间用**正确密码**登录 | ✅ 仍被拒绝（421），证明限流在服务端而非客户端 |
| **SSH（SFTP/SCP）** | 6 次错误密码（paramiko）后使用正确密码 | ✅ 被拒绝，SSH 侧限流同样生效 |

## 5. 发现的问题

| 严重度 | 问题 | 说明 |
| --- | --- | --- |
| 低（工具） | `scripts/server_mode_hosttest.py` 不适用于真机直连 | 该脚本为模拟器 + adb forward 设计，真机同网段测试需直连（本次已手工验证四协议全通过）。建议脚本加 `--direct` 模式跳过 adb forward。 |
| 提示 | MIUI 首次选择文件会触发「应用锁/指纹」 | 选择文件时 MIUI 要求验证身份，属系统行为，非 App 缺陷；真机自动化测试需人工确认一次。 |

## 6. 未覆盖

- 快速传文件的双机互传（需要第二台设备在线）
- 扫码 / 二维码加入
- 热点一键组网（会改变手机网络环境，需人工在机上进行）
- 断点续传（需要超大文件与人为中断）

## 结论

**v2.3.1 release 包在真机上启动、本机服务器四协议、服务端认证限流均验证通过**；应用内更新链路正常（多源回落工作）。
