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
| JWT (jjwt) | 0.12.3 | 用户认证 |
| Hutool | 5.8.24 | 工具库（雪花算法等） |
| JMeter | 5.6.3 | 压力测试 |

## 系统架构

```
┌─────────────────────────────────────────────────────────────┐
│                         客户端                               │
└────────────────────────┬────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────┐
│                    API Gateway (8080)                       │
│           路由转发 / JWT鉴权 / 限流 / 防刷                    │
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
| flashsale-gateway | 8080 | API 网关：路由转发、JWT 鉴权、令牌桶限流、防刷拦截 |
| flashsale-user | 8081 | 用户服务：注册、登录、JWT 生成、Token 缓存 |
| flashsale-activity | 8082 | 活动服务：活动管理、库存预热、自动状态切换 |
| flashsale-inventory | 8083 | 库存服务：Redis+Lua 原子扣减、库存回滚、日志批量刷写 |
| flashsale-order | 8084 | 订单服务：异步下单、Deadline 超时机制、订单取消 |
| flashsale-common | - | 公共模块：统一响应、错误码、DTO、异常处理、JWT 工具 |

## 核心功能

### 1. 网关防护层

网关通过 4 层 GlobalFilter 依次处理请求（按 order 优先级排序）：

| 过滤器 | 优先级 | 功能 |
|--------|--------|------|
| HealthFilter | -200 | `/health` 健康检查直接返回 |
| AuthenticationFilter | -100 | JWT 验证，提取 userId/phone 写入请求头 |
| RateLimitFilter | -99 | Redis 令牌桶限流（容量100，每秒补充10个） |
| AntiBrushFilter | -98 | Lua 防刷（60次/分钟正常，超200次/分钟加入黑名单1小时） |

- 令牌桶和防刷均通过 **Lua 脚本** 实现原子操作
- 限流/防刷异常时降级放行，避免误杀
- 登录、注册、活动列表等路径免认证

### 2. 防超卖机制

库存扣减通过 **Redis + Lua 脚本** 实现原子操作，单次调用完成 4 项检查：

```
DEDUCT_STOCK_LUA 返回值：
  -1 → 库存不足
  -2 → 库存未预热
  -3 → 扣减中（同一用户有正在处理的订单，120秒窗口期）
  -4 → 已购买（每人限购一件）
  ≥0 → 扣减成功，返回剩余库存
```

Lua 脚本在扣减库存的同时设置 `deducting:{userId}:{stockKey}` 标记（120秒过期），防止窗口期重复请求。

### 3. 防重复购买

- **扣减阶段**：Lua 脚本中检查 `user:purchase:{userId}:{stockKey}` 是否存在
- **订单阶段**：订单创建成功后在 Redis 中设置购买标记（24小时有效期）
- **取消清理**：订单取消时清除购买标记，允许重新购买

### 4. 异步下单流程（双 MQ 模式）

```
用户发起秒杀
      │
      ▼
┌──────────────────────────────────────────────────────┐
│  OrderService.seckill()                              │
│  1. Pipeline 读：幂等检查（purchase/deducting/商品信息）│
│  2. 雪花算法生成订单号                                  │
│  3. Pipeline 写：记录处理中订单（Hash + ZSET）          │
│  4. 保存本地消息表 + 异步发 MQ                         │
│  5. 返回处理中（前端开始轮询）                          │
└──────────────────────┬───────────────────────────────┘
                       │ MQ: SECKILL_ORDER_TOPIC
                       ▼
┌──────────────────────────────────────────────────────┐
│  InventoryConsumer (32消费线程)                        │
│  1. Redis 去重（success key + processing key）         │
│  2. 调用 InventoryService.deductStock()               │
│     → Lua 原子扣减 Redis 库存                          │
│     → 库存日志入缓冲队列                               │
│  3. 保存本地消息 + 异步发结果 MQ                        │
│  4. Pipeline：设置成功标记 + 清理 processing key       │
└──────────────────────┬───────────────────────────────┘
                       │ MQ: STOCK_RESULT_TOPIC
                       ▼
