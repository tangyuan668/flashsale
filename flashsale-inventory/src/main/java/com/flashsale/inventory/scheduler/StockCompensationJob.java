package com.flashsale.inventory.scheduler;

import com.flashsale.inventory.entity.InventoryLog;
import com.flashsale.inventory.feign.OrderFeignClient;
import com.flashsale.inventory.mapper.InventoryLogMapper;
import com.flashsale.inventory.service.InventoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 库存补偿定时任务
 * 定时扫描需要补偿回滚的库存记录
 */
@Slf4j
@Component
public class StockCompensationJob {

    @Autowired
    private InventoryLogMapper inventoryLogMapper;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private OrderFeignClient orderFeignClient;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * 超时时间（分钟）
     * 库存扣减后超过此时间订单未创建，则执行补偿回滚
     * 应该大于扣减中标记的 TTL（2分钟），小于订单支付超时（15分钟）
     */
    private static final int TIMEOUT_MINUTES = 10;

    /**
     * 每次扫描的最大记录数
     */
    private static final int BATCH_SIZE = 100;

    /**
     * 订单补偿标记前缀
     * 当库存补偿回滚后，设置此标记，订单服务创建订单前会检查
     */
    private static final String ORDER_COMPENSATED_KEY_PREFIX = "order:compensated:";

    /**
     * 订单补偿标记有效期（小时）
     * 24小时足以覆盖所有可能的延迟消息
     */
    private static final int COMPENSATED_MARK_TTL_HOURS = 24;

    /**
     * 定时扫描并补偿孤儿库存
     * 每分钟执行一次
     */
    @Scheduled(fixedRate = 60000)
    public void compensateOrphanedStock() {
        try {
            LocalDateTime beforeTime = LocalDateTime.now().minusMinutes(TIMEOUT_MINUTES);
            List<InventoryLog> pendingLogs = inventoryLogMapper.selectPendingCompensation(beforeTime, BATCH_SIZE);

            if (pendingLogs.isEmpty()) {
                return;
            }

            log.info("发现{}条待补偿的库存记录，开始处理", pendingLogs.size());

            int compensatedCount = 0;
            int skippedCount = 0;
            int errorCount = 0;

            for (InventoryLog inventoryLog : pendingLogs) {
                try {
                    // 1. 检查原始订单是否存在
                    boolean orderExists = checkOrderExists(inventoryLog.getOrderNo());

                    if (orderExists) {
                        // 订单存在，跳过补偿
                        log.debug("订单已存在，跳过补偿: orderNo={}", inventoryLog.getOrderNo());
                        skippedCount++;
                        continue;
                    }

                    // 2. 检查用户是否对同一商品有后续成功的订单（排除当前失败订单）
                    boolean hasLaterSuccessOrder = checkUserHasSuccessOrder(
                            inventoryLog.getUserId(),
                            inventoryLog.getActivityId(),
                            inventoryLog.getItemId(),
                            inventoryLog.getOrderNo()
                    );

                    boolean compensated;
                    if (hasLaterSuccessOrder) {
                        // 用户已重试成功，只回滚库存，不删除购买标记
                        log.info("用户已重试成功，仅回滚库存: originalOrderNo={}, userId={}, activityId={}, itemId={}",
                                inventoryLog.getOrderNo(), inventoryLog.getUserId(),
                                inventoryLog.getActivityId(), inventoryLog.getItemId());
                        compensated = inventoryService.compensateRollbackOnlyStock(
                                inventoryLog.getOrderNo(),
                                inventoryLog.getActivityId(),
                                inventoryLog.getItemId(),
                                inventoryLog.getChangeAmount(),
                                inventoryLog.getUserId()
                        );
                    } else {
                        // 没有后续成功订单，完整回滚（库存+购买标记）
                        compensated = inventoryService.compensateRollback(
                                inventoryLog.getOrderNo(),
                                inventoryLog.getActivityId(),
                                inventoryLog.getItemId(),
                                inventoryLog.getChangeAmount(),
                                inventoryLog.getUserId()
                        );
                    }

                    if (compensated) {
                        compensatedCount++;

                        // 设置订单补偿标记，防止订单延迟创建导致的数据不一致
                        // 场景：库存已回滚，但库存结果消息延迟到达，订单服务可能仍会创建订单
                        String compensatedKey = ORDER_COMPENSATED_KEY_PREFIX + inventoryLog.getOrderNo();
                        String reason = hasLaterSuccessOrder
                                ? "订单创建超时且用户已重试成功，原始库存已回滚"
                                : "订单创建超时(10分钟)，库存已回滚";
                        redisTemplate.opsForValue().set(compensatedKey, reason, COMPENSATED_MARK_TTL_HOURS, TimeUnit.HOURS);
                        log.info("设置订单补偿标记: orderNo={}, reason={}", inventoryLog.getOrderNo(), reason);

                        log.info("补偿回滚成功: orderNo={}, activityId={}, itemId={}",
                                inventoryLog.getOrderNo(), inventoryLog.getActivityId(), inventoryLog.getItemId());
                    } else {
                        skippedCount++;
                    }

                } catch (Exception e) {
                    errorCount++;
                    log.error("补偿回滚处理失败: orderNo={}", inventoryLog.getOrderNo(), e);
                }
            }

            log.info("库存补偿处理完成，总计{}条，成功{}条，跳过{}条，失败{}条",
                    pendingLogs.size(), compensatedCount, skippedCount, errorCount);

        } catch (Exception e) {
            log.error("库存补偿定时任务执行失败", e);
        }
    }

    /**
     * 检查订单是否存在
     *
     * @param orderNo 订单号
     * @return 订单是否存在
     */
    private boolean checkOrderExists(String orderNo) {
        try {
            var response = orderFeignClient.checkOrderExists(orderNo);
            if (response != null && response.getCode() == 0) {
                Boolean exists = response.getData();
                return Boolean.TRUE.equals(exists);
            }
            // 调用失败时保守处理，认为订单存在，不执行补偿
            return true;
        } catch (Exception e) {
            log.error("检查订单是否存在失败: orderNo={}", orderNo, e);
            // 调用失败时保守处理，认为订单存在，不执行补偿
            return true;
        }
    }

    /**
     * 检查用户是否对指定商品有后续成功的订单
     *
     * @param userId 用户ID
     * @param activityId 活动ID
     * @param itemId 商品ID
     * @param excludeOrderNo 排除的订单号（当前检查的失败订单）
     * @return 是否有后续成功订单
     */
    private boolean checkUserHasSuccessOrder(Long userId, Long activityId, Long itemId, String excludeOrderNo) {
        try {
            var response = orderFeignClient.checkUserHasSuccessOrder(userId, activityId, itemId, excludeOrderNo);
            if (response != null && response.getCode() == 0) {
                Boolean hasSuccess = response.getData();
                return Boolean.TRUE.equals(hasSuccess);
            }
            // 调用失败时保守处理，认为有成功订单，只回滚库存不删除购买标记
            // 避免误删购买标记导致用户可以重复购买
            log.warn("检查用户是否有成功订单失败，保守处理为有成功订单: userId={}, activityId={}, itemId={}",
                    userId, activityId, itemId);
            return true;
        } catch (Exception e) {
            log.error("检查用户是否有成功订单失败，保守处理为有成功订单: userId={}, activityId={}, itemId={}, excludeOrderNo={}",
                    userId, activityId, itemId, excludeOrderNo, e);
            // 调用失败时保守处理，认为有成功订单，只回滚库存不删除购买标记
            // 避免误删购买标记导致用户可以重复购买
            return true;
        }
    }
}
