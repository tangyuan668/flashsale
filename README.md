# FlashSale 秒杀系统

> 基于 Spring Cloud 微服务架构的高并发秒杀系统 - 毕业设计项目

## 项目简介

本项目是一个完整的电商秒杀系统，采用微服务架构设计，支持高并发场景下的商品抢购。系统通过 Redis + Lua 脚本实现库存原子扣减防止超卖，使用 RocketMQ 实现服务间异步解耦，并通过本地消息表保证最终一致性。包含用户端和管理员端，支持活动管理、商品动态追加、订单管理、数据统计等功能。

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
| JWT (jjwt) | 0.12.3 | 用户认证（携带 userId/phone/role） |
| Hutool | 5.8.24 | 工具库（雪花算法等） |
| JMeter | 5.6.3 | 压力测试 |

## 系统架构

```
┌─────────────────────────────────────────────────────────────┐
│                    客户端（用户端 + 管理端）                    │
└────────────────────────┬────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────┐
│                    API Gateway (8080)                       │
│     路由转发 / JWT鉴权(含role) / 限流 / 防刷                   │
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
| flashsale-gateway | 8080 | API 网关：路由转发、JWT 鉴权（含 role）、令牌桶限流、防刷拦截 |
| flashsale-user | 8081 | 用户服务：注册、登录（返回 role）、JWT 生成、Token 缓存 |
| flashsale-activity | 8082 | 活动服务：活动管理、增量预热、动态加商品、自动状态切换 |
| flashsale-inventory | 8083 | 库存服务：Redis+Lua 原子扣减、库存回滚、日志批量刷写 |
| flashsale-order | 8084 | 订单服务：异步下单、Deadline 超时、订单取消、支付、统计 |
| flashsale-common | - | 公共模块：统一响应、错误码、DTO、异常处理、JWT 工具 |

## 核心功能

### 1. 网关防护层

网关通过 4 层 GlobalFilter 依次处理请求（按 order 优先级排序）：

| 过滤器 | 优先级 | 功能 |
|--------|--------|------|
| HealthFilter | -200 | `/health` 健康检查直接返回 |
| AuthenticationFilter | -100 | JWT 验证，提取 userId/phone/role 写入请求头（`X-User-Id`/`X-Phone`/`X-User-Role`） |
| RateLimitFilter | -99 | Redis 令牌桶限流（容量100，每秒补充10个） |
| AntiBrushFilter | -98 | Lua 防刷（60次/分钟正常，超200次/分钟加入黑名单1小时） |

- 令牌桶和防刷均通过 **Lua 脚本** 实现原子操作
- 限流/防刷异常时降级放行，避免误杀
- 登录、注册、活动列表、活动详情等路径免认证；网关鉴权白名单实际为 `/api/user/login`、`/api/user/register`、`/api/activity/list`、`/api/activity/`（前缀匹配）
- JWT Token 中携带 `userId`、`phone`、`role`，网关解析后通过请求头传递给下游服务

### 2. 用户角色与权限

- 用户表新增 `role` 字段：0-普通用户，1-管理员
- 登录和用户信息接口返回 `role`
- JWT claims 中携带 `role`，网关解析后通过 `X-User-Role` 请求头传递
- 管理端接口（活动管理、订单管理、数据统计）目前**仅在前端路由层做 `requiresAdmin` 拦截**，后端 Controller 暂未做角色级鉴权（已知待完善项）

### 3. 防超卖机制

库存扣减通过 **Redis + Lua 脚本** 实现原子操作，单次调用按以下顺序完成 4 项检查：

```
DEDUCT_STOCK_LUA 检查顺序与返回值：
  1. 库存未预热         → return -2
  2. 库存不足           → return -1
  3. 已购买（限购一件）  → return -4
  4. 扣减中（120秒窗口） → return -3
  全部通过 → 执行扣减并设置 deducting 标记（120秒），返回剩余库存 ≥0
