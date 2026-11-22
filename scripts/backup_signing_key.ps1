# 备份 LanLink 签名与凭据材料到指定目录。
# 脚本只做「文件复制 + SHA-256 计算」，绝不读取/打印任何密钥、口令或令牌内容。
#
# 备份内容：
#   <项目>\.update-signing\update_signing_private.pem  更新清单签名私钥（Ed25519，PKCS#8）
#   <项目>\.update-signing\update_signing_public.b64   更新清单验签公钥（DER Base64）
#   %USERPROFILE%\.lanlink\lanlink-release.jks         Android release 签名密钥库
#   %USERPROFILE%\.lanlink\keystore.properties         keystore 配置（含口令，敏感）
#   %USERPROFILE%\.lanlink\gitee_token                 Gitee 私人令牌（若存在）
#
# 目标目录结构：
#   <Destination>\update-signing\  与  <Destination>\lanlink\
#   以及  <Destination>\SHA256SUMS.txt（每个复制文件的 SHA-256，格式兼容 sha256sum -c）
#
# 用法（仓库根目录执行）：
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\backup_signing_key.ps1 -Destination D:\lanlink-backup
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\backup_signing_key.ps1 -Destination D:\lanlink-backup -DryRun
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\backup_signing_key.ps1 -Destination D:\lanlink-backup -Force
#
# 安全约定：
#   * 输出只有路径、大小、SHA-256 前 16 位，绝不输出文件内容；
#   * 目标目录已存在且非空时，实际备份必须显式加 -Force（-DryRun 仅提示、不写盘）；
#   * 备份含私钥与口令，务必放到离线介质；不要上传网盘、不要提交仓库。
#
# 提醒：私钥丢失将无法再签发后续版本更新清单；keystore 丢失将无法再覆盖安装升级。

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$Destination,

    # 只打印将复制的文件、目标路径、大小与 SHA-256 前 16 位，不复制文件、不创建目录、不写 SHA256SUMS.txt
    [switch]$DryRun,

    # 目标目录已存在且非空时，覆盖同名文件
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

function Write-Step([string]$Message) { Write-Host $Message }

