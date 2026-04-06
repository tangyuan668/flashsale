@echo off
cd /d "%~dp0"

REM 检查MySQL目录是否存在
if not exist "mysql\bin\mysqld.exe" (
    echo [错误] MySQL未安装或路径不正确
    echo 请下载MySQL ZIP压缩包并解压到 dev-tools\mysql\ 目录
    echo 下载地址: https://dev.mysql.com/downloads/mysql/
    pause
    exit /b 1
)

echo [启动] MySQL...

REM 检查数据目录是否存在，不存在则初始化
if not exist "mysql\data" (
    echo [首次运行] 正在初始化MySQL数据目录...
    cd mysql\bin
    mysqld --initialize-insecure --console
    cd ..\..
)

REM 启动MySQL
start "" mysql\bin\mysqld.exe --console

REM 等待MySQL启动
timeout /t 5 /nobreak >nul

REM 导入初始化SQL
echo [导入] 初始化数据库脚本...
mysql\bin\mysql.exe -uroot -e "source %~dp0mysql\mysql-init.sql"

echo [完成] MySQL已启动，端口: 3306
