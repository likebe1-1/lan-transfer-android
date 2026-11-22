# LanLink v2.3.1 发布说明

> 版本：v2.3.1（versionCode 12）
> 包名：com.manst.lantransfer（覆盖安装可直接保留数据）
> 发布日期：2026-11-22

## 本次重点

v2.3.1 是 v2.3.0 之后的**修复与工程化**版本，核心传输能力与 v2.3.0 一致，无破坏性变更。

### 1. 修复：调试版不再提示「应用内更新」
- debug 包带 `.debug` 后缀且使用不同签名，无法覆盖安装 release 包；
- 此前 debug 版仍会检查并提示更新，点下去必然失败。现在调试版直接禁用应用内更新并给出明确提示。

### 2. 新增：GitHub / F-Droid 双分发渠道
- 新增 `github` 与 `fdroid` 两个分发 flavor，两者的 `applicationId`、版本号与签名完全一致，
  **可以互相覆盖安装**，不会出现「签名不一致」；
- `fdroid` flavor 关闭应用内更新（由 F-Droid 客户端负责升级），符合 F-Droid 政策；
- `fdroid` 渠道输出单个 universal APK（F-Droid 不支持按 ABI 分发矩阵）；
- 已补齐 fastlane 元数据与 F-Droid 上架文档（`docs/FDROID.md`）。

### 3. 开源协议变更为 GPL-3.0-or-later
- 补齐 `LICENSE` 全文，README / 官网 / 架构文档同步；
- 此前仓库未声明明确协议。

### 4. 工程与构建
- 构建工具链对齐 **Gradle 8.9 + AGP 8.7**（此前为 Gradle 9.6 + AGP 8.7 的不稳定组合）；
- **lint 重新纳入 CI 门禁**，并修复了暴露出的 `NewApi` / `CoarseFineLocation` 问题；
- 移除 `gradle.properties` 里硬编码的 JDK 路径（`D:/SDK/JDK21`），改用环境变量 / `scripts/gw.cmd` 解析，
  对其他机器与 CI 友好；
- 新增 GitHub Actions CI（单元测试 + lint + debug 构建 + fdroid release 构建）、
  Dependabot、一键发版脚本 `scripts/release.py` 与版本工具 `scripts/bump_version.py`；
- 新增密钥备份脚本与 `docs/SECRETS.md`、`docs/CI.md`、`docs/RELEASING.md`。

### 5. 文档
- 新增公开安全设计页 `docs/security.html`，说明证书/主机密钥指纹固定、Keystore 加密、
  服务端限流等机制与已知取舍；
- README / 架构文档同步 v2.3.0 的安全基线。

## 升级说明

- 从 v2.3.0 覆盖安装即可，无需卸载；
- 若你之前安装的是 F-Droid 渠道版本，请继续通过 F-Droid 升级（两者签名相同，也可直接覆盖）；
- 调试版（`.debug`）无法覆盖正式版，两者可共存。

## 下载

| 架构 | 文件 |
| --- | --- |
| arm64-v8a（绝大多数手机） | LanLink-v2.3.1-arm64-v8a.apk |
| armeabi-v7a（老设备） | LanLink-v2.3.1-armeabi-v7a.apk |
| universal（通用，体积最大） | LanLink-v2.3.1-universal.apk |

- GitHub Releases（推荐）：https://github.com/likebe1-1/lan-transfer-android/releases/tag/v2.3.1
- Gitee 镜像（国内）：https://gitee.com/likebe1-1/lanlink-android/releases/tag/v2.3.1
- 蓝奏云备用：https://wwbds.lanzoum.com/b0139vopuf （密码：1234）

## 已知限制（与 v2.3.0 相同）

- SFTP 数据包尺寸受 JSch 硬编码 32 KiB 限制，应用侧无法放大；
- 本机服务器监听 `0.0.0.0`，依赖服务端认证限流兜底；
- 仅快速传文件路径做证书/主机密钥指纹固定，手动配置的普通服务器仍为首次信任；
- Keystore 加密异常时降级为明文保存（日志可查）。
