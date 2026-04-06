@echo off
echo ========================================
echo   FlashSale 中间件启动脚本
echo ========================================
echo.

REM 按顺序启动各中间件
call start-mysql.bat
timeout /t 3 /nobreak >nul

call start-redis.bat
timeout /t 2 /nobreak >nul

call start-nacos.bat
timeout /t 5 /nobreak >nul

call start-rocketmq.bat

echo.
echo ========================================
echo   所有中间件启动完成！
echo ========================================
echo.
echo 服务地址:
echo   - MySQL:    localhost:3306  (root/root)
echo   - Redis:    localhost:6379
echo   - Nacos:    http://localhost:8848/nacos (nacos/nacos)
echo   - RocketMQ: localhost:9876
echo.
echo 下一步: 在IDEA中启动各微服务
echo.
pause
