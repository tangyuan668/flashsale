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
     * Lua脚本：补偿回滚（用于消息消费失败等异常场景）
     * 返回: 1-回滚成功
     *
     * 说明：
     * - 补偿任务已根据日志时间（10分钟）判断是否需要补偿
     * - 此脚本直接执行回滚，不再依赖扣减中标记的TTL
     * - 清理扣减中标记和购买标记，恢复库存
     */
    private static final String COMPENSATE_ROLLBACK_LUA =
            "local stockKey = KEYS[1]\n" +
            "local deductingKey = KEYS[2]\n" +
            "local purchaseKey = KEYS[3]\n" +
            "local quantity = tonumber(ARGV[1])\n" +
            "\n" +
            "-- 执行回滚（原子操作）\n" +
            "local stock = tonumber(redis.call('GET', stockKey))\n" +
            "if stock ~= nil then\n" +
            "    redis.call('INCRBY', stockKey, quantity)\n" + // 恢复库存
            "end\n" +
            "redis.call('DEL', deductingKey)\n" + // 删除扣减中标记（如果存在）
            "redis.call('DEL', purchaseKey)\n" + // 删除购买标记（如果存在）
            "\n" +
            "return 1"; // 回滚成功

    /**
     * Lua脚本：仅回滚库存，保留购买标记
     * 返回: 1-回滚成功
     *
     * 说明：
     * - 用于用户已重试成功的场景，只回滚库存，不删除购买标记
     * - 避免删除后续成功订单的购买标记，导致用户可以重复购买
     */
    private static final String COMPENSATE_ROLLBACK_STOCK_ONLY_LUA =
            "local stockKey = KEYS[1]\n" +
            "local deductingKey = KEYS[2]\n" +
            "local quantity = tonumber(ARGV[1])\n" +
            "\n" +
            "-- 只恢复库存，不删除购买标记\n" +
            "local stock = tonumber(redis.call('GET', stockKey))\n" +
            "if stock ~= nil then\n" +
            "    redis.call('INCRBY', stockKey, quantity)\n" + // 恢复库存
            "end\n" +
            "redis.call('DEL', deductingKey)\n" + // 删除扣减中标记（如果存在）
            "-- 注意：不删除 purchaseKey\n" +
            "\n" +
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
        Long result = executeLua(DEDUCT_STOCK_LUA, Collections.singletonList(stockKey),
                String.valueOf(request.getQuantity()),
                String.valueOf(request.getUserId()),
                request.getOrderNo()
        );

        // 测试1：读取Redis中的库存值
        Long test1 = executeLua("return tonumber(redis.call('GET', KEYS[1]))", Collections.singletonList(stockKey));
        log.info("TEST1 读库存 stockKey={}, 结果={}", stockKey, test1);

        // 测试2：测试参数传递
        Long test2 = executeLua("return tonumber(ARGV[1]) + tonumber(ARGV[2])",
                Collections.emptyList(),
                String.valueOf(request.getQuantity()),
                String.valueOf(request.getUserId())
        );
        log.info("TEST2 参数传递 quantity={}, userId={}, 结果={}", request.getQuantity(), request.getUserId(), test2);

        // 测试3：计算remaining并返回（不执行SET）
        Long test3 = executeLua(
                "local stock = tonumber(redis.call('GET', KEYS[1])); " +
                "local quantity = tonumber(ARGV[1]); " +
                "local remaining = stock - quantity; " +
                "return remaining",
                Collections.singletonList(stockKey),
                String.valueOf(request.getQuantity())
        );
        log.info("TEST3 计算remaining 结果={}", test3);

        // 测试4：执行SET后返回
        Long test4 = executeLua(
                "local key = KEYS[1]; " +
                "local stock = tonumber(redis.call('GET', key)); " +
                "local remaining = stock - 1; " +
                "redis.call('SET', key, remaining); " +
                "redis.call('SET', key, stock); " +
                "return remaining",
                Collections.singletonList(stockKey)
        );
        log.info("TEST4 执行SET后恢复 结果={}", test4);

        // 测试5：执行SETEX后返回
        Long test5 = executeLua(
                "local key = KEYS[1]; " +
                "local stock = tonumber(redis.call('GET', key)); " +
                "redis.call('SETEX', 'test:deducting:tmp', 10, 'test'); " +
                "redis.call('DEL', 'test:deducting:tmp'); " +
                "return stock",
                Collections.singletonList(stockKey)
        );
        log.info("TEST5 执行SETEX 结果={}", test5);

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
     * 补偿回滚库存（用于处理消息消费失败等异常场景）
     * 通过Lua脚本原子操作检查并回滚
     *
     * @param orderNo        订单号
     * @param activityId     活动ID
     * @param itemId         商品ID
     * @param quantity       数量
     * @param userId         用户ID
     * @return 是否执行了补偿回滚
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean compensateRollback(String orderNo, Long activityId, Long itemId,
                                       Integer quantity, Long userId) {
        String stockKey = RedisConstant.STOCK_CACHE_PREFIX + activityId + ":" + itemId;
        String deductingKey = "deducting:" + userId + ":" + stockKey;
        String purchaseKey = "user:purchase:" + userId + ":" + stockKey;

        // 执行补偿回滚（直接回滚，由补偿任务判断是否需要调用）
        Long result = executeLua(COMPENSATE_ROLLBACK_LUA, Arrays.asList(stockKey, deductingKey, purchaseKey),
                String.valueOf(quantity)
        );

        log.info("补偿回滚执行: orderNo={}, activityId={}, itemId={}, result={}",
                orderNo, activityId, itemId, result);

        if (result != null && result == 1) {
            // 回滚成功，记录日志
            recordInventoryLogForRollback(orderNo, activityId, itemId, quantity,
                    0, quantity, userId); // 补偿场景无法获取准确的beforeStock
            log.info("补偿回滚成功: orderNo={}", orderNo);
            return true;
        }

        log.error("补偿回滚失败: orderNo={}, result={}", orderNo, result);
        return false;
    }

    /**
     * 补偿回滚库存（仅回滚库存，保留购买标记）
     * 用于用户已重试成功的场景，避免删除后续订单的购买标记
     *
     * @param orderNo 订单号
     * @param activityId 活动ID
     * @param itemId 商品ID
     * @param quantity 数量
     * @param userId 用户ID
     * @return 是否执行了补偿回滚
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean compensateRollbackOnlyStock(String orderNo, Long activityId, Long itemId,
                                                Integer quantity, Long userId) {
        String stockKey = RedisConstant.STOCK_CACHE_PREFIX + activityId + ":" + itemId;
        String deductingKey = "deducting:" + userId + ":" + stockKey;

        // 执行补偿回滚（只回滚库存，不删除购买标记）
        Long result = executeLua(COMPENSATE_ROLLBACK_STOCK_ONLY_LUA, Arrays.asList(stockKey, deductingKey),
                String.valueOf(quantity)
        );

        log.info("补偿回滚库存（保留购买标记）: orderNo={}, activityId={}, itemId={}, result={}",
                orderNo, activityId, itemId, result);

        if (result != null && result == 1) {
            // 回滚成功，记录日志
            recordInventoryLogForRollback(orderNo, activityId, itemId, quantity,
                    0, quantity, userId);
            log.info("补偿回滚成功（用户已重试）: orderNo={}", orderNo);
            return true;
        }

        log.error("补偿回滚失败: orderNo={}, result={}", orderNo, result);
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
