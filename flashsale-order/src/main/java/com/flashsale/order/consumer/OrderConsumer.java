package com.flashsale.order.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.dto.StockDeductMessage;
import com.flashsale.order.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 订单结果消费者
 * 消费库存扣减结果消息，创建订单或记录失败
 */
@Slf4j
@Component
@RocketMQMessageListener(
        topic = "STOCK_RESULT_TOPIC",
        consumerGroup = "ORDER_RESULT_CONSUMER_GROUP"
)
public class OrderConsumer implements RocketMQListener<String> {

    @Autowired
    private OrderService orderService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final String DEDUPE_KEY_PREFIX = "d:order:";
    private static final long DEDUPE_TIMEOUT_SECONDS = 300; // 5分钟

    @Override
    public void onMessage(String message) {
        try {
            log.info("接收到库存扣减结果消息: {}", message);

            // 解析库存扣减结果消息
            StockDeductMessage resultMessage =
                    objectMapper.readValue(message, StockDeductMessage.class);

            // 幂等性处理：检查是否已处理过
            String dedupeKey = DEDUPE_KEY_PREFIX + resultMessage.getOrderNo();
            Boolean isFirstTime = redisTemplate.opsForValue().setIfAbsent(dedupeKey, "1", DEDUPE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (Boolean.FALSE.equals(isFirstTime)) {
                log.info("重复消息，已跳过: orderNo={}", resultMessage.getOrderNo());
                return;
            }

            // 处理库存扣减结果
            orderService.processStockDeductResult(resultMessage);

        } catch (Exception e) {
            log.error("处理库存扣减结果消息失败: {}", message, e);
        }
    }
}
