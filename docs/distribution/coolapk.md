# 酷安发布材料（LanLink v2.3.1）

> 酷安是"开发者社区 + 应用分发"平台；发布需注册酷安账号并成为开发者（个人开发者可发）。
> 入口：酷安 App →「我的」→「开发者中心」→ 发布应用；或网页版 https://developer.coolapk.com/

## 应用名称 / 一句话简介（≤ 40 字）

- **名称**：LanLink
- **一句话**：手机变 FTP/SFTP 服务器，同一 Wi‑Fi 直传文件，不经过云端

## 应用介绍（正文）

```markdown
LanLink 可以把手机变成局域网里的 FTP / SFTP 服务器，也是一个 FTP/FTPS/SFTP/SCP 客户端——电脑（FileZilla、WinSCP、资源管理器）或其他手机可直接连上来传文件；两台手机之间也能用 8 位接收码/二维码快传。不经过云端、不需要账号、不收集任何数据。

**核心功能**
- 四种协议：FTP / FTPS / SFTP / SCP，既能连 NAS/服务器，也能把手机变成服务器
- 快速传文件：选文件 → 8 位接收码 / 二维码 → 对方直接接收，支持文件夹与断点续传
- 传输队列：3 路并发、实时速度/剩余时间、失败自动重试、历史记录
- 网络工具：局域网扫描、诊断、端口测试、一键加入热点

**安全**
- 快速传文件对 FTPS 证书与 SSH 主机密钥做指纹固定，防中间人
- 默认只用加密协议，明文 FTP 需手动选择
- 服务端防爆破限流；服务器密码用系统 KeyStore 加密存储

**开源**
GPL-3.0 开源：https://github.com/likebe1-1/lan-transfer-android
官网：https://likebe1-1.github.io/lan-transfer-android/
```

## 上传的 APK

**用 github flavor**（酷安用户习惯应用内更新，与官网版一致）：

```
app/build/outputs/apk/github/release/app-github-universal-release.apk
（或 Release 里的 LanLink-v2.3.1-universal.apk）
```

> 注意：酷安会保存并使用上传的 APK，**签名与你官网版一致**，用户从酷安安装后可直接被官网的应用内更新接管。

## 分类 / 标签

- 分类：**工具**（次选：系统/文件管理）
- 标签：局域网、文件传输、开源、FTP、SFTP

## 截图

用 `docs/screenshots/` 下 01–06（服务器列表、远程浏览、传输队列、服务器编辑、本机服务器、快速传文件）。
酷安要求至少 3 张，建议 5–6 张。

## 需要准备的信息

| 项 | 值 |
| --- | --- |
| 包名 | `com.manst.lantransfer` |
| 版本名 / 版本号 | 2.3.1 / 12 |
| 隐私政策链接 | 项目暂无独立页面；可先用官网安全页 https://likebe1-1.github.io/lan-transfer-android/security.html 说明"不收集数据" |
| 开源协议 | GPL-3.0-or-later |
| 开发者名称 | likebe1-1 |

## 注意事项

- 酷安审核会实际安装测试，确认能启动、不闪退、权限说明合理；
- 若被问到"文件传输类应用权限"，按 README/安全页的说明回答；
- 发布后可回复社区提问（本项目无云端，说明清楚上传/下载都在局域网内即可）。
