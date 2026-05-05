package com.flashsale.inventory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flashsale.common.ErrorCode;
import com.flashsale.common.constant.MqConstant;
import com.flashsale.common.constant.RedisConstant;
import com.flashsale.common.dto.StockDeductMessage;
import com.flashsale.common.dto.StockDeductRequest;
import com.flashsale.common.exception.BusinessException;
import com.flashsale.inventory.entity.Inventory;
import com.flashsale.inventory.service.LocalMessageService;
import com.flashsale.inventory.entity.InventoryLog;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import com.flashsale.inventory.mapper.InventoryLogMapper;
import com.flashsale.inventory.mapper.InventoryMapper;
import com.flashsale.inventory.vo.InventoryInfoResponse;
import com.flashsale.inventory.vo.StockDeductResponse;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import lombok.extern.slf4j.Slf4j;


import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 库存服务
 */
@Slf4j
@Service
public class InventoryService {

    @Autowired
    private InventoryMapper inventoryMapper;

    @Autowired
    private InventoryLogMapper inventoryLogMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    @Autowired
    private LocalMessageService localMessageService;

    /** 库存日志缓冲队列 */
    private static final int LOG_QUEUE_CAPACITY = 10000;
    private static final int LOG_BATCH_SIZE = 500;
    private final BlockingQueue<InventoryLog> logQueue = new LinkedBlockingQueue<>(LOG_QUEUE_CAPACITY);

    /**
     * Lua脚本：库存扣减（原子操作，防止超卖）
     * 返回: -1-库存不足, -2-库存未初始化, -3-扣减中(重复请求), -4-已购买, >=0-扣减后的剩余库存
     */
    private static final String DEDUCT_STOCK_LUA =
            "local key = KEYS[1]\n" +
            "local quantity = tonumber(ARGV[1])\n" +
            "local userId = ARGV[2]\n" +
            "local orderNo = ARGV[3]\n" +
            "\n" +
            "local stock = tonumber(redis.call('GET', key))\n" +
            "if stock == nil then\n" +
            "    return -2\n" + // 库存未初始化
            "end\n" +
            "\n" +
            "if stock < quantity then\n" +
            "    return -1\n" + // 库存不足
            "end\n" +
            "\n" +
            "-- 检查用户是否已购买（防重复购买）\n" +
            "local purchaseKey = 'user:purchase:' .. userId .. ':' .. key\n" +
            "if redis.call('EXISTS', purchaseKey) == 1 then\n" +
            "    return -4\n" + // 已购买，不允许再次购买
            "end\n" +
            "\n" +
            "-- 检查是否有正在进行的扣减（防窗口期重复请求）\n" +
            "local deductingKey = 'deducting:' .. userId .. ':' .. key\n" +
            "if redis.call('EXISTS', deductingKey) == 1 then\n" +
            "    return -3\n" + // 扣减中，请勿重复提交
            "end\n" +
            "\n" +
            "-- 扣减库存\n" +
            "local remaining = stock - quantity\n" +
            "redis.call('SET', key, remaining)\n" +
            "\n" +
            "-- 设置扣减中标记（有效期120秒，防止窗口期重复请求）\n" +
            "redis.call('SETEX', deductingKey, 120, orderNo)\n" +
            "\n" +
            "return remaining";

    /**
     * Lua脚本：库存回滚
     * 同时清理扣减中标记和用户购买标记
     */
    private static final String ROLLBACK_STOCK_LUA =
            "local key = KEYS[1]\n" +
            "local quantity = tonumber(ARGV[1])\n" +
            "local userId = ARGV[2]\n" +
            "\n" +
            "-- 移除扣减中标记\n" +
            "local deductingKey = 'deducting:' .. userId .. ':' .. key\n" +
            "redis.call('DEL', deductingKey)\n" +
            "\n" +
            "-- 移除用户购买记录（如果存在）\n" +
            "local purchaseKey = 'user:purchase:' .. userId .. ':' .. key\n" +
            "redis.call('DEL', purchaseKey)\n" +
            "\n" +
            "-- 恢复库存\n" +
            "local stock = tonumber(redis.call('GET', key))\n" +
            "if stock ~= nil then\n" +
            "    redis.call('SET', key, stock + quantity)\n" +
            "    return stock + quantity\n" +
            "end\n" +
            "return -1";

