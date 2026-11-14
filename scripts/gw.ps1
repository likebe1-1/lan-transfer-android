# 统一 Gradle 入口：优先使用调用方已设置的 JAVA_HOME（需 JDK 17+，推荐 21），
# 其次使用本机固定 JDK 路径，最后回退到 PATH 中的 java。
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $GradleArgs
)

$ErrorActionPreference = 'Stop'
if (-not $env:JAVA_HOME) {
    $candidate = 'D:\SDK\JDK21'
    if (Test-Path (Join-Path $candidate 'bin\java.exe')) {
        $env:JAVA_HOME = $candidate
    }
}
if ($env:JAVA_HOME) {
    $env:Path = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:Path
}
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    & (Join-Path $projectRoot 'gradlew.bat') @GradleArgs
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $code
