# 秒杀系统核心代码（答辩参考）

> 按请求流程序列排列，每个文件标注了关键行号。

---

## 一、网关防护层

### 1.1 JWT认证过滤器
**文件**: `flashsale-gateway/src/main/java/com/flashsale/gateway/filter/AuthenticationFilter.java`

```java
// order=-100，优先级最高，最先执行
public int getOrder() { return -100; }

// 不需要认证的公开接口
private static final List<String> EXCLUDE_PATHS = List.of(
    "/api/user/login", "/api/user/register",
    "/api/activity/list", "/api/activity/");

// 核心逻辑：验证JWT → 提取userId/phone → 注入请求头
Claims claims = Jwts.parser().verifyWith(key).build()
    .parseSignedClaims(token).getPayload();
Long userId = claims.get("userId", Long.class);
String phone = claims.get("phone", String.class);
// 注入X-User-Id和X-Phone，下游服务直接用，不用再解析JWT
ServerHttpRequest mutatedRequest = request.mutate()
    .header("X-User-Id", String.valueOf(userId))
    .header("X-Phone", phone).build();
```

### 1.2 令牌桶限流过滤器
**文件**: `flashsale-gateway/src/main/java/com/flashsale/gateway/filter/RateLimitFilter.java`

```java
// order=-99，认证之后执行
public int getOrder() { return -99; }

// 限流参数：桶容量100，每秒补充10个令牌
private static final int CAPACITY = 100;
private static final int REFILL_RATE = 10;

// Redis+Lua令牌桶算法（第46-81行）
// 按userId维度限流，未登录按IP限流
String clientId = getClientId(request); // 优先userId，其次IP
String key = "rate:limit:" + clientId;
// Lua脚本原子执行：检查令牌 → 扣减 → 更新
// 超限返回code=6001
```

### 1.3 IP防刷过滤器
**文件**: `flashsale-gateway/src/main/java/com/flashsale/gateway/filter/AntiBrushFilter.java`

```java
// order=-98，限流之后执行
public int getOrder() { return -98; }

// 防刷参数
private static final int MAX_REQUESTS_PER_MINUTE = 60;  // 每分钟60次正常
private static final int BLACKLIST_THRESHOLD = 200;     // 超200次拉黑
private static final Duration BLACKLIST_TTL = Duration.ofHours(1); // 黑名单1小时

// Redis Hash记录每分钟请求次数
String key = "anti:brush:" + clientId;
long currentMinute = System.currentTimeMillis() / 60000;
redisTemplate.opsForHash().increment(key, String.valueOf(currentMinute), 1);
// >60 返回"请求过于频繁"
// >200 加入黑名单 ip:blacklist:{clientId}，1小时内所有请求直接拦截
```

---

## 二、订单服务 — 接收秒杀请求

### 2.1 秒杀下单（入口方法）
**文件**: `flashsale-order/src/main/java/com/flashsale/order/service/OrderService.java`

```java
// ========== seckill() 方法（第83行）==========

// 第④步：前置幂等检查 — 快速拒绝，避免浪费后续资源
String stockKey = RedisConstant.STOCK_CACHE_PREFIX + request.getActivityId() + ":" + request.getItemId();
String purchaseKey = "user:purchase:" + userId + ":" + stockKey;
if (Boolean.TRUE.equals(redisTemplate.hasKey(purchaseKey))) {
    return SeckillResponse.fail("您已购买过该商品，每人限购一件");
}
String deductingKey = "deducting:" + userId + ":" + stockKey;
if (Boolean.TRUE.equals(redisTemplate.hasKey(deductingKey))) {
    return SeckillResponse.fail("订单处理中，请勿重复提交");
}

// 第⑤步：Feign调用活动服务，校验活动、商品、限购数量
ActivityDto activityDto = activityFeignClient.getActivityDetail(request.getActivityId()).getData();
ActivityItemDto targetItem = activityDto.getItems().stream()
    .filter(item -> item.getItemId().equals(request.getItemId())).findFirst().orElse(null);
if (request.getQuantity() > targetItem.getLimitPerUser()) {
    return SeckillResponse.fail("每人限购" + targetItem.getLimitPerUser() + "件");
}

// 第⑥步：生成订单号 + 记录处理中状态
String orderNo = String.valueOf(snowflake.nextId());
String processingKey = "order:processing:" + orderNo;
redisTemplate.opsForValue().set(processingKey, "1", 5, TimeUnit.MINUTES);
long expireTime = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5);
redisTemplate.opsForZSet().add("order:processing:zset", orderNo, expireTime);

// 第⑦步：保存本地消息表 + 发MQ
localMessageService.saveMessage(orderNo, MqConstant.SECKILL_ORDER_TOPIC, message);
try {
    rocketMQTemplate.syncSend(MqConstant.SECKILL_ORDER_TOPIC, message);
    localMessageService.markMessageAsSent(orderNo); // 发送成功，更新消息表状态
} catch (Exception e) {
    // 发送失败没关系，消息已存本地消息表，30秒定时任务会重试
}

// 第⑧步：立即返回"处理中"，前端开始轮询
return SeckillResponse.processing(orderNo);
```

