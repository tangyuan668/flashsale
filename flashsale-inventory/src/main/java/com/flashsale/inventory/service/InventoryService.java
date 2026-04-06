package com.flashsale.inventory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flashsale.common.ErrorCode;
import com.flashsale.common.constant.MqConstant;
import com.flashsale.common.constant.RedisConstant;
import com.flashsale.common.exception.BusinessException;
import com.flashsale.inventory.dto.StockDeductRequest;
import com.flashsale.inventory.entity.Inventory;
import com.flashsale.inventory.entity.InventoryLog;
import com.flashsale.inventory.mapper.InventoryLogMapper;
import com.flashsale.inventory.mapper.InventoryMapper;
import com.flashsale.inventory.vo.InventoryInfoResponse;
import com.flashsale.inventory.vo.StockDeductResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

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

    /**
     * Lua脚本：库存扣减（原子操作，防止超卖）
     * 返回: -1-库存不足, >=0-扣减后的剩余库存
     */
    private static final String DEDUCT_STOCK_LUA =
            "local key = KEYS[1] " +
            "local quantity = tonumber(ARGV[1]) " +
            "local userId = ARGV[2] " +
            "local orderId = ARGV[3] " +
            "" +
            "local stock = tonumber(redis.call('GET', key)) " +
            "if stock == nil then " +
            "    return -2 " + // 库存未初始化
            "end " +
            "" +
            "if stock < quantity then " +
            "    return -1 " + // 库存不足
            "end " +
            "" +
            "-- 检查用户是否已购买（防重复购买） " +
            "local purchaseKey = 'user:purchase:' .. userId .. ':' .. key " +
            "if redis.call('EXISTS', purchaseKey) == 1 then " +
            "    return -3 " + // 已购买
            "end " +
            "" +
            "-- 扣减库存 " +
            "local remaining = stock - quantity " +
            "redis.call('SET', key, remaining) " +
            "" +
            "-- 标记用户已购买（有效期24小时） " +
            "redis.call('SETEX', purchaseKey, 86400, orderId) " +
            "" +
            "return remaining";

    /**
     * Lua脚本：库存回滚
     */
    private static final String ROLLBACK_STOCK_LUA =
            "local key = KEYS[1] " +
            "local quantity = tonumber(ARGV[1]) " +
            "local userId = ARGV[2] " +
            "" +
            "-- 移除购买记录 " +
            "local purchaseKey = 'user:purchase:' .. userId .. ':' .. key " +
            "redis.call('DEL', purchaseKey) " +
            "" +
            "-- 恢复库存 " +
            "local stock = tonumber(redis.call('GET', key)) " +
            "if stock ~= nil then " +
            "    redis.call('SET', key, stock + quantity) " +
            "    return stock + quantity " +
            "end " +
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
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(DEDUCT_STOCK_LUA, Long.class);

        Long result = redisTemplate.execute(
                script,
                Collections.singletonList(stockKey),
                String.valueOf(request.getQuantity()),
                String.valueOf(request.getUserId()),
                request.getOrderNo()
        );

        if (result == null) {
            return StockDeductResponse.fail("库存扣减失败");
        }

        // 处理结果
        return switch (result.intValue()) {
            case -1 -> StockDeductResponse.fail("库存不足");
            case -2 -> StockDeductResponse.fail("库存未预热，请稍后重试");
            case -3 -> StockDeductResponse.fail("您已购买，每人限购一件");
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
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(ROLLBACK_STOCK_LUA, Long.class);

        Long result = redisTemplate.execute(
                script,
                Collections.singletonList(stockKey),
                String.valueOf(quantity),
                String.valueOf(userId)
        );

        log.info("库存回滚: orderNo={}, activityId={}, itemId={}, result={}",
                orderNo, activityId, itemId, result);

        // 发送MQ消息通知
        // rocketMQTemplate.convertAndSend(MqConstant.STOCK_ROLLBACK_TOPIC, ...);
    }

    /**
     * 记录库存变动日志
     */
    private void recordInventoryLog(StockDeductRequest request, Integer beforeStock, Integer afterStock, Integer changeType) {
        InventoryLog log = new InventoryLog();
        log.setActivityId(request.getActivityId());
        log.setItemId(request.getItemId());
        log.setOrderNo(request.getOrderNo());
        log.setChangeType(changeType);
        log.setChangeAmount(request.getQuantity());
        log.setBeforeStock(beforeStock);
        log.setAfterStock(afterStock);
        log.setRemark("库存扣减");

        inventoryLogMapper.insert(log);
    }

    /**
     * 发送库存扣减消息
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

            rocketMQTemplate.syncSend(MqConstant.SECKILL_ORDER_TOPIC, message);
            log.info("发送库存扣减消息: orderNo={}", request.getOrderNo());
        } catch (Exception e) {
            log.error("发送库存扣减消息失败: orderNo={}", request.getOrderNo(), e);
        }
    }

    /**
     * 库存扣减消息
     */
    @lombok.Data
    public static class StockDeductMessage {
        private String orderNo;
        private Long activityId;
        private Long itemId;
        private Integer quantity;
        private Long userId;
        private Boolean success;
    }
}
