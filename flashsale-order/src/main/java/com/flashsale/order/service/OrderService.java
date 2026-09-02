package com.flashsale.order.service;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.flashsale.common.constant.RedisConstant;
import com.flashsale.common.dto.StockDeductRequest;
import com.flashsale.common.dto.StockDeductMessage;
import com.flashsale.common.dto.StockRollbackMessage;
import com.flashsale.common.dto.OrderCreateMessage;
import com.flashsale.common.ErrorCode;
import com.flashsale.common.constant.MqConstant;
import com.flashsale.common.exception.BusinessException;
import com.flashsale.order.dto.SeckillRequest;
import com.flashsale.order.entity.OrderInfo;
import com.flashsale.order.entity.OrderItem;
import com.flashsale.order.mapper.OrderInfoMapper;
import com.flashsale.order.mapper.OrderItemMapper;
import com.flashsale.order.vo.OrderDetailResponse;
import com.flashsale.order.vo.OrderItemVO;
import com.flashsale.order.vo.OrderStatusResponse;
import com.flashsale.order.vo.SeckillResponse;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 订单服务
 */
@Slf4j
@Service
public class OrderService {

    @Autowired
    private OrderInfoMapper orderInfoMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private LocalMessageService localMessageService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Value("${order.snowflake.worker-id:1}")
    private long workerId;

    @Value("${order.snowflake.datacenter-id:1}")
    private long datacenterId;

    private Snowflake snowflake;

    @PostConstruct
    public void init() {
        this.snowflake = IdUtil.getSnowflake(workerId, datacenterId);
        log.info("订单服务初始化雪花算法: workerId={}, datacenterId={}", workerId, datacenterId);
    }

    /**
     * 秒杀下单（异步处理）
     */
    public SeckillResponse seckill(Long userId, SeckillRequest request) {
        String stockKey = RedisConstant.STOCK_CACHE_PREFIX + request.getActivityId() + ":" + request.getItemId();
        String purchaseKey = "user:purchase:" + userId + ":" + stockKey;
        String deductingKey = "deducting:" + userId + ":" + stockKey;
        String itemKey = "activity:item:" + request.getActivityId() + ":" + request.getItemId();

        // 0. Pipeline 读操作：幂等检查 + 商品信息（3次Redis调用合并为1次网络往返）
        try {
            List<Object> readResults = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                connection.keyCommands().exists(purchaseKey.getBytes(StandardCharsets.UTF_8));
                connection.keyCommands().exists(deductingKey.getBytes(StandardCharsets.UTF_8));
                connection.hashCommands().hGetAll(itemKey.getBytes(StandardCharsets.UTF_8));
                return null;
            });

            boolean purchased = Boolean.TRUE.equals(readResults.get(0));
            boolean deducting = Boolean.TRUE.equals(readResults.get(1));
            @SuppressWarnings("unchecked")
            Map<Object, Object> itemData = (Map<Object, Object>) readResults.get(2);

            if (purchased) {
                return SeckillResponse.fail("您已购买过该商品，每人限购一件");
            }
            if (deducting) {
                return SeckillResponse.fail("订单处理中，请勿重复提交");
            }
            if (itemData == null || itemData.isEmpty()) {
                return SeckillResponse.fail("活动或商品不存在（未预热）");
            }

