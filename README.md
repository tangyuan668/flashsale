# FlashSale 秒杀系统

> 基于 Spring Cloud 微服务架构的高并发秒杀系统 - 毕业设计项目

## 项目简介

本项目是一个完整的电商秒杀系统，采用微服务架构设计，支持高并发场景下的商品抢购。系统通过 Redis + Lua 脚本实现库存原子扣减防止超卖，使用 RocketMQ 实现服务间异步解耦，并通过本地消息表保证最终一致性。

## 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 17 | 开发语言 |
| Spring Boot | 3.2.0 | 核心框架 |
| Spring Cloud | 2023.0.0 | 微服务治理 |
| Spring Cloud Alibaba | 2023.0.0.0-RC1 | 阿里微服务组件 |
| Nacos | 2.x | 服务注册与配置中心 |
| MyBatis Plus | 3.5.5 | ORM 框架 |
| MySQL | 8.0+ | 数据存储 |
| Redis | 6.x+ | 缓存/分布式锁 |
| Redisson | 3.25.0 | 分布式锁实现 |
| RocketMQ | 5.x | 消息队列 |
| JWT | 0.12.3 | 用户认证 |

## 系统架构

```
┌─────────────────────────────────────────────────────────────┐
│                         客户端                               │
└────────────────────────┬────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────┐
│                    API Gateway (8080)                       │
│                   路由转发 / 负载均衡                         │
└────┬─────────────┬─────────────┬─────────────┬──────────────┘
     │             │             │             │
     ▼             ▼             ▼             ▼
┌─────────┐  ┌─────────┐  ┌──────────┐  ┌──────────┐
│  User   │  │Activity │  │Inventory │  │  Order   │
│ (8081)  │  │ (8082)  │  │  (8083)  │  │  (8084)  │
└─────────┘  └─────────┘  └──────────┘  └──────────┘
     │             │             │             │
     └─────────────┴─────────────┴─────────────┘
                   │                   │
                   ▼                   ▼
            ┌─────────┐         ┌─────────┐
            │  Nacos  │         │RocketMQ │
            └─────────┘         └─────────┘
                   │                   │
                   ▼                   ▼
            ┌─────────┐         ┌─────────┐
            │  MySQL  │         │  Redis  │
            └─────────┘         └─────────┘
```

## 模块说明

| 模块 | 端口 | 职责 |
|------|------|------|
| flashsale-gateway | 8080 | 网关服务，路由转发 |
| flashsale-user | 8081 | 用户服务，注册登录认证 |
| flashsale-activity | 8082 | 活动服务，秒杀活动管理 |
| flashsale-inventory | 8083 | 库存服务，库存扣减与回滚 |
| flashsale-order | 8084 | 订单服务，订单创建与状态管理 |
| flashsale-common | - | 公共模块，通用实体与工具类 |

## 核心功能

### 1. 防超卖机制
- **Redis + Lua 脚本**：原子操作扣减库存
- **扣减中标记**：防止窗口期重复请求
- **分布式锁**：Redisson 实现并发控制

### 2. 防重复购买
- Redis 记录用户购买标记（24小时有效期）
- 订单创建成功后才设置购买标记

### 3. 异步解耦
- 订单服务发送 MQ 到库存服务
- 库存服务处理完成后发送结果 MQ
- 前端轮询查询订单状态

### 4. 最终一致性
- **本地消息表**：保证消息可靠发送
- **定时重试**：失败消息自动重试
- **库存补偿**：异常场景自动回滚

### 5. 订单超时取消
- 15分钟未支付自动取消
- 取消时自动回滚库存

## 秒杀流程

```
用户发起秒杀
      │
      ▼
┌─────────────────┐
│   订单服务      │
│ 1. 生成订单号   │
│ 2. 保存本地消息 │
│ 3. 发送 MQ      │
│ 4. 返回处理中   │
└────────┬────────┘
         │ MQ
         ▼
┌─────────────────┐
│   库存服务      │
│ 1. Lua扣减库存  │
│ 2. 记录变动日志 │
│ 3. 发结果 MQ    │
└────────┬────────┘
         │ MQ
         ▼
┌─────────────────┐
│   订单服务      │
│ 1. 消费结果 MQ  │
│ 2. 创建订单     │
│ 3. 或记录失败   │
└─────────────────┘
```

