package com.flashsale.inventory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flashsale.common.ErrorCode;
import com.flashsale.common.constant.MqConstant;
import com.flashsale.common.constant.RedisConstant;
import com.flashsale.common.dto.StockDeductMessage;
import com.flashsale.common.dto.StockDeductRequest;
import com.flashsale.common.exception.BusinessException;
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

import java.util.Arrays;
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

    @Autowired
    private LocalMessageService localMessageService;

    /**
     * Lua脚本：库存扣减（原子操作，防止超卖）
     * 返回: -1-库存不足, -2-库存未初始化, -3-扣减中(重复请求), >=0-扣减后的剩余库存
     * 注意：防重复购买标记移到订单创建成功后设置
     */
    private static final String DEDUCT_STOCK_LUA =
            "local key = KEYS[1] " +
            "local quantity = tonumber(ARGV[1]) " +
            "local userId = ARGV[2] " +
            "local orderNo = ARGV[3] " +
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
            "-- 检查是否有正在进行的扣减（防窗口期重复请求） " +
            "local deductingKey = 'deducting:' .. userId .. ':' .. key " +
            "if redis.call('EXISTS', deductingKey) == 1 then " +
            "    return -3 " + // 扣减中，请勿重复提交
            "end " +
            "" +
            "-- 扣减库存 " +
            "local remaining = stock - quantity " +
            "redis.call('SET', key, remaining) " +
            "" +
            "-- 设置扣减中标记（有效期60秒，防止窗口期重复请求） " +
            "redis.call('SETEX', deductingKey, 60, orderNo) " +
            "" +
            "return remaining";

    /**
     * Lua脚本：库存回滚
     * 同时清理扣减中标记和用户购买标记
     */
    private static final String ROLLBACK_STOCK_LUA =
            "local key = KEYS[1] " +
            "local quantity = tonumber(ARGV[1]) " +
            "local userId = ARGV[2] " +
            "" +
            "-- 移除扣减中标记 " +
            "local deductingKey = 'deducting:' .. userId .. ':' .. key " +
            "redis.call('DEL', deductingKey) " +
            "" +
            "-- 移除用户购买记录（如果存在） " +
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
     * Lua脚本：补偿回滚（用于消息消费失败等异常场景）
     * 返回: -1-无需回滚(无扣减中标记), -2-订单号不匹配, -3-未超时, -4-标记无过期时间(异常), 1-回滚成功
     *
     * 检查条件：
     * 1. 扣减中标记是否存在
     * 2. 订单号是否匹配
     * 3. 是否超过超时阈值
     */
    private static final String COMPENSATE_ROLLBACK_LUA =
            "local stockKey = KEYS[1] " +
            "local deductingKey = KEYS[2] " +
            "local purchaseKey = KEYS[3] " +
            "local quantity = tonumber(ARGV[1]) " +
            "local expectedOrderNo = ARGV[2] " +
            "local timeoutSeconds = tonumber(ARGV[3]) " +
            "" +
            "-- 1. 检查扣减中标记是否存在 " +
            "local actualOrderNo = redis.call('GET', deductingKey) " +
            "if actualOrderNo == false then " +
            "    return -1 " + // 没有扣减中标记，已处理无需回滚
            "end " +
            "" +
            "-- 2. 校验订单号是否匹配（防止并发回滚错误的库存） " +
            "if actualOrderNo ~= expectedOrderNo then " +
            "    return -2 " + // 订单号不匹配
            "end " +
            "" +
            "-- 3. 检查标记的存活时间（是否超时） " +
            "local ttl = redis.call('TTL', deductingKey) " +
            "if ttl < 0 then " +
            "    return -4 " + // 标记没有过期时间，异常情况
            "end " +
            "local elapsed = 60 - ttl " + // 标记设置时 TTL=60，已过时间 = 60 - 当前TTL
            "if elapsed < timeoutSeconds then " +
            "    return -3 " + // 未超时，不补偿
            "end " +
            "" +
            "-- 4. 执行回滚（原子操作） " +
            "local stock = tonumber(redis.call('GET', stockKey)) " +
            "if stock ~= nil then " +
            "    redis.call('INCRBY', stockKey, quantity) " + // 恢复库存
            "end " +
            "redis.call('DEL', deductingKey) " + // 删除扣减中标记
            "redis.call('DEL', purchaseKey) " + // 删除购买标记 " +
            "" +
            "return 1"; // 回滚成功

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
                new Object[]{
                        String.valueOf(request.getQuantity()),
                        String.valueOf(request.getUserId()),
                        request.getOrderNo()
                }
        );

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
                new Object[]{
                        String.valueOf(quantity),
                        String.valueOf(userId)
                }
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
     * 补偿回滚库存（用于处理消息消费失败等异常场景）
     * 通过Lua脚本原子操作检查并回滚
     *
     * @param orderNo        订单号
     * @param activityId     活动ID
     * @param itemId         商品ID
     * @param quantity       数量
     * @param userId         用户ID
     * @param timeoutSeconds 超时阈值（秒），超过此时间才允许补偿
     * @return 是否执行了补偿回滚
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean compensateRollback(String orderNo, Long activityId, Long itemId,
                                       Integer quantity, Long userId, int timeoutSeconds) {
        String stockKey = RedisConstant.STOCK_CACHE_PREFIX + activityId + ":" + itemId;
        String deductingKey = "deducting:" + userId + ":" + stockKey;
        String purchaseKey = "user:purchase:" + userId + ":" + stockKey;

        DefaultRedisScript<Long> script = new DefaultRedisScript<>(COMPENSATE_ROLLBACK_LUA, Long.class);

        Long result = redisTemplate.execute(
                script,
                Arrays.asList(stockKey, deductingKey, purchaseKey),
                new Object[]{
                        String.valueOf(quantity),
                        orderNo,
                        String.valueOf(timeoutSeconds)
                }
        );

        log.info("补偿回滚检查: orderNo={}, activityId={}, itemId={}, result={}",
                orderNo, activityId, itemId, result);

        if (result != null && result == 1) {
            // 回滚成功，记录日志
            recordInventoryLogForRollback(orderNo, activityId, itemId, quantity,
                    0, quantity, userId); // 补偿场景无法获取准确的beforeStock
            log.info("补偿回滚成功: orderNo={}", orderNo);
            return true;
        }

        // 记录未回滚原因
        String reason = switch (result == null ? -99 : result.intValue()) {
            case -1 -> "没有扣减中标记，已处理";
            case -2 -> "订单号不匹配";
            case -3 -> "未超时，不补偿";
            case -4 -> "标记无过期时间，异常";
            default -> "未知原因: " + result;
        };
        log.debug("补偿回滚跳过: orderNo={}, reason={}", orderNo, reason);
        return false;
    }

    /**
     * 记录库存回滚日志
     */
    private void recordInventoryLogForRollback(String orderNo, Long activityId, Long itemId,
                                                Integer quantity, Integer beforeStock, Integer afterStock, Long userId) {
        InventoryLog logEntry = new InventoryLog();
        logEntry.setActivityId(activityId);
        logEntry.setItemId(itemId);
        logEntry.setOrderNo(orderNo);
        logEntry.setUserId(userId);
        logEntry.setChangeType(2); // 2-回滚
        logEntry.setChangeAmount(quantity);
        logEntry.setBeforeStock(beforeStock);
        logEntry.setAfterStock(afterStock);
        logEntry.setRemark("订单取消回滚，userId=" + userId);

        inventoryLogMapper.insert(logEntry);
    }

    /**
     * 记录库存变动日志
     */
    private void recordInventoryLog(StockDeductRequest request, Integer beforeStock, Integer afterStock, Integer changeType) {
        InventoryLog log = new InventoryLog();
        log.setActivityId(request.getActivityId());
        log.setItemId(request.getItemId());
        log.setOrderNo(request.getOrderNo());
        log.setUserId(request.getUserId());
        log.setChangeType(changeType);
        log.setChangeAmount(request.getQuantity());
        log.setBeforeStock(beforeStock);
        log.setAfterStock(afterStock);
        log.setRemark("库存扣减");

        inventoryLogMapper.insert(log);
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

            // 尝试发送（发送失败由定时任务重试）
            try {
                rocketMQTemplate.syncSend(MqConstant.STOCK_RESULT_TOPIC, message);
                log.info("发送库存扣减消息成功: orderNo={}", request.getOrderNo());
                // 立即更新本地消息表状态为已发送
                localMessageService.markMessageAsSent(request.getOrderNo());
            } catch (Exception e) {
                log.error("发送库存扣减消息失败，已保存到本地消息表等待重试: orderNo={}", request.getOrderNo(), e);
                // 消息已保存到本地消息表，定时任务会重试
            }
        } catch (Exception e) {
            log.error("构造库存扣减消息失败: orderNo={}", request.getOrderNo(), e);
        }
    }
}
