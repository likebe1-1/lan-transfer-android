# LanLink v2.3.1 已发布

这是一个**修复与工程化**版本，核心传输能力与 v2.3.0 相同，建议 v2.3.0 用户升级。

## 主要变化

**修复**
- 调试版不再误提示「应用内更新」（debug 与 release 签名不同，此前点了必然安装失败）。

**新增分发渠道**
- 新增 GitHub / F-Droid 两个渠道，两者签名一致、可互相覆盖安装；
- F-Droid 版本由商店客户端负责升级，不再自行检查更新。

**开源**
- 项目协议正式改为 **GPL-3.0-or-later**，补齐 LICENSE 全文。

**工程**
- 构建工具链对齐 Gradle 8.9 + AGP 8.7，lint 重新纳入 CI 门禁；
- 新增 GitHub Actions CI、一键发版脚本、密钥备份与 Secrets 管理文档；
- 新增公开安全设计页，说明各项安全机制与已知取舍。

## 下载

- GitHub：https://github.com/likebe1-1/lan-transfer-android/releases/tag/v2.3.1
- Gitee（国内）：https://gitee.com/likebe1-1/lanlink-android/releases/tag/v2.3.1
- 蓝奏云：https://wwbds.lanzoum.com/b0139vopuf （密码：1234）

从 v2.2.0 起支持应用内直接更新，也可在 App 首页手动「检查更新」。
