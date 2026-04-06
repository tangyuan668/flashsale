package com.flashsale.order.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.order.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 订单消息消费者
 */
@Slf4j
@Component
@RocketMQMessageListener(
        topic = "${rocketmq.topic.seckill-order:SECKILL_ORDER_TOPIC}",
        consumerGroup = "${rocketmq.consumer.order:ORDER_CREATE_CONSUMER_GROUP}"
)
public class OrderConsumer implements RocketMQListener<String> {

    @Autowired
    private OrderService orderService;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public void onMessage(String message) {
        try {
            log.info("接收到订单消息: {}", message);

            // 解析消息
            OrderService.OrderCreateMessage orderMessage =
                    objectMapper.readValue(message, OrderService.OrderCreateMessage.class);

            // 处理订单创建
            orderService.processOrderCreate(orderMessage);

        } catch (Exception e) {
            log.error("处理订单消息失败: {}", message, e);
        }
    }
}
