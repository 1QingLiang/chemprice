@echo off
chcp 65001 >nul
title ChemPrice 数据库连接信息

echo ============================================================
echo   ChemPrice 数据库 · 连接信息卡
echo ============================================================
echo.
echo 【第一步】先双击运行「数据库隧道.bat」，保持那个窗口开着
echo.
echo 【第二步】在 Navicat / DBeaver 里新建 MySQL 连接：
echo.
echo     连接名       ChemPrice（本地隧道）
echo     主机名        127.0.0.1
echo     端口          13306
echo     用户名        cardplaza
echo     密码          见下方说明
echo.
echo   ------------------------------------------------
echo   密码从服务器读取（不落盘，仅临时显示）：
echo.

rem 从服务器读取密码并显示，仅在本窗口临时呈现
ssh -o BatchMode=yes -o ConnectTimeout=10 ubuntu@82.156.8.214 "grep DB_PASSWORD ~/chemprice/wechat/.secrets.env | sed 's/export //; s/DB_PASSWORD=//'"

echo.
echo   ------------------------------------------------
echo.
echo   ⚠️ 注意事项
echo.
echo   1. 数据库密码不会保存在本机任何文件里，
echo      每次需要时运行本脚本会重新从服务器读取。
echo.
echo   2. 3306 端口对外网始终保持关闭，
echo      所有流量走 SSH 加密隧道，无法被扫描到。
echo.
echo   3. 隧道窗口关闭后，Navicat 会立即断连，属正常现象。
echo.
echo   4. 服务器地址 82.156.8.214（ubuntu 用户，已配置密钥免密）
echo.
pause