### 2.2 处理库存扣减结果（MQ消费回调）
**文件**: `flashsale-order/src/main/java/com/flashsale/order/service/OrderService.java`

```java
// ========== processStockDeductResult() 方法（第175行）==========

if (Boolean.TRUE.equals(message.getSuccess())) {
    createOrderAfterStockDeducted(message);  // 成功 → 创建订单
} else {
    // 失败 → 记录失败状态 + 清除扣减中标记（让用户可以重试）
    redisTemplate.opsForValue().set("order:fail:" + orderNo, "库存不足或已售罄", 24, TimeUnit.HOURS);
    redisTemplate.delete("deducting:" + userId + ":" + stockKey);
}

// ========== createOrderAfterStockDeducted() 方法（第196行）==========
// 库存扣减成功后创建订单，有三层防御：

// 防御1：补偿标记检查 — 库存已被补偿任务回滚，但消息延迟到达
Object compensatedValue = redisTemplate.opsForValue().get("order:compensated:" + orderNo);
if (compensatedValue != null) {
    // 拒绝创建，记录失败，清理各种标记
    return;
}

// 防御2：重复购买检查 — 用户已重试成功，旧消息延迟到达
Object purchaseValue = redisTemplate.opsForValue().get("user:purchase:" + userId + ":" + stockKey);
if (purchaseValue != null) {
    // 拒绝创建 → 发送库存回滚MQ，把这次多扣的库存还回去
    rocketMQTemplate.syncSend(MqConstant.STOCK_ROLLBACK_TOPIC, rollbackMessage);
    return;
}

// 防御3：正常创建订单
orderInfoMapper.insert(orderInfo);   // INSERT order_info (status=0)
orderItemMapper.insert(orderItem);   // INSERT order_item
redisTemplate.opsForValue().set("order:timeout:" + orderNo, orderInfo.getId(), 15, TimeUnit.MINUTES);
redisTemplate.opsForValue().set("user:purchase:" + userId + ":" + stockKey, orderNo, 24, TimeUnit.HOURS);
redisTemplate.delete("deducting:" + userId + ":" + stockKey);  // 清除扣减中标记
redisTemplate.delete("order:processing:" + orderNo);            // 清除处理中标记
redisTemplate.opsForZSet().remove("order:processing:zset", orderNo);
```

### 2.3 取消订单（CAS乐观锁）
**文件**: `flashsale-order/src/main/java/com/flashsale/order/service/OrderService.java`

```java
// ========== cancelOrder() 方法（第455行）==========

// CAS方式抢占更新：只有status=0才能更新为status=2
LambdaUpdateWrapper<OrderInfo> updateWrapper = new LambdaUpdateWrapper<>();
updateWrapper.eq(OrderInfo::getOrderNo, orderNo)
    .eq(OrderInfo::getStatus, 0)   // ← CAS条件
    .set(OrderInfo::getStatus, 2)   // 已取消
    .set(OrderInfo::getCancelTime, LocalDateTime.now());
int affected = orderInfoMapper.update(null, updateWrapper);
if (affected == 0) {
    // CAS失败，已被其他节点处理，直接跳过
    return;
}
// CAS成功 → 通过本地消息表发送库存回滚MQ
localMessageService.saveMessage(orderNo, MqConstant.STOCK_ROLLBACK_TOPIC, rollbackMessage);
rocketMQTemplate.syncSend(MqConstant.STOCK_ROLLBACK_TOPIC, rollbackMessage);
// 清理用户购买标记，使用户可以重新秒杀
redisTemplate.delete("user:purchase:" + userId + ":" + stockKey);
```

