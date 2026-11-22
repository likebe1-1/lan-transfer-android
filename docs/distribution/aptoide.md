# Aptoide 发布材料（LanLink v2.3.1）

> Aptoide 是独立的应用商店（葡萄牙），开发者可自助上传，**支持使用自己的签名**（不会重签），
> 入口：https://www.aptoide.com/ → Developer → "Upload app"（需注册开发者账号）

## 关键要点

| 项 | 说明 |
| --- | --- |
| 上传 APK | 用 **github flavor universal**（`LanLink-v2.3.1-universal.apk`），保持签名一致 |
| 签名 | Aptoide 保留开发者签名；与官网/F-Droid/酷安版本可互相覆盖 |
| 应用名 | LanLink |
| 包名 | com.manst.lantransfer |
| 分类 | Tools / File Transfer |
| 内容分级 | 所有人 |

## 英文商店文案（Aptoide 以英文为主）

**Short description（≤ 80 字符）**
```
FTP/SFTP server & client for your LAN — no cloud, no account.
```

**Full description**
```
LanLink turns your Android phone into a LAN file server and a file transfer client — no cloud, no account.
Run an FTP, FTPS, SFTP and SCP server on the phone and connect from a PC (FileZilla, WinSCP, Windows Explorer, ssh/scp) or another phone; or use it as a client to reach your NAS or any server.

• Four protocols — FTP, FTPS, SFTP and SCP. Use it as a client, or turn the phone into a server.
• Quick transfer — pick files, get an 8-digit code or QR code, the other device receives them. Folders,
  resume and auto-save into Downloads are supported.
• Transfer queue — 3 concurrent tasks, live speed/progress/ETA, automatic retry, history.
• Network tools — LAN discovery, diagnostics, port tests, one-click hotspot join.

Security: quick transfer pins the FTPS certificate and SSH host key fingerprints; automatic fallback only
uses encrypted transports (SFTP → SCP → FTPS); server-side auth throttling; credentials encrypted with
Android Keystore; update manifest verified with an Ed25519 signature.

Privacy: no data collection, no ads, no analytics. Files never leave the local network.

Open source under GPL-3.0-or-later: https://github.com/likebe1-1/lan-transfer-android
```

## 素材

- 图标 512×512：`build/icons/render/store_512.png`
- 截图：`docs/screenshots/01..06`（建议 5 张以上）
- 应用内横幅（feature graphic）可选：1280×720（可临时用截图拼版，如需我可以生成）

## 注意事项

- Aptoide 有自动扫描（VirusTotal 类）与人工抽检；
- 该平台**可能**会提示用户"来自未知来源"，属正常；
- 上架后可用 Aptoide 的 Aptoide Uploader 或开发者后台上传新版本（也可开自动跟进 GitHub Release）。
