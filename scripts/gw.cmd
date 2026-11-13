@echo off
rem 统一 Gradle 入口：固定 JDK 21
set "JAVA_HOME=D:\SDK\JDK21"
set "PATH=D:\SDK\JDK21\bin;%PATH%"
call "%~dp0..\gradlew.bat" %*
exit /b %ERRORLEVEL%
