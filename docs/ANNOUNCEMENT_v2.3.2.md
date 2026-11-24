# LanLink v2.3.2 发布公告

LanLink v2.3.2 已发布：**大文件传输明显更快 + 三项安全加固**。

## 下载

| 平台 | 链接 |
| --- | --- |
| GitHub Releases | https://github.com/likebe1-1/lan-transfer-android/releases/tag/v2.3.2 |
| Gitee Releases（国内） | https://gitee.com/likebe1-1/lanlink-android/releases/tag/v2.3.2 |
| 官网下载页 | https://likebe1-1.github.io/lan-transfer-android/downloads.html |

分 ABI 下载：arm64-v8a / armeabi-v7a / universal（不确定就选 universal）。

## 主要更新

**传输更快**
- 自动协议顺序改为 FTPS → SFTP → SCP（FTPS 更快且同样加密）；
- 下载不再「写两次」：直接写入系统下载目录，断点续传更可靠；
- 并发路数可配置（默认 3 → 5 路），首页右上角「传输设置」；
- SFTP 大文件（≥8 MiB）下载走多通道并行、上传走 MINA SSHD 引擎，常见 2–4 倍；
- SCP 上传改流水线（不再逐块等确认）；下载前预分配文件。

**更安全**
- 接收码 / 密码 / 热点二维码页面禁止截屏与录屏；
- release 包剥离调试日志（不再可能从日志泄露路径、地址、接收码）；
- 显式网络安全配置：全局禁止明文 HTTP，仅信任系统 CA。

## 升级

- 直接覆盖安装，无需卸载，配置与历史记录保留；
- 应用内更新：打开 App 右上角「检查更新」即可。

## 校验

- 签名证书 SHA-256：928480fb42f4962e27c0ad691b205f3cc4492ffee1199420211829dee8d56564；
- 更新清单 Ed25519 签名，下载后校验 size / SHA-256 / 签名 / versionCode。
