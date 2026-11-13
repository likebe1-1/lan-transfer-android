# 统一 Gradle 入口：固定使用 JDK 21，避免本机默认 JDK 版本不一致
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $GradleArgs
)

$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'D:\SDK\JDK21'
$env:Path = 'D:\SDK\JDK21\bin;' + $env:Path
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    & (Join-Path $projectRoot 'gradlew.bat') @GradleArgs
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $code