### 2.4 前端轮询查询订单状态
**文件**: `flashsale-order/src/main/java/com/flashsale/order/service/OrderService.java`

```java
// ========== getOrderStatus() 方法（第402行）==========

// 1. 先查Redis失败记录
Object failReason = redisTemplate.opsForValue().get("order:fail:" + orderNo);
if (failReason != null) {
    return OrderStatusResponse.failed(orderNo, failReason.toString());
}
// 2. 查数据库
OrderInfo orderInfo = orderInfoMapper.selectOne(wrapper);
if (orderInfo == null) {
    return OrderStatusResponse.processing(orderNo);  // 订单还没创建完
}
return OrderStatusResponse.success(orderNo, orderInfo.getId(), orderInfo.getTotalAmount());
```

### 2.5 定时任务：超时取消 + 处理超时标记
**文件**: `flashsale-order/src/main/java/com/flashsale/order/service/OrderService.java`

```java
// ========== cancelTimeoutOrders()（第608行）— 每60秒 ==========
// 扫描 status=0 且 createTime > 15分钟前 的订单 → 调cancelOrder()

// ========== markTimeoutOrdersAsFailed()（第641行）— 每60秒 ==========
// 扫描Redis ZSET中 score < 当前时间 的orderNo（即处理超过5分钟的）
// → 已有订单的从ZSET移除
// → 没有的设order:fail标记为失败
// 兜底的是"库存结果消息一直没到"的场景
```

---

## 三、库存服务 — 核心扣减逻辑

### 3.1 库存扣减（Lua原子脚本 — 防超卖核心）
**文件**: `flashsale-inventory/src/main/java/com/flashsale/inventory/service/InventoryService.java`

```java
// ========== DEDUCT_STOCK_LUA（第59-93行）==========
// Redis单线程执行，四步原子操作：

private static final String DEDUCT_STOCK_LUA =
    "local key = KEYS[1]\n" +
    "local quantity = tonumber(ARGV[1])\n" +
    "local userId = ARGV[2]\n" +
    "local orderNo = ARGV[3]\n" +
    "\n" +
    "local stock = tonumber(redis.call('GET', key))\n" +
    "if stock == nil then\n" +
    "    return -2\n" +                          // 库存未预热
    "end\n" +
    "\n" +
    "if stock < quantity then\n" +
    "    return -1\n" +                          // 库存不足
    "end\n" +
    "\n" +
    "-- 检查用户是否已购买\n" +
    "local purchaseKey = 'user:purchase:' .. userId .. ':' .. key\n" +
    "if redis.call('EXISTS', purchaseKey) == 1 then\n" +
    "    return -4\n" +                          // 已购买
    "end\n" +
    "\n" +
    "-- 检查是否有正在进行的扣减\n" +
    "local deductingKey = 'deducting:' .. userId .. ':' .. key\n" +
    "if redis.call('EXISTS', deductingKey) == 1 then\n" +
    "    return -3\n" +                          // 扣减中
    "end\n" +
    "\n" +
    "-- 扣减库存\n" +
    "local remaining = stock - quantity\n" +
    "redis.call('SET', key, remaining)\n" +
    "\n" +
    "-- 设置扣减中标记（120秒）\n" +
    "redis.call('SETEX', deductingKey, 120, orderNo)\n" +
    "\n" +
    "return remaining";

// ========== deductStock() 方法（第194行）==========
Long result = executeLua(DEDUCT_STOCK_LUA, ...);
// -1 库存不足 / -2 未预热 / -3 扣减中 / -4 已购买 / >=0 剩余库存
if (result >= 0) {
    recordInventoryLog(request, ...);  // 记录inventory_log
    sendStockDeductMessage(request, true);  // 发结果MQ
}
```

### 3.2 库存回滚脚本
**文件**: `flashsale-inventory/src/main/java/com/flashsale/inventory/service/InventoryService.java`

