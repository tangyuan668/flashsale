# 本地开发环境中间件

## 目录结构

```
dev-tools/
├── mysql/          # 解压 mysql-8.x.x-winx64.zip 到这里
├── nacos/          # 解压 nacos-server-x.x.x.zip 到这里
├── redis/          # 放 redis.exe 单文件版
├── rocketmq/       # 解压 rocketmq-all-x.x.x-bin-release.zip 到这里
├── start-all.bat   # 一键启动所有中间件
└── stop-all.bat    # 一键停止所有中间件
```

## 下载地址（建议放非C盘）

| 中间件 | 版本 | 下载地址 | 解压到 |
|--------|------|----------|--------|
| MySQL | 8.0.x | https://dev.mysql.com/downloads/mysql/ | `dev-tools/mysql/` |
| Nacos | 2.3.0 | https://github.com/alibaba/nacos/releases | `dev-tools/nacos/` |
| Redis | 5.x | https://github.com/microsoftarchive/redis/releases | `dev-tools/redis/` |
| RocketMQ | 5.3.1 | https://rocketmq.apache.org/zh/docs/4.x/ | `dev-tools/rocketmq/` |

## 快速开始

1. 按上表下载并解压到对应目录
2. 双击 `start-all.bat` 启动所有中间件
3. 双击 `stop-all.bat` 停止所有中间件

## 各中间件配置

### MySQL (端口 3306)
- 用户名: root
- 密码: root
- 自动导入 sql/ 目录下的初始化脚本

### Nacos (端口 8848)
- 访问: http://localhost:8848/nacos
- 用户名: nacos
- 密码: nacos

### Redis (端口 6379)
- 无密码

### RocketMQ (端口 9876)
- NameServer: 9876
- Broker: 10911