## 快速开始

### 环境准备

确保以下环境已安装：

- JDK 17+
- Maven 3.6+
- MySQL 8.0+
- Redis 6.x+
- Nacos 2.x+
- RocketMQ 5.x+

### 数据库初始化

```bash
# 创建数据库
mysql -u root -p < sql/flashsale_all.sql
```

或分别执行各模块 SQL：

```bash
mysql -u root -p < sql/flashsale_user.sql
mysql -u root -p < sql/flashsale_activity.sql
mysql -u root -p < sql/flashsale_inventory.sql
mysql -u root -p < sql/flashsale_order.sql
mysql -u root -p < sql/flashsale_local_message.sql
```

### 中间件启动

```bash
# Nacos (默认端口 8848)
cd nacos/bin
./startup.sh -m standalone

# Redis (默认端口 6379)
redis-server

# RocketMQ NameServer (默认端口 9876)
cd rocketmq
sh mqnamesrv

# RocketMQ Broker (默认端口 10911)
sh mqbroker -n localhost:9876
```

### 服务启动

```bash
# 编译打包
mvn clean package -DskipTests

# 按顺序启动各服务
java -jar flashsale-gateway/target/flashsale-gateway-1.0.0.jar
java -jar flashsale-user/target/flashsale-user-1.0.0.jar
java -jar flashsale-activity/target/flashsale-activity-1.0.0.jar
java -jar flashsale-inventory/target/flashsale-inventory-1.0.0.jar
java -jar flashsale-order/target/flashsale-order-1.0.0.jar
```

## API 接口

### 用户服务 (flashsale-user)

| 接口 | 方法 | 描述 |
|------|------|------|
| `/user/register` | POST | 用户注册 |
| `/user/login` | POST | 用户登录 |

### 活动服务 (flashsale-activity)

| 接口 | 方法 | 描述 |
|------|------|------|
| `/activity/create` | POST | 创建活动 |
| `/activity/list` | GET | 获取活动列表 |
| `/activity/{id}` | GET | 获取活动详情 |
| `/activity/{id}/preheat` | POST | 库存预热 |
| `/activity/{id}/status` | PUT | 更新活动状态 |

### 订单服务 (flashsale-order)

| 接口 | 方法 | 描述 |
|------|------|------|
| `/order/create` | POST | 秒杀下单 |
| `/order/{orderNo}` | GET | 获取订单详情 |
| `/order/{orderNo}/status` | GET | 获取订单状态(轮询) |
| `/order/my` | GET | 获取我的订单 |
| `/order/{orderNo}/cancel` | POST | 取消订单 |

### 库存服务 (flashsale-inventory)

| 接口 | 方法 | 描述 |
|------|------|------|
| `/inventory/deduct` | POST | 扣减库存 |
| `/inventory/{activityId}/{itemId}` | GET | 获取库存信息 |

## 配置说明

各服务默认配置如下，可根据实际情况修改：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| Nacos 地址 | localhost:8848 | 服务注册中心 |
| Redis 地址 | localhost:6379 | 缓存服务 |
| MySQL 地址 | localhost:3306 | 数据库 |
| RocketMQ 地址 | localhost:9876 | 消息队列 |
| 超时时间 | 5 分钟 | 库存补偿阈值 |
| 订单超时 | 15 分钟 | 未支付自动取消 |

## 项目亮点

1. **高并发支持**：Redis 缓存 + Lua 原子操作
2. **数据一致性**：本地消息表 + 定时补偿
3. **可扩展性**：微服务架构，易于水平扩展
4. **用户体验**：异步处理 + 轮询查询
5. **容错能力**：库存补偿回滚机制

## 目录结构

```
flashsale
├── flashsale-common       # 公共模块
├── flashsale-gateway      # 网关服务
├── flashsale-user         # 用户服务
├── flashsale-activity     # 活动服务
├── flashsale-inventory    # 库存服务
├── flashsale-order        # 订单服务
├── sql                    # 数据库脚本
└── README.md
```

## 开发者

本项目为毕业设计作品，欢迎学习交流。
