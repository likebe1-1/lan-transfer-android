# 密钥与令牌管理（SECRETS）

> 本文件只记录「凭据放在哪、怎么轮换、泄露怎么处理」，**不记录任何真实凭据内容**。
> 仓库内任何位置都不得出现真实的私钥、keystore、口令或访问令牌。
> 相关背景见 [ARCHITECTURE.md](ARCHITECTURE.md) 第 17 章（安全加固）；本文件补充日常运维操作。

## 0. 原则

1. **凭据不进仓库**：私钥 / keystore / 口令 / 令牌一律放在仓库之外（`%USERPROFILE%\.lanlink\` 或环境变量）；`.update-signing/` 虽在项目目录内，但被 `.gitignore` 整体排除。
2. **最小权限**：访问令牌按用途单独申请、只授予必要 scope、设置有效期；用完即撤。
3. **可恢复**：签名私钥与 keystore 必须离线备份（`scripts/backup_signing_key.ps1`），否则将无法继续发版 / 无法覆盖升级。
4. **只读不打印**：任何脚本、构建日志、CI 日志都不得回显凭据内容；备份脚本只输出路径、大小与 SHA-256 前 16 位。
5. **泄露先吊销**：清理 git 历史只是补救，已泄露的凭据必须在服务端吊销/重置，清理历史不能收回已泄露的秘密。

## 1. 凭据清单

| 凭据 | 用途 | 存放位置（当前约定） | 是否入库 / gitignore 覆盖 | 丢失或泄露的影响 |
| --- | --- | --- | --- | --- |
| **update.json 签名私钥**（Ed25519 PKCS#8） | 给 `docs/update.json` 追加 `sig`；App 用内置公钥验签，拒绝未签名/签名错误的清单 | 项目内 `<repo>\.update-signing\update_signing_private.pem` | 不入库；`.gitignore` 的 `.update-signing/`、`*.pem`、`*.key` 覆盖 | 丢失后**无法再签发后续版本更新清单**；泄露则攻击者可伪造更新清单（高危） |
| **update.json 验签公钥**（DER Base64） | 内置在 App 的 `UpdateManifestVerifier.PUBLIC_KEY_BASE64`，与私钥配对 | 项目内 `<repo>\.update-signing\update_signing_public.b64` | 不入库；同上（公钥本身不敏感，但默认不提交） | 可由私钥随时重新导出；单独丢失无影响 |
| **Android release keystore（.jks）+ 口令** | 给 release APK 签名，保证同包名可覆盖安装/升级 | `%USERPROFILE%\.lanlink\lanlink-release.jks`；口令在 `%USERPROFILE%\.lanlink\keystore.properties`（`storePassword` / `keyPassword`）；迁移脚本另生成 `store.password` / `key.password` / `key.alias` 供构建脚本取用 | 在仓库外，无需 gitignore；仓库内若出现由 `keystore.properties`、`*.jks`、`*.keystore`、`*.p12` 覆盖 | 丢失后无法产出可覆盖安装的 release 包，用户只能卸载重装（丢应用数据）或更换 `applicationId` |
| **Gitee 私人令牌** | `scripts/sync_gitee.py` 创建发行版、上传附件、生成 `docs/gitee/files.json`（scope：`projects`） | 环境变量 `GITEE_TOKEN`；或 `%USERPROFILE%\.lanlink\gitee_token` | 在仓库外；仓库内路径由 `.gitee_token`、`*_token`、`*token.txt`、`.env*` 兜底 | 泄露后他人可操作仓库发行版；吊销后重新生成即可，影响可控 |
| **GitHub token**（可选） | `scripts/gen_file_index.py` / `sync_gitee.py` 读取 GitHub Release 资产 digest；公开仓库匿名亦可读，令牌仅用于避免限流 | 仅环境变量 `GITHUB_TOKEN`，**不落盘** | 不入库；`.github_token`、`*_token`、`*token.txt`、`.env*` 兜底 | 影响小；建议使用 fine-grained token，只读 public repo |
| （非版本库）App 内服务器密码 | 客户端保存的 FTP/SFTP/SCP 服务器密码 | 设备上 `filesDir/servers.json`，用 Android Keystore AES-256-GCM 加密（`enc:v1:` 前缀） | 不在仓库、不在本备份范围 | 见 ARCHITECTURE.md §17.2；换机/清数据后需重新输入 |

> 明确结论：**本仓库当前不应出现任何令牌、口令、私钥或 keystore**。提交前用 §6 的检查命令复核。

## 2. 存放位置与 gitignore 覆盖

| 位置 | 内容 | 说明 |
| --- | --- | --- |
| `%USERPROFILE%\.lanlink\` | keystore、keystore.properties、口令拆分文件、gitee_token | 由 `scripts/migrate_keystore_to_userdir.ps1` 建立并用 `icacls` 收紧为「仅当前用户完全控制」 |
| `<repo>\.update-signing\` | 更新清单签名私钥 + 公钥 | 唯一留在项目目录内的敏感目录，被 `.gitignore` 排除；**不要**对它执行 `git add -f` |
| 环境变量 | `GITEE_TOKEN`、`GITHUB_TOKEN`、`LANT_KEYSTORE_FILE`、`LANT_KEYSTORE_PASSWORD`、`LANT_KEY_ALIAS`、`LANT_KEY_PASSWORD` | 构建/发布脚本的优先来源，进程退出即消失，最适合临时使用 |
| GitHub Actions secrets（当前未使用） | 同上 | 现有 `.github/workflows/ci.yml` 只构建 debug、不签名、不发布，因此不注入任何 secrets；接入 release 签名/发布时按 §4.5 配置 |

构建时 keystore 解析顺序（`app/build.gradle.kts`）：
环境变量 `LANT_*` → `%USERPROFILE%\.lanlink\keystore.properties` → 项目内 `keystore.properties`（仅兼容回退，必须已 gitignore）。

> 文档备注：`docs/ARCHITECTURE.md` §6 写的 `lantransfer-release.jks` 位于项目根，是迁移到用户目录之前的描述；§18.2 的「项目外 `.update-signing/`」实际指「项目内但被 gitignore 排除」。以本文件与 `scripts/migrate_keystore_to_userdir.ps1` 为准。

## 3. 备份与恢复

### 3.1 备份

```powershell
# 预览：只列出将复制的文件、目标路径、大小与 SHA-256 前 16 位，不写任何文件
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\backup_signing_key.ps1 -Destination D:\lanlink-backup -DryRun

