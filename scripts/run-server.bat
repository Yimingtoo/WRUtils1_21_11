@echo off
chcp 65001 >nul
setlocal

rem 启动独立多人测试服务器（run-server/）。Windows 版。
rem 容器 / Linux 用 scripts/run-server.sh，两边逻辑一致。
rem
rem 启动前必须把构建出的 mod jar 拷进 run-server\mods\ ——这一步是必需的：
rem 不拷的话服务器跑的是上一次遗留下来的旧 jar，你会对着一个根本不是当前代码的
rem 服务器调试，症状（比如 /wrutils 命令不存在）和真实原因完全对不上。
rem
rem 环境变量:
rem   WRT_SKIP_JAR_CHECK=1   跳过"构建产物是否比源码旧"的检查（知情时用）
rem
rem 注意: 本脚本不负责构建。本仓库用 gradle 构建（fabric-loom 要求 JDK 25），
rem       构建要在装了 JDK 25 的环境做（IDEA 的 Gradle 工具窗，或 gradle build）。

for %%I in ("%~dp0..") do set "ROOT=%%~fI"
set "SERVERDIR=%ROOT%\run-server"
set "JAR=%ROOT%\build\libs\wrutils1_21_11-1.0-SNAPSHOT.jar"
set "MODS=%SERVERDIR%\mods"

if not exist "%SERVERDIR%" (
    echo run-server: 找不到目录 %SERVERDIR%
    exit /b 1
)

if not exist "%JAR%" (
    echo run-server: 找不到 %JAR%
    echo   先在装了 JDK 25 的环境构建: gradle build
    exit /b 1
)

rem 过期检查：构建产物比源码旧就意味着服务器上跑的不是你刚写的代码。
rem 静默跑错比直接报错难查得多，所以默认挡下来。
rem （查不出来时放行——宁可漏挡，也不要因为脚本本身出问题而挡住启动。）
set "NEWER="
if not "%WRT_SKIP_JAR_CHECK%"=="1" for /f "usebackq delims=" %%F in (`powershell -NoProfile -Command "$j=(Get-Item -LiteralPath '%JAR%').LastWriteTime; $n=Get-ChildItem -LiteralPath '%ROOT%\src' -Recurse -File ^| Where-Object { $_.LastWriteTime -gt $j } ^| Select-Object -First 1; if ($n) { $n.FullName }"`) do set "NEWER=%%F"

if defined NEWER (
    echo run-server: 构建产物比源码旧，拒绝启动
    echo   jar: %JAR%
    echo   更新的源码: %NEWER%
    echo   重新构建这个 jar: gradle build
    echo   确实就想用这个旧 jar, 设 WRT_SKIP_JAR_CHECK=1 再跑
    exit /b 1
)

if not exist "%MODS%" mkdir "%MODS%"
copy /y "%JAR%" "%MODS%\" >nul
echo run-server: 已拷入 wrutils1_21_11-1.0-SNAPSHOT.jar 到 %MODS%

cd /d "%SERVERDIR%"
java -Xmx2G -Xms2G -jar launcher.jar nogui
exit /b %ERRORLEVEL%