```java
// ========== ROLLBACK_STOCK_LUA（第99-118行）— 订单取消时用 ==========
// DEL deducting标记 → DEL purchase标记 → 恢复库存

// ========== COMPENSATE_ROLLBACK_LUA（第129-143行）— 补偿任务用 ==========
// 恢复库存 + DEL deducting + DEL purchase（完整回滚）

// ========== COMPENSATE_ROLLBACK_STOCK_ONLY_LUA（第153-166行）— 用户已重试成功时用 ==========
// 恢复库存 + DEL deducting（保留purchase，不删购买标记）
```

### 3.3 发送库存扣减消息（本地消息表保证可靠性）
**文件**: `flashsale-inventory/src/main/java/com/flashsale/inventory/service/InventoryService.java`

```java
// ========== sendStockDeductMessage()（第386行）==========
private void sendStockDeductMessage(StockDeductRequest request, boolean success) {
    // 1. 构造消息
    StockDeductMessage message = new StockDeductMessage();
    // 2. 保存到本地消息表（REQUIRES_NEW独立事务）
    localMessageService.saveMessage(orderNo, MqConstant.STOCK_RESULT_TOPIC, message);
    // 3. 尝试发送MQ
    try {
        rocketMQTemplate.syncSend(MqConstant.STOCK_RESULT_TOPIC, message);
        localMessageService.markMessageAsSent(orderNo); // 成功 → 更新状态
    } catch (Exception e) {
        // 失败 → 消息已存本地消息表，30秒定时任务会重试
    }
}
```

---

## 四、MQ消费者 — 消息幂等

### 4.1 库存消费者
**文件**: `flashsale-inventory/src/main/java/com/flashsale/inventory/consumer/InventoryConsumer.java`

```java
@RocketMQMessageListener(
    topic = "SECKILL_ORDER_TOPIC",
    consumerGroup = "INVENTORY_DEDUCT_CONSUMER_GROUP",
    consumeThreadNumber = 32  // 32个消费线程
)
public class InventoryConsumer implements RocketMQListener<String> {

    // 去重键前缀
    private static final String DEDUPE_KEY_PREFIX = "d:inventory:";
    private static final String DEDUPE_PROCESSING_PREFIX = "d:inventory:processing:";
    private static final long DEDUPE_TIMEOUT_SECONDS = 1800; // 30分钟

    public void onMessage(String message) {
        // 1. 检查 d:inventory:{orderNo} → 已成功处理过，跳过
        if (Boolean.TRUE.equals(redisTemplate.hasKey(successKey))) { return; }

        // 2. 检查 d:inventory:processing:{orderNo} → 正在被其他节点处理，跳过
        Boolean isFirstTime = redisTemplate.opsForValue()
            .setIfAbsent(processingKey, "1", DEDUPE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (Boolean.FALSE.equals(isFirstTime)) { return; }

        // 3. 执行库存扣减
        inventoryService.deductStock(deductRequest);

        // 4. 成功 → 设置永久标记 + 删除处理中标记
        redisTemplate.opsForValue().set(successKey, "1");
        redisTemplate.delete(processingKey);

        // 异常处理：
        // 可重试错误(超时/连接) → 删除处理中标记，允许RocketMQ立即重试
        // 不可重试错误 → 保留处理中标记，等30分钟自动过期
    }
}
```

### 4.2 订单结果消费者
**文件**: `flashsale-order/src/main/java/com/flashsale/order/consumer/OrderConsumer.java`

```java
@RocketMQMessageListener(
    topic = "STOCK_RESULT_TOPIC",
    consumerGroup = "ORDER_RESULT_CONSUMER_GROUP",
    consumeThreadNumber = 32
)
// 结构与InventoryConsumer完全一致，去重键前缀为 d:order:
// 调用 orderService.processStockDeductResult(resultMessage)
```

### 4.3 库存回滚消费者
**文件**: `flashsale-inventory/src/main/java/com/flashsale/inventory/consumer/StockRollbackConsumer.java`

```java
@RocketMQMessageListener(
    topic = "STOCK_ROLLBACK_TOPIC",
    consumerGroup = "STOCK_ROLLBACK_CONSUMER_GROUP"
)
// 结构与前两个消费者一致，去重键前缀为 d:rollback:
// 调用 inventoryService.rollbackStock(...)
```

