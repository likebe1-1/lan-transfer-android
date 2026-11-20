# LanLink 国内下载镜像

本仓库用于存放 **LanLink** 的国内可直连下载文件（Gitee 在中国大陆访问稳定），是 GitHub Releases 的镜像。

- 主仓库（源码 / 源码发布）：https://github.com/likebe1-1/lan-transfer-android
- 官网：https://likebe1-1.github.io/lan-transfer-android/
- 在线下载页（Gitee 优先、GitHub 兜底）：https://likebe1-1.github.io/lan-transfer-android/downloads.html

## 文件清单

见 [files.json](files.json)：包含每个文件的名称、所属版本、大小、SHA-256 与 Gitee 下载直链。

## 下载地址格式

```
https://gitee.com/likebe1-1/lanlink-android/releases/download/<版本>/<文件名>
```

例如：

```
https://gitee.com/likebe1-1/lanlink-android/releases/download/v2.2.0/LanLink-v2.2.0-arm64-v8a.apk
```

## 说明

- 安装包与 GitHub Releases 中的文件**完全一致**（同一构建产物，SHA-256 相同）；
- Gitee 免费版限制：单文件 ≤ 100MB、仓库与发行版附件总量 ≤ 1GB；
- 应用内更新会优先读取 Gitee 的镜像清单，失败时再回落到 GitHub 与镜像代理；
- 本仓库只存放发布产物与下载清单，不包含源码。
