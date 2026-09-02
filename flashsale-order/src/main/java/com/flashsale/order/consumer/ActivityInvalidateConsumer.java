package com.flashsale.order.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.dto.ActivityInvalidateMessage;
import com.flashsale.order.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 活动失效消费者
 * 消费活动删除/过期消息，失效相关待支付订单
 */
@Slf4j
@Component
@RocketMQMessageListener(
        topic = "ACTIVITY_INVALIDATE_TOPIC",
        consumerGroup = "ORDER_INVALIDATE_CONSUMER_GROUP"
)
public class ActivityInvalidateConsumer implements RocketMQListener<String> {

    @Autowired
    private OrderService orderService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final String DEDUPE_KEY_PREFIX = "d:invalidate:";
    private static final long DEDUPE_TIMEOUT_SECONDS = 1800;

    @Override
    public void onMessage(String message) {
        try {
            log.info("接收到活动失效消息: {}", message);

            ActivityInvalidateMessage invalidateMessage =
                    objectMapper.readValue(message, ActivityInvalidateMessage.class);
            Long activityId = invalidateMessage.getActivityId();

            // 幂等去重
            String successKey = DEDUPE_KEY_PREFIX + activityId;
            if (Boolean.TRUE.equals(redisTemplate.hasKey(successKey))) {
                log.info("活动失效消息已处理，跳过: activityId={}", activityId);
                return;
            }

            // 执行订单失效
            orderService.invalidateOrders(activityId, invalidateMessage.getReason());

            // 标记已处理
            redisTemplate.opsForValue().set(successKey, "1", DEDUPE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            log.info("活动失效消息处理完成: activityId={}", activityId);

        } catch (Exception e) {
            log.error("处理活动失效消息失败: message={}", message, e);
            throw new RuntimeException("处理活动失效消息失败", e);
        }
    }
}