---

## 五、本地消息表 — 消息可靠性保障

### 5.1 本地消息服务
**文件**: `flashsale-inventory/src/main/java/com/flashsale/inventory/service/LocalMessageService.java`

```java
// ========== saveMessage()（第44行）==========
// 独立事务保存，不受外层业务回滚影响
@Transactional(propagation = Propagation.REQUIRES_NEW)
public Long saveMessage(String businessNo, String topic, Object messageBody) {
    LocalMessage message = new LocalMessage();
    message.setBusinessNo(businessNo);  // 订单号
    message.setTopic(topic);            // MQ主题
    message.setMessageBody(jsonBody);   // 消息体JSON
    message.setStatus(0);               // 待发送
    message.setRetryCount(0);
    message.setMaxRetry(5);
    message.setNextRetryTime(LocalDateTime.now());
    localMessageMapper.insert(message);
}

// ========== trySendMessage()（第74行）==========
// CAS认领：WHERE status=0 AND retryCount=? → SET status=3（发送中）
int claimed = localMessageMapper.update(null, claimWrapper);
if (claimed == 0) {
    return false;  // 被其他节点抢占了
}
// 抢占成功 → 发MQ
rocketMQTemplate.syncSend(message.getTopic(), message.getMessageBody());
// 成功 → 更新status=1
// 失败 → 恢复status=0 + retryCount+1 + 指数退避nextRetryTime
// 超过5次 → 标记status=2（永久失败）

// ========== markMessageAsSent()（第161行）==========
// 独立事务更新状态，不受外层异常影响
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void markMessageAsSent(String businessNo) {
    // UPDATE local_message SET status=1 WHERE business_no=? AND status=0
}
```

### 5.2 消息重发定时任务
**文件**: `flashsale-inventory/src/main/java/com/flashsale/inventory/scheduler/MessageResendJob.java`

```java
@Scheduled(fixedRate = 30000)  // 每30秒
public void resendPendingMessages() {
    localMessageService.processPendingMessages();
    // 查询 status=0, next_retry_time<=now, retry_count<max_retry 的消息
    // 逐条调用 trySendMessage() 进行CAS认领+发送
}
```

---

## 六、库存补偿 — 最终兜底

### 6.1 库存补偿定时任务
**文件**: `flashsale-inventory/src/main/java/com/flashsale/inventory/scheduler/StockCompensationJob.java`

```java
private static final int TIMEOUT_MINUTES = 10;  // 扣减后10分钟没关联订单就补偿

@Scheduled(fixedRate = 60000)  // 每60秒
public void compensateOrphanedStock() {
    // 扫描 inventory_log 中超过10分钟未回滚的扣减记录
    List<InventoryLog> pendingLogs = inventoryLogMapper.selectPendingCompensation(beforeTime, 100);

    for (InventoryLog inventoryLog : pendingLogs) {
        // 1. Feign调订单服务：订单已存在？→ 跳过补偿
        boolean orderExists = checkOrderExists(inventoryLog.getOrderNo());
        if (orderExists) { continue; }

        // 2. Feign调订单服务：用户有后续成功订单？
        boolean hasLaterSuccessOrder = checkUserHasSuccessOrder(userId, activityId, itemId, orderNo);

        if (hasLaterSuccessOrder) {
            // 用户已重试成功 → 只回滚库存，保留购买标记
            inventoryService.compensateRollbackOnlyStock(...);  // COMPENSATE_ROLLBACK_STOCK_ONLY_LUA
        } else {
            // 用户无后续成功订单 → 完整回滚
            inventoryService.compensateRollback(...);  // COMPENSATE_ROLLBACK_LUA
        }

        // 设置补偿标记，防止延迟消息再创建订单
        redisTemplate.opsForValue().set("order:compensated:" + orderNo, reason, 24, TimeUnit.HOURS);
    }
}

// Feign调用失败时保守处理：
// checkOrderExists失败 → return true（认为订单存在，不补偿）
// checkUserHasSuccessOrder失败 → return true（认为有成功订单，只回滚库存不删购买标记）
// → 宁可跳过补偿，也不误回滚
```