# 实际备份（目标目录须不存在或为空）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\backup_signing_key.ps1 -Destination D:\lanlink-backup

# 覆盖已有备份（目标目录非空时必须显式 -Force）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\backup_signing_key.ps1 -Destination D:\lanlink-backup -Force
```

备份产物：

```text
<Destination>\
├─ update-signing\
│  ├─ update_signing_private.pem
│  └─ update_signing_public.b64
├─ lanlink\
│  ├─ lanlink-release.jks
│  ├─ keystore.properties
│  └─ gitee_token            （存在时才有）
└─ SHA256SUMS.txt            （每行「SHA-256  相对路径」，兼容 sha256sum -c）
```

要点：

- 脚本**绝不打印文件内容**，只输出路径、字节数、SHA-256 前 16 位；
- `-DryRun` 不创建目录、不复制文件、不写 `SHA256SUMS.txt`；
- 备份含私钥、口令和令牌，必须放到**离线介质**（离线硬盘 / U 盘 / 加密卷），并建议加密存储；不要放网盘、OneDrive 自动同步目录或共享盘；
- 备份文件若离开当前可信机器，先压缩并设置口令，口令与备份分开保管；
- 建议频率：每次轮换凭据后立即备份；每季度做一次「恢复演练」（解到临时目录，跑 §3.2 的校验步骤后删除）。

### 3.2 恢复

1. 把 `lanlink-release.jks`、`keystore.properties` 复制回 `%USERPROFILE%\.lanlink\`；
2. 把 `update-signing\` 两个文件复制回 `<repo>\.update-signing\`；
3. 校验完整性：`sha256sum -c SHA256SUMS.txt`（Git Bash）或逐个 `Get-FileHash -Algorithm SHA256`；
4. 验证构建：`scripts\gw.ps1` 构建一次 release，确认签名成功；
5. 验证签名链路：见 §4.4 的「验证」步骤。

> 备份不含 `store.password` / `key.password` / `key.alias` 三个拆分文件（口令已包含在 `keystore.properties` 内）。正常构建用不到它们；若依赖环境变量方式构建，从 `keystore.properties` 手工读取对应值设置 `LANT_KEYSTORE_PASSWORD` / `LANT_KEY_PASSWORD` / `LANT_KEY_ALIAS` 即可。

## 4. 轮换流程

通用四步：**吊销 → 生成 → 验证 → 更新本地（`~/.lanlink`）与 CI secrets**，最后补一次离线备份并记录轮换时间。

### 4.1 Gitee 私人令牌（低风险，可随时轮换）

1. 吊销：Gitee「设置 → 安全设置 → 私人令牌」删除旧令牌；
2. 生成：新建令牌，scope 只勾 `projects`，设置合理有效期；
3. 验证：`GITEE_TOKEN=<新令牌> python scripts/sync_gitee.py --tag <最新版本>` 能正常读取/不报 401；
4. 更新本地：把新令牌写入 `%USERPROFILE%\.lanlink\gitee_token`（仅当前用户可读）或改用环境变量；
5. 更新 CI（若接入）：写入 GitHub Actions secret `GITEE_TOKEN`；
6. 重新执行 §3.1 备份。

### 4.2 GitHub token（低风险）

1. 吊销：GitHub「Settings → Developer settings → Personal access tokens」删除/Revoke 旧 token；
2. 生成：fine-grained token，只读目标 public repo（classic 只需 `public_repo`/只读）；
3. 验证：`GITHUB_TOKEN=<新令牌> python scripts/gen_file_index.py` 正常返回资产 digest；
4. 更新：不落盘，仅环境变量/CI secrets `GITHUB_TOKEN`；
5. 若曾把 token 写进文件，先删除文件并确认 `%USERPROFILE%\.lanlink\github_token` 之类路径不在仓库内。

### 4.3 Android release keystore（高风险：会破坏覆盖安装）

> 正常情况下**不要轮换**，优先保证备份可恢复。只有在 keystore 确认泄露或丢失时才换。

1. 确认影响：同 `applicationId` 的新包使用新密钥后无法覆盖安装旧包，用户必须卸载重装（丢应用数据），或发布新 `applicationId` 的独立版本；
2. 生成新 keystore（口令用强随机值，只保存在本地密码管理器与 `~/.lanlink`）：
   ```powershell
   & "$env:JAVA_HOME\bin\keytool" -genkeypair -v -keystore "$env:USERPROFILE\.lanlink\lanlink-release.jks" -alias lanlink -keyalg RSA -keysize 4096 -validity 10000
   ```
3. 更新 `%USERPROFILE%\.lanlink\keystore.properties`（首行必须是注释，`java.util.Properties` 会跳过首行）；
4. 验证：构建 release 后用 `apksigner verify --print-certs <apk>` 确认证书指纹为新证书；旧 APK 与新 APK 的签名指纹对比，确认已更换；
5. 更新本地环境变量与（若有）CI secrets；重新执行 §3.1 备份；
6. 对外公告安装方式变更，并保留旧签名版本一段时间供回滚。

### 4.4 update.json 签名私钥（高风险：影响更新通道）

> 已发布 App 内置的是**旧公钥**，只能验证旧私钥签出的清单。私钥丢失/泄露后，已安装的旧版本无法自动切换信任新公钥；当前代码仅支持单公钥，平滑轮换需要改代码让客户端同时信任新旧公钥（超出本文档范围）。

**丢失（非泄露）**：

1. 无法再签发新的更新清单；已发布版本继续接收旧的已签名清单（不要删除旧清单）；
2. 生成新密钥对：`python scripts/gen_update_signing_key.py --out-dir .update-signing`（会覆盖同名文件，先确认旧文件已无价值或已归档）；
3. 把新公钥填入 `UpdateManifestVerifier.PUBLIC_KEY_BASE64`，发布新版本 App；
4. 旧版本用户需通过手动下载安装包升级；发布公告说明原因。

**泄露**：

1. 立即停止使用泄露私钥签名，公告该时间段清单不可信；
2. 按上一步生成新密钥对并发布内置新公钥的版本；
3. 评估是否已有人用泄露私钥发布过伪造清单（检查 `docs/update.json` 的提交历史与远端发布记录），必要时临时下线更新通道；
4. 吊销无法对 Ed25519 私钥执行——只能换钥重建信任链。

**验证签名链路（轮换后必做）**：

```powershell
# 用临时副本签名，避免改动受版本控制的 docs/update.json
Copy-Item docs\update.json "$env:TEMP\update-test.json"
python scripts\sign_update_manifest.py --key .update-signing\update_signing_private.pem --in "$env:TEMP\update-test.json" --out "$env:TEMP\update-test-signed.json"
# 再用独立实现验签（三选一）：
#   a) 运行 JVM 单测 UpdateManifestSignatureTest（内置真实公钥验签逻辑）
#   b) 用 scripts\canonical_manifest.py 生成规范化载荷 + openssl pkeyutl -verify -pubin
#   c) 发布后 curl 远端 raw docs/update.json，确认 sig 存在且 App 能通过校验
Remove-Item "$env:TEMP\update-test*.json"
```

### 4.5 更新本地 `~/.lanlink` 与 CI secrets

- 本地：`%USERPROFILE%\.lanlink\` 下对应文件替换后，用 `icacls "$env:USERPROFILE\.lanlink" /inheritance:r /grant:r "$($env:USERNAME):(OI)(CI)F"` 收紧权限；
- CI（现有 `.github/workflows/ci.yml` 只构建 debug、不使用任何 secrets；接入 release 签名/发布时执行）：在 GitHub「Settings → Secrets and variables → Actions」写入
  `GITEE_TOKEN`、`GITHUB_TOKEN`、`LANT_KEYSTORE_FILE`（runner 上临时路径）、`LANT_KEYSTORE_PASSWORD`、`LANT_KEY_ALIAS`、`LANT_KEY_PASSWORD`；
  workflow 中把 keystore 以 base64 secret 解码到 `$RUNNER_TEMP`，用后删除；禁止 `echo`/调试输出 secret，必要时对派生值使用 `::add-mask::`；
- 若 CI 涉及 update.json 签名，私钥必须作为 CI secret 注入且视为高风险；更推荐**签名在本地完成**，CI 只做构建与上传；
- 轮换后重新执行 §3.1 备份，并在 §7 记录轮换时间。

## 5. 泄露应急响应

1. **先吊销**（分钟级）：
   - Gitee / GitHub 令牌：立即在服务端删除/Revoke，生成新令牌；
   - keystore：无法吊销，按泄露处理（§4.3），评估是否发布新包名版本，并公告用户；
   - update 签名私钥：无法吊销，按 §4.4 轮换并发布内置新公钥的版本。
2. **切断暴露面**：删除泄露文件所在位置（本地目录、CI 日志、issue、PR、gist、聊天记录），撤销对应机器/账户的访问权限。
3. **排查滥用**：查看 Gitee 动态、GitHub token last used、仓库 Releases 与 `docs/update.json` 历史、CI 运行记录；确认是否有异常发布或上传。
4. **历史清理（补救，非首选）**：确认文件曾被提交时，用 `git filter-repo`（或 `git filter-branch`）从历史移除，强制推送并通知所有 clone/fork；注意 GitHub/Gitee 的缓存、PR 引用、Actions artifact 可能仍保留旧内容，必须配合吊销才安全。
   ```powershell
   # 示例：从全部历史移除误提交的凭据文件（先备份仓库，确认路径后再执行）
   git filter-repo --invert-paths --path <误提交文件路径>
   git push --force --all
   # 清理后让协作者重新 clone；GitHub/Gitee 侧联系支持清理缓存
   ```
5. **重建与验证**：用新凭据重签 `docs/update.json`、重新上传 APK 并更新 `docs/files.json` / `docs/gitee/files.json` 的 SHA-256，按 §4 的验证步骤确认。
6. **记录**：在 team issue 中记录事件时间、泄露凭据类型、吊销时间、影响范围与后续措施（不要贴出凭据本身）。

## 6. 日常检查清单

提交前 / 发版前执行（仓库根目录，PowerShell）：

```powershell
# 1) 已知令牌格式与私钥标记（排除本文件，避免自匹配）
git grep -n -I -E 'gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{40,}|GITEE_TOKEN\s*=\s*[A-Za-z0-9]{16,}|BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY' -- . ':!docs/SECRETS.md'

