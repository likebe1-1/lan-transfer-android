# LanLink 发版 SOP（RELEASING）

> 适用仓库：LanLink（Android 局域网传输 App）
> 相关脚本：`scripts/release.py`、`scripts/bump_version.py`、`scripts/sign_update_manifest.py`、
> `scripts/sync_gitee.py`、`scripts/check_release_consistency.py`、`scripts/gen_file_index.py`、
> `scripts/probe_mirrors.py`
> 相关文件：`app/build.gradle.kts`、`docs/update.json`、`docs/files.json`、`docs/gitee/files.json`

本文是端到端发版手册：**dry-run 预览 → execute 执行 → GitHub Release → 清单签名 → Gitee 同步 → 自检 → 站点/公告**。

---

## 1. 安全默认（先读）

`scripts/release.py` **默认 dry-run**：

- 只打印计划、将写入 `docs/update.json` 的内容摘要、将执行的命令清单；
- **不修改任何文件、不构建、不上传、不同步**；
- 即使带 `--bump patch` 也不会写回 `app/build.gradle.kts`；
- 必须显式加 `--execute` 才真正改文件/构建/签名/同步。

脚本**不会**做的事（始终人工）：

- 不创建 GitHub Release、不上传资产（无 GitHub 写权限 API 调用）；
- 不 `git commit` / `git push`；
- 不部署站点、不发公告；
- 不打印任何 token、密钥内容。

只发布 **3 个 ABI**：`arm64-v8a`、`armeabi-v7a`、`universal`（x86/x86_64 产物忽略）。

---

## 2. 脚本职责一览

| 脚本 | 作用 | 是否改文件 |
| --- | --- | --- |
| `scripts/release.py` | 一键编排：bump → 构建 → 收集 APK → 更新清单 → 签名 → Gitee → 自检 → 打印人工步骤 | `--execute` 时改 |
| `scripts/bump_version.py` | 读取/递增 `versionCode`/`versionName`（默认 dry-run） | `--execute` 时改 |
| `scripts/sign_update_manifest.py` | 用 Ed25519 私钥给 `docs/update.json` 生成 `sig` | 改 `update.json` |
| `scripts/sync_gitee.py` | 从 GitHub Releases 拉资产同步到 Gitee，并生成 `docs/gitee/files.json` | 改 `docs/gitee/files.json` |
| `scripts/check_release_consistency.py` | 校验 Gradle 版本 ↔ `update.json` ↔ 本地 APK size/sha256 ↔ HTTPS ↔ 签名 | 只读 |
| `scripts/gen_file_index.py` | 从 GitHub Releases 生成站点文件清单 `docs/files.json` | 改 `docs/files.json` |
| `scripts/probe_mirrors.py` | 探测镜像可用性，为 `docs/mirrors.json` 的 `enabled` 提供依据 | 只读（打印结果） |

---

## 3. 发布前准备

### 3.1 工具链

- JDK 17+（推荐 21）；Windows 统一走 `scripts/gw.cmd`（自动设置 `JAVA_HOME`，回退 `D:\SDK\JDK21`）；
- Android SDK（`compileSdk = 35`）；
- Python 3.8+（脚本仅用标准库）；
- OpenSSL（Windows 可用 Git 自带的 `C:\Program Files\Git\usr\bin\openssl.exe`）；
- Gitee 同步需要可访问 GitHub API 与 Gitee API。

### 3.2 凭据（详见第 6 节，绝不入库）

| 用途 | 变量/路径 | 说明 |
| --- | --- | --- |
| Release 签名（APK） | `LANT_KEYSTORE_FILE` / `LANT_KEYSTORE_PASSWORD` / `LANT_KEY_ALIAS` / `LANT_KEY_PASSWORD` | 也可放 `~/.lanlink/keystore.properties`（首行必须是注释）；项目内 `keystore.properties` 仅兼容回退 |
| 更新清单签名 | `.update-signing/update_signing_private.pem`（已 gitignore） | 丢失将无法再发布可被 App 信任的更新，务必备份 |
| Gitee 同步 | `GITEE_TOKEN` 或 `~/.lanlink/gitee_token` | 缺失时 release.py 跳过并提示，不视为失败 |
| GitHub 资产 digest（可选） | `GITHUB_TOKEN` | 供 `sync_gitee.py`/`gen_file_index.py` 读取资产 SHA-256 |

### 3.3 版本号与 changelog