```

> 注：扣减中标记（`deducting:{userId}:{stockKey}`）由 Lua 写入，购买标记（`user:purchase:{userId}:{stockKey}`）由订单服务在订单创建成功后写入。

### 4. 异步下单流程（双 MQ 模式）

```
用户发起秒杀 → OrderService.seckill()
    → Pipeline 读：幂等检查 + 商品信息
    → 生成订单号 + Pipeline 写：记录处理中订单
    → 保存本地消息表 + 异步发 MQ → 返回处理中

MQ: SECKILL_ORDER_TOPIC → InventoryConsumer (32线程)
    → Redis 去重 → Lua 原子扣减 → 库存日志入缓冲队列
    → 保存本地消息 + 异步发结果 MQ

MQ: STOCK_RESULT_TOPIC → OrderConsumer (32线程)
    → Redis 去重 → 处理扣减结果
    → 成功：TransactionTemplate 写入订单
    → 失败/超时/重复：发回滚 MQ + 清理
```

### 5. Deadline 超时机制

订单服务使用 **Redis ZSET** 实现订单创建超时控制：

- 下单时将订单号写入 `order:processing:zset`，score 为 5 分钟后的时间戳
- 定时任务每分钟扫描过期订单
- 超时处理：已创建 → 清理；库存已扣 → 发回滚；未扣 → 标记失败
- 防止迟到消息重复回滚：检查 `stock:rollback:sent` 标记

### 6. 最终一致性（本地消息表）

```
业务操作 + 保存本地消息（同一事务）
    → 异步发送 MQ → 成功标记已发送
    → 失败 → 定时任务线性退避重试（30s/60s/90s/120s/150s，最多5次）
