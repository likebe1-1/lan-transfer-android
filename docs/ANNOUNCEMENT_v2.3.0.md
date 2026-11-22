# LanLink v2.3.0 已发布

LanLink v2.3.0 是一次**性能与安全**专项更新，无破坏性变更，覆盖安装即可保留全部数据。

## 这次更新了什么

**更快**
- 传输队列改为 3 路并发 —— 传大量小文件时不再一个一个排队等待；
- FTP/FTPS 数据缓冲区 8 KiB → 128 KiB，SFTP/SCP 拷贝缓冲区 32 KiB → 128 KiB。

**更安全**
- 服务端新增全局限流：即使攻击者不断更换 IP 猜接收码，也会被整体阻断；
- 顺带修掉了「共享网络下邻居输错密码把你一起锁掉」的误伤问题；
- 快速传文件的 FTPS 证书与 SSH 主机密钥改为**指纹固定**，中间人无法冒充；
- 保存在手机里的服务器密码改用 Android Keystore 加密（密钥不可导出）；
- 更新清单加入 Ed25519 数字签名，镜像站也无法篡改更新内容。

## 下载

- GitHub Releases：https://github.com/likebe1-1/lan-transfer-android/releases/tag/v2.3.0
- 蓝奏云备用：https://wwbds.lanzoum.com/b0139vopuf （密码：1234）

从 v2.2.0 起支持应用内直接更新，也可在 App 首页手动「检查更新」。

安全设计与已知取舍详见：https://likebe1-1.github.io/lan-transfer-android/security.html

## 说明

单个大文件的 SFTP 传输速度提升有限——SFTP 的数据包尺寸由 JSch 库硬编码为 32 KiB，
应用侧无法调整，这是库的能力边界，已在发布说明中注明。