┌──────────────────────────────────────────────────────┐
│  OrderConsumer (32消费线程)                            │
│  1. Redis 去重                                        │
│  2. OrderService.processStockDeductResult()           │
│     → 分支1：超时失败 → 发回滚 MQ + 清理               │
│     → 分支2：重复购买 → 发回滚 MQ + 记录失败            │
│     → 分支3：商品不存在 → 标记失败 + 清理               │
│     → 正常路径：TransactionTemplate 写入订单            │
│       + Pipeline 设置购买标记/超时标记/清理             │
└──────────────────────────────────────────────────────┘
```

### 5. Deadline 超时机制

订单服务使用 **Redis ZSET** 实现订单创建超时控制：

- 下单时将订单号写入 `order:processing:zset`，score 为 5 分钟后的时间戳
- 定时任务每分钟扫描 ZSET 中 score < now 的过期订单
- 超时处理逻辑：
  - 订单已创建 → 仅清理 ZSET
  - 库存已扣减（`d:inventory:{orderNo}` 存在）→ 发送回滚 MQ + 设置 `stock:rollback:sent` 防止重复回滚
  - 库存未扣减 → 仅标记失败
- 防止迟到消息重复回滚：迟到订单检查 `stock:rollback:sent` 标记

### 6. 最终一致性（本地消息表）

```
业务操作 + 保存本地消息（同一事务）
      │
      ▼
异步发送 MQ → 成功 → 标记已发送（CAS: status 0→1）
      │
      └→ 失败 → 留在本地消息表
                    │
                    ▼
              定时任务（每30秒）
              CAS 抢占（status 0→3→发送→1/0）
              指数退避重试（30s, 60s, 120s, 240s, 480s）
              最大5次 → 标记失败（status=2）
```

**高并发优化**：
- 内存 `BlockingQueue`（容量10000）缓冲消息，每 200ms 批量刷入数据库（批量500条）
- `ConcurrentHashMap<String, Boolean> sentFlags` 解决缓冲与异步回调的竞态条件
- 队列满时降级为直接写库，`@PreDestroy` 优雅关闭时刷入剩余消息
- 独立事务（`REQUIRES_NEW`）避免与业务事务耦合

### 7. 库存日志批量刷写

- 库存变动日志通过内存 `BlockingQueue`（容量10000）缓冲
- 定时任务每 1 秒批量刷入数据库（批量500条）
- 队列满时降级为直接写库
- `@PreDestroy` 优雅关闭

### 8. 订单超时取消

- **已创建订单**：定时任务每分钟扫描待支付超过 15 分钟的订单，CAS 乐观锁抢占取消（`status=0 → status=2`），发送回滚 MQ
- **未创建订单**：ZSET Deadline 机制，5 分钟超时自动标记失败

### 9. 库存预热

- 使用 **Redisson 分布式锁**（等待10秒，持有30秒）防止并发预热
- 同时预热库存数量和商品元数据到 Redis Hash
- 商品元数据预热避免秒杀时 Feign 调用活动服务

### 10. 活动自动管理

- 定时任务每分钟自动切换活动状态（待开始→进行中→已结束）
- 状态更新通过 Redisson 分布式锁保证并发安全

## Redis Key 设计

| Key 格式 | 用途 | 过期时间 |
|----------|------|----------|
| `stock:cache:{activityId}:{itemId}` | 库存数量 | 无（预热写入） |
| `activity:item:{activityId}:{itemId}` | 商品元数据 Hash | 2小时 |
| `user:purchase:{userId}:{stockKey}` | 用户购买标记 | 24小时 |
| `deducting:{userId}:{stockKey}` | 扣减中标记 | 120秒 |
| `order:processing:{orderNo}` | 处理中订单 Hash | 10分钟 |
| `order:processing:zset` | 超时订单 ZSET | - |
| `order:timeout:{orderNo}` | 订单支付超时标记 | 15分钟 |
| `order:fail:{orderNo}` | 订单失败原因 | 24小时 |
| `stock:rollback:sent:{orderNo}` | 回滚已发送标记 | 24小时 |
| `d:inventory:{orderNo}` | 库存消费去重成功标记 | 30分钟 |
| `d:order:{orderNo}` | 订单消费去重成功标记 | 30分钟 |
| `d:rollback:{orderNo}` | 回滚消费去重成功标记 | 30分钟 |
| `rate:limit:{clientId}` | 令牌桶限流 Hash | 1小时 |
| `anti:brush:{clientId}` | 防刷计数 Hash | 5分钟 |
| `ip:blacklist:{clientId}` | IP 黑名单 | 1小时 |

## MQ Topic 设计

| Topic | 生产者 | 消费者 | 消费线程数 | 用途 |
|-------|--------|--------|-----------|------|
| `SECKILL_ORDER_TOPIC` | Order 服务 | Inventory 服务 | 32 | 秒杀订单创建消息 |
| `STOCK_RESULT_TOPIC` | Inventory 服务 | Order 服务 | 32 | 库存扣减结果消息 |
| `STOCK_ROLLBACK_TOPIC` | Order 服务 | Inventory 服务 | 默认 | 库存回滚消息 |

## 消费者幂等与重试机制

所有 MQ 消费者采用统一的去重和错误处理模式：

```
1. 检查 success key（d:{type}:{orderNo}）→ 已处理则跳过
2. setIfAbsent processing key（30分钟 TTL）→ 正在处理则跳过
3. 执行业务逻辑
4. Pipeline：设置 success key + 删除 processing key（1次 RTT）
5. 异常处理：
   - 可重试错误（timeout/connection/network）→ 删除 processing key，抛异常触发 MQ 重试
   - 不可重试错误 → 保留 processing key，30分钟后自然过期允许重试
