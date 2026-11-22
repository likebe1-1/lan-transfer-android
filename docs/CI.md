# CI（GitHub Actions）

本仓库使用 GitHub Actions 做持续集成，工作流定义在 [`.github/workflows/ci.yml`](../.github/workflows/ci.yml)。
本文说明 CI 做什么、如何在本地复现、为什么 lint 只在 CI 跑、如何加状态徽章，以及失败时该看哪里。

## CI 做什么

| 项目 | 内容 |
| --- | --- |
| 工作流名 | `CI`（badge 名为 CI） |
| 触发 | push 到 `master`；任意 `pull_request` |
| Runner | `ubuntu-latest` |
| JDK | Temurin 21（`actions/setup-java@v4`，启用 Gradle 缓存） |
| 超时 | 40 分钟 |
| 并发 | 同一 `github.ref` 只保留最新一次运行，旧的自动取消 |
| 权限 | `contents: read`（只读） |

Job `test-lint-build` 依次执行：

1. `actions/checkout@v4` 检出代码；
2. `actions/setup-java@v4` 安装 Temurin 21 并缓存 Gradle；
3. **CI 专属**：把 `org.gradle.java.home` 覆盖为 runner 上的 `$JAVA_HOME`（见下节）；
4. `chmod +x ./gradlew`；
5. `./gradlew testDebugUnitTest lintDebug assembleDebug --console=plain`；
6. 无论成功失败（`if: always()`）都上传两份 artifact：
   - `unit-test-results`：`app/build/test-results/testDebugUnitTest`（JUnit XML）
   - `lint-reports`：`app/build/reports/lint-results*.html`（Lint HTML 报告）

> 成功标准：单测通过、lint 不因 error 中断、debug APK 能构建出来。
> `app/build.gradle.kts` 里 `lint { abortOnError = true }`，所以 **lint error 会让 CI 失败**；warning 不会（如需更严格可开 `warningsAsErrors`）。

### 为什么多了一步 "Point Gradle at the CI JDK"

项目根目录 `gradle.properties` 中写死了本机 Windows 路径：

```properties
org.gradle.java.home=D:/SDK/JDK21
```

该路径在 Linux runner 上不存在，Gradle 会直接失败（`Java home supplied is invalid`）。
`settings.gradle.kts` 已经用 `foojay-resolver-convention` 处理工具链，但 `org.gradle.java.home` 的优先级更高。
因此 CI 在 `$GRADLE_USER_HOME/gradle.properties`（即 `~/.gradle/gradle.properties`）里写入同名的
`org.gradle.java.home=$JAVA_HOME`：**用户级 gradle.properties 的优先级高于项目级**，从而覆盖该 Windows 路径，
且不需要改动仓库内的任何 Gradle 配置。

如果以后从 `gradle.properties` 删掉 `org.gradle.java.home`（或改成相对/可跨平台的值），这一步就可以删除。

## 本地如何复现 CI

CI 跑的就是这条命令（`--console=plain` 让日志在 CI 里更干净）：

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug --console=plain
```

### Windows（PowerShell / cmd）

用仓库里的统一入口 `scripts\gw.cmd`，它会自动选择 JDK（优先 `JAVA_HOME`，其次 `D:\SDK\JDK21`）：

```bat
scripts\gw.cmd testDebugUnitTest lintDebug assembleDebug --console=plain
```

只想跑单测（最快）：

```bat
scripts\gw.cmd testDebugUnitTest
```

### Linux / macOS

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug --console=plain
```

### 只跑单测或只跑 lint

```bash
./gradlew testDebugUnitTest --console=plain   # 单测
./gradlew lintDebug --console=plain           # debug 变体 lint
```

报告位置（本地）：

- 单测 XML：`app/build/test-results/testDebugUnitTest/`
- 单测 HTML：`app/build/reports/tests/testDebugUnitTest/index.html`
- Lint HTML：`app/build/reports/lint-results-debug.html`

## lint 状态：已恢复为强制门禁

Gradle 已对齐到 AGP 8.7 的官方基线 **Gradle 8.9**（此前 Gradle 9.6 + AGP 8.7 属非认证组合，lint worker 必崩）。

- 本地 `assembleRelease` 现在同样强制 lint：`checkReleaseBuilds = true`、`abortOnError = true`；
- 已修复的 lint error：`NewApi`（`BigInteger.TWO` 是 API 33 字段 → 改为自建常量）、`CoarseFineLocation`（补 `ACCESS_COARSE_LOCATION`）；
- 已评估后抑制：`TrustAllX509TrustManager` / `CustomX509TrustManager`（FTPS 证书固定与“用户显式信任”分支，见 docs/security.html）；
- 当前基线：**0 error / 60 warning**，warning 会随版本逐步清理，不影响 CI 通过。

## 为什么 lint 放在 CI 跑

CI 强制跑 lint 的好处：任何 PR 都会过一遍静态检查，不依赖开发者本地是否执行。历史上 lint 曾因 Gradle 9.6 组合在本机崩溃，
现已通过版本对齐解决；Gradle 工具链路线见 docs/ARCHITECTURE.md §23。
## 如何加 CI badge

在 [README.md](../README.md) 顶部（标题或官网链接下方）加一行 Markdown：

```markdown
[![CI](https://github.com/likebe1-1/lan-transfer-android/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/likebe1-1/lan-transfer-android/actions/workflows/ci.yml)
```

说明：

- badge URL 里的 `ci.yml` 必须与工作流文件名一致；重命名工作流文件要同步改 badge。
- `?branch=master` 表示显示 master 分支的状态；去掉则显示默认分支。
- badge 只有在工作流至少成功运行过一次、且仓库为 **public** 时才会匿名显示；私有仓库需要登录 GitHub 才能看到。
- 工作流文件必须先提交到 `master`（默认分支）并运行一次，Actions 页面才会出现该 workflow，badge 也才会出图。

## 失败时看哪里

1. **GitHub Actions 页面**：仓库 → Actions → `CI` → 选择失败的运行。
2. **失败 job 的日志**：左侧 `test-lint-build` → 展开失败 step（`Run unit tests, lint and assemble debug`）。
   先看 `> Task :app:testDebugUnitTest FAILED` 或 `> Task :app:lintDebug FAILED` 附近的行。
3. **下载 artifact**（运行页面底部 `Artifacts`）：
   - `unit-test-results`：`TEST-*.xml`，用浏览器打开或搜索 `<failure`；
   - `lint-reports`：`lint-results-debug.html`，按 `Error`/`Warning` 过滤。
4. **本地复现**：按上面命令在本地跑同样的 task；CI 上 `--console=plain` 的日志可以和本地逐行对照。
5. **常见原因**：
   - Android SDK / platform 35 缺失：runner 镜像需具备 `compileSdk = 35` 的平台；
   - JDK 版本不符：确认 `org.gradle.java.home` 已被覆盖为 Temurin 21；
   - 单测依赖网络/端口：单测里启动了本地 FTP/SSH 服务器，端口被占用或受限时会失败；
   - Gradle 依赖下载超时：重跑一次（缓存会命中），或检查 `settings.gradle.kts` 里的仓库配置。

## 维护提示

- 修改 `ci.yml` 的触发分支、任务列表或 artifact 路径时，请同步更新本文。
- CI 不签名、不发布 release（`assembleDebug` 用 debug keystore 自动签名）；release 签名材料只在本地/发布流程使用，**不要**把 `keystore.properties` 或密钥提交进仓库。
