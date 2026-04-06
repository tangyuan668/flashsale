@echo off
cd /d "%~dp0"

if not exist "rocketmq\bin\mqnamesrv.cmd" (
    echo [错误] RocketMQ未安装或路径不正确
    echo 请下载RocketMQ并解压到 dev-tools\rocketmq\ 目录
    echo 下载地址: https://rocketmq.apache.org/zh/docs/4.x/
    pause
    exit /b 1
)

echo [启动] RocketMQ NameServer...
start "RocketMQ-NameServer" rocketmq\bin\mqnamesrv.cmd

REM 等待NameServer启动
timeout /t 3 /nobreak >nul

echo [启动] RocketMQ Broker...
start "RocketMQ-Broker" rocketmq\bin\mqbroker.cmd -n localhost:9876 autoCreateTopicEnable=true

echo [完成] RocketMQ已启动
echo [NameServer] localhost:9876
echo [Broker] localhost:10911
