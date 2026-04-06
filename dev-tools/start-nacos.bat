@echo off
cd /d "%~dp0"

if not exist "nacos\bin\startup.cmd" (
    echo [错误] Nacos未安装或路径不正确
    echo 请下载Nacos并解压到 dev-tools\nacos\ 目录
    echo 下载地址: https://github.com/alibaba/nacos/releases
    pause
    exit /b 1
)

echo [启动] Nacos (单机模式)...
start "" nacos\bin\startup.cmd -m standalone

echo [完成] Nacos已启动
echo [控制台] http://localhost:8848/nacos (nacos/nacos)
