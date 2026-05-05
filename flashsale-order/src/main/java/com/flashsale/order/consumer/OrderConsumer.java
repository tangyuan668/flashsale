package com.flashsale.order.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.dto.StockDeductMessage;
import com.flashsale.order.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 订单结果消费者
 * 消费库存扣减结果消息，创建订单或记录失败
 */
@Slf4j
@Component
@RocketMQMessageListener(
        topic = "STOCK_RESULT_TOPIC",
        consumerGroup = "ORDER_RESULT_CONSUMER_GROUP",
        consumeThreadNumber = 32
)
public class OrderConsumer implements RocketMQListener<String> {

    @Autowired
    private OrderService orderService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final String DEDUPE_KEY_PREFIX = "d:order:";
    private static final String DEDUPE_PROCESSING_PREFIX = "d:order:processing:";
    private static final long DEDUPE_TIMEOUT_SECONDS = 1800;

    @Override
    public void onMessage(String message) {
        String orderNo = null;
        try {
            log.info("接收到库存扣减结果消息: {}", message);

            StockDeductMessage resultMessage = objectMapper.readValue(message, StockDeductMessage.class);
            orderNo = resultMessage.getOrderNo();

            String successKey = DEDUPE_KEY_PREFIX + orderNo;
            String processingKey = DEDUPE_PROCESSING_PREFIX + orderNo;

            // 1. 去重检查（setIfAbsent 是原子操作，需保留串行语义）
            if (Boolean.TRUE.equals(redisTemplate.hasKey(successKey))) {
                log.info("消息已处理成功，跳过: orderNo={}", orderNo);
                return;
            }

            Boolean isFirstTime = redisTemplate.opsForValue().setIfAbsent(processingKey, "1", DEDUPE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (Boolean.FALSE.equals(isFirstTime)) {
                log.info("消息正在处理中，跳过: orderNo={}", orderNo);
                return;
            }

            // 2. 处理库存扣减结果
            orderService.processStockDeductResult(resultMessage);

            // 3. Pipeline：设置成功标记 + 清理处理中标记 → 1 次 RTT
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                connection.stringCommands().set(successKey.getBytes(StandardCharsets.UTF_8),
                        "1".getBytes(StandardCharsets.UTF_8));
                connection.keyCommands().del(processingKey.getBytes(StandardCharsets.UTF_8));
                return null;
            });

        } catch (Exception e) {
            log.error("处理库存扣减结果消息失败: orderNo={}, message={}", orderNo, message, e);

            if (isRetryableError(e)) {
                if (orderNo != null) {
                    String processingKey = DEDUPE_PROCESSING_PREFIX + orderNo;
                    redisTemplate.delete(processingKey);
                    log.info("检测到可重试错误，已删除处理中标记，允许重试: orderNo={}", orderNo);
                }
                throw new RuntimeException("处理失败（可重试），将重试", e);
            } else {
                log.warn("检测到不可重试错误，保留处理中标记，30分钟后将允许重试: orderNo={}", orderNo);
                throw new RuntimeException("处理失败（不可重试）", e);
            }
        }
    }

    private boolean isRetryableError(Exception e) {
        if (e == null) return false;
        String msg = e.getMessage();
        if (msg == null) return false;
        String lower = msg.toLowerCase();
        return lower.contains("timeout") || lower.contains("timed out")
                || lower.contains("connection") || lower.contains("network")
                || lower.contains("refused") || lower.contains("reset")
                || lower.contains("interrupted")
                || e instanceof java.net.SocketException
                || e instanceof java.net.ConnectException;
    }
}