# 2) 版本库内是否跟踪了凭据类文件（预期：无输出）
git ls-files | Select-String -Pattern '(^|/)(keystore\.properties|\.env(\.|$)|[^/]*token[^/]*(\.txt)?|[^/]*\.(jks|keystore|p12|pem|key)$)'

# 3) 工作区未跟踪/被忽略的敏感文件（确认没有将被 add）
git status --porcelain --ignored | Select-String -Pattern '\.update-signing|\.lanlink|token|\.jks|\.pem|\.key'

# 4) 确认敏感路径确实被忽略（应输出匹配的 .gitignore 规则）
git check-ignore -v .update-signing/update_signing_private.pem
git check-ignore -v keystore.properties

# 5) 历史自查（最近提交里是否出现过敏感路径/关键字）
git log --all --oneline -- .update-signing keystore.properties '*.jks' '*.pem'
git log --all -S 'GITEE_TOKEN' --oneline -- scripts docs
```

发版前额外检查：

- [ ] `.gitignore` 未被修改（`.update-signing/`、`*.pem`、`*.jks`、`keystore.properties`、`*_token` 等规则仍在）；
- [ ] 构建日志、截图、报错信息中没有出现口令/令牌明文；
- [ ] `docs/update.json` 的 `sig` 已更新且可验签；`docs/files.json` 中 APK 的 SHA-256 与上传产物一致；
- [ ] 离线备份是最新的（§3.1），并且最近做过一次恢复校验；
- [ ] `%USERPROFILE%\.lanlink\` 的 ACL 仍为仅当前用户可读写；
- [ ] Gitee/GitHub 令牌有效期未过期，权限未被意外扩大。

## 7. 轮换与备份记录（手工维护，不记录凭据内容）

| 日期 | 对象 | 操作（生成/轮换/吊销/备份/恢复演练） | 执行人 | 备注 |
| --- | --- | --- | --- | --- |
| 2026-11-22 | 全部签名材料 | 新增离线备份脚本与本节文档（未执行真实备份） | secret-keeper | 备份命令见 §3.1 |
