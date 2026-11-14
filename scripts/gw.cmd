@echo off
rem 统一 Gradle 入口：优先使用调用方已设置的 JAVA_HOME（需 JDK 17+，推荐 21），
rem 其次使用本机固定 JDK 路径，最后回退到 PATH 中的 java。
setlocal
if not defined JAVA_HOME (
    if exist "D:\SDK\JDK21\bin\java.exe" set "JAVA_HOME=D:\SDK\JDK21"
)
if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"
call "%~dp0..\gradlew.bat" %*
exit /b %ERRORLEVEL%