try {
    $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
    $projectRoot = Split-Path -Parent $scriptDir
    $profileRoot = if ($env:USERPROFILE) { $env:USERPROFILE } else { $HOME }
    $userDir = Join-Path $profileRoot '.lanlink'
    $updateSigningDir = Join-Path $projectRoot '.update-signing'

    # ---- 解析目标目录（允许尚不存在；相对路径按当前工作目录解析）----
    if (-not [System.IO.Path]::IsPathRooted($Destination)) {
        $Destination = Join-Path (Get-Location).Path $Destination
    }
    $destRoot = [System.IO.Path]::GetFullPath($Destination)
    $destRoot = $destRoot.TrimEnd([char[]]@('\', '/'))
    if ($destRoot -match '^[A-Za-z]:$') { $destRoot += '\' }
    if ([string]::IsNullOrWhiteSpace($destRoot)) {
        throw '无法解析 -Destination 参数'
    }

    # ---- 备份计划 ----
    $plan = @(
        [pscustomobject]@{
            Group    = 'update-signing'
            Name     = 'update_signing_private.pem'
            Path     = (Join-Path $updateSigningDir 'update_signing_private.pem')
            Label    = '更新清单签名私钥（Ed25519）'
            Required = $true
        },
        [pscustomobject]@{
            Group    = 'update-signing'
            Name     = 'update_signing_public.b64'
            Path     = (Join-Path $updateSigningDir 'update_signing_public.b64')
            Label    = '更新清单验签公钥（DER Base64）'
            Required = $true
        },
        [pscustomobject]@{
            Group    = 'lanlink'
            Name     = 'lanlink-release.jks'
            Path     = (Join-Path $userDir 'lanlink-release.jks')
            Label    = 'Android release 签名密钥库'
            Required = $true
        },
        [pscustomobject]@{
            Group    = 'lanlink'
            Name     = 'keystore.properties'
            Path     = (Join-Path $userDir 'keystore.properties')
            Label    = 'keystore 配置（含口令）'
            Required = $true
        },
        [pscustomobject]@{
            Group    = 'lanlink'
            Name     = 'gitee_token'
            Path     = (Join-Path $userDir 'gitee_token')
            Label    = 'Gitee 私人令牌'
            Required = $false
        }
    )

    Write-Step '=== LanLink 签名/凭据备份 ==='
    Write-Step ("项目根目录  : {0}" -f $projectRoot)
    Write-Step ("用户材料目录: {0}" -f $userDir)
    Write-Step ("目标目录    : {0}" -f $destRoot)
    if ($DryRun) {
        Write-Step '运行模式    : DryRun（仅预览，不复制文件、不创建目录、不写 SHA256SUMS.txt）'
    } else {
        Write-Step '运行模式    : 实际备份'
    }

    # ---- 目标目录非空保护 ----
    $destNotEmpty = $false
    if (Test-Path -LiteralPath $destRoot) {
        $existing = @(Get-ChildItem -LiteralPath $destRoot -Force -ErrorAction SilentlyContinue)
        $destNotEmpty = ($existing.Count -gt 0)
    }
    if ($destNotEmpty) {
        if ($Force) {
            if ($DryRun) { Write-Step '注意        : 目标目录非空，已提供 -Force；实际执行会覆盖同名文件。' }
        } elseif ($DryRun) {
            Write-Step '注意        : 目标目录非空；实际执行需加 -Force 才会覆盖（DryRun 不做任何写入）。'
        } else {
            throw ("目标目录已存在且非空：{0}。请改用空目录，或加 -Force 覆盖同名文件。" -f $destRoot)
        }
    }

    # ---- 逐个复制并计算哈希 ----
    $hashEntries = New-Object 'System.Collections.Generic.List[object]'
    $copied = 0
    $missingRequired = 0

    foreach ($item in $plan) {
        $relative = $item.Group + '/' + $item.Name
        $target = Join-Path (Join-Path $destRoot $item.Group) $item.Name

        if (-not (Test-Path -LiteralPath $item.Path -PathType Leaf)) {
            if ($item.Required) {
                $missingRequired++
                Write-Warning ("缺少文件，跳过：{0}（{1}）" -f $item.Path, $item.Label)
            } else {
                Write-Step ("跳过（不存在）：{0}（{1}）" -f $item.Path, $item.Label)
            }
            continue
        }

        $file = Get-Item -LiteralPath $item.Path
        $hash = (Get-FileHash -LiteralPath $item.Path -Algorithm SHA256).Hash.ToLowerInvariant()
        $hashPrefix = $hash.Substring(0, 16)
        $hashEntries.Add([pscustomobject]@{ Hash = $hash; Relative = $relative })
        $copied++

        Write-Step ''
        if ($DryRun) {
            Write-Step ("[将复制] {0}  —  {1}" -f $relative, $item.Label)
            Write-Step ("  来源：{0}" -f $item.Path)
            Write-Step ("  目标：{0}" -f $target)
            Write-Step ("  大小：{0:N0} 字节 | SHA-256：{1}..." -f $file.Length, $hashPrefix)
        } else {
            $groupDir = Join-Path $destRoot $item.Group
            if (-not (Test-Path -LiteralPath $groupDir)) {
                New-Item -ItemType Directory -Path $groupDir -Force | Out-Null
            }
            Copy-Item -LiteralPath $item.Path -Destination $target -Force
            $targetItem = Get-Item -LiteralPath $target
            if ($targetItem.Length -ne $file.Length) {
                throw ("复制后大小不一致：{0}" -f $target)
            }
            Write-Step ("[已复制] {0}  —  {1}" -f $relative, $item.Label)
            Write-Step ("  来源：{0}" -f $item.Path)
            Write-Step ("  目标：{0}" -f $target)
            Write-Step ("  大小：{0:N0} 字节 | SHA-256：{1}..." -f $targetItem.Length, $hashPrefix)
        }
    }

    if ($copied -eq 0) {
        throw '没有找到任何可备份的文件，请先确认签名材料所在位置。'
    }

    # ---- SHA256SUMS.txt ----
    $sumsPath = Join-Path $destRoot 'SHA256SUMS.txt'
    # 统一用 LF：兼容 Git Bash 的 sha256sum -c（CRLF 会让文件名带上 \r 导致校验失败）
    $newline = [string][char]10
    Write-Step ''
    if ($DryRun) {
        Write-Step ("[DryRun] 将写入校验清单：{0}（{1} 个文件）" -f $sumsPath, $hashEntries.Count)
        Write-Step ''
        Write-Step '--- SHA256SUMS.txt 预览（hash 截断显示）---'
        foreach ($entry in $hashEntries) {
            Write-Step ("{0}  {1}" -f $entry.Hash.Substring(0, 16), $entry.Relative)
        }
    } else {
        $lines = @($hashEntries | ForEach-Object { $_.Hash + '  ' + $_.Relative })
        $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
        [System.IO.File]::WriteAllText($sumsPath, (($lines -join $newline) + $newline), $utf8NoBom)
        Write-Step ("已写入校验清单：{0}（{1} 个文件）" -f $sumsPath, $hashEntries.Count)
    }

    if ($missingRequired -gt 0) {
        Write-Warning ("有 {0} 个必需文件缺失，本次备份不完整。" -f $missingRequired)
    }

    Write-Step ''
    if ($DryRun) {
        Write-Step ("DryRun 完成：共 {0} 个文件待复制到 {1}" -f $copied, $destRoot)
        Write-Step '未创建目录、未复制任何文件、未写入 SHA256SUMS.txt。'
    } else {
        Write-Step ("备份完成：共 {0} 个文件已复制到 {1}" -f $copied, $destRoot)
        Write-Step '校验：PowerShell 用 Get-FileHash -Algorithm SHA256 <文件>；Git Bash 用 sha256sum -c SHA256SUMS.txt'
    }
    Write-Step ''
    Write-Step '请把备份放到离线介质并妥善保管；私钥丢失将无法再签发后续版本更新清单。'
    exit 0
} catch {
    Write-Host ("备份失败：{0}" -f $_.Exception.Message) -ForegroundColor Red
    exit 1
}