    /**
     * 获取库存信息
     */
    public InventoryInfoResponse getInventory(Long activityId, Long itemId) {
        LambdaQueryWrapper<Inventory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Inventory::getActivityId, activityId)
                .eq(Inventory::getItemId, itemId);
        Inventory inventory = inventoryMapper.selectOne(wrapper);

        if (inventory == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "库存信息不存在");
        }

        return new InventoryInfoResponse(
                inventory.getActivityId(),
                inventory.getItemId(),
                inventory.getTotalStock(),
                inventory.getAvailableStock(),
                inventory.getFrozenStock()
        );
    }

    /**
     * Redis + Lua 原子扣减库存（防止超卖）
     */
    @Transactional(rollbackFor = Exception.class)
    public StockDeductResponse deductStock(StockDeductRequest request) {
        String stockKey = RedisConstant.STOCK_CACHE_PREFIX + request.getActivityId() + ":" + request.getItemId();

        // 执行Lua脚本原子扣减
        Long result = executeLua(DEDUCT_STOCK_LUA, Collections.singletonList(stockKey),
                String.valueOf(request.getQuantity()),
                String.valueOf(request.getUserId()),
                request.getOrderNo()
        );

        log.info("Lua脚本返回值: orderNo={}, stockKey={}, result={}", request.getOrderNo(), stockKey, result);

        if (result == null) {
            sendStockDeductMessage(request, false);
            return StockDeductResponse.fail("库存扣减失败");
        }

        // 处理结果
        return switch (result.intValue()) {
            case -1 -> {
                // 库存不足，发送失败消息
                sendStockDeductMessage(request, false);
                yield StockDeductResponse.fail("库存不足");
            }
            case -2 -> {
                // 库存未预热，发送失败消息
                sendStockDeductMessage(request, false);
                yield StockDeductResponse.fail("库存未预热，请稍后重试");
            }
            case -3 -> {
                // 扣减中（重复请求），发送失败消息
                sendStockDeductMessage(request, false);
                yield StockDeductResponse.fail("订单处理中，请勿重复提交");
            }
            case -4 -> {
                // 已购买，发送失败消息
                sendStockDeductMessage(request, false);
                yield StockDeductResponse.fail("您已购买过该商品，每人限购一件");
            }
            default -> {
                // 扣减成功，记录日志
                recordInventoryLog(request, result.intValue() + request.getQuantity(), result.intValue(), 1);

                // 发送MQ消息通知订单服务
                sendStockDeductMessage(request, true);

                yield StockDeductResponse.success(result.intValue());
            }
        };
    }

    /**
     * 回滚库存
     */
    @Transactional(rollbackFor = Exception.class)
    public void rollbackStock(String orderNo, Long activityId, Long itemId, Integer quantity, Long userId) {
        String stockKey = RedisConstant.STOCK_CACHE_PREFIX + activityId + ":" + itemId;

        // 执行Lua脚本回滚
        Long result = executeLua(ROLLBACK_STOCK_LUA, Collections.singletonList(stockKey),
                String.valueOf(quantity),
                String.valueOf(userId)
        );

        log.info("库存回滚: orderNo={}, activityId={}, itemId={}, result={}",
                orderNo, activityId, itemId, result);

        // 回滚成功，记录日志（回滚后库存 = 回滚前库存 + quantity）
        if (result != null && result >= 0) {
            recordInventoryLogForRollback(orderNo, activityId, itemId, quantity,
                    Math.toIntExact(result - quantity),
                    Math.toIntExact(result),
                    userId);
        }
    }

    /**
     * 记录库存回滚日志（缓冲模式）
     */
    private void recordInventoryLogForRollback(String orderNo, Long activityId, Long itemId,
                                                Integer quantity, Integer beforeStock, Integer afterStock, Long userId) {
        InventoryLog logEntry = new InventoryLog();
        logEntry.setActivityId(activityId);
        logEntry.setItemId(itemId);
        logEntry.setOrderNo(orderNo);
        logEntry.setUserId(userId);
        logEntry.setChangeType(2);
        logEntry.setChangeAmount(quantity);
        logEntry.setBeforeStock(beforeStock);
        logEntry.setAfterStock(afterStock);
        logEntry.setRemark("订单取消回滚，userId=" + userId);

        if (!logQueue.offer(logEntry)) {
            log.warn("库存日志队列已满，降级为直接写库: orderNo={}", orderNo);
            inventoryLogMapper.insert(logEntry);
        }
    }

    /**
     * 记录库存变动日志（缓冲模式）
     */
    private void recordInventoryLog(StockDeductRequest request, Integer beforeStock, Integer afterStock, Integer changeType) {
        InventoryLog logEntry = new InventoryLog();
        logEntry.setActivityId(request.getActivityId());
        logEntry.setItemId(request.getItemId());
        logEntry.setOrderNo(request.getOrderNo());
        logEntry.setUserId(request.getUserId());
        logEntry.setChangeType(changeType);
        logEntry.setChangeAmount(request.getQuantity());
        logEntry.setBeforeStock(beforeStock);
        logEntry.setAfterStock(afterStock);
        logEntry.setRemark("库存扣减");

        if (!logQueue.offer(logEntry)) {
            log.warn("库存日志队列已满，降级为直接写库: orderNo={}", request.getOrderNo());
            inventoryLogMapper.insert(logEntry);
        }
    }

    /**
     * 批量刷入库存日志
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void flushInventoryLogs() {
        List<InventoryLog> batch = new ArrayList<>(LOG_BATCH_SIZE);
        logQueue.drainTo(batch, LOG_BATCH_SIZE);

        if (batch.isEmpty()) {
            return;
        }

        try {
            for (InventoryLog logEntry : batch) {
                inventoryLogMapper.insert(logEntry);
            }
            log.debug("批量写入库存日志成功: batchSize={}", batch.size());
        } catch (Exception e) {
            log.error("批量写入库存日志失败: batchSize={}", batch.size(), e);
        }
    }

    /**
     * 应用关闭时刷入剩余日志
     */
    @PreDestroy
    public void shutdown() {
        int remaining = logQueue.size();
        if (remaining > 0) {
            log.info("应用关闭，刷入剩余库存日志: remaining={}", remaining);
            while (!logQueue.isEmpty()) {
                flushInventoryLogs();
            }
        }
    }

    /**
     * 发送库存扣减消息
     * 使用本地消息表保障消息可靠发送
     */
    private void sendStockDeductMessage(StockDeductRequest request, boolean success) {
        try {
            // 构造消息
            StockDeductMessage message = new StockDeductMessage();
            message.setOrderNo(request.getOrderNo());
            message.setActivityId(request.getActivityId());
            message.setItemId(request.getItemId());
            message.setQuantity(request.getQuantity());
            message.setUserId(request.getUserId());
            message.setSuccess(success);

            // 保存到本地消息表
            localMessageService.saveMessage(request.getOrderNo(), MqConstant.STOCK_RESULT_TOPIC, message);

            // 异步发送（本地消息表已保证可靠性，无需同步等待 Broker ACK）
            try {
                rocketMQTemplate.asyncSend(MqConstant.STOCK_RESULT_TOPIC, message, new SendCallback() {
                    @Override
                    public void onSuccess(SendResult result) {
                        localMessageService.markMessageAsSent(request.getOrderNo());
                        log.info("发送库存扣减消息成功: orderNo={}", request.getOrderNo());
                    }

                    @Override
                    public void onException(Throwable e) {
                        log.error("发送库存扣减消息失败，已保存到本地消息表等待重试: orderNo={}", request.getOrderNo(), e);
                    }
                });
            } catch (Exception e) {
                log.error("发送库存扣减消息失败，已保存到本地消息表等待重试: orderNo={}", request.getOrderNo(), e);
            }
        } catch (Exception e) {
            log.error("构造库存扣减消息失败: orderNo={}", request.getOrderNo(), e);
        }
    }

    /**
     * 执行Lua脚本
     * 直接使用Redis原生连接，手动序列化参数，绕开Spring序列化器的反序列化问题
     */
    private Long executeLua(String luaScript, List<String> keys, Object... args) {
        return redisTemplate.execute((RedisCallback<Long>) connection -> {
            byte[][] keysAndArgs = new byte[keys.size() + args.length][];
            int i = 0;
            for (String key : keys) {
                keysAndArgs[i++] = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
            for (Object arg : args) {
                keysAndArgs[i++] = arg.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
            return connection.eval(
                    luaScript.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    ReturnType.INTEGER,
                    keys.size(),
                    keysAndArgs
            );
        });
    }
}