- `versionCode` **只能单调递增**（Android 不允许降级安装），每次发版 +1；
- `versionName` 采用 `X.Y.Z`：bugfix 用 `--bump patch`，功能版本用 `--bump minor`；
- 脚本会把 `docs/update.json` 的 `changelog` 首行更新为 `LanLink vX.Y.Z：…`，**正文保留**。
  正式发布前请先人工编辑 `docs/update.json` 的 changelog 正文（或运行时传 `--changelog "..."`）。

---

## 4. 标准发布流程

> 以下命令均在**仓库根目录**执行。Windows PowerShell 示例；bash 把 `python scripts\xxx.py` 写成 `python scripts/xxx.py` 即可。

### 步骤 0：预检

```powershell
git status                        # 工作区应干净（仅保留本次发版要改的文件）
python scripts\check_release_consistency.py            # 当前已发布版本应一致
```

### 步骤 1：dry-run 预览（默认，安全）

```powershell
python scripts\release.py --help
python scripts\release.py                     # 预览：不改版本
python scripts\release.py --bump patch        # 预览：versionCode +1、versionName 递增
```

检查 dry-run 输出中的：

1. 版本号递增计划；
2. 扫描到的 3 个 APK（size/sha256）与忽略项；
3. `docs/update.json` 将写入的版本、changelog 首行、`releasePageUrl`、3 个 `urls`、保留字段（`lanzouUrl` 等）与 `sig` 重签说明；
4. 将执行的命令清单与将修改的文件清单。

> dry-run 必须**不产生任何改动**：完成后 `git status --porcelain` 应与执行前一致。

### 步骤 2：execute 执行（首次建议 `--skip-gitee`）

```powershell
python scripts\release.py --bump patch --execute --skip-gitee
```

依次执行：写回 `app/build.gradle.kts` → `:app:assembleRelease` → 扫描 3 个 APK →
复制重命名为发布资产 → 写入 `docs/update.json` → Ed25519 签名 → 一致性自检（`--strict-apk`）。

产物：

- `app/build/outputs/apk/release/app-<abi>-release.apk`（Gradle 原始产物，已 gitignore）；
- `app/build/outputs/release-assets/vX.Y.Z/LanLink-vX.Y.Z-<abi>.apk`（**上传 GitHub 用**，3 个）。

> 为什么先 `--skip-gitee`：`sync_gitee.py` 从 **GitHub Releases** 拉资产，
> GitHub Release 还没创建时必然失败。先完成构建/清单/签名，发布 Release 后再同步 Gitee。

如果只想重刷清单/签名（版本号与构建都不变）：

```powershell
python scripts\release.py --execute --skip-build --skip-gitee
```

> `--skip-build` 会用 `output-metadata.json` 校验现有 APK 的真实版本号，避免误用旧产物。

### 步骤 3：GitHub Release 与资产上传（人工）

1. 打开 <https://github.com/likebe1-1/lan-transfer-android/releases/new>；
2. Tag 填 `vX.Y.Z`（与 `versionName` 一致，目标 `master`），标题 `LanLink vX.Y.Z`；
3. 上传 `app/build/outputs/release-assets/vX.Y.Z/` 下 3 个 APK（文件名必须与 `LanLink-vX.Y.Z-<abi>.apk` 完全一致，
   否则 `docs/update.json` 的 `urls` 会 404）；
4. 说明区粘贴 `docs/RELEASE_NOTES_vX.Y.Z.md` 内容（没有就先补写）；
5. 发布后核对每个资产的大小与 SHA-256 与 release.py 输出一致。

### 步骤 4：清单签名（release.py 已自动执行，人工兜底）

```powershell
python scripts\sign_update_manifest.py --key .update-signing/update_signing_private.pem --in docs/update.json
```

注意：签名是**规范化 JSON** 的签名（递归去 `sig`、键排序、紧凑分隔符），见 `scripts/canonical_manifest.py`。
**任何**对 `docs/update.json` 的字段修改（含 `lanzouUrl`）都必须重新签名，否则 App 验签失败拒绝更新。

### 步骤 5：Gitee 同步（GitHub Release 发布后）

```powershell
$env:GITEE_TOKEN = "<你的 Gitee 私人令牌>"     # 只在本终端生效；不要写入文件/命令历史
python scripts\sync_gitee.py --tag vX.Y.Z
Remove-Item Env:GITEE_TOKEN                     # 用完清掉
```

或重新执行（已完成构建，只同步）：

```powershell
python scripts\release.py --execute --skip-build --skip-gitee
python scripts\sync_gitee.py --tag vX.Y.Z
```

产物：`docs/gitee/files.json`（Gitee 直链清单）。确认 Gitee Release 页面已有 vX.Y.Z 与 3 个资产。

