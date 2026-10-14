@echo off
rem ============================================================
rem  秒杀系统一键启动（demo 模式，内存 H2 + 内置 Redis 依赖）
rem  双击运行即可，浏览器访问 http://localhost:8080/
rem  测试账号: zhangsan / 123456
rem  关闭窗口或按 Ctrl+C 即停止应用
rem ============================================================
chcp 65001 >nul
title seckill-system demo

rem 若 8080 被占用（比如已经有一个实例在跑），提示后退出
netstat -ano | findstr "LISTENING" | findstr ":8080 " >nul 2>&1
if %errorlevel%==0 (
    echo [!] 端口 8080 已被占用——应用可能已经在运行。
    echo     直接在浏览器打开 http://localhost:8080/ 即可；
    echo     若要重启，请先关闭占用 8080 的进程。
    pause
    exit /b 1
)

rem 切到本脚本所在目录（无论项目放在哪个盘哪个路径都通用）
cd /d "%~dp0"

echo [1/2] 清空 Redis 缓存（保证全新演示状态）...
rem 找 redis-cli：优先用 tools 目录（PATH 里通常没有 redis-cli）
set "RCLI=%USERPROFILE%\tools\redis\redis-cli.exe"
if not exist "%RCLI%" set "RCLI=redis-cli"
"%RCLI%" FLUSHDB >nul 2>&1

echo [2/2] 启动应用（首次启动约 10 秒）...
java -Dfile.encoding=UTF-8 -jar target\seckill-system-1.0.0.jar --spring.profiles.active=demo

pause
