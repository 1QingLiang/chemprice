@echo off
chcp 65001 >nul
title ChemPrice MySQL 隧道
cd /d "%~dp0"

echo ============================================================
echo   ChemPrice 数据库隧道
echo   本地 127.0.0.1:13306  --加密转发--^>  服务器 localhost:3306
echo ============================================================
echo.

rem ---------- 检查本地端口是否已被占用 ----------
netstat -ano | findstr ":13306" | findstr "LISTENING" >nul 2>&1
if %errorlevel%==0 (
    echo [!] 本地 13306 端口已被占用，可能隧道已在运行。
    echo     若 Navicat 能连上，直接关掉本窗口即可。
    echo     若要强制重开，请先结束占用该端口的进程。
    echo.
    pause
    exit /b 1
)

echo [*] 正在建立隧道...（本窗口需保持打开）
echo.
echo     连上后用 Navicat 新建 MySQL 连接：
echo       主机   127.0.0.1
echo       端口   13306
echo       用户   cardplaza
echo       密码   见服务器 ~/chemprice/wechat/.secrets.env 的 DB_PASSWORD
echo.
echo     用完直接关闭本窗口，隧道自动断开。
echo     ------------------------------------------------
echo.

rem ---------- 建立隧道（-N 不执行命令，只转发；保活 30s） ----------
rem   ⚠️ 注意：不要加 -f（实测在部分环境下会立即退出，隧道无法保持）
ssh -N ^
    -L 13306:127.0.0.1:3306 ^
    -o ServerAliveInterval=30 ^
    -o ServerAliveCountMax=3 ^
    -o ExitOnForwardFailure=yes ^
    -o StrictHostKeyChecking=accept-new ^
    ubuntu@82.156.8.214

echo.
echo [*] 隧道已断开（码 %errorlevel%）。
pause
