# 把签名材料从项目目录迁移到用户目录（项目外），避免 keystore 与明文口令长期暴露在工作区。
# 用法（PowerShell）：
#   powershell -ExecutionPolicy Bypass -File scripts\migrate_keystore_to_userdir.ps1
# 迁移后构建时可这样提供口令（不要把口令写进仓库）：
#   $env:LANT_KEYSTORE_FILE   = "$HOME\.lanlink\keystore.jks"
#   $env:LANT_KEYSTORE_PASSWORD = (Get-Content "$HOME\.lanlink\store.password" -Raw)
#   $env:LANT_KEY_ALIAS       = (Get-Content "$HOME\.lanlink\key.alias" -Raw)
#   $env:LANT_KEY_PASSWORD    = (Get-Content "$HOME\.lanlink\key.password" -Raw)

param(
    [string]$ProjectRoot = (Get-Location).Path,
    [string]$TargetDir = (Join-Path $HOME ".lanlink")
)

$propertiesPath = Join-Path $ProjectRoot "keystore.properties"
if (-not (Test-Path $propertiesPath)) {
    Write-Output "未找到 keystore.properties，无需迁移"
    exit 0
}

$props = @{}
Get-Content $propertiesPath | ForEach-Object {
    if ($_ -match '^\s*([^#\s][^=]*)=(.*)$') { $props[$Matches[1].Trim()] = $Matches[2].Trim() }
}

$storeFile = $props["storeFile"]
$storePassword = $props["storePassword"]
$keyAlias = $props["keyAlias"]
$keyPassword = $props["keyPassword"]

$sourceJks = if ([System.IO.Path]::IsPathRooted($storeFile)) { $storeFile } else { Join-Path $ProjectRoot $storeFile }
if (-not (Test-Path $sourceJks)) {
    Write-Error "找不到密钥库：$sourceJks"
    exit 1
}

New-Item -ItemType Directory -Force -Path $TargetDir | Out-Null
$targetJks = Join-Path $TargetDir "lanlink-release.jks"
Copy-Item $sourceJks $targetJks -Force

# 说明：java.util.Properties 会把首行当作注释吞掉，因此配置文件必须以注释行开头
$propsContent = @(
    "# LanLink release signing (outside repo, user-only)"
    "storeFile=" + ($targetJks -replace '\\', '/')
    "storePassword=$storePassword"
    "keyAlias=$keyAlias"
    "keyPassword=$keyPassword"
) -join "`r`n"
Set-Content -Path (Join-Path $TargetDir "keystore.properties") -Value $propsContent -Encoding UTF8

# 口令单独存放，限制为仅当前用户可读
Set-Content -Path (Join-Path $TargetDir "store.password") -Value $storePassword -NoNewline
Set-Content -Path (Join-Path $TargetDir "key.alias") -Value $keyAlias -NoNewline
Set-Content -Path (Join-Path $TargetDir "key.password") -Value $keyPassword -NoNewline
icacls $TargetDir /inheritance:r /grant:r "$($env:USERNAME):(OI)(CI)F" | Out-Null

Write-Output "已迁移到：$TargetDir"
Write-Output "接下来可删除项目内明文签名材料："
Write-Output "  Remove-Item '$propertiesPath'"
Write-Output "  Remove-Item '$sourceJks'"
Write-Output "构建前设置环境变量（示例见脚本头部注释）。"
