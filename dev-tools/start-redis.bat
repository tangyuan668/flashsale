@echo off
cd /d "%~dp0"

REM 检查Redis
if exist "redis\redis-server.exe" (
    set REDIS_CMD=redis\redis-server.exe
) else if exist "redis\redis-server" (
    set REDIS_CMD=redis\redis-server
) else (
    echo [错误] Redis未找到
    echo 请下载Redis并放到 dev-tools\redis\ 目录
    echo Windows版本下载: https://github.com/microsoftarchive/redis/releases
    pause
    exit /b 1
)

echo [启动] Redis...
start "" %REDIS_CMD% redis\redis.windows.conf

echo [完成] Redis已启动，端口: 6379