```

## API 接口

### 用户服务 (flashsale-user)

| 接口 | 方法 | 认证 | 描述 |
|------|------|------|------|
| `/user/register` | POST | 否 | 用户注册（手机号+密码） |
| `/user/login` | POST | 否 | 用户登录，返回 JWT Token |
| `/user/info` | GET | 是 | 获取用户信息 |
| `/user/logout` | POST | 是 | 用户登出，清除 Token |

### 活动服务 (flashsale-activity)

| 接口 | 方法 | 认证 | 描述 |
|------|------|------|------|
| `/activity/create` | POST | 是 | 创建活动（含商品列表） |
| `/activity/list` | GET | 否 | 获取进行中的活动列表 |
| `/activity/{id}` | GET | 否 | 获取活动详情（含商品） |
| `/activity/{id}/preheat` | POST | 是 | 库存预热（Redisson 分布式锁） |
| `/activity/{id}/status` | PUT | 是 | 更新活动状态 |

### 订单服务 (flashsale-order)

| 接口 | 方法 | 认证 | 描述 |
|------|------|------|------|
| `/order/create` | POST | 是 | 秒杀下单（异步，返回处理中） |
| `/order/{orderNo}` | GET | 是 | 获取订单详情 |
| `/order/{orderNo}/status` | GET | 否 | 获取订单状态（前端轮询） |
| `/order/my` | GET | 是 | 获取我的订单列表 |
| `/order/{orderNo}/cancel` | POST | 是 | 取消订单（CAS 乐观锁） |

### 库存服务 (flashsale-inventory)

| 接口 | 方法 | 认证 | 描述 |
|------|------|------|------|
| `/inventory` | GET | 是 | 获取库存信息 |
| `/inventory/deduct` | POST | 是 | 扣减库存（Redis+Lua 原子操作） |
| `/inventory/rollback` | POST | 是 | 回滚库存 |

### 网关服务 (flashsale-gateway)

| 接口 | 方法 | 描述 |
|------|------|------|
| `/health` | GET | 健康检查 |
| `/api/**` | * | 路由转发到各微服务 |

## 数据库设计

每个微服务使用独立数据库，共 5 个数据库：

| 数据库 | 表 | 说明 |
|--------|-----|------|
| flashsale_user | `user` | 用户表（BCrypt 密码、逻辑删除） |
| flashsale_activity | `activity`, `activity_item` | 活动表、活动商品关联表 |
| flashsale_inventory | `inventory`, `inventory_log`, `local_message` | 库存表（含乐观锁 version）、库存变动日志、本地消息表 |
| flashsale_order | `order_info`, `order_item`, `local_message` | 订单表、订单明细表、本地消息表 |

**订单状态流转**：0-待支付 → 1-已支付 / 2-已取消 / 3-已超时

## 配置说明

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| Nacos 地址 | localhost:8848 | 服务注册中心（namespace: flashsale） |
| Redis 地址 | localhost:6379 | 缓存服务（db 0） |
| MySQL 地址 | localhost:3306 | 数据库（root/123456） |
| RocketMQ 地址 | localhost:9876 | 消息队列 |
| 库存扣减中窗口 | 120 秒 | 防止重复请求 |
| 订单创建超时 | 5 分钟 | Deadline ZSET |
| 订单支付超时 | 15 分钟 | 未支付自动取消 |
| 令牌桶容量 | 100 | 每客户端 |
| 令牌补充速率 | 10/秒 | 每客户端 |
| 防刷阈值 | 60次/分钟 | 正常请求上限 |
| 黑名单阈值 | 200次/分钟 | 加入黑名单1小时 |
| 消息最大重试 | 5 次 | 指数退避 |
| 消费者线程数 | 32 | Inventory/Order 消费者 |

## 压力测试

使用 JMeter 进行压力测试，包含 4 个线程组：

| 线程组 | 并发数 | Ramp-up | 描述 |
|--------|--------|---------|------|
| 批量用户登录 | 500 | 10s | 注册并登录用户，Token 保存到 CSV |
| 秒杀核心 | 500 | 2s | 发起秒杀请求 + 轮询订单状态 |
| 混合负载 | 100 | 10s | 浏览活动列表 → 查看详情 → 秒杀（5轮） |
| 稳定性测试 | 50 | 10s | 查询我的订单列表（持续10分钟） |

## 快速开始

### 环境准备

- JDK 17+
- Maven 3.6+
- MySQL 8.0+
- Redis 6.x+
- Nacos 2.x+
- RocketMQ 5.x+

### 一键启动

```bash
# 启动所有中间件（MySQL、Redis、Nacos、RocketMQ）
dev-tools\start-all.bat
```

### 手动启动

```bash
# 1. 数据库初始化
mysql -u root -p < sql/flashsale_all.sql

# 2. 中间件启动
# Nacos (端口 8848)
nacos/bin/startup.cmd -m standalone
# Redis (端口 6379)
redis-server
# RocketMQ NameServer (端口 9876) + Broker (端口 10911)
mqnamesrv
mqbroker -n localhost:9876 autoCreateTopicEnable=true

# 3. 编译打包
mvn clean package -DskipTests

# 4. 按顺序启动服务
java -jar flashsale-gateway/target/flashsale-gateway-1.0.0.jar
java -jar flashsale-user/target/flashsale-user-1.0.0.jar
java -jar flashsale-activity/target/flashsale-activity-1.0.0.jar
java -jar flashsale-inventory/target/flashsale-inventory-1.0.0.jar
java -jar flashsale-order/target/flashsale-order-1.0.0.jar
```

### 停止所有服务

```bash
dev-tools\stop-all.bat
```

## 项目亮点

1. **多层防护**：网关限流 + 防刷 + JWT 鉴权，Lua 原子操作保障
2. **Redis Pipeline 优化**：多次 Redis 操作合并为 1 次网络往返，降低延迟
3. **双 MQ 异步架构**：订单创建与库存扣减完全解耦，前端轮询获取结果
4. **Deadline 超时机制**：ZSET 实现订单创建超时，防止资源泄露
5. **本地消息表 + 内存缓冲**：高并发下先缓冲再批量入库，指数退避重试
6. **消费者幂等**：Redis 去重 + 可重试/不可重试错误分类
7. **CAS 乐观锁**：订单取消使用 CAS 防止多实例重复处理
8. **迟到消息处理**：通过 `stock:rollback:sent` 标记防止超时与结果到达的竞态
9. **库存日志批量刷写**：内存队列缓冲 + 定时批量入库，降低 DB 压力
10. **优雅关闭**：`@PreDestroy` 刷入缓冲队列中的剩余消息和日志

## 目录结构

```
flashsale
├── flashsale-common       # 公共模块（统一响应、错误码、DTO、JWT 工具）
├── flashsale-gateway      # 网关服务（JWT 鉴权、令牌桶限流、Lua 防刷）
├── flashsale-user         # 用户服务（注册登录、Token 管理）
├── flashsale-activity     # 活动服务（活动管理、Redisson 分布式锁预热）
├── flashsale-inventory    # 库存服务（Lua 原子扣减、MQ 消费、日志批量刷写）
├── flashsale-order        # 订单服务（异步下单、Deadline 超时、CAS 取消）
├── sql                    # 数据库初始化脚本
├── jmeter                 # JMeter 压力测试脚本
├── dev-tools              # 开发工具（启动脚本、报告生成）
├── checkstyle.xml         # 代码规范检查配置
├── pom.xml                # Maven 父 POM
└── README.md
```

## 开发者

本项目为毕业设计作品，欢迎学习交流。
