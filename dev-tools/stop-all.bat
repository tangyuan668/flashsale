@echo off
echo [停止] 所有中间件...

REM 停止Nacos
if exist "nacos\bin\shutdown.cmd" (
    echo [停止] Nacos...
    call nacos\bin\shutdown.cmd
)

REM 停止MySQL
taskkill /F /IM mysqld.exe 2>nul

REM 停止Redis
taskkill /F /IM redis-server.exe 2>nul

REM 停止RocketMQ
taskkill /F /IM java.exe /FI "WINDOWTITLE eq RocketMQ*" 2>nul

echo [完成] 所有中间件已停止
pause
