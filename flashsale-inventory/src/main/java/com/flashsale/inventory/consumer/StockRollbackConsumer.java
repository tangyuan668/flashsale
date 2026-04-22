package com.flashsale.inventory.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.dto.StockRollbackMessage;
import com.flashsale.inventory.service.InventoryService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 库存回滚消费者
 * 消费库存回滚消息，执行库存回滚
 */
@Slf4j
@Component
@RocketMQMessageListener(
        topic = "STOCK_ROLLBACK_TOPIC",
        consumerGroup = "STOCK_ROLLBACK_CONSUMER_GROUP"
)
public class StockRollbackConsumer implements RocketMQListener<String> {

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final String DEDUPE_KEY_PREFIX = "d:rollback:";
    private static final String DEDUPE_PROCESSING_PREFIX = "d:rollback:processing:";
    private static final long DEDUPE_TIMEOUT_SECONDS = 1800; // 30分钟，覆盖 RocketMQ 最大重试间隔

    @Override
    public void onMessage(String message) {
        String orderNo = null;
        try {
            log.info("接收到库存回滚消息: {}", message);

            // 解析消息
            StockRollbackMessage rollbackMessage = objectMapper.readValue(message, StockRollbackMessage.class);
            orderNo = rollbackMessage.getOrderNo();

            // 1. 检查是否已成功处理过
            String successKey = DEDUPE_KEY_PREFIX + orderNo;
            if (Boolean.TRUE.equals(redisTemplate.hasKey(successKey))) {
                log.info("消息已处理成功，跳过: orderNo={}", orderNo);
                return;
            }

            // 2. 设置处理中标记（防止并发重复处理）
            String processingKey = DEDUPE_PROCESSING_PREFIX + orderNo;
            Boolean isFirstTime = redisTemplate.opsForValue().setIfAbsent(processingKey, "1", DEDUPE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (Boolean.FALSE.equals(isFirstTime)) {
                log.info("消息正在处理中，跳过: orderNo={}", orderNo);
                return;
            }

            // 3. 执行库存回滚
            inventoryService.rollbackStock(
                    rollbackMessage.getOrderNo(),
                    rollbackMessage.getActivityId(),
                    rollbackMessage.getItemId(),
                    rollbackMessage.getQuantity(),
                    rollbackMessage.getUserId()
            );

            // 4. 处理成功，设置永久成功标记
            redisTemplate.opsForValue().set(successKey, "1");
            redisTemplate.delete(processingKey);

            log.info("库存回滚处理完成: orderNo={}", orderNo);

        } catch (Exception e) {
            log.error("处理库存回滚消息失败: orderNo={}, message={}", orderNo, message, e);

            // 判断是否为可重试的错误
            if (isRetryableError(e)) {
                // 可重试错误（临时故障）：删除处理中标记，允许立即重试
                if (orderNo != null) {
                    String processingKey = DEDUPE_PROCESSING_PREFIX + orderNo;
                    redisTemplate.delete(processingKey);
                    log.info("检测到可重试错误，已删除处理中标记，允许重试: orderNo={}", orderNo);
                }
                throw new RuntimeException("处理失败（可重试），将重试", e);
            } else {
                // 不可重试错误（业务失败）：保留处理中标记，等待30分钟过期或人工介入
                log.warn("检测到不可重试错误，保留处理中标记，30分钟后将允许重试: orderNo={}", orderNo);
                throw new RuntimeException("处理失败（不可重试）", e);
            }
        }
    }

    /**
     * 判断异常是否为可重试的临时故障
     */
    private boolean isRetryableError(Exception e) {
        if (e == null) {
            return false;
        }

        String message = e.getMessage();
        if (message == null) {
            return false;
        }

        String lowerMessage = message.toLowerCase();

        // 可重试的错误特征
        return lowerMessage.contains("timeout")           // 超时
                || lowerMessage.contains("timed out")     // 超时
                || lowerMessage.contains("connection")    // 连接问题
                || lowerMessage.contains("network")       // 网络问题
                || lowerMessage.contains("refused")       // 连接拒绝
                || lowerMessage.contains("reset")         // 连接重置
                || lowerMessage.contains("interrupted")   // 中断
                || e instanceof java.net.SocketException  // Socket 异常
                || e instanceof java.net.ConnectException; // 连接异常
    }
}