```

**高并发优化**：内存 `BlockingQueue` 缓冲（容量 10000）+ 每 200ms 批量刷库（批量500条）+ `@PreDestroy` 关闭时刷入剩余消息

### 7. 增量预热机制

- 使用 **Redisson 分布式锁** 防止并发预热
- **幂等设计**：`SETNX` 代替 `SET`，已缓存的商品跳过不覆盖
- **支持动态加商品**：活动中途追加商品后重新预热，只写入新增商品的库存和元数据
- 同时预热库存数量和商品元数据到 Redis Hash

### 8. 活动生命周期管理

- **创建活动**：自动判断时间，当前时间在活动范围内直接设为进行中
- **动态加商品**：活动中追加商品，重置预热状态
- **下架商品**：删除单个商品，清理 Redis 缓存，已有订单不受影响
- **删除活动**：逻辑删除 + 清理 Redis 缓存 + 发 MQ 通知订单服务失效订单
- **自动状态切换**：定时任务每分钟切换活动状态（待开始→进行中→已结束）
- **过期处理**：活动过期时自动发 MQ 失效所有待支付订单

### 9. 订单状态与失效机制

| 状态值 | 含义 | 触发场景 |
|--------|------|----------|
| 0 | 待支付 | 秒杀下单成功 |
| 1 | 已支付 | 支付成功 |
| 2 | 已取消 | 用户主动取消 |
| 3 | 已超时 | 支付超时（15分钟） |
| 4 | 已失效 | 活动被删除或过期 |

- 活动删除/过期时，通过 MQ 通知订单服务批量失效待支付订单
- 失效订单同时清理购买标记 + 发送库存回滚 MQ

### 10. 支付功能

- 幂等检查（`payment:done:{orderNo}`）+ 分布式锁（`payment:lock:{orderNo}`）
- CAS 更新订单状态 0→1
- 模拟支付网关（支持 alipay/wechat/mock）
- 支付完成标记写入 Redis

### 11. 管理员数据统计

提供 5 个统计 API，支持管理端数据看板：

| 接口 | 描述 |
|------|------|
| `GET /api/stat/overview` | 总览：用户数、活动数、订单数、总销售额 |
| `GET /api/stat/order-trend?days=7` | 订单趋势：按天统计订单量和销售额 |
| `GET /api/stat/activity-rank?limit=10` | 活动排行：按销量排序 |
| `GET /api/stat/order-status-distribution` | 订单分布：按状态统计 |
| `GET /api/stat/hourly-distribution` | 时段分布：按小时统计订单量 |

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
| `d:invalidate:{activityId}` | 活动失效去重标记 | 30分钟 |
| `rate:limit:{clientId}` | 令牌桶限流 Hash | 1小时 |
| `anti:brush:{clientId}` | 防刷计数 Hash | 5分钟 |
| `ip:blacklist:{clientId}` | IP 黑名单 | 1小时 |
| `payment:done:{orderNo}` | 支付完成标记 | 24小时 |
| `payment:lock:{orderNo}` | 支付分布式锁 | 5分钟 |

## MQ Topic 设计

| Topic | 生产者 | 消费者 | 消费线程数 | 用途 |
|-------|--------|--------|-----------|------|
| `SECKILL_ORDER_TOPIC` | Order 服务 | Inventory 服务 | 32 | 秒杀订单创建消息 |
| `STOCK_RESULT_TOPIC` | Inventory 服务 | Order 服务 | 32 | 库存扣减结果消息 |
| `STOCK_ROLLBACK_TOPIC` | Order 服务 | Inventory 服务 | 默认 | 库存回滚消息 |
| `ACTIVITY_INVALIDATE_TOPIC` | Activity 服务 | Order 服务 | 默认 | 活动失效消息（删除/过期） |

## API 接口

### 用户服务 (flashsale-user)

| 接口 | 方法 | 认证 | 描述 |
|------|------|------|------|
| `/user/register` | POST | 否 | 用户注册（手机号+密码） |
| `/user/login` | POST | 否 | 用户登录，返回 JWT Token + role |
| `/user/info` | GET | 是 | 获取用户信息（含 role） |
| `/user/logout` | POST | 是 | 用户登出，清除 Token |
| `/user/count` | GET | 是 | 获取用户总数（内部调用） |

### 活动服务 (flashsale-activity)

| 接口 | 方法 | 认证 | 描述 |
|------|------|------|------|
| `/activity/create` | POST | 是 | 创建活动（含商品列表，自动判断状态） |
| `/activity/list` | GET | 否 | 获取进行中的活动列表 |
| `/activity/{id}` | GET | 否 | 获取活动详情（含商品） |
| `/activity/{id}/preheat` | POST | 是 | 增量预热（幂等，已有商品不覆盖） |
| `/activity/{id}/status` | PUT | 是 | 更新活动状态 |
| `/activity/{id}/items` | POST | 是 | 给活动添加商品 |
| `/activity/{activityId}/items/{itemId}` | DELETE | 是 | 下架活动商品（清理 Redis 缓存） |
| `/activity/{id}` | DELETE | 是 | 删除活动（级联清理缓存+失效订单） |
| `/activity/count` | GET | 是 | 获取活动总数（内部调用） |

### 订单服务 (flashsale-order)

| 接口 | 方法 | 认证 | 描述 |
|------|------|------|------|
| `/order/create` | POST | 是 | 秒杀下单（异步，返回处理中） |
| `/order/{orderNo}` | GET | 是 | 获取订单详情 |
| `/order/{orderNo}/status` | GET | 否 | 获取订单状态（前端轮询） |
| `/order/my` | GET | 是 | 获取我的订单列表 |
| `/order/all` | GET | 是 | 获取所有订单列表（管理员） |
| `/order/{orderNo}/cancel` | POST | 是 | 取消订单（CAS 乐观锁） |

### 支付服务 (flashsale-order)

| 接口 | 方法 | 认证 | 描述 |
|------|------|------|------|
| `/payment/pay` | POST | 是 | 发起支付（RequestBody: `{orderNo, payMethod}`，payMethod 支持 alipay/wechat/mock） |
| `/payment/{orderNo}` | GET | 是 | 查询支付信息 |

### 统计服务 (flashsale-order)

| 接口 | 方法 | 认证 | 描述 |
|------|------|------|------|
| `/stat/overview` | GET | 是 | 总览统计（用户数不含管理员、活动数、订单数、销售额） |
| `/stat/order-trend` | GET | 是 | 订单趋势（按天，默认7天） |
| `/stat/activity-rank` | GET | 是 | 活动销量排行（默认Top10） |
| `/stat/order-status-distribution` | GET | 是 | 订单状态分布 |
| `/stat/hourly-distribution?days=7` | GET | 是 | 每小时订单分布（默认7天） |

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
| flashsale_user | `user` | 用户表（含 role 字段、密码加密占位实现、逻辑删除） |
| flashsale_activity | `activity`, `activity_item` | 活动表、活动商品关联表 |
| flashsale_inventory | `inventory`, `inventory_log`, `local_message` | 库存表（含乐观锁 version）、库存变动日志、本地消息表 |
| flashsale_order | `order_info`, `order_item`, `payment`, `local_message` | 订单表、订单明细表、支付记录表、本地消息表 |

**订单状态流转**：0-待支付 → 1-已支付 / 2-已取消 / 3-已超时 / 4-已失效

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
| 批量用户登录 | 1000 | 10s | 注册并登录用户，Token 保存到 CSV |
| 秒杀核心 | 1000 | 1s | 发起秒杀请求 + 轮询订单状态 |
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

# 2. 添加 role 字段（如使用旧版 SQL）
ALTER TABLE flashsale_user.user ADD COLUMN role TINYINT DEFAULT 0 COMMENT '0-普通用户 1-管理员';
UPDATE flashsale_user.user SET role = 1 WHERE id = 1;

# 3. 中间件启动
nacos/bin/startup.cmd -m standalone
redis-server
mqnamesrv
mqbroker -n localhost:9876 autoCreateTopicEnable=true

# 4. 编译打包
mvn clean package -DskipTests

# 5. 按顺序启动服务
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

1. **多层防护**：网关限流 + 防刷 + JWT 鉴权（含角色），Lua 原子操作保障
2. **Redis Pipeline 优化**：多次 Redis 操作合并为 1 次网络往返，降低延迟
3. **双 MQ 异步架构**：订单创建与库存扣减完全解耦，前端轮询获取结果
4. **Deadline 超时机制**：ZSET 实现订单创建超时，防止资源泄露
5. **本地消息表 + 内存缓冲**：高并发下先缓冲再批量入库，指数退避重试
6. **消费者幂等**：Redis 去重 + 可重试/不可重试错误分类
7. **CAS 乐观锁**：订单取消使用 CAS 防止多实例重复处理
8. **迟到消息处理**：通过 `stock:rollback:sent` 标记防止超时与结果到达的竞态
9. **增量预热**：幂等设计，支持活动中途追加商品，已有库存不被覆盖
10. **活动生命周期闭环**：删除/过期自动失效订单 + 库存回滚，数据一致
11. **管理后台**：角色权限、数据统计看板、活动管理、订单管理
12. **优雅关闭**：`@PreDestroy` 刷入缓冲队列中的剩余消息和日志

## 目录结构

```
flashsale
├── flashsale-common       # 公共模块（统一响应、错误码、DTO、JWT 工具）
├── flashsale-gateway      # 网关服务（JWT 鉴权含 role、令牌桶限流、Lua 防刷）
├── flashsale-user         # 用户服务（注册登录、Token 管理、角色）
├── flashsale-activity     # 活动服务（活动管理、增量预热、RocketMQ 通知）
├── flashsale-inventory    # 库存服务（Lua 原子扣减、MQ 消费、日志批量刷写）
├── flashsale-order        # 订单服务（异步下单、Deadline 超时、支付、统计）
├── sql                    # 数据库初始化脚本
├── jmeter                 # JMeter 压力测试脚本
├── dev-tools              # 开发工具（启动脚本、报告生成）
├── checkstyle.xml         # 代码规范检查配置
├── pom.xml                # Maven 父 POM
└── README.md
```

## 开发者

本项目为毕业设计作品，欢迎学习交流。