### 步骤 6：发布一致性自检

```powershell
python scripts\check_release_consistency.py --strict-apk
```

必须看到 `发布一致性检查通过`。检查项：Gradle 版本 ↔ `update.json`、每个 ABI 的 size/sha256 ↔ 本地 APK、
清单 `sig` 存在、所有下载 URL 为 HTTPS。

### 步骤 7：站点与公告

```powershell
$env:GITHUB_TOKEN = "<GitHub token>"           # 可选：读取资产 digest
python scripts\gen_file_index.py                # 生成/更新 docs/files.json
python scripts\probe_mirrors.py                 # 探测镜像，必要时调整 docs/mirrors.json 的 enabled
Remove-Item Env:GITHUB_TOKEN
```

1. 部署 `docs/` 到 GitHub Pages / Gitee Pages（`docs/update.json`、`docs/files.json`、`docs/gitee/files.json` 一并生效）；
2. 确认线上 `update.json` 与本地一致，且 GitHub raw / Gitee raw 返回 200 且带 `sig`；
3. 发布 `docs/RELEASE_NOTES_vX.Y.Z.md`、`docs/ANNOUNCEMENT_vX.Y.Z.md`，更新下载页；
4. 真机 App 内「检查更新」验证：下载 → size/SHA-256 校验 → Ed25519 清单验签 → APK 签名校验。

---

## 5. 命令速查

| 目的 | 命令 |
| --- | --- |
| 查看当前版本 | `python scripts\bump_version.py --show` |
| 预览递增（不写文件） | `python scripts\bump_version.py --bump patch` |
| 手动递增版本号 | `python scripts\bump_version.py --bump minor --execute` |
| 发布预览（dry-run） | `python scripts\release.py` / `python scripts\release.py --bump patch` |
| 正式发布（先跳过 Gitee） | `python scripts\release.py --bump patch --execute --skip-gitee` |
| 只重建清单+签名+自检 | `python scripts\release.py --execute --skip-build --skip-gitee` |
| 清单签名 | `python scripts\sign_update_manifest.py --key .update-signing/update_signing_private.pem --in docs/update.json` |
| Gitee 同步 | `python scripts\sync_gitee.py --tag vX.Y.Z` |
| 一致性自检 | `python scripts\check_release_consistency.py --strict-apk` |
| 站点文件清单 | `python scripts\gen_file_index.py` |
| 镜像探测 | `python scripts\probe_mirrors.py` |

---

## 6. 环境变量与凭据

| 变量 | 用途 | 读取方 | 缺失时行为 |
| --- | --- | --- | --- |
| `GITEE_TOKEN` | Gitee 私人令牌（`projects` 权限） | `sync_gitee.py` / `release.py` | release.py 跳过 Gitee 并提示；单独运行 sync_gitee.py 会报错退出 |
| `GITHUB_TOKEN` | 读取 GitHub Release 资产 digest（可选） | `sync_gitee.py` / `gen_file_index.py` | 退化为下载后本地计算 SHA-256 |
| `LANT_KEYSTORE_FILE` | Release APK 签名 keystore 路径 | Gradle | 无签名材料时构建 unsigned，脚本会因缺 APK/一致性失败而停止 |
| `LANT_KEYSTORE_PASSWORD` | keystore 口令 | Gradle | 同上 |
| `LANT_KEY_ALIAS` | key alias | Gradle | 同上 |
| `LANT_KEY_PASSWORD` | key 口令 | Gradle | 同上 |

凭据规则：

- `GITEE_TOKEN` 推荐放 `~/.lanlink/gitee_token`（项目外、已 gitignore）或 CI Secret；
  临时使用时 `$env:GITEE_TOKEN="..."`，**用完 `Remove-Item Env:GITEE_TOKEN`**；
- 不要把 token 写进脚本、命令示例、截图、issue/PR 或聊天记录；
- 本项目 `.gitignore` 已排除 `.update-signing/`、`*.pem`、`*_token`、`.env` 等，
  提交前仍应 `git status` 自查；
- release.py 与 sync_gitee.py **不会打印** token（命令清单里也不含 token 参数）；
- 更新清单私钥 `.update-signing/update_signing_private.pem` 与公钥 `update_signing_public.b64`：
  私钥离线备份；公钥内置在 App（`UpdateManifestVerifier.PUBLIC_KEY_BASE64`）。
  **私钥丢失 = 无法再发布可被现有用户信任的更新**（只能发新版 App 换公钥，需用户手动重装）。

---

## 7. 回滚与应急

### 7.1 execute 中途失败