            int limitPerUser = Integer.parseInt(itemData.get("limitPerUser").toString());
            if (request.getQuantity() > limitPerUser) {
                return SeckillResponse.fail("每人限购" + limitPerUser + "件");
            }

        } catch (Exception e) {
            log.error("Redis读取失败: activityId={}, itemId={}", request.getActivityId(), request.getItemId(), e);
            return SeckillResponse.fail("获取商品信息失败，请稍后重试");
        }

        // 1. 生成订单号
        String orderNo = generateOrderNo();

        // 2. Pipeline 写操作：记录处理中订单（3次Redis调用合并为1次网络往返）
        String processingKey = "order:processing:" + orderNo;
        Map<String, String> orderInfoMap = new HashMap<>();
        orderInfoMap.put("userId", String.valueOf(userId));
        orderInfoMap.put("activityId", String.valueOf(request.getActivityId()));
        orderInfoMap.put("itemId", String.valueOf(request.getItemId()));
        orderInfoMap.put("quantity", String.valueOf(request.getQuantity()));
        long expireTime = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5);

        try {
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                byte[] keyBytes = processingKey.getBytes(StandardCharsets.UTF_8);
                Map<byte[], byte[]> hashFields = new HashMap<>();
                for (Map.Entry<String, String> entry : orderInfoMap.entrySet()) {
                    hashFields.put(entry.getKey().getBytes(StandardCharsets.UTF_8),
                            entry.getValue().getBytes(StandardCharsets.UTF_8));
                }
                connection.hashCommands().hMSet(keyBytes, hashFields);
                connection.keyCommands().expire(keyBytes, 600);
                connection.zSetCommands().zAdd(
                        "order:processing:zset".getBytes(StandardCharsets.UTF_8),
                        expireTime,
                        orderNo.getBytes(StandardCharsets.UTF_8)
                );
                return null;
            });
            log.info("记录处理中订单: orderNo={}, userId={}", orderNo, userId);
        } catch (Exception e) {
            log.error("Redis写入失败: orderNo={}", orderNo, e);
            return SeckillResponse.fail("系统繁忙，请稍后重试");
        }

        // 4. 构造订单创建消息
        OrderCreateMessage message = new OrderCreateMessage();
        message.setOrderNo(orderNo);
        message.setUserId(userId);
        message.setActivityId(request.getActivityId());
        message.setItemId(request.getItemId());
        message.setQuantity(request.getQuantity());

        // 5. 保存到本地消息表
        localMessageService.saveMessage(orderNo, MqConstant.SECKILL_ORDER_TOPIC, message);

        // 6. 异步发送 MQ（本地消息表已保证可靠性，无需同步等待Broker ACK）
        rocketMQTemplate.asyncSend(MqConstant.SECKILL_ORDER_TOPIC, message, new SendCallback() {
            @Override
            public void onSuccess(SendResult result) {
                localMessageService.markMessageAsSent(orderNo);
                log.info("发送秒杀订单消息成功: orderNo={}, userId={}", orderNo, userId);
            }
            @Override
            public void onException(Throwable e) {
                log.error("发送秒杀订单消息失败，已保存到本地消息表等待重试: orderNo={}", orderNo, e);
            }
        });

        // 7. 返回处理中状态
        return SeckillResponse.processing(orderNo);
    }

    /**
     * 处理订单创建（MQ消费者）
     * 注意：此方法已废弃，现在使用方案B（双MQ），库存扣减由InventoryConsumer处理
     */
    @Deprecated
    @Transactional(rollbackFor = Exception.class)
    public void processOrderCreate(OrderCreateMessage message) {
        log.info("processOrderCreate已废弃，订单由processStockDeductResult创建: orderNo={}",
                message.getOrderNo());
        // 此方法保留兼容性，实际订单创建由 processStockDeductResult 处理
    }

    /**
     * 处理库存扣减结果（MQ消费者）
     * 不使用 @Transactional，Redis 检查在事务外执行，仅 DB 写入包裹在 TransactionTemplate 中
     */
    public void processStockDeductResult(StockDeductMessage message) {
        log.info("处理库存扣减结果: orderNo={}, success={}", message.getOrderNo(), message.getSuccess());

        if (Boolean.TRUE.equals(message.getSuccess())) {
            // 库存扣减成功，创建订单
            createOrderAfterStockDeducted(message);
        } else {
            // 库存扣减失败，Pipeline：记录失败状态 + 清理扣减中标记
            String failKey = "order:fail:" + message.getOrderNo();
            String stockKey = RedisConstant.STOCK_CACHE_PREFIX + message.getActivityId() + ":" + message.getItemId();
            String deductingKey = "deducting:" + message.getUserId() + ":" + stockKey;
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                connection.stringCommands().set(failKey.getBytes(StandardCharsets.UTF_8),
                        "\"库存不足或已售罄\"".getBytes(StandardCharsets.UTF_8));
                connection.keyCommands().expire(failKey.getBytes(StandardCharsets.UTF_8), 86400);
                connection.keyCommands().del(deductingKey.getBytes(StandardCharsets.UTF_8));
                return null;
            });
            log.info("库存扣减失败，已记录: orderNo={}", message.getOrderNo());
        }
    }

    /**
     * 库存扣减成功后创建订单
     */
    private void createOrderAfterStockDeducted(StockDeductMessage message) {
        String orderNo = message.getOrderNo();
        String stockKey = RedisConstant.STOCK_CACHE_PREFIX + message.getActivityId() + ":" + message.getItemId();
        String purchaseKey = "user:purchase:" + message.getUserId() + ":" + stockKey;
        String deductingKey = "deducting:" + message.getUserId() + ":" + stockKey;
        String itemKey = "activity:item:" + message.getActivityId() + ":" + message.getItemId();
        String deadlineFailKey = "order:fail:" + orderNo;
        String rollbackSentKey = "stock:rollback:sent:" + orderNo;
        String processingKey = "order:processing:" + orderNo;
        String zsetKey = "order:processing:zset";

        // === Pipeline 读批次：3 次检查合并为 1 次 RTT ===
        try {
            List<Object> readResults = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                connection.keyCommands().exists(deadlineFailKey.getBytes(StandardCharsets.UTF_8));
                connection.stringCommands().get(purchaseKey.getBytes(StandardCharsets.UTF_8));
                connection.hashCommands().hGetAll(itemKey.getBytes(StandardCharsets.UTF_8));
                return null;
            });

            boolean hasDeadlineFail = Boolean.TRUE.equals(readResults.get(0));
            byte[] purchaseBytes = (byte[]) readResults.get(1);
            @SuppressWarnings("unchecked")
            Map<Object, Object> itemData = (Map<Object, Object>) readResults.get(2);

            // --- 分支 1：订单已超时失败 ---
            if (hasDeadlineFail) {
                log.warn("订单已超时失败，拒绝创建迟到订单: orderNo={}", orderNo);
                handleLateOrder(orderNo, message, rollbackSentKey, stockKey, deductingKey, processingKey, zsetKey);
                return;
            }

            // --- 分支 2：用户已购买 ---
            if (purchaseBytes != null) {
                String existingOrderNo = new String(purchaseBytes, StandardCharsets.UTF_8);
                log.warn("用户已有订单，拒绝创建延迟订单: orderNo={}, existingOrderNo={}", orderNo, existingOrderNo);
                handleDuplicateOrder(orderNo, message, existingOrderNo, deadlineFailKey, processingKey, zsetKey);
                return;
            }

            // --- 分支 3：商品信息不存在 ---
            if (itemData == null || itemData.isEmpty()) {
                log.error("商品信息不存在（未预热）: activityId={}, itemId={}", message.getActivityId(), message.getItemId());
                redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                    connection.stringCommands().set(deadlineFailKey.getBytes(StandardCharsets.UTF_8),
                            "\"商品信息不存在\"".getBytes(StandardCharsets.UTF_8));
                    connection.keyCommands().expire(deadlineFailKey.getBytes(StandardCharsets.UTF_8), 86400);
                    connection.keyCommands().del(processingKey.getBytes(StandardCharsets.UTF_8));
                    connection.keyCommands().del(deductingKey.getBytes(StandardCharsets.UTF_8));
                    connection.zSetCommands().zRem(zsetKey.getBytes(StandardCharsets.UTF_8),
                            orderNo.getBytes(StandardCharsets.UTF_8));
                    return null;
                });
                return;
            }

            // --- 正常路径：创建订单 ---
            String itemName = itemData.get("itemName") != null ? itemData.get("itemName").toString() : "";
            String itemImage = itemData.get("itemImage") != null ? itemData.get("itemImage").toString() : "";
            BigDecimal seckillPrice = new BigDecimal(itemData.get("seckillPrice").toString());

            // TransactionTemplate 包裹 DB 写入，减少事务持有时间
            final OrderInfo orderInfo = new OrderInfo();
            orderInfo.setOrderNo(orderNo);
            orderInfo.setUserId(message.getUserId());
            orderInfo.setActivityId(message.getActivityId());
            orderInfo.setTotalAmount(seckillPrice.multiply(BigDecimal.valueOf(message.getQuantity())));
            orderInfo.setStatus(0);
            orderInfo.setCreateTime(LocalDateTime.now());

            final OrderItem orderItem = new OrderItem();
            orderItem.setOrderNo(orderNo);
            orderItem.setItemId(message.getItemId());
            orderItem.setItemName(itemName);
            orderItem.setItemImage(itemImage);
            orderItem.setPrice(seckillPrice);
            orderItem.setQuantity(message.getQuantity());
            orderItem.setTotalAmount(seckillPrice.multiply(BigDecimal.valueOf(message.getQuantity())));
            orderItem.setCreateTime(LocalDateTime.now());

            transactionTemplate.executeWithoutResult(status -> {
                orderInfoMapper.insert(orderInfo);
                orderItem.setOrderId(orderInfo.getId());
                orderItemMapper.insert(orderItem);
            });

            // === Pipeline 写批次：5 次 Redis 操作合并为 1 次 RTT ===
            String orderKey = "order:timeout:" + orderNo;
            byte[] orderTimeoutValue = String.valueOf(orderInfo.getId()).getBytes(StandardCharsets.UTF_8);
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                connection.stringCommands().set(orderKey.getBytes(StandardCharsets.UTF_8), orderTimeoutValue);
                connection.keyCommands().expire(orderKey.getBytes(StandardCharsets.UTF_8), 900); // 15 min
                connection.stringCommands().set(purchaseKey.getBytes(StandardCharsets.UTF_8),
                        orderNo.getBytes(StandardCharsets.UTF_8));
                connection.keyCommands().expire(purchaseKey.getBytes(StandardCharsets.UTF_8), 86400); // 24h
                connection.keyCommands().del(deductingKey.getBytes(StandardCharsets.UTF_8));
                connection.keyCommands().del(processingKey.getBytes(StandardCharsets.UTF_8));
                connection.zSetCommands().zRem(zsetKey.getBytes(StandardCharsets.UTF_8),
                        orderNo.getBytes(StandardCharsets.UTF_8));
                return null;
            });

            log.info("订单创建成功: orderNo={}, totalAmount={}", orderNo, orderInfo.getTotalAmount());

        } catch (Exception e) {
            log.error("创建订单异常: orderNo={}", orderNo, e);
        }
    }

    /**
     * 处理迟到订单（超时后库存结果才到达）
     */
    private void handleLateOrder(String orderNo, StockDeductMessage message,
                                   String rollbackSentKey, String stockKey,
                                   String deductingKey, String processingKey, String zsetKey) {
        // 检查 deadline 任务是否已经发送过回滚消息
        boolean hasRollbackSent = Boolean.TRUE.equals(redisTemplate.hasKey(rollbackSentKey));
        if (!hasRollbackSent) {
            // deadline 任务检查时库存还没扣，后来才扣的 → 由这里发送回滚
            com.flashsale.common.dto.StockRollbackMessage rollbackMessage =
                    new com.flashsale.common.dto.StockRollbackMessage();
            rollbackMessage.setOrderNo(orderNo);
            rollbackMessage.setActivityId(message.getActivityId());
            rollbackMessage.setItemId(message.getItemId());
            rollbackMessage.setQuantity(message.getQuantity());
            rollbackMessage.setUserId(message.getUserId());
            try {
                localMessageService.saveMessage(orderNo, MqConstant.STOCK_ROLLBACK_TOPIC, rollbackMessage);
                rocketMQTemplate.syncSend(MqConstant.STOCK_ROLLBACK_TOPIC, rollbackMessage);
                localMessageService.markMessageAsSent(orderNo);
            } catch (Exception e) {
                log.error("迟到订单库存回滚消息发送失败，已保存到本地消息表等待重试: orderNo={}", orderNo, e);
            }
            // Pipeline：设置回滚已发送标记 + 清理
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                connection.stringCommands().set(rollbackSentKey.getBytes(StandardCharsets.UTF_8),
                        "1".getBytes(StandardCharsets.UTF_8));
                connection.keyCommands().expire(rollbackSentKey.getBytes(StandardCharsets.UTF_8), 86400);
                connection.keyCommands().del(deductingKey.getBytes(StandardCharsets.UTF_8));
                connection.keyCommands().del(processingKey.getBytes(StandardCharsets.UTF_8));
                connection.zSetCommands().zRem(zsetKey.getBytes(StandardCharsets.UTF_8),
                        orderNo.getBytes(StandardCharsets.UTF_8));
                return null;
            });
            log.info("迟到订单库存回滚消息已发送: orderNo={}", orderNo);
        } else {
            // 回滚已由 deadline 任务发送，只做清理
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                connection.keyCommands().del(deductingKey.getBytes(StandardCharsets.UTF_8));
                connection.keyCommands().del(processingKey.getBytes(StandardCharsets.UTF_8));
                connection.zSetCommands().zRem(zsetKey.getBytes(StandardCharsets.UTF_8),
                        orderNo.getBytes(StandardCharsets.UTF_8));
                return null;
            });
        }
    }

    /**
     * 处理重复订单（用户已有购买标记）
     */
    private void handleDuplicateOrder(String orderNo, StockDeductMessage message,
                                        String existingOrderNo, String deadlineFailKey,
                                        String processingKey, String zsetKey) {
        // 发送库存回滚消息
        com.flashsale.common.dto.StockRollbackMessage rollbackMessage =
                new com.flashsale.common.dto.StockRollbackMessage();
        rollbackMessage.setOrderNo(orderNo);
        rollbackMessage.setActivityId(message.getActivityId());
        rollbackMessage.setItemId(message.getItemId());
        rollbackMessage.setQuantity(message.getQuantity());
        rollbackMessage.setUserId(message.getUserId());

        try {
            rocketMQTemplate.syncSend(MqConstant.STOCK_ROLLBACK_TOPIC, rollbackMessage);
            log.info("因重复订单发送库存回滚消息: orderNo={}, existingOrderNo={}", orderNo, existingOrderNo);
        } catch (Exception e) {
            log.error("发送库存回滚消息失败: orderNo={}", orderNo, e);
        }

        // Pipeline：记录失败 + 清理
        String failReason = "\"订单重复，您已有成功订单\"";
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            connection.stringCommands().set(deadlineFailKey.getBytes(StandardCharsets.UTF_8),
                    failReason.getBytes(StandardCharsets.UTF_8));
            connection.keyCommands().expire(deadlineFailKey.getBytes(StandardCharsets.UTF_8), 86400);
            connection.keyCommands().del(processingKey.getBytes(StandardCharsets.UTF_8));
            connection.zSetCommands().zRem(zsetKey.getBytes(StandardCharsets.UTF_8),
                    orderNo.getBytes(StandardCharsets.UTF_8));
            return null;
        });
    }

    /**
     * 获取订单详情
     */
    public OrderDetailResponse getOrderDetail(String orderNo, Long userId) {
        LambdaQueryWrapper<OrderInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderInfo::getOrderNo, orderNo);
        OrderInfo orderInfo = orderInfoMapper.selectOne(wrapper);

        if (orderInfo == null) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }

        // 校验订单归属
        if (!orderInfo.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }

        // 查询订单明细
        List<OrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>()
                        .eq(OrderItem::getOrderNo, orderNo)
        );

        List<OrderItemVO> itemVOs = items.stream().map(item -> {
            OrderItemVO vo = new OrderItemVO();
            vo.setId(item.getId());
            vo.setItemId(item.getItemId());
            vo.setItemName(item.getItemName());
            vo.setItemImage(item.getItemImage());
            vo.setPrice(item.getPrice());
            vo.setQuantity(item.getQuantity());
            vo.setTotalAmount(item.getTotalAmount());
            return vo;
        }).toList();

        OrderDetailResponse response = new OrderDetailResponse();
        response.setId(orderInfo.getId());
        response.setOrderNo(orderInfo.getOrderNo());
        response.setUserId(orderInfo.getUserId());
        response.setActivityId(orderInfo.getActivityId());
        response.setTotalAmount(orderInfo.getTotalAmount());
        response.setStatus(orderInfo.getStatus());
        response.setStatusDesc(getStatusDesc(orderInfo.getStatus()));
        response.setPayTime(orderInfo.getPayTime());
        response.setCancelTime(orderInfo.getCancelTime());
        response.setCreateTime(orderInfo.getCreateTime());
        response.setItems(itemVOs);

        return response;
    }

    /**
     * 获取订单状态（用于轮询查询）
     * @param orderNo 订单号
     * @return 订单状态
     */
    public OrderStatusResponse getOrderStatus(String orderNo) {
        // 1. 先检查是否在失败记录中
        String failKey = "order:fail:" + orderNo;
        Object failReason = redisTemplate.opsForValue().get(failKey);
        if (failReason != null) {
            return OrderStatusResponse.failed(orderNo, failReason.toString());
        }

        // 2. 查询订单是否存在
        LambdaQueryWrapper<OrderInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderInfo::getOrderNo, orderNo);
        OrderInfo orderInfo = orderInfoMapper.selectOne(wrapper);

        if (orderInfo == null) {
            // 订单还未创建，返回处理中
            return OrderStatusResponse.processing(orderNo);
        }

        // 3. 返回订单状态
        return OrderStatusResponse.success(orderNo, orderInfo.getId(), orderInfo.getTotalAmount());
    }

    /**
     * 获取我的订单列表
     */
    public List<OrderDetailResponse> getMyOrders(Long userId) {
        LambdaQueryWrapper<OrderInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderInfo::getUserId, userId)
                .orderByDesc(OrderInfo::getCreateTime)
                .last("LIMIT 20");

        List<OrderInfo> orderInfos = orderInfoMapper.selectList(wrapper);

        return orderInfos.stream().map(orderInfo -> {
            OrderDetailResponse response = new OrderDetailResponse();
            response.setId(orderInfo.getId());
            response.setOrderNo(orderInfo.getOrderNo());
            response.setUserId(orderInfo.getUserId());
            response.setActivityId(orderInfo.getActivityId());
            response.setTotalAmount(orderInfo.getTotalAmount());
            response.setStatus(orderInfo.getStatus());
            response.setStatusDesc(getStatusDesc(orderInfo.getStatus()));
            response.setPayTime(orderInfo.getPayTime());
            response.setCancelTime(orderInfo.getCancelTime());
            response.setCreateTime(orderInfo.getCreateTime());
            return response;
        }).toList();
    }

    /**
     * 获取所有订单列表（管理员）
     */
    public List<OrderDetailResponse> getAllOrders() {
        LambdaQueryWrapper<OrderInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(OrderInfo::getCreateTime)
                .last("LIMIT 100");

        List<OrderInfo> orderInfos = orderInfoMapper.selectList(wrapper);

        return orderInfos.stream().map(orderInfo -> {
            OrderDetailResponse response = new OrderDetailResponse();
            response.setId(orderInfo.getId());
            response.setOrderNo(orderInfo.getOrderNo());
            response.setUserId(orderInfo.getUserId());
            response.setActivityId(orderInfo.getActivityId());
            response.setTotalAmount(orderInfo.getTotalAmount());
            response.setStatus(orderInfo.getStatus());
            response.setStatusDesc(getStatusDesc(orderInfo.getStatus()));
            response.setPayTime(orderInfo.getPayTime());
            response.setCancelTime(orderInfo.getCancelTime());
            response.setCreateTime(orderInfo.getCreateTime());
            return response;
        }).toList();
    }

    /**
     * 取消订单（CAS 乐观锁方式，防止多实例重复处理）
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(Long userId, String orderNo, String reason) {
        // 先查询订单信息（用于后续业务处理）
        LambdaQueryWrapper<OrderInfo> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(OrderInfo::getOrderNo, orderNo);
        OrderInfo orderInfo = orderInfoMapper.selectOne(queryWrapper);

        if (orderInfo == null) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }

        // 校验订单归属
        if (!orderInfo.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }

        // CAS 方式抢占更新：只有 status=0 的才能更新为 status=2
        LambdaUpdateWrapper<OrderInfo> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(OrderInfo::getOrderNo, orderNo)
                .eq(OrderInfo::getStatus, 0)  // ← CAS 条件：必须是待支付状态
                .set(OrderInfo::getStatus, 2)  // 已取消
                .set(OrderInfo::getCancelTime, LocalDateTime.now());

        int affected = orderInfoMapper.update(null, updateWrapper);

        if (affected == 0) {
            // CAS 失败，说明订单已被其他实例处理或状态已变更
            log.info("订单已被其他实例处理或状态已变更，跳过取消: orderNo={}, currentStatus={}",
                    orderNo, orderInfo.getStatus());
            return;
        }

        // CAS 抢占成功，继续后续处理

        // 获取订单明细
        List<OrderItem> orderItems = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>()
                        .eq(OrderItem::getOrderNo, orderNo)
        );

        if (!orderItems.isEmpty()) {
            OrderItem orderItem = orderItems.get(0);

            // 构造库存回滚消息
            com.flashsale.common.dto.StockRollbackMessage rollbackMessage =
                    new com.flashsale.common.dto.StockRollbackMessage();
            rollbackMessage.setOrderNo(orderNo);
            rollbackMessage.setActivityId(orderInfo.getActivityId());
            rollbackMessage.setItemId(orderItem.getItemId());
            rollbackMessage.setQuantity(orderItem.getQuantity());
            rollbackMessage.setUserId(orderInfo.getUserId());

            // 保存到本地消息表（可靠发送）
            localMessageService.saveMessage(orderNo, MqConstant.STOCK_ROLLBACK_TOPIC, rollbackMessage);

            // 尝试发送 MQ（发送失败由定时任务重试）
            try {
                rocketMQTemplate.syncSend(MqConstant.STOCK_ROLLBACK_TOPIC, rollbackMessage);
                log.info("发送库存回滚消息成功: orderNo={}", orderNo);
                // 立即更新本地消息表状态为已发送
                localMessageService.markMessageAsSent(orderNo);
            } catch (Exception e) {
                log.error("发送库存回滚消息失败，已保存到本地消息表等待重试: orderNo={}", orderNo, e);
                // 消息已保存到本地消息表，定时任务会重试
            }
        }

        // 清理用户购买标记（使用与 InventoryService 相同的 key 格式）
        for (OrderItem orderItem : orderItems) {
            String stockKey = RedisConstant.STOCK_CACHE_PREFIX + orderInfo.getActivityId() + ":" + orderItem.getItemId();
            String purchaseKey = "user:purchase:" + orderInfo.getUserId() + ":" + stockKey;
            redisTemplate.delete(purchaseKey);
        }

        log.info("订单取消成功: orderNo={}, reason={}", orderNo, reason);
    }

    /**
     * 检查订单是否存在（内部使用）
     */
    private boolean checkOrderExists(String orderNo) {
        LambdaQueryWrapper<OrderInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderInfo::getOrderNo, orderNo);
        return orderInfoMapper.selectCount(wrapper) > 0;
    }

    /**
     * 生成订单号
     */
    private String generateOrderNo() {
        return String.valueOf(snowflake.nextId());
    }

    /**
     * 获取状态描述
     */
    private String getStatusDesc(Integer status) {
        return switch (status) {
            case 0 -> "待支付";
            case 1 -> "已支付";
            case 2 -> "已取消";
            case 3 -> "已超时";
            case 4 -> "已失效";
            default -> "未知";
        };
    }

    /**
     * 失效指定活动的所有待支付订单（活动删除/过期时调用）
     */
    public void invalidateOrders(Long activityId, String reason) {
        // 查询该活动下所有待支付订单
        LambdaQueryWrapper<OrderInfo> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(OrderInfo::getActivityId, activityId)
                .eq(OrderInfo::getStatus, 0);
        List<OrderInfo> pendingOrders = orderInfoMapper.selectList(queryWrapper);

        if (pendingOrders.isEmpty()) {
            log.info("活动{}没有待支付订单，无需失效", activityId);
            return;
        }

        // 批量 CAS 更新：status=0 → status=4
        for (OrderInfo order : pendingOrders) {
            LambdaUpdateWrapper<OrderInfo> updateWrapper = new LambdaUpdateWrapper<>();
            updateWrapper.eq(OrderInfo::getOrderNo, order.getOrderNo())
                    .eq(OrderInfo::getStatus, 0)
                    .set(OrderInfo::getStatus, 4)
                    .set(OrderInfo::getCancelTime, LocalDateTime.now());

            int affected = orderInfoMapper.update(null, updateWrapper);
            if (affected > 0) {
                // 清理用户购买标记 + 发送库存回滚
                List<OrderItem> orderItems = orderItemMapper.selectList(
                        new LambdaQueryWrapper<OrderItem>()
                                .eq(OrderItem::getOrderNo, order.getOrderNo())
                );
                for (OrderItem orderItem : orderItems) {
                    String stockKey = RedisConstant.STOCK_CACHE_PREFIX + activityId + ":" + orderItem.getItemId();
                    String purchaseKey = "user:purchase:" + order.getUserId() + ":" + stockKey;
                    redisTemplate.delete(purchaseKey);

                    // 发送库存回滚消息
                    com.flashsale.common.dto.StockRollbackMessage rollbackMessage =
                            new com.flashsale.common.dto.StockRollbackMessage();
                    rollbackMessage.setOrderNo(order.getOrderNo());
                    rollbackMessage.setActivityId(activityId);
                    rollbackMessage.setItemId(orderItem.getItemId());
                    rollbackMessage.setQuantity(orderItem.getQuantity());
                    rollbackMessage.setUserId(order.getUserId());

                    try {
                        localMessageService.saveMessage(order.getOrderNo(),
                                com.flashsale.common.constant.MqConstant.STOCK_ROLLBACK_TOPIC, rollbackMessage);
                        rocketMQTemplate.syncSend(
                                com.flashsale.common.constant.MqConstant.STOCK_ROLLBACK_TOPIC, rollbackMessage);
                        localMessageService.markMessageAsSent(order.getOrderNo());
                    } catch (Exception e) {
                        log.error("失效订单库存回滚消息发送失败: orderNo={}", order.getOrderNo(), e);
                    }
                }

                log.info("订单已失效: orderNo={}, reason={}", order.getOrderNo(), reason);
            }
        }

        log.info("活动{}订单失效完成，共处理{}笔，reason={}", activityId, pendingOrders.size(), reason);
    }

    /**
     * 定时任务：取消超时未支付订单
     * 每分钟执行一次，扫描待支付超过15分钟的订单
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedRate = 60000)
    public void cancelTimeoutOrders() {
        try {
            LocalDateTime timeoutThreshold = LocalDateTime.now().minusMinutes(15);

            // 查询待支付且超时的订单
            LambdaQueryWrapper<OrderInfo> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(OrderInfo::getStatus, 0) // 待支付
                    .lt(OrderInfo::getCreateTime, timeoutThreshold); // 创建时间超过15分钟

            List<OrderInfo> timeoutOrders = orderInfoMapper.selectList(wrapper);

            if (!timeoutOrders.isEmpty()) {
                log.info("发现{}笔超时订单，开始取消", timeoutOrders.size());

                for (OrderInfo order : timeoutOrders) {
                    try {
                        cancelOrder(order.getUserId(), order.getOrderNo(), "订单超时未支付");
                    } catch (Exception e) {
                        log.error("取消超时订单失败: orderNo={}", order.getOrderNo(), e);
                    }
                }

                log.info("超时订单取消完成，共处理{}笔", timeoutOrders.size());
            }
        } catch (Exception e) {
            log.error("取消超时订单定时任务执行失败", e);
        }
    }

    /**
     * 定时任务：标记超时未创建的订单为失败
     * 每分钟执行一次，扫描处理中超过5分钟的订单
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedRate = 60000)
    public void markTimeoutOrdersAsFailed() {
        try {
            long now = System.currentTimeMillis();

            // 扫描 ZSET 中过期的订单（score < 当前时间）
            // 使用 Redis 的 ZRANGEBYSCORE 命令
            Set<Object> expiredOrders = redisTemplate.opsForZSet().rangeByScore(
                    "order:processing:zset",
                    0,
                    now
            );

            if (expiredOrders == null || expiredOrders.isEmpty()) {
                return;
            }

            log.info("发现{}个超时未创建的订单，开始处理", expiredOrders.size());

            int markedCount = 0;
            for (Object obj : expiredOrders) {
                String orderNo = obj.toString();
                try {
                    // 检查订单是否已创建
                    if (checkOrderExists(orderNo)) {
                        // 订单已创建，从 ZSET 中移除
                        redisTemplate.opsForZSet().remove("order:processing:zset", orderNo);
                        redisTemplate.delete("order:processing:" + orderNo);
                        continue;
                    }

                    // 检查是否已有失败标记
                    String failKey = "order:fail:" + orderNo;
                    if (Boolean.TRUE.equals(redisTemplate.hasKey(failKey))) {
                        // 已有失败标记，从 ZSET 中移除
                        redisTemplate.opsForZSet().remove("order:processing:zset", orderNo);
                        redisTemplate.delete("order:processing:" + orderNo);
                        continue;
                    }

                    // 设置失败标记
                    redisTemplate.opsForValue().set(failKey, "订单创建超时，请重试", 24, TimeUnit.HOURS);

                    // deadline模式：超时即死亡，检查库存是否已扣减后再决定是否回滚
                    // d:inventory:{orderNo} 由 InventoryConsumer 在库存扣减成功后设置
                    String deductedKey = "d:inventory:" + orderNo;
                    String processingKeyLocal = "order:processing:" + orderNo;
                    if (Boolean.TRUE.equals(redisTemplate.hasKey(deductedKey))) {
                        // 库存已扣减 → 需要回滚
                        Map<Object, Object> orderInfoData = redisTemplate.opsForHash().entries(processingKeyLocal);
                        if (!orderInfoData.isEmpty()) {
                            try {
                                Long timeoutUserId = Long.valueOf(orderInfoData.get("userId").toString());
                                Long timeoutActivityId = Long.valueOf(orderInfoData.get("activityId").toString());
                                Long timeoutItemId = Long.valueOf(orderInfoData.get("itemId").toString());
                                Integer timeoutQuantity = Integer.valueOf(orderInfoData.get("quantity").toString());

                                StockRollbackMessage rollbackMessage = new StockRollbackMessage();
                                rollbackMessage.setOrderNo(orderNo);
                                rollbackMessage.setActivityId(timeoutActivityId);
                                rollbackMessage.setItemId(timeoutItemId);
                                rollbackMessage.setQuantity(timeoutQuantity);
                                rollbackMessage.setUserId(timeoutUserId);

                                localMessageService.saveMessage(orderNo, MqConstant.STOCK_ROLLBACK_TOPIC, rollbackMessage);
                                rocketMQTemplate.syncSend(MqConstant.STOCK_ROLLBACK_TOPIC, rollbackMessage);
                                localMessageService.markMessageAsSent(orderNo);
                                // 设置回滚已发送标记，防止迟到消息重复回滚
                                redisTemplate.opsForValue().set("stock:rollback:sent:" + orderNo, "1", 24, TimeUnit.HOURS);
                                log.info("超时订单库存回滚消息已发送: orderNo={}", orderNo);
                            } catch (Exception e) {
                                log.error("超时订单库存回滚消息发送失败，已保存到本地消息表等待重试: orderNo={}", orderNo, e);
                            }
                        } else {
                            log.warn("超时订单信息不完整，无法发送回滚消息: orderNo={}", orderNo);
                        }
                    } else {
                        // 库存未扣减 → 只标记失败，不回滚（可能是MQ卡住或库存扣减失败）
                        log.info("超时订单库存未扣减，仅标记失败不回滚: orderNo={}", orderNo);
                    }

                    // 从 ZSET 中移除
                    redisTemplate.opsForZSet().remove("order:processing:zset", orderNo);
                    redisTemplate.delete(processingKeyLocal);

                    markedCount++;
                    log.info("标记超时订单为失败: orderNo={}", orderNo);

                } catch (Exception e) {
                    log.error("标记超时订单失败: orderNo={}", orderNo, e);
                }
            }

            if (markedCount > 0) {
                log.info("超时订单标记完成，共标记{}笔", markedCount);
            }

        } catch (Exception e) {
            log.error("标记超时订单定时任务执行失败", e);
        }
    }
}