---

## 七、活动服务 — 库存预热与分布式锁

### 7.1 库存预热
**文件**: `flashsale-activity/src/main/java/com/flashsale/activity/service/ActivityService.java`

```java
// ========== preheatStock()（第163行）==========
RLock lock = redissonClient.getLock("stock:preheat:lock:" + activityId);
try {
    if (lock.tryLock(10, 30, TimeUnit.SECONDS)) {  // 10秒等待，30秒持有
        // 查活动商品 → 写入Redis
        String stockKey = "stock:cache:" + activityId + ":" + item.getItemId();
        redisTemplate.opsForValue().set(stockKey, item.getStock());
        // 更新预热状态
        activity.setPreheatStatus(1);
        activityMapper.updateById(activity);
    }
} finally {
    if (lock.isHeldByCurrentThread()) { lock.unlock(); }
}
```

### 7.2 活动状态自动切换
**文件**: `flashsale-activity/src/main/java/com/flashsale/activity/service/ActivityService.java`

```java
// ========== autoUpdateActivityStatus()（第258行）==========
@Scheduled(cron = "0 * * * * ?")  // 每分钟
public void autoUpdateActivityStatus() {
    LocalDateTime now = LocalDateTime.now();
    // 待开始 → 进行中（到达开始时间）
    activityMapper.update(null, new LambdaUpdateWrapper<Activity>()
        .set(Activity::getStatus, 1).eq(Activity::getStatus, 0)
        .le(Activity::getStartTime, now).ge(Activity::getEndTime, now));
    // 进行中 → 已结束（超过结束时间）
    activityMapper.update(null, new LambdaUpdateWrapper<Activity>()
        .set(Activity::getStatus, 2).eq(Activity::getStatus, 1)
        .lt(Activity::getEndTime, now));
}
```

---

## 完整流程图

```
用户请求
  │
  ▼
Gateway(8080) 三层过滤器
  ├── AuthenticationFilter(order=-100) JWT认证，注入X-User-Id
  ├── RateLimitFilter(order=-99)     Redis+Lua令牌桶限流
  └── AntiBrushFilter(order=-98)      60次/分钟正常，200次拉黑
  │
  ▼
OrderService.seckill()                    ── 前置幂等(purchase+deducting)
  ├── Feign校验活动/商品/限购
  ├── Snowflake生成订单号
  ├── Redis记录处理中(5min) + ZSET
  ├── 本地消息表(REQUIRES_NEW)
  ├── MQ → SECKILL_ORDER_TOPIC
  └── 返回"处理中"
       │
       ▼ MQ
InventoryConsumer(32线程)               ── 消费者去重(d:inventory)
  └── InventoryService.deductStock()
        ├── Lua原子脚本(防超卖核心)
        │   ├── 检查库存 → -1
        │   ├── 检查purchase → -4
        │   ├── 检查deducting → -3
        │   └── 扣减 + SETEX deducting 120s
        ├── 记录inventory_log
        └── 本地消息表 → MQ → STOCK_RESULT_TOPIC
             │
             ▼ MQ
OrderConsumer(32线程)                   ── 消费者去重(d:order)
  └── OrderService.createOrderAfterStockDeducted()
        ├── 检查order:compensated标记     ← 防御1：补偿已回滚
        ├── 检查user:purchase标记         ← 防御2：用户已重试
        ├── INSERT order_info + order_item ← 防御3：正常创建
        ├── SET order:timeout(15min)
        ├── SET user:purchase(24h)
        └── DEL deducting + order:processing

前端轮询 GET /order/{orderNo}/status
  ├── Redis order:fail → FAILED
  ├── DB order_info   → SUCCESS
  └── 都没有           → PROCESSING

═══ 兜底机制 ═══

① cancelTimeoutOrders(每60s)    → 15分钟未支付 → CAS取消 → 本地消息表 → MQ回滚库存
② markTimeoutOrdersAsFailed(每60s) → 处理超5分钟 → 标记失败
③ StockCompensationJob(每60s)    → 扣减10分钟无订单 → Feign查订单 → 两种回滚策略
④ MessageResendJob(每30s)        → 扫描本地消息表 → CAS认领 → 指数退避重试
```