release.py 任一步失败会立即停止、打印中文原因并提示已修改的文件；按提示处理：

```powershell
git checkout -- app/build.gradle.kts docs/update.json   # 回滚版本号与清单
rmdir /s /q app\build\outputs\release-assets           # 可选：清掉本次资产副本
```

修复原因（如 JDK/SDK、签名材料、token）后重跑。构建产物与 staging 目录都在 `app/build/`（已 gitignore），可随时删除。

### 7.2 已 bump/提交但尚未发布 Release

- 若还没发 GitHub Release、也没提交：直接 `git checkout` 回滚，或删掉对应 commit；
- 若已提交但未发布：可以 `git revert` 该 commit；
- 注意：**versionCode 一经发布就不可回退复用**。尚未发布时回退版本号是安全的；已发布后必须用更大的 versionCode 发 hotfix。

### 7.3 已发布版本发现严重问题

Android 不允许降级安装，正确做法是**发 hotfix**（versionCode 更大）：

1. `python scripts\release.py --bump patch --execute --skip-gitee`；
2. 创建新的 GitHub Release（可把有问题的 Release 标记为 Pre-release 或删除其资产，避免继续被下载）；
3. 重新签名 `docs/update.json` 指向 hotfix，并确保 `lanzouUrl` 等备用链接更新；
4. 同步 Gitee、更新 `docs/files.json`、部署站点、发公告说明。

### 7.4 清单/资产被篡改或镜像不一致

- `update.json` 有 Ed25519 签名，App 端会拒绝被篡改的清单；排查时以 GitHub 仓库 `docs/update.json` 为准；
- 某镜像异常：`python scripts\probe_mirrors.py` 后调整 `docs/mirrors.json` 的 `enabled` 并部署；
- Gitee 资产缺失：重跑 `python scripts\sync_gitee.py --tag vX.Y.Z`（已存在的附件会跳过）。

### 7.5 误同步 / 误发布

- GitHub Release：在网页删除该 Release 及资产（或转为 draft/prerelease）；
- Gitee：在 Gitee Release 页面删除附件/版本；
- 随后用 hotfix 版本 + 重签 `update.json` 恢复更新通道；
- 站点部署失误：回滚 `docs/` 的提交并重新部署。

---

## 8. 故障排查

| 现象 | 常见原因 / 处理 |
| --- | --- |
| `无法在 app/build.gradle.kts 中解析 versionCode / versionName` | 版本号行格式被改动；保持 `versionCode = N`、`versionName = "X.Y.Z"` 各自独占一行 |
| 构建失败 | JDK 版本/SDK 缺失；Windows 用 `scripts\gw.cmd`；确认 `JAVA_HOME` |
| `缺少 APK：...` | 用了 `--skip-build` 但产物不存在；去掉 `--skip-build` 重新构建 |
| `本地产物版本 ... 与目标 ... 不一致` | `--skip-build` 复用了旧 APK；重新构建或先 `--bump` |
| 签名步骤失败 | `openssl` 不可用或私钥缺失；安装 Git for Windows / 恢复私钥 |
| Gitee 同步失败 | GitHub Release 尚未发布/资产未上传、token 无效或权限不足、Gitee 限流；先 `--skip-gitee`，发布后再同步 |
| `check_release_consistency` 报 APK 不一致 | 构建产物与清单不匹配；重跑 release.py 的清单+签名步骤 |
| App 提示清单验签失败 | `update.json` 改了字段但没重签；重新执行签名脚本 |
| 下载 404 | Release 资产名与 `update.json` 的 `urls` 不一致（必须 `LanLink-vX.Y.Z-<abi>.apk`） |

---

## 9. 发布检查清单

- [ ] `git status` 干净；当前版本号与 `docs/update.json` 一致
- [ ] dry-run 输出符合预期，且 dry-run 后工作区无变化
- [ ] `docs/update.json` 的 changelog 正文已更新（首行版本前缀由脚本维护）
- [ ] `--execute` 完成：构建成功、3 个 APK 齐全、清单已签名、一致性自检通过
- [ ] GitHub Release `vX.Y.Z` 已创建，3 个资产名/大小/SHA-256 与清单一致
- [ ] Gitee Release 已同步（`docs/gitee/files.json` 已更新）
- [ ] `docs/files.json` 已更新，镜像探测正常，站点已部署
- [ ] 线上 `update.json`（GitHub raw / Gitee raw）可访问、带 `sig`、`versionName` 正确
- [ ] 真机「检查更新」验证通过（下载 + SHA-256 + 清单验签）
- [ ] Release Notes / 公告 / 下载页已发布
